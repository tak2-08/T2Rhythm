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

object AudioSources {
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
