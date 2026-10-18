// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import android.content.Context
import android.net.Uri
import androidx.annotation.Keep
import chromahub.rhythm.app.shared.data.model.Song
import com.google.gson.Gson
import java.io.File
import kr.co.metadata.crawler.CatalogFileStore

/** User choices are source data, independent of Room, MediaStore IDs and catalog policies. */
class SongArtworkOverrides(private val context: Context) {
    @Keep data class Choice(val key: String, val uri: String?, val removed: Boolean, val updatedAt: Long)
    private val root = File(context.filesDir, "music_metadata/artwork_choices")
    private val gson = Gson()
    private fun file(key: String) = File(root, "${CatalogFileStore.hash(key)}.json")
    fun read(song: Song): Choice? = synchronized(lock) {
        val key = key(song)
        val identity = context.filesDir.path + ":" + key
        if (cache.containsKey(identity)) return@synchronized cache[identity]
        runCatching {
            val f = file(key)
            require(f.length() in 1..16_000)
            gson.fromJson(f.readText(), Choice::class.java).takeIf { it.key == key }
        }.getOrNull().also { cache[identity] = it }
    }
    fun save(song: Song, uri: Uri?, removed: Boolean = uri == null) = synchronized(lock) {
        val key = key(song)
        val choice = Choice(key, uri?.toString(), removed, System.currentTimeMillis())
        PersonalArchive.atomicWrite(file(key), gson.toJson(choice).toByteArray())
        cache[context.filesDir.path + ":" + key] = choice
        PersonalArchive.enqueue(context)
    }
    /** Only migrate while the old Room row still identifies the original file. Never guess by ID. */
    fun migrate(song: Song) = synchronized(lock) {
        if (read(song) != null) return@synchronized
        val prefs = context.getSharedPreferences("artwork_overrides", Context.MODE_PRIVATE)
        val removed = prefs.getBoolean("removed_${song.id}", false)
        val uri = prefs.getString("uri_${song.id}", null)?.let(Uri::parse)
        if (removed || uri != null) save(song, uri, removed)
        if (removed || uri != null) check(prefs.edit().remove("removed_${song.id}").remove("uri_${song.id}").commit())
    }
    companion object {
        private val lock = Any()
        private val cache = object : LinkedHashMap<String, Choice?>(64, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Choice?>?) = size > 1024
        }
        fun invalidate() = synchronized(lock) { cache.clear() }
        fun key(song: Song) = song.path?.takeIf { it.isNotBlank() } ?: song.uri.toString().substringBefore('?')
    }
}
