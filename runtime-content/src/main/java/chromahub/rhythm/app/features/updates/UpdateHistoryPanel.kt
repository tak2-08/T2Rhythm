// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.updates

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chromahub.rhythm.app.R
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

@Composable fun UpdateHistoryPanel(journal: UpdateJournal = UpdateJournal.get(LocalContext.current)) {
    val records by journal.records.collectAsState()
    var open by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(MaterialSymbolIcon("history"), null); Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.update_history_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.update_history_summary), style = MaterialTheme.typography.bodyMedium)
            records.firstOrNull { it.stage == UpdateStage.INSTALLED }?.let { Text(it.version, fontWeight = FontWeight.Bold) }
            FilledTonalButton({ open = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_history_manage)) }
        }
    }
    if (open) UpdateHistoryDialog(journal) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun UpdateHistoryDialog(journal: UpdateJournal, onDismiss: () -> Unit) {
    val records by journal.records.collectAsState()
    val scope = rememberCoroutineScope()
    var remove by remember { mutableStateOf<UpdateRecord?>(null) }
    var clear by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<UpdateRecord?>(null) }
    var error by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun mutate(action: () -> Unit) { scope.launch { try { withContext(Dispatchers.IO) { action() } } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (_: Exception) { error = true } } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(12.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column {
                TopAppBar(title = { Text(stringResource(R.string.update_history_title), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, navigationIcon = {
                    IconButton(onDismiss) { Icon(MaterialSymbolIcon("close"), stringResource(R.string.streaming_done)) }
                }, actions = { IconButton({ clear = true }, enabled = records.isNotEmpty()) { Icon(MaterialSymbolIcon("delete_sweep"), stringResource(R.string.update_history_clear)) } })
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(stringResource(R.string.update_history_tracking), style = MaterialTheme.typography.bodySmall) }
                    if (records.isEmpty()) item { Text(stringResource(R.string.update_history_empty)) }
                    items(records, key = { it.id }) { record ->
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(Modifier.fillMaxWidth().clickable { detail = record }.padding(16.dp), verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(record.version, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    if (record.from.isNotBlank() && record.from != record.version) Text(stringResource(R.string.update_history_from, record.from), style = MaterialTheme.typography.bodySmall)
                                    Text(DateFormat.getMediumDateFormat(context).format(Date(record.time)) + " · " + DateFormat.getTimeFormat(context).format(Date(record.time)), style = MaterialTheme.typography.bodySmall)
                                    Text(stringResource(when (record.stage) {
                                        UpdateStage.READY -> R.string.update_history_ready
                                        UpdateStage.INSTALL_REQUESTED -> R.string.update_history_requested
                                        UpdateStage.INSTALLED -> R.string.update_history_installed
                                        UpdateStage.SUPERSEDED -> R.string.update_history_superseded
                                        UpdateStage.ROLLED_BACK -> R.string.runtime_history_rollback
                                    }), color = MaterialTheme.colorScheme.primary)
                                    if (record.apkRemoved) Text(stringResource(R.string.update_history_apk_removed) + if (record.bytes > 0) " · " + android.text.format.Formatter.formatShortFileSize(context, record.bytes) else "", style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton({ remove = record }) { Icon(MaterialSymbolIcon("delete"), stringResource(R.string.update_history_remove)) }
                            }
                        }
                    }
                    if (error) item { Text(stringResource(R.string.update_history_error), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (clear || remove != null) AlertDialog(onDismissRequest = { clear = false; remove = null }, title = { Text(stringResource(R.string.update_history_remove)) },
        text = { Text(stringResource(R.string.update_history_delete_hint)) }, confirmButton = {
            TextButton({ val id = remove?.id; mutate { if (id == null) journal.clear() else journal.remove(id) }; clear = false; remove = null }) { Text(stringResource(R.string.update_history_remove)) }
        }, dismissButton = { TextButton({ clear = false; remove = null }) { Text(stringResource(R.string.streaming_cancel)) } })
    detail?.let { record -> AlertDialog(onDismissRequest = { detail = null }, title = { Text(record.version) }, text = {
        SelectionContainer { Text(record.notes.ifBlank { context.getString(R.string.update_history_no_notes) }, Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) }
    }, confirmButton = { TextButton({ detail = null }) { Text(stringResource(R.string.streaming_done)) } }) }
}
