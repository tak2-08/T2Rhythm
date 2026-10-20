// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.discovery.*
import chromahub.rhythm.app.features.local.data.experience.PersonalCollections
import chromahub.rhythm.app.features.local.data.video.DownloadRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce

@OptIn(FlowPreview::class)
@Composable
fun LearningPreferences(lyricOnly: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { EnrichmentStore(context) }
    var mode by remember { mutableStateOf(store.mode()) }
    var choices by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf(SpeechModelStore.installed(context)) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var hours by remember {
        mutableIntStateOf(
            PersonalCollections.options(context).refreshHours.takeIf { it in 1..48 } ?: 6
        )
    }
    var model by remember {
        mutableStateOf<chromahub.rhythm.app.features.local.data.video.DownloadRecord?>(null)
    }
    var refreshChoices by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        val previous =
            withContext(Dispatchers.IO) {
                DownloadRegistry.recent(context, 1000).firstOrNull { it.kind == "MODEL" }
            }
        combine(
                WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("speech_model_ko"),
                DownloadRegistry.revision,
            ) { work, _ ->
                work
            }
            .debounce(250)
            .collect { work ->
                val state =
                    withContext(Dispatchers.IO) {
                        val latest =
                            work
                                .mapNotNull { job ->
                                    DownloadRegistry.get(context, job.id.toString())?.let {
                                        DownloadRegistry.merge(it, job)
                                    }
                                }
                                .maxByOrNull { it.createdAt }
                                ?: previous?.let { DownloadRegistry.get(context, it.id) }
                        SpeechModelStore.installed(context) to latest
                    }
                installed = state.first
                model = state.second
            }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!lyricOnly) {
        Text(
            context.getString(R.string.discovery_learning_settings),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            context.getString(R.string.discovery_learning_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        } else Text(context.getString(R.string.lyric_ai_mode_label), style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { choices = true }) {
                Text(
                    context.getString(
                        when (mode) {
                            "PRECISE" -> R.string.discovery_mode_precise
                            "HYBRID" -> R.string.discovery_mode_hybrid
                            else -> R.string.discovery_mode_light
                        }
                    )
                )
            }
            DropdownMenu(choices, onDismissRequest = { choices = false }) {
                listOf(
                        "LIGHT" to R.string.discovery_mode_light,
                        "PRECISE" to R.string.discovery_mode_precise,
                        "HYBRID" to R.string.discovery_mode_hybrid,
                    )
                    .forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(context.getString(label)) },
                            onClick = {
                                mode = value
                                choices = false
                                store.mode(value)
                            },
                        )
                    }
            }
        }
        if (mode != "LIGHT" || installed) {
            Text(
                context.getString(R.string.discovery_model_size),
                style = MaterialTheme.typography.bodySmall,
            )
            if (installed)
                TextButton(enabled = !busy, onClick = { remove = true }) {
                    Text(context.getString(R.string.discovery_remove_model))
                }
            else
                OutlinedButton(
                    enabled = !busy && model?.state !in setOf("RUNNING", "INSTALLING"),
                    onClick = {
                        scope.launch {
                            busy = true
                            error = false
                            try {
                                SpeechModelStore.enqueue(context)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                error = true
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Text(
                        context.getString(
                            if (model?.state in setOf("RUNNING", "INSTALLING"))
                                R.string.popular_downloading
                            else R.string.discovery_install_model
                        )
                    )
                }
            Text(
                context.getString(
                    if (installed) R.string.discovery_model_ready
                    else if (model?.state == "INSTALLING") R.string.discovery_model_installing
                    else if (model?.state == "RUNNING") R.string.discovery_downloading
                    else if (model?.state == "ENQUEUED") R.string.discovery_model_wait_network
                    else if (model?.state == "FAILED") R.string.discovery_download_failed
                    else R.string.discovery_model_wait
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (!installed && model?.state in setOf("RUNNING", "INSTALLING", "ENQUEUED")) {
            val task = model!!
            if (task.totalBytes > 0 && task.state != "INSTALLING")
                LinearProgressIndicator(
                    progress = { (task.bytes.toFloat() / task.totalBytes).coerceIn(0f, 1f) },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                )
            else LinearProgressIndicator(androidx.compose.ui.Modifier.fillMaxWidth())
            TextButton(
                onClick = {
                    scope.launch(Dispatchers.IO) { DownloadRegistry.cancel(context, task.id) }
                }
            ) {
                Text(context.getString(R.string.discovery_cancel_download))
            }
        }
        if (!lyricOnly) Box {
            TextButton(onClick = { refreshChoices = true }) {
                Text(context.getString(R.string.discovery_auto_refresh, hours))
            }
            DropdownMenu(refreshChoices, onDismissRequest = { refreshChoices = false }) {
                listOf(1, 3, 6, 12, 24, 48).forEach { value ->
                    DropdownMenuItem(
                        text = { Text(context.getString(R.string.discovery_auto_refresh, value)) },
                        onClick = {
                            hours = value
                            refreshChoices = false
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        PersonalCollections.update(context) {
                                            it.copy(refreshHours = value)
                                        }
                                    }
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    error = true
                                }
                            }
                        },
                    )
                }
            }
        }
        if (error)
            Text(
                context.getString(R.string.discovery_operation_failed),
                color = MaterialTheme.colorScheme.error,
            )
    }
    if (remove)
        AlertDialog(
            onDismissRequest = { remove = false },
            title = { Text(context.getString(R.string.discovery_remove_model)) },
            text = { Text(context.getString(R.string.discovery_remove_model_hint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                SpeechModelStore.remove(context)
                                installed = false
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                error = true
                            } finally {
                                busy = false
                                remove = false
                            }
                        }
                    }
                ) {
                    Text(context.getString(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { remove = false }) {
                    Text(context.getString(android.R.string.cancel))
                }
            },
        )
}
