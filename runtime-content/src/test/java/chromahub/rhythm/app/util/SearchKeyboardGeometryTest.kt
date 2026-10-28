// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchKeyboardGeometryTest {
    @Test fun modernWindowUsesTheRealImeEdgeEvenBeforeTheVisibleFrameResizes() {
        assertEquals(SearchKeyboardGeometry(0, 1048, 872),
            calculateSearchKeyboardGeometry(1920f, 0, 0, 0, 1920, 1920, 1857, 872))
    }

    @Test fun capturedAndroid17PanDoesNotCountAsAnotherKeyboardInset() {
        // Captured failure: a -739px pan used to move the computed IME top to 309px.
        assertEquals(SearchKeyboardGeometry(0, 1048, 872),
            calculateSearchKeyboardGeometry(1920f, -739, -739, -739, 1920, 1920, 1048, 872))
    }

    @Test fun capturedLegacyPanPreservesTheFittingDecorOffset() {
        assertEquals(SearchKeyboardGeometry(63, 1053, 741),
            calculateSearchKeyboardGeometry(1731f, -602, -665, -665, 1920, 1731, 1053, null))
    }

    @Test fun alreadyResizedLegacyDestinationNeedsNoSecondImePadding() {
        assertEquals(SearchKeyboardGeometry(63, 1053, 0),
            calculateSearchKeyboardGeometry(990f, 63, 0, 0, 1920, 990, 1053, null))
    }

    @Test fun panAndMultiwindowTranslationDoNotChangeTheOverlap() {
        for (windowTop in listOf(0, 160, 300)) for (pan in 0..900 step 37) {
            val geometry = calculateSearchKeyboardGeometry(1920f, windowTop - pan,
                windowTop - pan, -pan, 1920, 1920, windowTop + 1048, 872)
            assertEquals(872, geometry.overlap)
            assertEquals(windowTop + 1048, geometry.keyboardTop)
            assertEquals(windowTop, geometry.viewOrigin)
        }
    }
}
