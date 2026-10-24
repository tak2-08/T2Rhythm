// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.metadata.*
import chromahub.rhythm.app.features.local.di.LocalMusicModule
import chromahub.rhythm.app.shared.data.model.AppSettings
import java.io.File
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileNameSettings(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.file_names_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_close)) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding()) { FileNameSettingsPanel() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileNameSettingsPanel() {
    val context = LocalContext.current; val settings = remember { AppSettings.getInstance(context) }
    val enabled by settings.autoFileNames.collectAsState(); val format by settings.fileNameFormat.collectAsState()
    val service = remember { FileNameService(context) }; val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }; var progress by remember { mutableIntStateOf(0) }
    var pending by remember { mutableIntStateOf(0) }; var scanned by remember { mutableStateOf(false) }
    var plans by remember { mutableStateOf<List<FileNamePlan>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var denied by remember { mutableStateOf<List<FileNameOutcome>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }; var previewJob by remember { mutableStateOf<Job?>(null) }
    val work by remember { androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(FileNameWorker.NAME) }.collectAsState(emptyList())
    LaunchedEffect(work, busy) { pending = withContext(Dispatchers.IO) { service.pendingCount() } }
    fun apply(batch: List<FileNamePlan>) {
        if (busy || batch.isEmpty()) return
        busy = true; denied = emptyList(); message = null
        scope.launch {
            try {
                val results = withContext(Dispatchers.IO) { batch.map { plan ->
                    currentCoroutineContext().ensureActive()
                    try { service.rename(plan) } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { FileNameOutcome("FAILED", plan) }
                } }
                val changed = results.filter { it.state == "DONE" }.map { it.plan.song.uri.toString() }.toSet()
                plans = plans.filterNot { it.song.uri.toString() in changed }; selected = selected - changed
                denied = results.filter { it.state == "PERMISSION" }
                message = context.getString(R.string.file_names_result, changed.size, results.size - changed.size)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = context.getString(R.string.file_names_failed) }
            finally { busy = false }
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) apply(denied.map { it.plan })
        else message = context.getString(R.string.file_names_permission_denied)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.file_names_auto), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(enabled, onCheckedChange = { value ->
                settings.setAutoFileNames(value)
                if (value) FileNameWorker.enqueue(context) else FileNameWorker.stop(context)
            }, modifier = Modifier.testTag("file-names-auto"))
        }
        Text(stringResource(R.string.file_names_hint), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ARTIST_TITLE" to R.string.file_names_artist_title, "TITLE" to R.string.file_names_title_only).forEach { (value, label) ->
                FilterChip(format == value, enabled = !busy, onClick = {
                    settings.setFileNameFormat(value); plans = emptyList(); selected = emptySet(); scanned = false
                    if (enabled) FileNameWorker.enqueue(context)
                }, label = { Text(stringResource(label)) })
            }
        }
        Text(stringResource(R.string.file_names_example, if (format == "TITLE") "노래.mp3" else "아티스트 - 노래.mp3"), style = MaterialTheme.typography.labelMedium)
        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("file-names-preview"), onClick = {
            busy = true; message = null; progress = 0; denied = emptyList()
            previewJob = scope.launch {
                try {
                    val candidate = withContext(Dispatchers.IO) {
                        service.recover()
                        val reserved = mutableMapOf<String, MutableSet<String>>()
                        val result = mutableListOf<FileNamePlan>()
                        val songs = LocalMusicModule.provideMusicRepository(context).loadSongs().sortedBy { it.uri.toString() }
                        for ((index, song) in songs.withIndex()) {
                            currentCoroutineContext().ensureActive()
                            try { service.plan(song, format, reserved)?.let(result::add) }
                            catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { /* Show other writable files. */ }
                            if (index % 16 == 0) withContext(Dispatchers.Main) { progress = index + 1 }
                            if (result.size >= 500) break
                        }
                        result
                    }
                    plans = candidate; selected = candidate.map { it.song.uri.toString() }.toSet(); scanned = true
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { message = context.getString(R.string.file_names_failed) }
                finally { busy = false; previewJob = null }
            }
        }) { Text(stringResource(R.string.file_names_preview)) }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if (previewJob != null) stringResource(R.string.file_names_progress, progress) else stringResource(R.string.file_names_applying),
                modifier = Modifier.testTag("file-names-progress"), style = MaterialTheme.typography.bodySmall)
            if (previewJob != null) TextButton(onClick = { previewJob?.cancel() }) { Text(stringResource(android.R.string.cancel)) }
        }
        if (pending > 0) Text(stringResource(R.string.file_names_pending, pending), style = MaterialTheme.typography.bodySmall)
        if (scanned && plans.isEmpty() && message == null) Text(stringResource(R.string.file_names_empty))
        if (plans.isNotEmpty()) {
            Text(stringResource(R.string.file_names_count, selected.size, plans.size), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.file_names_batch), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(plans, key = { it.song.uri.toString() }) { plan ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val key = plan.song.uri.toString()
                        Checkbox(key in selected, enabled = !busy, onCheckedChange = { checked -> selected = if (checked) selected + key else selected - key })
                        Column(Modifier.weight(1f)) { Text(File(plan.oldPath).name, style = MaterialTheme.typography.bodySmall)
                            Text("→ " + plan.name, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            FilledTonalButton(enabled = !busy && selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("file-names-apply"),
                onClick = { apply(plans.filter { it.song.uri.toString() in selected }) }) { Text(stringResource(R.string.file_names_apply, selected.size)) }
        }
        if (denied.isNotEmpty()) OutlinedButton(enabled = !busy, onClick = {
            try { val request = service.consent(denied)
                if (request == null) message = context.getString(R.string.file_names_permission_denied)
                else consent.launch(IntentSenderRequest.Builder(request).build())
            } catch (_: Exception) { message = context.getString(R.string.file_names_failed) }
        }) { Text(stringResource(R.string.file_names_permission)) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
