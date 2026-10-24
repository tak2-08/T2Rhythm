// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.base

import chromahub.rhythm.app.R

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Always available from the fixed APK, including when a module's class initialization fails. */
class RuntimeRecoveryActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding * 2, padding, padding)
        }
        root.addView(TextView(this).apply { text = "T2Rhythm"; textSize = 26f })
        root.addView(TextView(this).apply {
            text = getString(R.string.runtime_recovery_message)
            textSize = 16f
            setPadding(0, padding, 0, padding)
        })
        root.addView(Button(this).apply {
            text = getString(R.string.runtime_recover_restart)
            setOnClickListener {
                startActivity(Intent(this@RuntimeRecoveryActivity, RuntimeRestartActivity::class.java)
                    .putExtra("origin_pid", Process.myPid()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
        })
        setContentView(root)
    }
}
