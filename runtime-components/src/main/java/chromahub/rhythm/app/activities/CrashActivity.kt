// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.activities

import chromahub.rhythm.runtime.components.RuntimeAppCompatActivity

class CrashActivity : RuntimeAppCompatActivity() {
    override val runtimeRole = "crash"
    companion object {
        const val EXTRA_CRASH_LOG = "extra_crash_log"
        fun start(context: android.content.Context, crashLog: String) {
            context.startActivity(android.content.Intent(context, CrashActivity::class.java).apply {
                putExtra(EXTRA_CRASH_LOG, crashLog)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
        }
    }
}
