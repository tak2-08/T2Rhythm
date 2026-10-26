// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import org.junit.Assert.*
import org.junit.Test

class PlaylistTextParserTest {
    @Test fun m3uAcceptsBomIndentationAndCrLfWithoutLosingMetadata() {
        val content = "\uFEFF#EXTM3U\r\n  #EXTINF:-1,김광석 - 서른, 즈음에\r\n  Music/song.mp3  \r\n#comment\r\nsecond.flac\r\n"
        assertEquals(listOf(PlaylistTextEntry("Music/song.mp3", "김광석 - 서른, 즈음에"), PlaylistTextEntry("second.flac")), PlaylistTextParser.m3u(content))
    }
    @Test fun missingExtinfTitleDoesNotLeakPreviousMetadata() {
        assertEquals(listOf(PlaylistTextEntry("a", "old"), PlaylistTextEntry("b")),
            PlaylistTextParser.m3u("#EXTINF:1,old\na\n#EXTINF:2\nb"))
    }
    @Test fun plsAcceptsCaseWhitespaceUnorderedKeysAndEqualsInPaths() {
        assertEquals(listOf(PlaylistTextEntry("a.mp3", "First"), PlaylistTextEntry("https://example.test/song?id=2", "Second")),
            PlaylistTextParser.pls("\uFEFF[playlist]\n title2 = Second\n FILE2 = https://example.test/song?id=2\nfile1=a.mp3\nTitle1=First\nNumberOfEntries=2"))
    }
    @Test fun plsRejectsInvalidNumbersAndBlankFiles() {
        assertEquals(listOf(PlaylistTextEntry("valid")), PlaylistTextParser.pls("File0=zero\nFile-1=negative\nFile999999999999=overflow\nFile3=\nTitle4=orphan\nFile2=valid"))
    }
    @Test fun duplicatePathsRemainAvailableForCallerPolicy() {
        assertEquals(2, PlaylistTextParser.m3u("a.mp3\na.mp3").size)
        assertEquals(2, PlaylistTextParser.pls("File1=a.mp3\nFile2=a.mp3").size)
    }
}
