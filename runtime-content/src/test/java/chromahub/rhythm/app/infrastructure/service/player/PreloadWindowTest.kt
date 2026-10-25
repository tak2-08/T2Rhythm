// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.infrastructure.service.player

import org.junit.Assert.*
import org.junit.Test

class PreloadWindowTest {
    @Test fun hundredThousandTrackQueueStillVisitsAtMostTwoUpcomingItems() {
        var visits = 0
        assertEquals(listOf(50_001, 50_002), PreloadWindow.upcoming(100_000, 50_000, 1000) { visits++; it + 1 })
        assertEquals(2, visits)
    }
    @Test fun endRepeatAndEmptyQueuesNeverProduceInvalidItems() {
        assertTrue(PreloadWindow.upcoming(0, -1, 2) { error("must not visit") }.isEmpty())
        assertTrue(PreloadWindow.upcoming(10, 9, 2) { -1 }.isEmpty())
        assertTrue(PreloadWindow.upcoming(10, 4, 2) { 4 }.isEmpty())
        assertTrue(PreloadWindow.upcoming(10, 4, 0) { error("disabled") }.isEmpty())
    }
    @Test fun shuffleOrderingAndRepeatAllUseActualNextIndices() {
        assertEquals(listOf(8, 2), PreloadWindow.upcoming(10, 5, 2) { if (it == 5) 8 else 2 })
        assertEquals(listOf(0, 1), PreloadWindow.upcoming(10, 9, 2) { (it + 1) % 10 })
        assertEquals(listOf(1), PreloadWindow.upcoming(2, 0, 2) { (it + 1) % 2 })
    }
}
