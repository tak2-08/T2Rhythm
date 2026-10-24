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
import chromahub.rhythm.runtime.api.RuntimeComponentRegistry
import java.util.concurrent.Executors

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
        root.addView(Button(this).apply {
            text = getString(R.string.runtime_restore_embedded)
            setOnClickListener {
                android.app.AlertDialog.Builder(this@RuntimeRecoveryActivity)
                    .setMessage(R.string.runtime_restore_embedded_prompt)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        isEnabled = false
                        Executors.newSingleThreadExecutor().apply {
                            execute {
                                val restored = (RuntimeComponentRegistry.host() as NativeRuntimeBootstrap).restoreEmbeddedForRecovery()
                                runOnUiThread {
                                    if (restored) {
                                        startActivity(Intent(this@RuntimeRecoveryActivity, RuntimeRestartActivity::class.java)
                                            .putExtra("origin_pid", Process.myPid()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        finish()
                                    } else {
                                        android.widget.Toast.makeText(this@RuntimeRecoveryActivity, R.string.runtime_verification_failed, android.widget.Toast.LENGTH_LONG).show()
                                        isEnabled = true
                                    }
                                }
                                shutdown()
                            }
                        }
                    }.show()
            }
        })
        setContentView(root)
    }
}
