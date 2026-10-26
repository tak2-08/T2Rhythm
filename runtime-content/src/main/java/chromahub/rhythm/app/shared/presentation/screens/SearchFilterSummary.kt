// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.shared.presentation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R

/** Keep restricted search scope visible even while its filter chips are collapsed. */
@Composable
internal fun SearchFilterSummary(selectedTypes: List<String>, onReset: () -> Unit) {
    val filters = stringResource(R.string.cd_filters)
    val reset = stringResource(R.string.ui_reset)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("search-filter-summary"),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text("$filters · ${selectedTypes.size}/4", style = MaterialTheme.typography.labelMedium)
                if (selectedTypes.isNotEmpty()) {
                    Text(selectedTypes.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = onReset, modifier = Modifier.testTag("search-reset-filters")
                .semantics { contentDescription = "$filters: $reset" }) {
                Text(reset)
            }
        }
    }
}
