// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.util.GenreUtils

@Composable
fun GenreSelectionDialog(available: List<String>, selected: Set<String>, onDismiss: () -> Unit,
                         onApply: (Set<String>) -> Unit) {
    var draft by remember { mutableStateOf(GenreUtils.availableGenres(selected).toSet()) }
    var query by remember { mutableStateOf("") }
    val options = remember(available, selected) { (available + selected).flatMap(GenreUtils::splitGenres)
        .distinctBy { it.lowercase(java.util.Locale.ROOT) }.sortedBy { it.lowercase(java.util.Locale.ROOT) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.genre_choose_multiple)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.genre_choose_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, onValueChange = { query = it }, singleLine = true,
                label = { Text(stringResource(R.string.genre_find)) }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { draft = emptySet() }) { Text(stringResource(R.string.personal_all_genres)) }
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                items(options.filter { it.contains(query.trim(), true) }, key = { it }) { label ->
                    val checked = draft.any { it.equals(label, true) }
                    Row(Modifier.fillMaxWidth().toggleable(checked, role = androidx.compose.ui.semantics.Role.Checkbox,
                        onValueChange = { draft = if (checked) draft.filterNot { it.equals(label, true) }.toSet() else draft + label })
                        .padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = null)
                        Text(label, Modifier.padding(start = 8.dp))
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = { onApply(draft); onDismiss() }) { Text(stringResource(R.string.genre_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}
