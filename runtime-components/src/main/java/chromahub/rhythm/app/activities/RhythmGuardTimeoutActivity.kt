// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.activities

import chromahub.rhythm.runtime.components.RuntimeAppCompatActivity

class RhythmGuardTimeoutActivity : RuntimeAppCompatActivity() {
    override val runtimeRole = "guard"
    companion object {
        const val EXTRA_REASON = "extra_timeout_reason"
        const val EXTRA_TIMEOUT_UNTIL_MS = "extra_timeout_until_ms"
        const val EXTRA_TIMEOUT_STARTED_AT_MS = "extra_timeout_started_at_ms"
        fun start(context: android.content.Context, reason: String, timeoutUntilMs: Long, timeoutStartedAtMs: Long) {
            context.startActivity(android.content.Intent(context, RhythmGuardTimeoutActivity::class.java).apply {
                putExtra(EXTRA_REASON, reason)
                putExtra(EXTRA_TIMEOUT_UNTIL_MS, timeoutUntilMs)
                putExtra(EXTRA_TIMEOUT_STARTED_AT_MS, timeoutStartedAtMs)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
        }
    }
}
