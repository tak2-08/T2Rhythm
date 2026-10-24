// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.base

import android.content.Context
import android.content.Intent
import android.os.Bundle
import chromahub.rhythm.runtime.*
import chromahub.rhythm.runtime.api.RuntimeUpdateController
import chromahub.rhythm.runtime.host.ModuleStateStore
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/** Downloading and staging cannot imply the user's separate apply confirmation. */
internal class NativeUpdateController(
    private val context: Context,
    private val verifier: RuntimePackageVerifier,
    private val host: HostEnvironment,
    private val archives: File,
    private val stateStore: () -> ModuleStateStore,
    private val processVersion: () -> ModuleReference?,
) : RuntimeUpdateController {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Runnable>()
    override fun observe(listener: Runnable): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }
    internal fun notifyChanged() { listeners.forEach { runCatching { it.run() } } }
    override fun fingerprint(): String = host.runtimeFingerprint

    override fun status(): Bundle {
        val state = stateStore().snapshot()
        val active = processVersion() ?: state.active
        val verified = verifier.verifyAuthenticity(file(active.archiveSha256))
        return info(verified).apply {
            putLong("highestApprovedVersion", state.highestApprovedVersion)
            putBoolean("trialInProgress", state.trialInProgress)
            putString("lastFailedArchive", state.lastFailedArchive)
            state.candidate?.let { candidate ->
                putBundle("candidate", info(verifier.verifyAuthenticity(file(candidate.module.archiveSha256))).apply {
                    putBoolean("approved", candidate.approved)
                })
            }
        }
    }

    @Synchronized override fun stage(completedDownloadPath: String, expectedVersion: Long): Bundle {
        val source = File(completedDownloadPath)
        check(!Files.isSymbolicLink(source.toPath()) && source.isFile)
        val allowed = listOfNotNull(context.noBackupFilesDir, context.filesDir, context.cacheDir,
            context.getExternalFilesDir(null)).map { it.canonicalPath + File.separator }
        require(allowed.any { source.canonicalPath.startsWith(it) }) { "Download is outside application storage" }
        require(source.length() in 1..224L * 1024 * 1024)
        val temporary = File.createTempFile("download-", ".t2module", archives)
        try {
            FileOutputStream(temporary).use { output ->
                check(temporary.setReadOnly())
                source.inputStream().use { input ->
                    val bytes = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val count = input.read(bytes)
                        if (count == -1) break
                        total += count; require(total <= 224L * 1024 * 1024)
                        output.write(bytes, 0, count)
                    }
                }
                output.fd.sync()
            }
            val verified = try { verifier.verify(temporary, host) }
            catch (rejected: ModuleRejected) { throw IllegalArgumentException("Feature verification failed", rejected) }
            require(expectedVersion <= 0 || verified.manifest.versionCode == expectedVersion)
            val destination = file(verified.archiveSha256)
            withArchiveLock(archives) {
                if (destination.exists()) {
                    require(!Files.isSymbolicLink(destination.toPath()))
                    require(verifier.verify(destination, host).reference == verified.reference)
                } else {
                    check(temporary.renameTo(destination))
                    syncRuntimeDirectory(archives)
                }
            }
            val store = stateStore()
            val existing = store.snapshot().candidate
            if (existing?.module != verified.reference) store.stage(verified)
            notifyChanged()
            return info(verified).apply { putBoolean("approved", existing?.approved == true) }
        } finally { temporary.delete() }
    }

    override fun verifyCandidate(archiveHash: String): Bundle {
        val candidate = requireNotNull(stateStore().snapshot().candidate)
        require(candidate.module.archiveSha256 == archiveHash)
        val verified = verifier.verify(file(archiveHash), host)
        require(candidate.module == verified.reference)
        return info(verified).apply { putBoolean("approved", candidate.approved) }
    }

    override fun requestApproval(archiveHash: String) {
        require(archiveHash.matches(HASH))
        context.startActivity(Intent(context, RuntimeApprovalActivity::class.java)
            .putExtra("archive_hash", archiveHash).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Only RuntimeApprovalActivity calls this after the user confirms its exact verified hash. */
    internal fun confirmApproval(archiveHash: String) {
        verifyCandidate(archiveHash)
        stateStore().approve(archiveHash)
        notifyChanged()
    }

    override fun discardUnapproved(archiveHash: String) {
        val store = stateStore()
        val candidate = requireNotNull(store.snapshot().candidate)
        require(candidate.module.archiveSha256 == archiveHash && !candidate.approved)
        store.discardUnapproved(archiveHash)
        notifyChanged()
    }

    private fun info(verified: VerifiedModule) = Bundle().apply {
        putLong("versionCode", verified.manifest.versionCode)
        putString("versionName", verified.manifest.versionName)
        putString("notes", localizedNotes(verified.releaseNotes))
        putString("archiveHash", verified.archiveSha256)
        putString("path", file(verified.archiveSha256).path)
    }
    private fun file(hash: String): File {
        require(hash.matches(HASH)); return File(archives, "$hash.t2module")
    }
    private fun localizedNotes(notes: String): String {
        val korean = "## 한국어\n\n"
        val english = "\n\n## English\n\n"
        val separator = notes.indexOf(english)
        if (!notes.startsWith(korean) || separator < 0) return notes
        return if (java.util.Locale.getDefault().language == "ko") notes.substring(korean.length, separator)
        else notes.substring(separator + english.length).trimEnd()
    }
    private companion object { val HASH = Regex("[0-9a-f]{64}") }
}
