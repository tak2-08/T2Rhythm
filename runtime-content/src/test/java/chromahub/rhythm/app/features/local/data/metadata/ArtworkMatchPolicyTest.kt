package chromahub.rhythm.app.features.local.data.metadata

import org.junit.Assert.*
import org.junit.Test

class ArtworkMatchPolicyTest {
    @Test fun exactMatchingRequiresBothTitleAndArtistAndCorroboratingLength() {
        assertTrue(ArtworkMatchPolicy.accepts("Song", "Artist", "Song", "Artist", 100, 200_000, 201_000))
        assertFalse(ArtworkMatchPolicy.accepts("Song", "Artist", "Song", "Another artist", 90, 200_000, 200_000))
        assertFalse(ArtworkMatchPolicy.accepts("Song", "Artist", "Song", "Artist", 94, 200_000, 300_000))
        assertFalse(ArtworkMatchPolicy.accepts("Song", "Artist", "Song", "Artist", 94, 200_000, null))
    }
    @Test fun nearMatchThresholdIsConfigurableWithoutAWeightedScoreHidingWrongArtists() {
        assertTrue(ArtworkMatchPolicy.accepts("Somebody that I used to know", "Gotye", "Somebody that I use to know", "Gotye", 94))
        assertFalse(ArtworkMatchPolicy.accepts("Somebody that I used to know", "Gotye", "Somebody that I use to know", "Gotye", 100))
        assertFalse(ArtworkMatchPolicy.accepts("Somebody that I used to know", "Gotye", "Somebody that I used to know", "Sting", 90))
    }
    @Test fun youtubeDecorationAndArtistCreditsAreHandledWithoutTrustingArbitraryChannels() {
        assertEquals(1.0, ArtworkMatchPolicy.score("Song", "Artist", "Artist - Song (Official MV)", "Label", youtube = true), 1e-8)
        assertEquals(1.0, ArtworkMatchPolicy.score("Love poem", "아이유", "IU(아이유) 'Love poem' Official MV", "Label", youtube = true), 1e-8)
        assertEquals(1.0, ArtworkMatchPolicy.score("Song", "Artist", "Song (Official Audio)", "Artist - Topic", youtube = true), 1e-8)
        assertTrue(ArtworkMatchPolicy.score("Song", "Artist", "Song (Official Video)", "Unrelated", youtube = true) < .9)
    }
    @Test fun alternateRecordingsDoNotBecomeAutomaticCovers() {
        for (variant in listOf("Live", "Cover", "Remix", "Instrumental", "Sped up", "커버")) {
            assertEquals(0.0, ArtworkMatchPolicy.score("Song", "Artist", "Song ($variant)", "Artist", youtube = true), 0.0)
        }
    }
}
