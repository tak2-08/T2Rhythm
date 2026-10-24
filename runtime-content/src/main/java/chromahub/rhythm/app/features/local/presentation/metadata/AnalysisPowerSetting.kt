// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.shared.data.model.AppSettings
import kotlin.math.roundToInt

@Composable
fun AnalysisPowerSetting() {
    val settings = AppSettings.getInstance(LocalContext.current)
    val percent by settings.analysisMinimumBattery.collectAsState()
    var editing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.analysis_battery_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.analysis_battery_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { editing = true }, modifier = Modifier.testTag("analysis-battery-setting")) {
            Text(stringResource(R.string.analysis_battery_value, percent))
        }
    }
    if (editing) AnalysisPowerDialog { editing = false }
}

@Composable
fun AnalysisPowerDialog(onDismiss: () -> Unit) {
    val settings = AppSettings.getInstance(LocalContext.current)
    var draft by remember { mutableFloatStateOf(settings.analysisMinimumBattery.value.toFloat()) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.analysis_battery_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.analysis_battery_hint))
            Text(stringResource(R.string.analysis_battery_value, draft.roundToInt()), style = MaterialTheme.typography.headlineMedium)
            Slider(value = draft, onValueChange = { draft = it }, valueRange = 50f..95f, steps = 44,
                modifier = Modifier.fillMaxWidth().testTag("analysis-battery-slider"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("50%"); Text("95%") }
            TextButton(onClick = { draft = 75f }) { Text(stringResource(R.string.analysis_battery_reset)) }
        } },
        confirmButton = { TextButton(onClick = { settings.setAnalysisMinimumBattery(draft.roundToInt()); onDismiss() }) { Text(stringResource(R.string.page_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}
