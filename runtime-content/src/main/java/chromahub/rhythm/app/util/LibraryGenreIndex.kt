// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import android.content.Context
import android.util.AtomicFile
import chromahub.rhythm.app.features.local.data.discovery.GenreCorrections
import chromahub.rhythm.app.shared.data.model.Song
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Disposable projection alongside the Room song cache. Labels remain bound to exact library URIs. */
internal object LibraryGenreIndex {
    private val lock = Mutex()
    private var snapshot: List<Song>? = null
    private var revision = -1L
    private var owner = ""
    private var completed: GenreBrowseIndex<Song>? = null
    private data class Disk(val version: Int = 1, val fingerprint: String, val genres: Map<String, String>)

    suspend fun get(context: Context, songs: List<Song>): GenreBrowseIndex<Song> = lock.withLock {
        val nextRevision = GenreCorrections.revision.value
        val path = context.filesDir.path
        if (owner == path && snapshot == songs && revision == nextRevision) return@withLock requireNotNull(completed)
        val active = currentCoroutineContext()
        val labeled = runCatching { GenreCorrections(context).apply(songs) }.getOrDefault(songs)
        val hash = MessageDigest.getInstance("SHA-256")
        labeled.forEachIndexed { i, song ->
            if (i % 128 == 0) active.ensureActive()
            listOf(song.uri.toString(), song.dateModified.toString(), song.genre.orEmpty()).forEach {
                val bytes = it.toByteArray(); hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes)
            }
        }
        val fingerprint = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val file = AtomicFile(File(context.cacheDir, "library-genres-v1.json"))
        val disk = runCatching {
            require(file.baseFile.length() in 1..8_000_000)
            file.openRead().use { input ->
                val bytes = input.readBytes(); require(bytes.size <= 8_000_000)
                Gson().fromJson(bytes.toString(Charsets.UTF_8), Disk::class.java)
            }
        }.getOrNull()?.takeIf { it.version == 1 && it.fingerprint == fingerprint }
        val labels = disk?.genres ?: labeled.associate { it.uri.toString() to it.genre.orEmpty() }
        val result = GenreBrowseIndex.build(songs, { labels[it.uri.toString()] }, { active.ensureActive() }, GenreCatalog.labels)
        if (disk == null) {
            val bytes = Gson().toJson(Disk(fingerprint = fingerprint, genres = labels)).toByteArray()
            if (bytes.size <= 8_000_000) runCatching {
                val stream = file.startWrite()
                try { stream.write(bytes); file.finishWrite(stream) }
                catch (error: Throwable) { file.failWrite(stream); throw error }
            }
        }
        owner = path; snapshot = songs; revision = nextRevision; completed = result
        result
    }
}
