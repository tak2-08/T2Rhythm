// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import chromahub.rhythm.app.features.local.data.discovery.AudioSources
import chromahub.rhythm.app.features.local.data.video.MusicVideoLinks
import kr.co.metadata.crawler.*
import kotlinx.coroutines.CancellationException

internal data class YouTubeArtworkMatch(val id: String, val title: String, val artist: String, val score: Double, val durationMs: Long) {
    val page get() = "https://www.youtube.com/watch?v=$id"
    val thumbnail get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    fun document(includeDuration: Boolean = false) = CatalogDocument(EntityKind.TRACK,
        listOf(CatalogValue(CatalogField.TRACK_IMAGE, thumbnail, page)) +
            if (includeDuration) listOf(CatalogValue(CatalogField.TRACK_DURATION_MS, durationMs.toString(), page)) else emptyList())
}

internal object YouTubeArtwork {
    suspend fun search(title: String, artist: String, durationMs: Long, options: CatalogOptions,
        fetch: suspend (String) -> List<chromahub.rhythm.app.features.local.data.discovery.AudioSourceCandidate> = { AudioSources.search("YOUTUBE", it) }): List<YouTubeArtworkMatch> {
        if (!options.youtubeArtworkFallback || CatalogIdentity.missing(title) || CatalogIdentity.missing(artist)) return emptyList()
        return try {
            fetch("$artist $title".take(500)).mapNotNull { hit ->
                val id = MusicVideoLinks.youtubeId(hit.url) ?: return@mapNotNull null
                if (!ArtworkMatchPolicy.accepts(title, artist, hit.title, hit.artist, options.artworkMatchPercent,
                    if (options.strictDuration) durationMs else 0, hit.durationSeconds * 1000, options.durationTolerancePercent, youtube = true)) return@mapNotNull null
                YouTubeArtworkMatch(id, hit.title, hit.artist, ArtworkMatchPolicy.score(title, artist, hit.title, hit.artist, youtube = true), hit.durationSeconds * 1000)
            }.distinctBy { it.id }.sortedByDescending { it.score }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
    }
}
