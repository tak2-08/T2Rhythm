// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.shared.data.repository

import java.io.Reader
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class LegacyPlaybackImportTest {
    private fun event(i: Int) =
        """{"songId":"song-$i","timestamp":${(i + 1) * 1000L},"durationMs":1000,"future":{"ignored":[1,2,3]}}"""

    @Test
    fun largeHistoryIsReadInBoundedBatchesWithoutMaterializingTheArray() {
        // Generate the source on demand: neither production nor this test holds the full document.
        val size = 100000
        val reader =
            object : Reader() {
                var i = -1
                var piece = "["
                var offset = 0

                override fun read(buffer: CharArray, start: Int, length: Int): Int {
                    if (offset == piece.length) {
                        if (i == size) return -1
                        i++
                        piece = if (i == size) "]" else (if (i == 0) "" else ",") + event(i)
                        offset = 0
                    }
                    val count = minOf(length, piece.length - offset)
                    piece.toCharArray(buffer, start, offset, offset + count)
                    offset += count
                    return count
                }

                override fun close() {}
            }
        var received = 0
        LegacyPlaybackImport.read(reader) { batch ->
            if (received == 0) assertTrue("Consume before reading the full source", reader.i < size)
            assertTrue(batch.size in 1..128)
            assertEquals("song-$received", batch.first().songId)
            received += batch.size
        }
        assertEquals(size, received)
    }

    @Test
    fun incompleteSourceCommitsOnlyCompleteBatchesAndNeverReportsSuccess() {
        var received = 0
        val source = (0..127).joinToString(",", "[", ",{") { event(it) }
        try {
            LegacyPlaybackImport.read(StringReader(source)) { received += it.size }
            fail("An interrupted file must not complete migration")
        } catch (_: Exception) {
            assertEquals(128, received)
        }
    }

    @Test
    fun emptyHistoryAndOldOptionalFieldsAreSupported() {
        for (text in listOf("", " \r\n ", "[]")) {
            LegacyPlaybackImport.read(StringReader(text)) { fail("No events expected") }
        }
        var read: PlaybackStatsRepository.PlaybackEvent? = null
        LegacyPlaybackImport.read(StringReader("[${event(0)}]")) { read = it.single() }
        val event = requireNotNull(read)
        assertEquals(0L, event.startMillis())
        assertNull(event.songTitle)
    }

    @Test
    fun corruptIntervalsNullsAndTrailingDocumentsRemainUnfinished() {
        for (source in
            listOf(
                "[null]",
                "[{}]",
                "[{\"songId\":null}]",
                "[{\"songId\":\"s\",\"timestamp\":100,\"durationMs\":-1}]",
                "[] []",
            )) {
            var succeeded = false
            try {
                LegacyPlaybackImport.read(StringReader(source)) {}
                succeeded = true
            } catch (_: Exception) {}
            assertFalse("Should reject $source", succeeded)
        }
    }
}
