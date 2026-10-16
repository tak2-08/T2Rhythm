// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.video.*
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadCenter(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(emptyList<DownloadRecord>()) }
    var limit by remember { mutableIntStateOf(100) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(limit) {
        try {
            DownloadRegistry.recoverLegacy(context)
            combine(
                    WorkManager.getInstance(context).getWorkInfosByTagFlow(DownloadRegistry.TAG),
                    DownloadRegistry.revision,
                ) { work, _ ->
                    work
                }
                .collect { work ->
                    records =
                        withContext(Dispatchers.IO) {
                            val byId = work.associateBy { it.id.toString() }
                            val active =
                                work
                                    .filter { !it.state.isFinished }
                                    .mapNotNull { DownloadRegistry.get(context, it.id.toString()) }
                            (active + DownloadRegistry.recent(context, limit))
                                .distinctBy { it.id }
                                .map { record ->
                                    val merged = DownloadRegistry.merge(record, byId[record.id])
                                    if (
                                        merged.state in setOf("SUCCEEDED", "FAILED", "CANCELLED") &&
                                            merged.state != record.state
                                    )
                                        DownloadRegistry.save(context, merged)
                                    merged
                                }
                        }
                }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            error = true
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 700.dp).navigationBarsPadding(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    context.getString(R.string.discovery_downloads),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            item {
                Text(
                    context.getString(R.string.discovery_downloads_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                val used = records.sumOf { it.fileBytes }
                Text(
                    context.getString(
                        R.string.discovery_stored_size,
                        Formatter.formatShortFileSize(context, used),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (error)
                item {
                    Text(
                        context.getString(R.string.discovery_operation_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            if (records.isEmpty())
                item {
                    Text(
                        context.getString(R.string.discovery_downloads_empty),
                        Modifier.padding(vertical = 24.dp),
                    )
                }
            items(records, key = { it.id }) { record ->
                val active = record.state !in setOf("SUCCEEDED", "FAILED", "CANCELLED")
                Card(
                    Modifier.fillMaxWidth().testTag("download:${record.id}"),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Icon(
                                MaterialSymbolIcon(
                                    when (record.kind) {
                                        "VIDEO" -> "smart_display"
                                        "MODEL" -> "neurology"
                                        else -> "music_note"
                                    }
                                ),
                                null,
                                Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    record.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                )
                                if (record.artist.isNotBlank())
                                    Text(record.artist, style = MaterialTheme.typography.bodySmall)
                            }
                            if (active)
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    DownloadRegistry.cancel(context, record.id)
                                                }
                                            } catch (cancel: CancellationException) {
                                                throw cancel
                                            } catch (_: Exception) {
                                                error = true
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        MaterialSymbolIcon("close"),
                                        context.getString(R.string.discovery_cancel_download),
                                        Modifier.size(24.dp),
                                    )
                                }
                        }
                        Text(
                            context.getString(
                                when (record.state) {
                                    "SUCCEEDED" -> R.string.discovery_download_done
                                    "FAILED" -> R.string.discovery_download_failed
                                    "CANCELLED" -> R.string.discovery_download_cancelled
                                    "RUNNING" -> R.string.discovery_downloading
                                    "INSTALLING" -> R.string.discovery_model_installing
                                    else -> R.string.discovery_download_waiting
                                }
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            if (record.state == "SUCCEEDED")
                                context.getString(
                                    R.string.discovery_file_size,
                                    Formatter.formatShortFileSize(context, record.fileBytes),
                                )
                            else if (record.totalBytes > 0)
                                Formatter.formatShortFileSize(context, record.bytes) +
                                    " / " +
                                    Formatter.formatShortFileSize(context, record.totalBytes)
                            else
                                Formatter.formatShortFileSize(
                                    context,
                                    if (record.fileBytes > 0) record.fileBytes else record.bytes,
                                ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (active) {
                            if (record.totalBytes > 0)
                                LinearProgressIndicator(
                                    progress = {
                                        (record.bytes.toFloat() / record.totalBytes).coerceIn(
                                            0f,
                                            1f,
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (!active && record.state != "SUCCEEDED")
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        try {
                                            when (record.kind) {
                                                "VIDEO" ->
                                                    YouTubeVideoDownload.enqueue(
                                                        context,
                                                        record.key,
                                                        record.sourceId,
                                                        record.title,
                                                        force = true,
                                                    )
                                                "AUDIO" ->
                                                    AudioDownload.enqueueSource(
                                                        context,
                                                        if (record.service == "YOUTUBE")
                                                            "https://www.youtube.com/watch?v=${record.sourceId}"
                                                        else record.sourceId,
                                                        record.title,
                                                        record.artist,
                                                        record.album,
                                                    )
                                                "MODEL" ->
                                                    chromahub.rhythm.app.features.local.data
                                                        .discovery
                                                        .SpeechModelStore
                                                        .enqueue(context)
                                            }
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (_: Exception) {
                                            error = true
                                        }
                                    }
                                }
                            ) {
                                Text(context.getString(R.string.mv_retry))
                            }
                    }
                }
            }
            if (records.size >= limit && limit < 1000)
                item {
                    TextButton(onClick = { limit += 100 }) {
                        Text(context.getString(R.string.discovery_more))
                    }
                }
        }
    }
}
