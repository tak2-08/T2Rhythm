// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.base

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.content.Intent

/** Recovery only: a separate installed process starts the app after the failed process exits. */
class RuntimeRestartActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val origin = intent.getIntExtra("origin_pid", -1)
        if (origin <= 0 || origin == Process.myPid()) { finish(); return }
        // Only processes belonging to this UID can be signalled by the Android app sandbox.
        Process.killProcess(origin)
        Handler(Looper.getMainLooper()).postDelayed({
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
            finish()
        }, 350)
    }
}
