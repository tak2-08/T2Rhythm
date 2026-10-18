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
}
