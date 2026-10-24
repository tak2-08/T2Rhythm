// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.activities

import chromahub.rhythm.runtime.components.RuntimeAppCompatActivity

class MainActivity : RuntimeAppCompatActivity() {
    override val runtimeRole = "main"
    companion object {
        const val DISPLAY_AUDIO_EFFECT_CONTROL_PANEL_REQUEST = 1002
        const val EXTRA_OPEN_PLAYER = "OPEN_PLAYER"
        const val EXTRA_OPEN_QUEUE = "OPEN_QUEUE"
    }
}
