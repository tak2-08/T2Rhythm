// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Build
import android.util.AtomicFile
import chromahub.rhythm.app.BuildConfig
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class UpdateStage { READY, INSTALL_REQUESTED, INSTALLED, SUPERSEDED }
data class UpdateRecord(val id: String, val from: String, val version: String, val code: Long,
    val time: Long, val stage: UpdateStage, val file: String = "", val bytes: Long = 0,
    val notes: String = "", val apkRemoved: Boolean = false, val fromCode: Long = 0)

/** Installation success is proven by PackageManager, never by launching the installer. */
object UpdateInstallPolicy {
    fun confirmed(target: Long, previous: Long, installed: Long, requested: Long, updated: Long): Boolean =
        target > previous && installed == target && updated >= requested - 2_000
}

class UpdateJournal private constructor(private val context: Context, namespace: String) {
    private val disk = AtomicFile(File(context.noBackupFilesDir, "$namespace.json"))
    private var document = runCatching { disk.openRead().use { JSONObject(it.reader().readText()) } }.getOrDefault(JSONObject())
    private val _records = MutableStateFlow(readRecords())
    val records = _records.asStateFlow()
    private fun readRecords(): List<UpdateRecord> {
        val array = document.optJSONArray("records") ?: return emptyList()
        return (0 until minOf(array.length(), 100)).mapNotNull { i -> runCatching {
            val j = array.getJSONObject(i)
            UpdateRecord(j.getString("id"), j.optString("from"), j.getString("version"), j.getLong("code"), j.getLong("time"),
                UpdateStage.valueOf(j.getString("stage")), j.optString("file"), j.optLong("bytes"), j.optString("notes").take(16_384), j.optBoolean("removed"), j.optLong("fromCode"))
        }.getOrNull() }
    }
    private fun persist(records: List<UpdateRecord> = _records.value) {
        val bounded = records.sortedByDescending { it.time }.take(100)
        document.put("records", JSONArray().apply { bounded.forEach { r -> put(JSONObject()
            .put("id", r.id).put("from", r.from).put("version", r.version).put("code", r.code).put("time", r.time)
            .put("stage", r.stage.name).put("file", r.file).put("bytes", r.bytes).put("notes", r.notes.take(16_384)).put("removed", r.apkRemoved).put("fromCode", r.fromCode)) } })
        val out = disk.startWrite()
        try { out.write(document.toString().toByteArray()); disk.finishWrite(out) }
        catch (e: Exception) { disk.failWrite(out); throw e }
        _records.value = bounded
    }
    @Synchronized fun ready(file: File, notes: String = "") {
        require(owned(file))
        val info = archive(file) ?: error("Unverified update package")
        val old = _records.value.firstOrNull { it.file == file.absolutePath && it.code == info.installCode && it.stage != UpdateStage.INSTALLED }
        val record = UpdateRecord(old?.id ?: UUID.randomUUID().toString(), BuildConfig.VERSION_NAME,
            info.versionName.orEmpty(), info.installCode, System.currentTimeMillis(), UpdateStage.READY,
            file.absolutePath, file.length(), notes.take(16_384), fromCode = BuildConfig.VERSION_CODE.toLong())
        persist(_records.value.filterNot { it.id == record.id } + record)
    }
    @Synchronized fun installing(file: File) {
        if (_records.value.none { it.file == file.absolutePath && it.stage != UpdateStage.INSTALLED }) ready(file)
        persist(_records.value.map { if (it.file == file.absolutePath && it.stage != UpdateStage.INSTALLED)
            it.copy(stage = UpdateStage.INSTALL_REQUESTED, time = System.currentTimeMillis()) else it })
    }
    @Synchronized fun installerFailed(file: File) {
        persist(_records.value.map { if (it.file == file.absolutePath && it.stage == UpdateStage.INSTALL_REQUESTED) it.copy(stage = UpdateStage.READY) else it })
    }
    @Synchronized fun attachInstalledNotes(code: Long, notes: String) {
        if (notes.isBlank()) return
        val updated = _records.value.map { r ->
            if (r.code == code && r.stage == UpdateStage.INSTALLED && r.notes.isBlank()) r.copy(notes = notes.take(16_384)) else r
        }
        if (updated != _records.value) persist(updated)
    }
    @Synchronized fun remove(id: String) = persist(_records.value.filterNot { it.id == id })
    @Synchronized fun clear() = persist(emptyList())

    /** Only this app's managed update directory is touched. Future/cancelled APKs stay retryable. */
    @Synchronized fun reconcile(): Long {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val previousName = document.optString("seenName")
        var records = _records.value.map { r ->
            if (r.stage in setOf(UpdateStage.READY, UpdateStage.INSTALL_REQUESTED)) {
                when {
                    UpdateInstallPolicy.confirmed(r.code, r.fromCode, installed.installCode, r.time, installed.lastUpdateTime) -> r.copy(stage = UpdateStage.INSTALLED, time = installed.lastUpdateTime)
                    r.code < installed.installCode -> r.copy(stage = UpdateStage.SUPERSEDED)
                    else -> r
                }
            } else r
        }
        if (document.optLong("seenCode", -1) != installed.installCode || document.optLong("seenTime", -1) != installed.lastUpdateTime) {
            if (records.none { it.stage == UpdateStage.INSTALLED && it.code == installed.installCode && it.time == installed.lastUpdateTime })
                records = records + UpdateRecord(UUID.randomUUID().toString(), previousName, installed.versionName.orEmpty(),
                    installed.installCode, installed.lastUpdateTime, UpdateStage.INSTALLED)
            document.put("seenCode", installed.installCode).put("seenName", installed.versionName.orEmpty()).put("seenTime", installed.lastUpdateTime)
        }
        persist(records)
        var freed = 0L
        val deleted = mutableSetOf<String>()
        managedDirectories().flatMap { it.listFiles()?.toList().orEmpty() }.asSequence()
            .filter { it.extension.equals("apk", true) && owned(it) }.take(100).forEach { file ->
                val info = archive(file)
                if (info != null && info.installCode <= installed.installCode) {
                    val size = file.length()
                    if (file.delete()) { freed += size; deleted += file.absolutePath }
                }
            }
        if (deleted.isNotEmpty()) {
            persist(_records.value.map { if (it.file in deleted) it.copy(apkRemoved = true) else it })
            val prefs = context.getSharedPreferences("app_updater_downloads", Context.MODE_PRIVATE)
            if (prefs.getString("downloaded_file", null) in deleted) {
                prefs.edit().remove("downloaded_file").remove("verified_checksum").remove("verified_size")
                    .putFloat("download_progress", 0f).commit()
            }
        }
        return freed
    }
    private fun managedDirectories(): List<File> = (context.getExternalFilesDirs(Environment.DIRECTORY_DOWNLOADS).filterNotNull()
        .map { File(it, "verified-updates") } + File(context.filesDir, "verified-updates")).distinctBy { it.absolutePath }
    private fun owned(file: File): Boolean = runCatching {
        !Files.isSymbolicLink(file.toPath()) && managedDirectories().any { file.absoluteFile.parentFile == it.absoluteFile && file.canonicalFile.parentFile == it.canonicalFile }
    }.getOrDefault(false)
    @Suppress("DEPRECATION") private fun archive(file: File): PackageInfo? = runCatching {
        val flags = PackageManager.GET_SIGNATURES or if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val apk = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: return null
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        fun signatures(info: PackageInfo) = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners ?: info.signatures else info.signatures).orEmpty().map { it.toCharsString() }.toSet()
        apk.takeIf { it.packageName == context.packageName && it.installCode > 0 && signatures(it).isNotEmpty() && signatures(it) == signatures(installed) }
    }.getOrNull()
    companion object {
        @Volatile private var instance: UpdateJournal? = null
        fun get(context: Context): UpdateJournal = instance ?: synchronized(this) {
            instance ?: UpdateJournal(context.applicationContext, "update-history-v1").also { instance = it }
        }
        internal fun isolated(context: Context, namespace: String) = UpdateJournal(context, namespace.also { require(Regex("[a-zA-Z0-9_-]{1,80}").matches(it)) })
    }
}

@Suppress("DEPRECATION") private val PackageInfo.installCode: Long get() = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
