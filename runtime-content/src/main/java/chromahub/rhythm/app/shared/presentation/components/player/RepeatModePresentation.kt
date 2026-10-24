// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.shared.presentation.components.player

import androidx.media3.common.Player
import chromahub.rhythm.app.R
import chromahub.rhythm.app.shared.presentation.components.icons.RhythmIcons

fun repeatModeIcon(mode: Int) = if (mode == Player.REPEAT_MODE_ONE) RhythmIcons.Player.RepeatOne else RhythmIcons.Player.Repeat

fun repeatModeLabel(mode: Int): Int = when (mode) {
    Player.REPEAT_MODE_ONE -> R.string.repeat_current_forever
    Player.REPEAT_MODE_ALL -> R.string.repeat_queue_forever
    else -> R.string.repeat_disabled
}
