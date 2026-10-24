// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.experience.HistoryRetention
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HistoryRetentionPanel() {
    val context = LocalContext.current
    var saved by remember { mutableLongStateOf(0) }
    var updated by remember { mutableLongStateOf(0) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            saved = HistoryRetention.savedBytes(context)
            updated = HistoryRetention.lastRun(context)
        }
    }
    HorizontalDivider()
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            context.getString(R.string.retention_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            context.getString(R.string.retention_description, HistoryRetention.DETAIL_DAYS),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            context.getString(R.string.retention_conditions),
            style = MaterialTheme.typography.bodySmall,
        )
        if (saved > 0)
            Text(
                context.getString(
                    R.string.retention_saved,
                    Formatter.formatShortFileSize(context, saved),
                ),
                style = MaterialTheme.typography.labelMedium,
            )
        if (updated > 0)
            Text(
                context.getString(
                    R.string.discovery_updated,
                    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(updated)),
                ),
                style = MaterialTheme.typography.labelSmall,
            )
    }
}
