// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.video

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.Keep
import androidx.work.WorkInfo
import androidx.work.WorkManager
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow

@Keep
data class DownloadRecord(
    val id: String,
    val kind: String,
    val key: String,
    val sourceId: String,
    val title: String,
    val artist: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val bytes: Long = 0,
    val totalBytes: Long = -1,
    val state: String = "ENQUEUED",
    val uri: String? = null,
    val fileBytes: Long = 0,
    val error: String? = null,
    val album: String = "",
    val service: String = "YOUTUBE",
)

/**
 * A compact permanent index outlives Compose, process restarts and WorkManager's retention window.
 */
object DownloadRegistry {
    const val TAG = "t2rhythm_media_download"
    val revision = MutableStateFlow(0L)
    private val gson = Gson()

    private fun removals(context: Context) = context.getSharedPreferences("removed_downloads", Context.MODE_PRIVATE)

    private fun removed(context: Context, id: String) = removals(context).getLong(id, 0L) > 0

    private fun root(context: Context) =
        File(context.filesDir, "music_metadata/downloads").apply { mkdirs() }

    @Synchronized
    fun save(context: Context, record: DownloadRecord) {
        require(runCatching { UUID.fromString(record.id) }.isSuccess)
        if (removed(context, record.id)) return
        PersonalArchive.atomicWrite(
            File(root(context), record.id + ".json"),
            gson.toJson(record).toByteArray(),
        )
        if (record.kind == "AUDIO" && record.state == "SUCCEEDED") {
            val pointer =
                File(
                    root(context),
                    "sources/${kr.co.metadata.crawler.CatalogFileStore.hash(record.sourceId)}",
                )
            PersonalArchive.atomicWrite(pointer, record.id.toByteArray())
        }
        revision.value++
    }

    @Synchronized
    fun get(context: Context, id: String): DownloadRecord? =
        runCatching {
                require(UUID.fromString(id).toString() == id)
                if (removed(context, id)) return@runCatching null
                val file = File(root(context), "$id.json")
                require(file.length() in 1..16_000)
                gson.fromJson(file.readText(), DownloadRecord::class.java)
            }
            .getOrNull()

    @Synchronized
    fun update(context: Context, id: String, change: (DownloadRecord) -> DownloadRecord) {
        get(context, id)?.let { record ->
            // A worker finishing a progress callback must never undo an explicit cancel.
            if (record.state != "CANCELLED") save(context, change(record))
        }
    }

    fun completedAudio(context: Context, sourceId: String): DownloadRecord? =
        runCatching {
                val pointer =
                    File(
                        root(context),
                        "sources/${kr.co.metadata.crawler.CatalogFileStore.hash(sourceId)}",
                    )
                require(pointer.length() in 1..40)
                get(context, pointer.readText())?.takeIf {
                    it.kind == "AUDIO" && it.sourceId == sourceId && it.state == "SUCCEEDED"
                }
            }
            .getOrNull()
            ?: recent(context, 1000).firstOrNull {
                it.kind == "AUDIO" && it.sourceId == sourceId && it.state == "SUCCEEDED"
            }

    fun recent(context: Context, limit: Int = 100): List<DownloadRecord> =
        root(context)
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .sortedByDescending { it.lastModified() }
            .take(limit.coerceIn(1, 1000))
            .mapNotNull { get(context, it.nameWithoutExtension) }
            .sortedWith(
                compareBy<DownloadRecord> {
                        if (it.state in setOf("SUCCEEDED", "FAILED", "CANCELLED")) 1 else 0
                    }
                    .thenByDescending { it.createdAt }
            )

    suspend fun recoverLegacy(context: Context) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val manager = WorkManager.getInstance(context)
            MusicVideoRepository(context).pendingLinks().forEach { video ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val work =
                    manager
                        .getWorkInfosForUniqueWork(
                            YouTubeVideoDownload.name(video.songKey, video.videoId!!)
                        )
                        .get(10, java.util.concurrent.TimeUnit.SECONDS)
                work
                    .filter { !it.state.isFinished }
                    .forEach { job ->
                        if (get(context, job.id.toString()) == null)
                            save(
                                context,
                                DownloadRecord(
                                    job.id.toString(),
                                    "VIDEO",
                                    video.songKey,
                                    video.videoId!!,
                                    video.title,
                                    state = job.state.name,
                                ),
                            )
                    }
            }
        }

    fun cancel(context: Context, id: String) {
        update(context, id) { if (it.state == "SUCCEEDED") it else it.copy(state = "CANCELLED") }
        WorkManager.getInstance(context).cancelWorkById(UUID.fromString(id))
        MediaTransfer.discard(context, id)
    }

    /** One explicit action removes the failed transfer and its history, without another prompt. */
    suspend fun removeFailed(context: Context, id: String) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            val record = synchronized(this@DownloadRegistry) {
                val value = get(context, id) ?: return@withContext
                require(value.state in setOf("FAILED", "CANCELLED"))
                val preferences = removals(context)
                val cutoff = System.currentTimeMillis() - 7 * 86_400_000L
                // Only cancellation race guards remain, for a bounded seven days, without payloads.
                val edit = preferences.edit()
                preferences.all.forEach { (key, at) -> if (at is Long && at < cutoff) edit.remove(key) }
                check(edit.putLong(id, System.currentTimeMillis()).commit())
                value
            }
            try {
                WorkManager.getInstance(context).cancelWorkById(UUID.fromString(id)).result.get(15, java.util.concurrent.TimeUnit.SECONDS)
                MediaTransfer.discardChecked(context, id)
                if (record.kind == "AUDIO") DownloadedAudioLibrary.discardPending(context, record)
                if (record.kind == "MODEL") {
                    val manager=WorkManager.getInstance(context)
                    val active=manager.getWorkInfosForUniqueWork("speech_model_ko").get(15,java.util.concurrent.TimeUnit.SECONDS).any { !it.state.isFinished }
                    chromahub.rhythm.app.features.local.data.discovery.SpeechModelStore.access.withLock {
                        val parent=chromahub.rhythm.app.features.local.data.discovery.SpeechModelStore.directory(context).parentFile!!
                        parent.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("install-[0-9a-f-]{36}")) && (!active || it.name=="install-$id") }
                            .forEach { check(!it.exists() || it.deleteRecursively()) }
                    }
                }
                synchronized(this@DownloadRegistry) {
                    val file = File(root(context), "$id.json")
                    check(!file.exists() || file.delete())
                    revision.value++
                }
            } catch (error: Exception) {
                // Retain a visible retryable entry if the filesystem refused deletion.
                synchronized(this@DownloadRegistry) { removals(context).edit().remove(id).commit(); revision.value++ }
                throw error
            }
        }

    suspend fun removeAllFailed(context: Context) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
        val records = root(context).listFiles().orEmpty().filter { it.extension == "json" }
            .mapNotNull { get(context, it.nameWithoutExtension) }
            .filter { it.state in setOf("FAILED", "CANCELLED") }
        var failure: Exception? = null
        for (record in records) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            try { removeFailed(context, record.id) } catch (error: Exception) { failure = error }
        }
        failure?.let { throw it }
    }

    fun merge(record: DownloadRecord, work: WorkInfo?): DownloadRecord =
        if (work == null) record
        else
            record.copy(
                state =
                    if (
                        record.state == "SUCCEEDED" ||
                            record.state == "CANCELLED" ||
                            work.state == WorkInfo.State.RUNNING
                    )
                        record.state
                    else work.state.name,
                bytes = maxOf(record.bytes, work.progress.getLong("bytes", 0)),
                totalBytes = work.progress.getLong("total", record.totalBytes),
                error = work.outputData.getString("download_error") ?: record.error,
            )
}

/**
 * Only small network buffers run in parallel. Device pressure reduces the next admission to one.
 */
internal object DownloadSlots {
    private var active = 0
    private val monitor = Any()

    fun capacity(context: Context): Int {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val memory = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return if (
            memory.isLowRamDevice ||
                context.cacheDir.usableSpace < 768L * 1024 * 1024 ||
                power.isPowerSaveMode ||
                Build.VERSION.SDK_INT >= 29 &&
                    power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        )
            1
        else 2
    }

    suspend fun <T> run(context: Context, block: suspend () -> T): T {
        while (true) {
            if (
                synchronized(monitor) {
                    if (active < capacity(context)) {
                        active++
                        true
                    } else false
                }
            )
                break
            delay(500)
        }
        try {
            return block()
        } finally {
            synchronized(monitor) { active-- }
        }
    }
}
