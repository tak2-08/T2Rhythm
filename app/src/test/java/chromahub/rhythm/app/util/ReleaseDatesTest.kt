// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util
import org.junit.Assert.*
import org.junit.Test
class ReleaseDatesTest {
    @Test fun realReleaseDatesKeepTheirPrecisionAndCalendarValidity() {
        assertEquals("2024-02-29", ReleaseDates.display("2024.02.29"))
        assertEquals("2024-02-29", ReleaseDates.display("20240229"))
        assertNull(ReleaseDates.display("2023-02-29"))
        assertNull(ReleaseDates.display("2024-13-01"))
    }
    @Test fun aKnownYearNeverInventsADayOrUsesFileModificationDate() {
        assertEquals("2020", ReleaseDates.display("2020"))
        assertEquals("1999", ReleaseDates.display(null, 1999))
        assertNull(ReleaseDates.display("2024-01", 0))
        assertNull(ReleaseDates.display(null, 0))
    }
}
