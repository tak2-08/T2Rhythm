// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.annotation.Keep
import chromahub.rhythm.app.features.local.data.database.RhythmDatabase
import chromahub.rhythm.app.features.local.di.LocalMusicModule
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.util.MediaUtils
import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.co.metadata.crawler.CatalogFileStore

data class FileNamePlan(val song: Song, val mutationUri: Uri, val oldPath: String, val name: String,
    val bytes: Long, val modified: Long) {
    val newPath get() = File(File(oldPath).parentFile, name).path
}
data class FileNameOutcome(val state: String, val plan: FileNamePlan, val after: Song? = null, val consent: IntentSender? = null)
@Keep internal data class FileNameJournal(val song: String, val mutationUri: String, val oldPath: String,
    val newPath: String, val key: String, val digest: String, val state: String = "PREPARED", val at: Long = System.currentTimeMillis())

/** Rename through MediaStore so playlists, playback URIs and library IDs remain intact. */
class FileNameService(private val context: Context) {
    companion object { private val mutex = Mutex() }
    private val gson = Gson()
    private val catalog get() = CatalogEnrichmentService.get(context)
    private val journals get() = File(context.filesDir, "music_metadata/file_names/pending").apply { mkdirs() }
    private fun journal(uri: String) = File(journals, CatalogFileStore.hash(uri) + ".json")
    private data class Location(val path: String, val modified: Long)
    private fun location(uri: Uri): Location? = context.contentResolver.query(uri,
        arrayOf(MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DATE_MODIFIED), null, null, null)?.use { c ->
        if (!c.moveToFirst() || c.isNull(0)) null else {
            val raw = c.getLong(1); Location(c.getString(0), if (raw in 1..99_999_999_999L) raw * 1000 else raw)
        }
    }
    private fun mutationUri(song: Song): Uri? {
        if (song.uri.scheme != "content" || song.uri.authority != "media") return null
        return song.id.toLongOrNull()?.let { MediaUtils.getSpecificVolumeUri(context, it) } ?: song.uri
    }
    /** Read actual embedded tags, never MediaStore's filename-derived fallback title. */
    private fun tags(uri: Uri): Pair<String?, String?> {
        val tagged = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            com.kyant.taglib.TagLib.getMetadata(fd.detachFd())?.propertyMap?.let { it["TITLE"]?.firstOrNull() to (it["ARTIST"]?.firstOrNull() ?: it["ALBUMARTIST"]?.firstOrNull()) }
        } }.getOrNull()
        if (tagged != null && !tagged.first.isNullOrBlank()) return tagged
        val retriever = MediaMetadataRetriever()
        return try { retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) to retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
        } finally { retriever.release() }
    }
    suspend fun plan(song: Song, format: String, reserved: MutableMap<String, MutableSet<String>> = mutableMapOf()): FileNamePlan? = withContext(Dispatchers.IO) {
        val uri = mutationUri(song) ?: return@withContext null
        val current = location(uri) ?: return@withContext null
        val source = File(current.path); if (!source.isFile || source.length() <= 0 || catalog.audioBusy(song)) return@withContext null
        val beforeSize = source.length(); val beforeModified = source.lastModified()
        val (title, artist) = tags(uri)
        val base = FileNamePolicy.base(title, artist, format) ?: return@withContext null
        val parent = source.parentFile ?: return@withContext null
        val occupied = reserved.getOrPut(parent.path) { parent.list().orEmpty().toMutableSet() }
        val name = FileNamePolicy.available(base, source.extension, source.name, occupied)
        occupied += name
        if (name.equals(source.name, true)) return@withContext null
        if (source.length() != beforeSize || source.lastModified() != beforeModified) return@withContext null
        FileNamePlan(song.copy(path = source.path, dateModified = current.modified), uri, source.path, name, beforeSize, beforeModified)
    }
    private fun digest(uri: Uri): String {
        val hash = MessageDigest.getInstance("SHA-256")
        // Android 10 scoped storage can allow a MediaStore URI while rejecting a raw path.
        val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "r"))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val channel = input.channel
            val length = channel.size()
            hash.update(length.toString().toByteArray())
            val buffer = ByteArray(65536)
            val n = input.read(buffer); if (n > 0) hash.update(buffer, 0, n)
            if (length > buffer.size) { channel.position((length - buffer.size).coerceAtLeast(0)); val tail = input.read(buffer); if (tail > 0) hash.update(buffer, 0, tail) }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun save(entry: FileNameJournal) = PersonalArchive.atomicWrite(journal(CatalogSongCodec.decode(entry.song).uri.toString()), gson.toJson(entry).toByteArray())
    suspend fun rename(plan: FileNamePlan): FileNameOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            var outcome = FileNameOutcome("BUSY", plan)
            catalog.withAudioWrite(plan.song) {
                withContext(NonCancellable) {
                    val source = File(plan.oldPath); val target = File(plan.newPath)
                    if (location(plan.mutationUri)?.path != plan.oldPath || !source.isFile || source.length() != plan.bytes || source.lastModified() != plan.modified)
                        return@withContext FileNameOutcome("STALE", plan)
                    if (target.exists()) return@withContext FileNameOutcome("COLLISION", plan)
                    val repository = LocalMusicModule.provideMusicRepository(context)
                    repository.peekLocalLyrics(plan.song)?.let { AutomaticLyricsStore.get(context).preserveForRename(plan.song, it) }
                    val key = catalog.songKey(plan.song)
                    val entry = FileNameJournal(CatalogSongCodec.encode(plan.song), plan.mutationUri.toString(), plan.oldPath, plan.newPath, key, digest(plan.mutationUri))
                    save(entry)
                    SongFileIdentity.stage(context, plan.oldPath, plan.newPath, key, plan.song.uri.toString())
                    try {
                        val values = ContentValues().apply { put(MediaStore.Audio.Media.DISPLAY_NAME, plan.name)
                            if (plan.song.dateModified > 0) put(MediaStore.Audio.Media.DATE_MODIFIED, plan.song.dateModified / 1000) }
                        if (Build.VERSION.SDK_INT < 29) {
                            Files.move(source.toPath(), target.toPath())
                            values.put(MediaStore.Audio.Media.DATA, target.path)
                        }
                        check(context.contentResolver.update(plan.mutationUri, values, null, null) == 1)
                        val current = location(plan.mutationUri) ?: error("Missing renamed media")
                        check(File(current.path).parentFile == source.parentFile && File(current.path).isFile && digest(plan.mutationUri) == entry.digest)
                        if (current.path != plan.newPath) SongFileIdentity.rollback(context, plan.oldPath, plan.newPath, key)
                        SongFileIdentity.commit(context, plan.oldPath, current.path, key, plan.song.uri.toString())
                        val after = plan.song.copy(path = current.path, dateModified = current.modified)
                        RhythmDatabase.getInstance(context).songDao().updateFileLocation(plan.song.id, plan.song.uri.toString(), current.path, current.modified)
                        repository.clearInMemoryCaches()
                        catalog.notifyChanged()
                        journal(plan.song.uri.toString()).delete()
                        PersonalArchive.enqueue(context)
                        outcome = FileNameOutcome("DONE", plan, after)
                    } catch (error: Exception) {
                        if (Build.VERSION.SDK_INT < 29 && !source.exists() && target.isFile &&
                            runCatching { location(plan.mutationUri)?.path == plan.oldPath }.getOrDefault(false))
                            runCatching { Files.move(target.toPath(), source.toPath()) }
                        // Only roll back staged identity if the audio is still at its original name.
                        if (source.isFile && !target.exists()) SongFileIdentity.rollback(context, plan.oldPath, plan.newPath, key)
                        val denied = error is SecurityException
                        save(entry.copy(state = if (denied) "PERMISSION" else "RECOVER"))
                        val sender = if (Build.VERSION.SDK_INT >= 29 && error is RecoverableSecurityException) error.userAction.actionIntent.intentSender else null
                        outcome = FileNameOutcome(if (denied) "PERMISSION" else "FAILED", plan, consent = sender)
                    }
                    outcome
                }.also { outcome = it }
                outcome.state == "DONE"
            }
            outcome
        }
    }
    suspend fun recover() = withContext(Dispatchers.IO) {
        mutex.withLock {
            journals.listFiles().orEmpty().filter { it.extension == "json" }.forEach { file ->
                currentCoroutineContext().ensureActive()
                runCatching {
                    val entry = AtomicFile(file).openRead().use { input -> require(file.length() in 1..32000); gson.fromJson(input.bufferedReader().readText(), FileNameJournal::class.java) }
                    if (entry.state == "PERMISSION") return@runCatching
                    val current = location(Uri.parse(entry.mutationUri)) ?: return@runCatching
                    if (current.path == entry.newPath && File(current.path).isFile && digest(Uri.parse(entry.mutationUri)) == entry.digest) {
                        val song = CatalogSongCodec.decode(entry.song)
                        SongFileIdentity.commit(context, entry.oldPath, entry.newPath, entry.key, song.uri.toString())
                        RhythmDatabase.getInstance(context).songDao().updateFileLocation(song.id, song.uri.toString(), current.path, current.modified)
                        file.delete()
                    } else if (current.path == entry.oldPath && File(entry.oldPath).isFile) {
                        SongFileIdentity.rollback(context, entry.oldPath, entry.newPath, entry.key); file.delete()
                    }
                }
            }
        }
    }
    fun pendingCount(): Int = journals.listFiles().orEmpty().count { it.extension == "json" }
    fun consent(outcomes: List<FileNameOutcome>): IntentSender? {
        val denied = outcomes.filter { it.state == "PERMISSION" }
        if (denied.isEmpty()) return null
        return if (Build.VERSION.SDK_INT >= 30) MediaStore.createWriteRequest(context.contentResolver, denied.map { it.plan.mutationUri }.distinct().take(2000)).intentSender
            else denied.firstNotNullOfOrNull { it.consent }
    }
}
