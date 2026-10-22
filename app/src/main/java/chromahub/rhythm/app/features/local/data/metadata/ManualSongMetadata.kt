// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import android.content.Context
import android.util.AtomicFile
import androidx.annotation.Keep
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.util.GenreUtils
import com.google.gson.Gson
import java.io.File
import kr.co.metadata.crawler.CatalogFileStore

/** Explicit edits survive scans and automatic enrichment, including deliberately empty fields. */
class ManualSongMetadata(private val context: Context) {
    @Keep data class Tags(val title: String, val artist: String, val album: String, val genre: String?,
        val year: Int, val albumArtist: String?, val track: Int, val disc: Int)

    companion object {
        private val lock = Any()
        private val gson = Gson()
        private val cache = object : LinkedHashMap<String, Tags?>(64, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Tags?>?) = size > 512
        }
        fun invalidate() = synchronized(lock) { cache.clear() }
    }
    private fun file(key: String) = File(context.filesDir, "music_metadata/manual_tags/${CatalogFileStore.hash(key)}.json")
    private fun cacheKey(key: String) = context.filesDir.path + ":" + key
    fun read(key: String): Tags? = synchronized(lock) {
        val id = cacheKey(key)
        if (cache.containsKey(id)) return@synchronized cache[id]
        runCatching {
            val source = file(key)
            // openRead restores a .bak left by an interrupted write before checking its size.
            AtomicFile(source).openRead().bufferedReader().use {
                require(source.length() in 1..64_000)
                gson.fromJson(it, Tags::class.java).also { tags ->
                    // Gson can populate null into non-null Kotlin fields in a damaged file.
                    require(tags.title.length <= 16_000 && tags.artist.length <= 16_000 && tags.album.length <= 16_000)
                }
            }
        }.getOrNull().also { cache[id] = it }
    }
    fun save(key: String, song: Song) = synchronized(lock) {
        val tags = Tags(song.title, song.artist, song.album, GenreUtils.splitGenres(song.genre).joinToString(", ").ifBlank { null },
            song.year, song.albumArtist, song.trackNumber, song.discNumber)
        val bytes = gson.toJson(tags).toByteArray()
        require(bytes.size <= 64_000)
        PersonalArchive.atomicWrite(file(key), bytes)
        cache[cacheKey(key)] = tags
    }
    fun apply(song: Song, tags: Tags) = song.copy(title = tags.title, artist = tags.artist, album = tags.album,
        genre = tags.genre, year = tags.year, albumArtist = tags.albumArtist, trackNumber = tags.track, discNumber = tags.disc)
}
