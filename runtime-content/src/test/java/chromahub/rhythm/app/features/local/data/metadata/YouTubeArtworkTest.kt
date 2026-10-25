package chromahub.rhythm.app.features.local.data.metadata

import chromahub.rhythm.app.features.local.data.discovery.AudioSourceCandidate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class YouTubeArtworkTest {
    @Test fun searchImportsOnlyTheVerifiedThumbnailAndLeavesOtherMetadataUntouched() = runBlocking {
        val result = YouTubeArtwork.search("Song", "Artist", 200_000, CatalogOptions()) { query ->
            assertEquals("Artist Song", query)
            listOf(AudioSourceCandidate("https://www.youtube.com/watch?v=abcdefghijk", "Artist - Song (Official Video)", "Label", 201),
                AudioSourceCandidate("https://www.youtube.com/watch?v=abcdefghij0", "Artist - Song (Live)", "Artist", 200),
                AudioSourceCandidate("https://www.youtube.com/watch?v=abcdefghij1", "Song", "Different", 200))
        }
        assertEquals(1, result.size)
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg", result.single().thumbnail)
        assertEquals(listOf(kr.co.metadata.crawler.CatalogField.TRACK_IMAGE), result.single().document().values.map { it.field })
        assertEquals("201000", result.single().document(includeDuration = true)[kr.co.metadata.crawler.CatalogField.TRACK_DURATION_MS])
    }
    @Test fun disablingFallbackPreventsAnyRequestAndProviderFailureDoesNotBreakExistingMetadata() = runBlocking {
        val disabled = YouTubeArtwork.search("Song", "Artist", 200_000, CatalogOptions(youtubeArtworkFallback = false)) { error("Must not search") }
        assertTrue(disabled.isEmpty())
        assertTrue(YouTubeArtwork.search("Song", "Artist", 200_000, CatalogOptions()) { throw java.io.IOException("Unavailable") }.isEmpty())
    }
    @Test fun theSharedDurationPreferenceAlsoAppliesToYouTubeMatching() = runBlocking {
        val fetch: suspend (String) -> List<AudioSourceCandidate> = {
            listOf(AudioSourceCandidate("https://www.youtube.com/watch?v=abcdefghijk", "Song", "Artist", 400))
        }
        assertTrue(YouTubeArtwork.search("Song", "Artist", 200_000, CatalogOptions(), fetch).isEmpty())
        assertEquals(1, YouTubeArtwork.search("Song", "Artist", 200_000, CatalogOptions(strictDuration = false), fetch).size)
    }
}
