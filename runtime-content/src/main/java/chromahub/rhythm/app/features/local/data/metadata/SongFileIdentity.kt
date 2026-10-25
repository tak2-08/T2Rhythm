// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import android.content.Context
import android.util.AtomicFile
import androidx.annotation.Keep
import chromahub.rhythm.app.shared.data.model.Song
import com.google.gson.Gson
import kr.co.metadata.crawler.CatalogFileStore
import java.io.File
import java.util.UUID

/** The original storage key remains stable when only a filename changes, including after a Room rebuild. */
object SongFileIdentity {
    @Keep data class Alias(val key: String, val path: String, val activePath: String, val pending: Boolean = false, val uri: String? = null)
    private val lock = Any()
    private val gson = Gson()
    private val cache = object : LinkedHashMap<String, Alias?>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Alias?>?) = size > 8192
    }
    private fun file(context: Context, path: String) = File(context.filesDir, "music_metadata/file_names/identities/${CatalogFileStore.hash(path)}.json")
    private fun read(context: Context, path: String): Alias? {
        val id = context.filesDir.path + ":" + path
        if (cache.containsKey(id)) return cache[id]
        val value = runCatching {
            val source = file(context, path)
            AtomicFile(source).openRead().use { input -> require(source.length() in 1..16000)
                gson.fromJson(input.bufferedReader().readText(), Alias::class.java)?.takeIf { it.path == path && it.key.isNotBlank() } }
        }.getOrNull()
        cache[id] = value; return value
    }
    private fun write(context: Context, alias: Alias) {
        PersonalArchive.atomicWrite(file(context, alias.path), gson.toJson(alias).toByteArray())
        cache[context.filesDir.path + ":" + alias.path] = alias
    }
    fun key(context: Context, song: Song): String = synchronized(lock) {
        val path = song.path?.takeIf(String::isNotBlank) ?: return@synchronized song.uri.toString().substringBefore('?')
        val alias = read(context, path) ?: return@synchronized path
        // A new unrelated file placed at the vacated name must not inherit the old cover/video.
        if (alias.activePath != path && !alias.pending && alias.uri != song.uri.toString() && File(path).isFile) {
            val fresh = Alias("file:${UUID.randomUUID()}", path, path, uri = song.uri.toString())
            write(context, fresh); return@synchronized fresh.key
        }
        alias.key
    }

    /** A delayed scan must follow completed, URI-bound renames before it saves an old snapshot. */
    fun currentLocation(context: Context, song: Song): Song = synchronized(lock) {
        var path = song.path?.takeIf(String::isNotBlank) ?: return@synchronized song
        repeat(64) {
            val alias = read(context, path) ?: return@synchronized if (path == song.path) song else song.copy(path = path)
            if (alias.pending || alias.uri != song.uri.toString()) return@synchronized song
            if (alias.activePath == path) return@synchronized if (path == song.path) song else song.copy(path = path)
            path = alias.activePath
        }
        // Corrupt/cyclic or unexpectedly long chains must not invent a location.
        song
    }
    fun stage(context: Context, oldPath: String, newPath: String, key: String, uri: String? = null) = synchronized(lock) {
        write(context, Alias(key, oldPath, newPath, true, uri)); write(context, Alias(key, newPath, newPath, true, uri))
    }
    fun commit(context: Context, oldPath: String, newPath: String, key: String, uri: String? = null) {
        synchronized(lock) { write(context, Alias(key, oldPath, newPath, uri = uri)); write(context, Alias(key, newPath, newPath, uri = uri)) }
        SongArtworkOverrides.invalidate()
    }
    fun rollback(context: Context, oldPath: String, newPath: String, key: String) = synchronized(lock) {
        write(context, Alias(key, oldPath, oldPath))
        if (!File(newPath).exists()) { file(context, newPath).delete(); cache.remove(context.filesDir.path + ":" + newPath) }
    }
    fun invalidate() = synchronized(lock) { cache.clear() }
}
