// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import chromahub.rhythm.app.features.local.data.discovery.*
import org.junit.Assert.*
import org.junit.Test

class ManualLrcPlaybackTest {
    @Test fun capturedEndsClearGapsAndDoNotEraseAdjacentLinesInThePlayerParser() {
        val rows = listOf(TimedLine(1234, "첫 번째", 3456), TimedLine(3456, "두 번째", 5678), TimedLine(7890, "세 번째", 9876))
        val parsed = LyricsParser.parseLyrics(LocalLyricAlignment.lrc(rows))
        val words = parsed.filter { it.text.isNotBlank() }
        assertEquals(rows.map { it.text }, words.map { it.text })
        assertEquals(rows.map { it.ms }, words.map { it.timestamp })
        assertTrue(parsed.any { it.timestamp == 5678L && it.text.isBlank() } || words[1].endTime == 5678L)
    }
}
