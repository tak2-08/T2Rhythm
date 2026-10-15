// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.discovery.*
import chromahub.rhythm.app.features.local.data.experience.PersonalCollections
import chromahub.rhythm.app.features.local.data.video.DownloadRegistry
import kotlinx.coroutines.*

@Composable fun LearningPreferences() {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val store=remember { EnrichmentStore(context) }
    var mode by remember { mutableStateOf(store.mode()) };var choices by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf(SpeechModelStore.installed(context)) };var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) };var remove by remember { mutableStateOf(false) }
    var hours by remember { mutableIntStateOf(PersonalCollections.options(context).refreshHours.takeIf { it in 1..48 } ?: 6) }
    var refreshChoices by remember { mutableStateOf(false) }
    val revision by DownloadRegistry.revision.collectAsState()
    LaunchedEffect(revision) { installed=withContext(Dispatchers.IO) { SpeechModelStore.installed(context) } }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(context.getString(R.string.discovery_learning_settings),style=MaterialTheme.typography.titleMedium)
        Text(context.getString(R.string.discovery_learning_hint),style=MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(onClick={choices=true}) { Text(context.getString(when(mode) { "PRECISE"->R.string.discovery_mode_precise;"HYBRID"->R.string.discovery_mode_hybrid;else->R.string.discovery_mode_light })) }
            DropdownMenu(choices,onDismissRequest={choices=false}) {
                listOf("LIGHT" to R.string.discovery_mode_light,"PRECISE" to R.string.discovery_mode_precise,"HYBRID" to R.string.discovery_mode_hybrid).forEach { (value,label) ->
                    DropdownMenuItem(text={Text(context.getString(label))},onClick={mode=value;choices=false;store.mode(value)})
                }
            }
        }
        if(mode!="LIGHT" || installed) {
            Text(context.getString(R.string.discovery_model_size),style=MaterialTheme.typography.bodySmall)
            if(installed)TextButton(enabled=!busy,onClick={remove=true}) { Text(context.getString(R.string.discovery_remove_model)) }
            else OutlinedButton(enabled=!busy,onClick={scope.launch {
                busy=true;error=false
                try { SpeechModelStore.enqueue(context) } catch(cancel:CancellationException) { throw cancel } catch(_:Exception) { error=true }
                finally { busy=false }
            }}) { Text(context.getString(R.string.discovery_install_model)) }
            Text(context.getString(if(installed)R.string.discovery_model_ready else R.string.discovery_model_wait),style=MaterialTheme.typography.labelSmall)
        }
        Box {
            TextButton(onClick={refreshChoices=true}) { Text(context.getString(R.string.discovery_auto_refresh,hours)) }
            DropdownMenu(refreshChoices,onDismissRequest={refreshChoices=false}) {
                listOf(1,3,6,12,24,48).forEach { value -> DropdownMenuItem(text={Text(context.getString(R.string.discovery_auto_refresh,value))},onClick={
                    hours=value;refreshChoices=false;scope.launch {try { withContext(Dispatchers.IO) { PersonalCollections.update(context) { it.copy(refreshHours=value) } } } catch(cancel:CancellationException) {throw cancel} catch(_:Exception) {error=true}}
                }) }
            }
        }
        if(error)Text(context.getString(R.string.discovery_operation_failed),color=MaterialTheme.colorScheme.error)
    }
    if(remove)AlertDialog(onDismissRequest={remove=false},title={Text(context.getString(R.string.discovery_remove_model))},text={Text(context.getString(R.string.discovery_remove_model_hint))},
        confirmButton={TextButton(onClick={scope.launch {busy=true;try { SpeechModelStore.remove(context);installed=false } catch(cancel:CancellationException) {throw cancel} catch(_:Exception) {error=true} finally {busy=false;remove=false} }}) {Text(context.getString(android.R.string.ok))}},
        dismissButton={TextButton(onClick={remove=false}) {Text(context.getString(android.R.string.cancel))}})
}
