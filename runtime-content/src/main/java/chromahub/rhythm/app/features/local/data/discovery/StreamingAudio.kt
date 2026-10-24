// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.net.Uri
import androidx.media3.datasource.DataSpec
import chromahub.rhythm.app.features.local.data.video.AudioDownload
import chromahub.rhythm.app.features.local.data.video.YouTubeVideoDownload
import chromahub.rhythm.app.shared.data.model.Song
import java.io.IOException
import kotlinx.coroutines.*
import kr.co.metadata.crawler.CatalogFileStore
import kr.co.metadata.crawler.ChartTrack
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

/** Stable queue URIs are resolved on Media3's loader thread, never written to the library. */
object StreamingAudio {
    private const val HOST = "stream.t2rhythm.invalid"

    private data class Source(val url: String, val suffix: String, val expires: Long)

    private val cache =
        object : LinkedHashMap<String, Source>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Source>?) =
                size > 24
        }

    private suspend fun source(url: String, hls: Boolean? = null): Source =
        withContext(Dispatchers.IO) {
            val key = "$url|$hls"
            synchronized(cache) {
                    cache[key]?.takeIf { it.expires > android.os.SystemClock.elapsedRealtime() }
                }
                ?.let {
                    return@withContext it
                }
            val service = AudioSources.service(url)
            val result =
                if (service == "DIRECT") Source(url, "", Long.MAX_VALUE)
                else {
                    YouTubeVideoDownload.initialize()
                    val info =
                        YouTubeVideoDownload.extract {
                            StreamInfo.getInfo(
                                if (service == "SOUNDCLOUD") ServiceList.SoundCloud
                                else ServiceList.YouTube,
                                url,
                            )
                        }
                    val streams =
                        info.audioStreams.filter {
                            hls == null || (it.deliveryMethod == DeliveryMethod.HLS) == hls
                        }
                    val stream = AudioDownload.choose(streams, service)
                    Source(
                        stream.content,
                        if (stream.deliveryMethod == DeliveryMethod.HLS) "m3u8"
                        else stream.format?.suffix ?: "m4a",
                        android.os.SystemClock.elapsedRealtime() + 120_000,
                    )
                }
            synchronized(cache) { cache[key] = result }
            result
        }

    suspend fun song(track: ChartTrack, candidate: AudioSourceCandidate): Song {
        val service = AudioSources.service(candidate.url)
        val resolved = source(candidate.url)
        val uri =
            if (service == "DIRECT") Uri.parse(candidate.url)
            else
                Uri.Builder()
                    .scheme("https")
                    .authority(HOST)
                    .path("/audio.${resolved.suffix}")
                    .appendQueryParameter("source", candidate.url)
                    .build()
        if (service != "DIRECT")
            synchronized(cache) {
                cache["${candidate.url}|${resolved.suffix == "m3u8"}"] = resolved
            }
        return Song(
            "stream:" + CatalogFileStore.hash(candidate.url),
            track.title,
            track.artist,
            track.album,
            duration = candidate.durationSeconds.coerceIn(0, 86_400) * 1000,
            uri = uri,
            artworkUri = track.artwork?.let(Uri::parse),
        )
    }

    fun resolve(data: DataSpec): DataSpec {
        if (data.uri.scheme != "https" || data.uri.host != HOST) return data
        try {
            val url = requireNotNull(data.uri.getQueryParameter("source"))
            val resolved = runBlocking {
                source(url, data.uri.lastPathSegment?.endsWith(".m3u8") == true)
            }
            return data.withUri(Uri.parse(resolved.url))
        } catch (error: Exception) {
            throw IOException("Could not resolve audio source", error)
        }
    }
}
