// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ListeningReportTest {
    @Test fun calendarPeriodsHandleLeapYearsAndYearTransitions() {
        val now = LocalDate.of(2024, 2, 29)
        assertEquals(LocalDate.of(2024, 2, 26), ReportPeriod.WEEK.window(now).first)
        assertEquals(LocalDate.of(2024, 3, 1), ReportPeriod.MONTH.window(now).until)
        val quarter = ReportPeriod.QUARTER.window(LocalDate.of(2026, 1, 3), -1)
        assertEquals(LocalDate.of(2025, 10, 1), quarter.first)
        assertEquals(LocalDate.of(2026, 1, 1), quarter.until)
    }
    @Test fun daylightSavingsWeekUsesLocalMidnights() {
        val week = ReportPeriod.WEEK.window(LocalDate.of(2026, 3, 8), zone = ZoneId.of("America/New_York"))
        assertEquals(167 * 3600_000L, week.endMs - week.startMs)
    }
    @Test fun boundarySegmentsSplitActualListeningAndNeverCountGaps() {
        assertEquals(500L, ListeningReports.overlap(1000, 0, 2000, 1000, 3000))
        assertEquals(500L, ListeningReports.overlap(1000, 0, 2000, 0, 1000))
        assertEquals(0L, ListeningReports.overlap(1000, 0, 2000, 2000, 4000))
        assertEquals(0L, ListeningReports.overlap(1000, 2000, 2000, 0, 4000))
        assertEquals(1000L, ListeningReports.overlap(1000, 0, 2000, -1000, 3000))
    }
    @Test fun ongoingMonthComparesTheSameLocalDayAndTimeAcrossDst() {
        val zone = ZoneId.of("America/New_York")
        val now = java.time.ZonedDateTime.of(2026, 3, 15, 14, 30, 0, 0, zone)
        val window = ReportPeriod.MONTH.window(now.toLocalDate(), zone = zone)
        val (current, previous) = ListeningReports.comparisonRanges(window, now.toInstant().toEpochMilli())!!
        assertEquals(now.toInstant().toEpochMilli(), current.last + 1)
        assertEquals(java.time.ZonedDateTime.of(2026, 2, 15, 14, 30, 0, 0, zone).toInstant().toEpochMilli(), previous.last + 1)
        assertEquals(3600_000L, (previous.last - previous.first) - (current.last - current.first))
    }
    @Test fun shorterPreviousMonthCapsBothSidesInsteadOfComparingExtraDays() {
        val zone = ZoneId.of("UTC")
        val now = java.time.ZonedDateTime.of(2026, 3, 31, 12, 0, 0, 0, zone)
        val window = ReportPeriod.MONTH.window(now.toLocalDate(), zone = zone)
        val (current, previous) = ListeningReports.comparisonRanges(window, now.toInstant().toEpochMilli())!!
        assertEquals(28 * 86_400_000L, current.last - current.first + 1)
        assertEquals(current.last - current.first, previous.last - previous.first)
        assertNull(ListeningReports.comparisonRanges(window, window.startMs))
    }
    @Test fun completedPeriodUsesBothWholeCalendarPeriods() {
        val zone = ZoneId.of("UTC")
        val window = ReportPeriod.MONTH.window(LocalDate.of(2026, 3, 1), -1, zone)
        val (current, previous) = ListeningReports.comparisonRanges(window, window.endMs)!!
        assertEquals(28 * 86_400_000L, current.last - current.first + 1)
        assertEquals(31 * 86_400_000L, previous.last - previous.first + 1)
    }
    @Test fun artistAndGenreHighlightsUseAllTracksAndSplitMultiGenreTime() {
        val window = ReportPeriod.WEEK.window(LocalDate.of(2026, 10, 21))
        val tracks = listOf(ReportTrack("1", "1", "a", "Singer", "album", 1001, 1, "pop, rock"),
            ReportTrack("2", "2", "b", "singer", "album", 1000, 1, "POP"),
            ReportTrack("3", "3", "c", "Other", "album", 500, 1, "-"))
        val report = ListeningReports.summarize(ListeningReport(window, 2501, 3, 3, tracks, emptyList()))
        assertEquals("Singer", report.artists.first().name)
        assertEquals(2001L, report.artists.first().listenedMs)
        assertEquals(2, report.artists.first().tracks)
        assertEquals(listOf("POP" to 1501L, "ROCK" to 500L), report.genres)
        assertTrue(report.genres.sumOf { it.second } <= report.listenedMs)
    }
}
