// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.video

import android.content.Context
import androidx.annotation.Keep
import chromahub.rhythm.app.features.local.data.discovery.AudioAlbumCandidate
import chromahub.rhythm.app.features.local.data.discovery.AudioSourceCandidate
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import com.google.gson.Gson
import java.io.File
import kr.co.metadata.crawler.CatalogFileStore

@Keep data class DownloadAlbum(val source: String, val title: String, val artist: String, val tracks: List<AudioSourceCandidate>, val at: Long = System.currentTimeMillis())
data class DownloadAlbumStatus(val album: DownloadAlbum, val completed: List<DownloadRecord>)

/** A collection keeps its exact source order, even when a track was downloaded previously. */
object DownloadAlbums {
    private fun root(context: Context) = File(context.filesDir, "music_metadata/downloads/collections")
    fun save(context: Context, album: AudioAlbumCandidate, tracks: List<AudioSourceCandidate>) {
        require(tracks.size in 1..200)
        val bytes = Gson().toJson(DownloadAlbum(album.url, album.title, album.artist, tracks)).toByteArray()
        require(bytes.size < 512_000)
        PersonalArchive.atomicWrite(File(root(context), "${CatalogFileStore.hash(album.url)}.json"), bytes)
        DownloadRegistry.revision.value++
        PersonalArchive.enqueue(context)
    }
    fun list(context: Context): List<DownloadAlbumStatus> {
        val albums = root(context).listFiles().orEmpty().filter { it.length() in 1..512_000 }.sortedByDescending { it.lastModified() }.take(20)
            .mapNotNull { runCatching { Gson().fromJson(it.readText(), DownloadAlbum::class.java) }.getOrNull() }
            .filter { it.tracks.size in 1..200 }
        val sources = albums.flatMap { it.tracks }.map { MusicVideoLinks.youtubeId(it.url) ?: it.url.trim() }.toSet()
        val completed = sources.mapNotNull { id -> DownloadRegistry.completedAudioPointer(context, id)?.let { id to it } }.toMap().toMutableMap()
        if (completed.size < sources.size) DownloadRegistry.recent(context, 1000).filter { it.kind == "AUDIO" && it.state == "SUCCEEDED" && it.sourceId in sources }
            .forEach { completed.putIfAbsent(it.sourceId, it) }
        return albums.map { album -> DownloadAlbumStatus(album, album.tracks.mapNotNull { completed[MusicVideoLinks.youtubeId(it.url) ?: it.url.trim()] }) }
    }
}
