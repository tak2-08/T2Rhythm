// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.module

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import chromahub.rhythm.runtime.api.NativeUiModule

/** Test-only screen. Not a replacement design or feature implementation for T2Rhythm. */
class ComposeProbeEntry : NativeUiModule {
    init { System.setProperty("t2rhythm.runtime.probe.initialized", "true") }

    override fun createView(context: Context): View = ComposeView(context).apply {
        setContent {
            MaterialTheme {
                var clicks by remember { mutableIntStateOf(0) }
                Column(Modifier.padding(24.dp)) {
                    Text("T2Rhythm native module", style = MaterialTheme.typography.titleLarge)
                    Text("Loaded from verified DEX")
                    Button(onClick = { clicks++ }) { Text("Module clicks: $clicks") }
                }
            }
        }
    }
}
