// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import androidx.media3.common.Player
import chromahub.rhythm.app.R
import chromahub.rhythm.app.shared.presentation.components.icons.RhythmIcons
import chromahub.rhythm.app.shared.presentation.components.player.*
import org.junit.Assert.*
import org.junit.Test

class RepeatModePresentationTest {
    @Test fun media3RepeatOneUsesTheNumberedIconAndContinuousSongLabel() {
        assertEquals(RhythmIcons.Player.RepeatOne, repeatModeIcon(Player.REPEAT_MODE_ONE))
        assertEquals(R.string.repeat_current_forever, repeatModeLabel(Player.REPEAT_MODE_ONE))
    }
    @Test fun queueRepetitionUsesThePlainIconAndDistinctOffLabel() {
        assertEquals(RhythmIcons.Player.Repeat, repeatModeIcon(Player.REPEAT_MODE_ALL))
        assertEquals(R.string.repeat_queue_forever, repeatModeLabel(Player.REPEAT_MODE_ALL))
        assertEquals(R.string.repeat_disabled, repeatModeLabel(Player.REPEAT_MODE_OFF))
    }
}
