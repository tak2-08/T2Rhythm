// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.base

import chromahub.rhythm.app.R

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import chromahub.rhythm.runtime.api.RuntimeComponentRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fixed, non-exported confirmation UI: a successful download never approves itself. */
class RuntimeApprovalActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hash = intent.getStringExtra("archive_hash") ?: run { finish(); return }
        val controller = RuntimeComponentRegistry.host().updates() as NativeUpdateController
        lifecycleScope.launch {
            val candidate = withContext(Dispatchers.IO) { runCatching { controller.verifyCandidate(hash) } }.getOrElse {
                Toast.makeText(this@RuntimeApprovalActivity, getString(R.string.runtime_verification_failed), Toast.LENGTH_LONG).show()
                finish(); return@launch
            }
            if (candidate.getBoolean("approved")) {
                Toast.makeText(this@RuntimeApprovalActivity, getString(R.string.runtime_apply_pending), Toast.LENGTH_LONG).show()
                finish(); return@launch
            }
            AlertDialog.Builder(this@RuntimeApprovalActivity)
                .setTitle(getString(R.string.runtime_approval_title, candidate.getString("versionName")))
                .setMessage(getString(R.string.runtime_approval_message, candidate.getString("notes")))
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    lifecycleScope.launch {
                        val approved = withContext(Dispatchers.IO) { runCatching { controller.confirmApproval(hash) } }.isSuccess
                        Toast.makeText(this@RuntimeApprovalActivity,
                            if (approved) getString(R.string.runtime_approval_confirmed) else getString(R.string.runtime_verification_failed), Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
                .setOnCancelListener { finish() }
                .show()
        }
    }
}
