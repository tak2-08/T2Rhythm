// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.util.GenreUtils

@Composable
internal fun GenreTeachingDialog(title: String, value: String, onValueChange: (String) -> Unit,
    available: List<String>, busy: Boolean, canForget: Boolean, error: String?,
    onDismiss: () -> Unit, onSave: () -> Unit, onForget: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val selected = remember(value) { GenreUtils.splitGenres(value) }
    val valid = selected.size in 1..8 && selected.all { it.length <= 80 }
    val matches = remember(available, query) { available.filter { it.contains(query.trim(), true) } }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.genre_edit_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value, onValueChange = { onValueChange(it.take(1024)) }, enabled = !busy,
                isError = selected.size > 8 || selected.any { it.length > 80 },
                label = { Text(stringResource(R.string.genre_correct_label)) }, modifier = Modifier.fillMaxWidth(),
                maxLines = 3, supportingText = { Text(stringResource(R.string.genre_selected_count, selected.size)) })
            OutlinedTextField(query, onValueChange = { query = it }, enabled = !busy, singleLine = true,
                label = { Text(stringResource(R.string.genre_library_search)) }, modifier = Modifier.fillMaxWidth())
            if (available.isEmpty()) Text(stringResource(R.string.genre_library_empty), style = MaterialTheme.typography.bodySmall)
            else if (matches.isEmpty()) Text(stringResource(R.string.genre_library_no_matches), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 210.dp).testTag("genre-library-options")) {
                items(matches, key = { it }) { label ->
                    val checked = selected.any { it.equals(label, true) }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(checked, enabled = !busy && (checked || selected.size < 8), role = Role.Checkbox,
                            onValueChange = {
                                onValueChange((if (checked) selected.filterNot { it.equals(label, true) } else selected + label).joinToString(", "))
                            }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = null, enabled = !busy && (checked || selected.size < 8))
                        Text(label, Modifier.padding(start = 8.dp))
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (canForget) TextButton(enabled = !busy, onClick = onForget) { Text(stringResource(R.string.genre_forget)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(enabled = !busy && valid, onClick = onSave) { Text(stringResource(R.string.genre_teach)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}
