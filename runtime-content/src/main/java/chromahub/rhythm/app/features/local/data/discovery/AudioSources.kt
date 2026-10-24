// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import androidx.annotation.Keep
import chromahub.rhythm.app.features.local.data.video.MusicVideoLinks
import chromahub.rhythm.app.features.local.data.video.YouTubeVideoDownload
import kotlinx.coroutines.*
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem

@Keep
data class AudioSourceCandidate(
    val url: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
)

@Keep data class AudioAlbumCandidate(val url: String, val title: String, val artist: String, val tracks: Long)

object AudioSources {
    suspend fun albums(source: String, query: String): List<AudioAlbumCandidate> = withContext(Dispatchers.IO) {
        require(source in setOf("YOUTUBE", "SOUNDCLOUD") && query.isNotBlank() && query.length <= 500)
        YouTubeVideoDownload.initialize()
        YouTubeVideoDownload.extract {
            val service = if (source == "SOUNDCLOUD") ServiceList.SoundCloud else ServiceList.YouTube
            val filter = if (source == "SOUNDCLOUD") org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudSearchQueryHandlerFactory.PLAYLISTS
                else org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
            val extractor = service.getSearchExtractor(query, listOf(filter), "")
            extractor.fetchPage()
            extractor.initialPage.items.filterIsInstance<org.schabi.newpipe.extractor.playlist.PlaylistInfoItem>()
                .take(20).map { AudioAlbumCandidate(it.url, it.name, it.uploaderName.orEmpty(), it.streamCount) }
        }
    }

    /** The preview is the exact provider playlist; users verify editions before adding downloads. */
    suspend fun albumTracks(album: AudioAlbumCandidate): List<AudioSourceCandidate> = withContext(Dispatchers.IO) {
        val host = java.net.URI(album.url).host?.lowercase()?.removePrefix("www.")
        val kind = if (host in setOf("youtube.com", "music.youtube.com", "youtu.be")) "YOUTUBE" else service(album.url)
        require(kind in setOf("YOUTUBE", "SOUNDCLOUD"))
        YouTubeVideoDownload.initialize()
        val job = currentCoroutineContext()[Job]
        YouTubeVideoDownload.extract {
            val service = if (kind == "SOUNDCLOUD") ServiceList.SoundCloud else ServiceList.YouTube
            val info = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getInfo(service, album.url)
            val tracks = info.relatedItems.toMutableList()
            var page = info.nextPage
            var pages = 0
            while (org.schabi.newpipe.extractor.Page.isValid(page) && tracks.size < 200 && pages++ < 10) {
                job?.ensureActive()
                val more = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getMoreItems(service, album.url, page)
                tracks.addAll(more.items)
                page = more.nextPage
            }
            require(!org.schabi.newpipe.extractor.Page.isValid(page)) { "Collection too large; choose a smaller album" }
            require(tracks.size <= 200) { "Collection too large; choose a smaller album" }
            tracks.filterIsInstance<StreamInfoItem>().filter { it.duration in 15..3600 }
                .distinctBy { it.url }.take(200).map { AudioSourceCandidate(it.url, it.name, it.uploaderName.orEmpty(), it.duration) }
        }
    }
    fun service(url: String): String {
        val parsed = java.net.URI(url.trim())
        require(parsed.scheme == "https" && parsed.userInfo == null && !parsed.host.isNullOrBlank())
        if (MusicVideoLinks.youtubeId(url) != null) return "YOUTUBE"
        val host = parsed.host.lowercase().removePrefix("www.")
        return if (host == "soundcloud.com" || host == "m.soundcloud.com") "SOUNDCLOUD"
        else "DIRECT"
    }

    suspend fun search(source: String, query: String): List<AudioSourceCandidate> =
        withContext(Dispatchers.IO) {
            require(
                source in setOf("YOUTUBE", "SOUNDCLOUD") &&
                    query.isNotBlank() &&
                    query.length <= 500
            )
            YouTubeVideoDownload.initialize()
            YouTubeVideoDownload.extract {
                val service =
                    if (source == "SOUNDCLOUD") ServiceList.SoundCloud else ServiceList.YouTube
                val extractor = service.getSearchExtractor(query)
                extractor.fetchPage()
                extractor.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .filter { it.duration in 15..3600 }
                    .take(12)
                    .map {
                        AudioSourceCandidate(
                            it.url,
                            it.name,
                            it.uploaderName.orEmpty(),
                            it.duration,
                        )
                    }
            }
        }
}
