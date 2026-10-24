// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.experience.*
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import kotlinx.coroutines.*

@Composable
fun SoftUpdatePanel(checkUpdates: (suspend () -> ExperienceRulesCheck)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by ExperienceProfile.revision.collectAsState()
    var version by remember { mutableIntStateOf(ExperienceProfile.read(context).version) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ExperienceRulesStatus?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(revision) { version = withContext(Dispatchers.IO) { ExperienceProfile.read(context).version } }
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("recommendation-rules-card"), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(MaterialSymbolIcon("tune"), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.soft_update_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.soft_update_current, version), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(stringResource(R.string.soft_update_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (result != null || failed) Text(
                stringResource(if (failed) R.string.soft_update_error else when (result) {
                    ExperienceRulesStatus.UPDATED -> R.string.soft_update_done
                    ExperienceRulesStatus.APP_UPDATE_REQUIRED -> R.string.soft_update_app_required
                    else -> R.string.soft_update_latest
                }),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                busy = true; failed = false; result = null
                scope.launch {
                    try {
                        val checked = checkUpdates?.invoke() ?: ExperienceRulesWorker.checkNow(context)
                        version = checked.version; result = checked.status
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { failed = true }
                    finally { busy = false }
                }
            }) {
                Icon(MaterialSymbolIcon("refresh"), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (busy) R.string.soft_update_checking else R.string.soft_update_check))
            }
        }
    }
}
