// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import org.junit.Assert.*
import org.junit.Test

class FileNamePolicyTest {
    @Test fun embeddedTitleAndArtistFormTheFilenameWithoutChangingTheExtension() {
        assertEquals("가수 - 노래", FileNamePolicy.base("노래", "가수", "ARTIST_TITLE"))
        assertEquals("노래.MP3", FileNamePolicy.filename(FileNamePolicy.base("노래", "가수", "TITLE")!!, "MP3"))
    }
    @Test fun missingTitleIsNeverInventedFromAnUntidyFilename() {
        listOf(null, "", "-", "Unknown", "<unknown>").forEach { assertNull(FileNamePolicy.base(it, "가수", "ARTIST_TITLE")) }
        assertEquals("노래", FileNamePolicy.base("노래", "Unknown Artist", "ARTIST_TITLE"))
    }
    @Test fun separatorsAndControlCharactersCannotEscapeTheExistingFolder() {
        assertEquals("가수 - 제목 위험", FileNamePolicy.base("../제목\\위험\n", "가수", "ARTIST_TITLE"))
        val name = FileNamePolicy.filename(FileNamePolicy.base("a/b:c*?\"<>|", "Artist", "ARTIST_TITLE")!!, "flac")
        assertFalse(name.any { it in "/\\:*?\"<>|" }); assertFalse(name.startsWith('.'))
    }
    @Test fun collisionSuffixesNeverOverwriteAnExistingName() {
        assertEquals("Artist - Song (3).mp3", FileNamePolicy.available("Artist - Song", "mp3", "untidy.mp3", setOf("ARTIST - SONG.mp3", "Artist - Song (2).MP3")))
        assertEquals("Artist - Song.mp3", FileNamePolicy.available("Artist - Song", "mp3", "Artist - Song.mp3", setOf("Artist - Song.mp3")))
    }
    @Test fun longUnicodeNamesStayWithinByteLimitsWithoutBreakingCharacters() {
        val name = FileNamePolicy.filename("음악🎵".repeat(500), "flac", 999)
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 220)
        assertTrue(name.endsWith(" (999).flac")); assertFalse(name.contains('\uFFFD'))
    }
}
