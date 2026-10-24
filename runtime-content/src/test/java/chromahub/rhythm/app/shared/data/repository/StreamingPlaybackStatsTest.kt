// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.shared.data.repository

import chromahub.rhythm.app.features.local.data.experience.ListeningStatsRow
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class StreamingPlaybackStatsTest {
    private val utc = ZoneId.of("UTC")

    private fun row(song: String, start: Long, end: Long, play: String = song) =
        ListeningStatsRow(song, play, start, end, song, "Artist", "Album", "Pop")

    @Test
    fun overlapDoesNotDoubleCountWallTimeAndCheckpointsDoNotInflatePlays() {
        val stats = StreamingPlaybackStats(StatsTimeRange.ALL_TIME, 180000, utc, Locale.US)
        stats.add(row("A", 0, 60000))
        stats.add(row("A", 30000, 90000))
        stats.add(row("B", 45000, 120000))
        val result = stats.finish(mapOf("A" to 1, "B" to 1))
        assertEquals(120000L, result.totalDurationMs)
        assertEquals(2, result.totalPlayCount)
        assertEquals(listOf(90000L, 75000L), result.topSongs.map { it.totalDurationMs })
        assertEquals(1, result.totalSessions)
        assertEquals(result.totalDurationMs, result.dailyDistribution.sumOf { it.totalDurationMs })
    }

    @Test
    fun anOvernightPlayIsClippedAtTheRequestedLocalMidnight() {
        val midnight = Instant.parse("2026-10-15T00:00:00Z").toEpochMilli()
        val stats =
            StreamingPlaybackStats(StatsTimeRange.TODAY, midnight + 12 * 3600000, utc, Locale.US)
        stats.add(row("A", midnight - 60000, midnight + 60000))
        val result = stats.finish(mapOf("A" to 1))
        assertEquals(60000L, result.totalDurationMs)
        assertEquals(60000L, result.dailyDistribution[0].totalDurationMs)
    }

    @Test
    fun repeatedDaylightSavingHourRetainsEveryMinute() {
        val start = Instant.parse("2026-11-01T05:30:00Z").toEpochMilli()
        val end = Instant.parse("2026-11-01T07:30:00Z").toEpochMilli()
        val stats =
            StreamingPlaybackStats(
                StatsTimeRange.TODAY,
                end,
                ZoneId.of("America/New_York"),
                Locale.US,
            )
        stats.add(row("A", start, end))
        val result = stats.finish(mapOf("A" to 1))
        assertEquals(2 * 3600000L, result.totalDurationMs)
        assertEquals(90 * 60000L, result.dailyDistribution[1].totalDurationMs)
        assertEquals(30 * 60000L, result.dailyDistribution[2].totalDurationMs)
        assertEquals(result.totalDurationMs, result.timeline.sumOf { it.totalDurationMs })
    }

    @Test
    fun aLargeHistoryCanBeConsumedWithoutAnEventList() {
        val count = 100000
        val start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val stats =
            StreamingPlaybackStats(StatsTimeRange.ALL_TIME, start + count * 1000L, utc, Locale.US)
        repeat(count) { index ->
            stats.add(
                row("${index % 128}", start + index * 1000L, start + (index + 1) * 1000L, "$index")
            )
        }
        val counts = (0 until 128).associate { "$it" to (count + 127 - it) / 128 }
        val result = stats.finish(counts)
        assertEquals(count * 1000L, result.totalDurationMs)
        assertEquals(count, result.totalPlayCount)
        assertEquals(128, result.uniqueSongs)
        assertEquals(5, result.topSongs.size)
        assertEquals(result.totalDurationMs, result.timeline.sumOf { it.totalDurationMs })
    }

    @Test
    fun weeklyAverageIncludesQuietDaysAndEmptyHistoryHasNoFakePeak() {
        val now = Instant.parse("2026-10-15T12:00:00Z").toEpochMilli()
        val stats = StreamingPlaybackStats(StatsTimeRange.WEEK, now, utc, Locale.US)
        stats.add(row("A", now - 70000, now))
        assertEquals(10000L, stats.finish(mapOf("A" to 1)).averageDailyDurationMs)
        val empty =
            StreamingPlaybackStats(StatsTimeRange.TODAY, now, utc, Locale.US).finish(emptyMap())
        assertNull(empty.peakHour)
        assertEquals(0, empty.totalPlayCount)
        assertEquals(0, empty.totalSessions)
    }
}
