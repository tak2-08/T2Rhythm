// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import chromahub.rhythm.app.shared.data.model.LyricsData
import org.junit.Assert.*
import org.junit.Test

class LyricTimingInputTest {
    private val text = "첫 번째 문장\n두 번째 문장\n마지막 문장"
    @Test fun ordinaryTextInEitherProviderFieldCanBeAlignedWithoutChangingItsSnapshot() {
        listOf(LyricsData(text, null), LyricsData(null, text), LyricsData("older text", text)).forEach {
            val before = it.copy()
            assertEquals(text.lines(), LyricTimingInput.lines(it))
            assertEquals(before, it)
        }
    }
    @Test fun existingTimestampsAreRecognizedEvenInThePlainField() {
        val lrc = "[00:01]첫 번째 문장\n[00:04]두 번째 문장\n[00:08]마지막 문장"
        assertTrue(LyricTimingInput.lines(LyricsData(lrc, null)).isEmpty())
        assertTrue(LyricTimingInput.lines(LyricsData(text, lrc)).isEmpty())
        assertTrue(LyricTimingInput.lines(LyricsData(text, null, "[{\"timestamp\":1000}]")).isEmpty())
    }
    @Test fun metadataAndProviderErrorsAreNotTreatedAsSungLines() {
        assertEquals(text.lines(), LyricTimingInput.lines(LyricsData(null, "[ar:가수]\n[ti:곡]\n$text")))
        assertTrue(LyricTimingInput.lines(LyricsData("Error fetching lyrics", null)).isEmpty())
        assertTrue(LyricTimingInput.lines(LyricsData(null, "<p begin=\"1s\">가사</p>")).isEmpty())
    }
}
