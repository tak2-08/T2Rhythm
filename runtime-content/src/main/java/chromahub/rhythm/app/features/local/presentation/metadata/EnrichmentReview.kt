// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.discovery.*
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrichmentReview(viewModel: MusicViewModel, songKey: String? = null, initialProposalId: String? = null,
    fallbackSong: chromahub.rhythm.app.shared.data.model.Song? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { EnrichmentStore(context) }
    val songs by viewModel.songs.collectAsState()
    val current by viewModel.currentSong.collectAsState()
    val playing by viewModel.isPlaying.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val revision by EnrichmentStore.revision.collectAsState()
    var proposals by remember { mutableStateOf(emptyList<EnrichmentProposal>()) }
    var history by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<EnrichmentProposal?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var permissionId by remember { mutableStateOf<String?>(null) }
    var permissionUndo by remember { mutableStateOf(false) }
    val singleProposal = remember { initialProposalId != null }
    LaunchedEffect(revision, songKey, initialProposalId) {
        proposals = withContext(Dispatchers.IO) { store.list(1000).filter { songKey == null || it.songKey == songKey } }
        if (selected == null && initialProposalId != null) selected = proposals.firstOrNull { it.id == initialProposalId && it.state == "PENDING" }
    }
    fun finish(id: String, undo: Boolean) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    store.decide(id, if (undo) "REVERTED" else "ACCEPTED")
                    EnrichmentLearningWorker.enqueue(context)
                }
                selected = null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                message = context.getString(R.string.discovery_operation_failed)
            } finally {
                busy = false
            }
        }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            result ->
            val id = permissionId
            if (result.resultCode == Activity.RESULT_OK && id != null)
                viewModel.completeMetadataWriteAfterPermission(
                    onSuccess = { finish(id, permissionUndo) },
                    onError = {
                        message = it
                        busy = false
                    },
                )
            else {
                viewModel.cancelPendingMetadataWrite()
                busy = false
                message = context.getString(R.string.discovery_write_permission)
            }
            permissionId = null
        }
    fun apply(proposal: EnrichmentProposal, undo: Boolean) {
        val song =
            songs.firstOrNull { it.uri.toString() == proposal.songKey } ?: fallbackSong?.takeIf { it.uri.toString() == proposal.songKey }
                ?: run {
                    message = context.getString(R.string.discovery_stale)
                    return
                }
        if (!undo && EnrichmentStore.signature(song) != proposal.sourceSignature) {
            message = context.getString(R.string.discovery_stale)
            return
        }
        busy = true
        message = null
        if (proposal.kind == "GENRE") {
            if (undo && song.genre != proposal.value) {
                busy = false
                message = context.getString(R.string.discovery_stale)
                return
            }
            viewModel.saveMetadataChanges(
                song,
                song.title,
                song.artist,
                song.album,
                if (undo) proposal.beforeGenre.orEmpty() else proposal.value,
                song.year,
                song.trackNumber,
                albumArtist = song.albumArtist,
                discNumber = song.discNumber,
                onSuccess = { written ->
                    if (written) finish(proposal.id, undo)
                    else {
                        busy = false
                        message = context.getString(R.string.discovery_write_permission)
                    }
                },
                onError = {
                    message = it
                    busy = false
                },
                onPermissionRequired = { request ->
                    permissionId = proposal.id
                    permissionUndo = undo
                    permission.launch(IntentSenderRequest.Builder(request.intentSender).build())
                },
            )
        } else
            scope.launch {
                try {
                    viewModel.applyReviewedLyrics(song, proposal, undo)
                    finish(proposal.id, undo)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    message = context.getString(R.string.discovery_stale)
                    busy = false
                }
            }
    }
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 720.dp).navigationBarsPadding(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    context.getString(R.string.discovery_review_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            item {
                Text(
                    context.getString(R.string.discovery_review_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        !history,
                        onClick = { history = false },
                        label = { Text(context.getString(R.string.discovery_pending)) },
                    )
                    FilterChip(
                        history,
                        onClick = { history = true },
                        label = { Text(context.getString(R.string.discovery_history)) },
                    )
                }
            }
            val shown =
                proposals.filter {
                    if (history) it.state in setOf("ACCEPTED", "REJECTED", "REVERTED")
                    else it.state == "PENDING"
                }
            if (shown.isEmpty())
                item {
                    Text(
                        context.getString(R.string.discovery_review_empty),
                        Modifier.padding(vertical = 16.dp),
                    )
                }
            message?.let { text -> item { Text(text, color = MaterialTheme.colorScheme.error) } }
            items(shown, key = { it.id }) { proposal ->
                OutlinedCard(
                    onClick = { if (!busy) selected = proposal },
                    modifier = Modifier.fillMaxWidth().testTag("proposal:${proposal.id}"),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            proposal.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                        )
                        Text(proposal.artist, style = MaterialTheme.typography.bodySmall)
                        Text(
                            context.getString(
                                if (proposal.kind == "GENRE") R.string.discovery_genre_proposal
                                else R.string.discovery_timing_proposal
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (proposal.kind == "GENRE")
                            Text(
                                proposal.beforeGenre
                                    ?.takeIf { it.isNotBlank() }
                                    ?.plus(" → ")
                                    .orEmpty() + proposal.value
                            )
                        Text(
                            context.getString(
                                R.string.discovery_proposal_strength,
                                (proposal.confidence * 100).toInt(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            item {
                TextButton(onClick = { showSettings = !showSettings }) {
                    Icon(MaterialSymbolIcon("tune"), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(context.getString(R.string.discovery_learning_settings))
                }
                if (showSettings) LearningPreferences()
            }
        }
    }
    selected?.let { proposal ->
        val song = songs.firstOrNull { it.uri.toString() == proposal.songKey } ?: fallbackSong?.takeIf { it.uri.toString() == proposal.songKey }
        if (proposal.kind == "LYRICS" && proposal.state == "PENDING" && song != null && proposal.beforeLyrics != null) {
            LyricTimingEditor(song, proposal.beforeLyrics, viewModel, proposal = proposal) {
                selected = null
                if (singleProposal) onDismiss()
            }
        } else {
        val lines = remember(proposal.value) { LocalLyricAlignment.parse(proposal.value) }
        val position = if (song != null && current?.uri == song.uri) (progress * song.duration).toLong() else -1L
        val activeLine = lines.indexOfLast { it.ms <= position }
        val reviewList = rememberLazyListState()
        AlertDialog(
            onDismissRequest = { if (!busy) selected = null },
            title = { Text(proposal.title, maxLines = 2) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (proposal.kind == "LYRICS" && song != null) Row(
                        Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalIconButton(enabled = !busy, onClick = {
                            if (current?.uri == song.uri && playing) viewModel.togglePlayPause()
                            else viewModel.previewLyricLine(song, if (position >= 0) position else lines.firstOrNull()?.ms ?: 0L)
                        }) {
                            Icon(MaterialSymbolIcon(if (current?.uri == song.uri && playing) "pause" else "play_arrow"),
                                context.getString(if (current?.uri == song.uri && playing) R.string.lyric_ai_pause_preview else R.string.discovery_play_review))
                        }
                        fun clock(ms: Long) = "%02d:%02d".format(java.util.Locale.ROOT, ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
                        Text(clock(position) + " / " + clock(song.duration), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        TextButton(enabled = activeLine >= 0, onClick = { scope.launch { reviewList.animateScrollToItem(activeLine + 2) } }) {
                            Text(context.getString(R.string.lyric_ai_current_line))
                        }
                    }
                LazyColumn(
                    Modifier.heightIn(max = 360.dp),
                    state = reviewList,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            context.getString(
                                when (proposal.method) {
                                    "waveform_estimate" -> R.string.lyric_ai_waveform
                                    "speech_anchors" -> R.string.discovery_method_speech
                                    "same_audio_reference" -> R.string.discovery_method_reference
                                    "local_neighbors" -> R.string.discovery_method_genre
                                    else -> R.string.discovery_method_estimate
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (proposal.kind == "GENRE")
                        item {
                            Text(proposal.value, style = MaterialTheme.typography.headlineSmall)
                        }
                    if (proposal.kind == "LYRICS") {
                        item {
                            Text(
                                context.getString(R.string.discovery_preview_hint),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        val before =
                            LocalLyricAlignment.parse(proposal.beforeLyrics?.syncedLyrics.orEmpty())
                        items(
                            lines
                                .take(1000)
                                .withIndex()
                                .toList(),
                            key = { it.index },
                        ) { (index, line) ->
                            val seekable = song != null && !busy
                            fun stamp(ms: Long) =
                                "%02d:%02d.%03d"
                                    .format(
                                        java.util.Locale.ROOT,
                                        ms / 60000,
                                        ms / 1000 % 60,
                                        ms % 1000,
                                    )
                            if (proposal.state == "PENDING" && song != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(-200L to "−0.2s", 200L to "+0.2s").forEach { (delta, label) ->
                                    TextButton(enabled = !busy, onClick = {
                                        scope.launch {
                                            try {
                                                val changed = withContext(Dispatchers.IO) {
                                                    val lines = LocalLyricAlignment.parse(proposal.value).toMutableList()
                                                    lines[index] = line.copy(ms = line.ms + delta)
                                                    store.adjustTiming(proposal.id, lines, song.duration)
                                                }
                                                selected = changed
                                            } catch (cancel: CancellationException) { throw cancel }
                                            catch (_: Exception) { message = context.getString(R.string.lyric_ai_order) }
                                        }
                                    }) { Text(label) }
                                }
                                TextButton(enabled = current?.uri == song.uri && !busy, onClick = {
                                    scope.launch {
                                        try {
                                            val changed = withContext(Dispatchers.IO) {
                                                val lines = LocalLyricAlignment.parse(proposal.value).toMutableList()
                                                lines[index] = line.copy(ms = (viewModel.progress.value * song.duration).toLong())
                                                store.adjustTiming(proposal.id, lines, song.duration)
                                            }
                                            selected = changed
                                        } catch (cancel: CancellationException) { throw cancel }
                                        catch (_: Exception) { message = context.getString(R.string.lyric_ai_order) }
                                    }
                                }) { Text(context.getString(R.string.lyric_ai_mark_now)) }
                            }
                            ListItem(
                                headlineContent = {
                                    Text(line.text, style = MaterialTheme.typography.bodyMedium)
                                },
                                supportingContent = {
                                    Text(
                                        (before.getOrNull(index)?.ms?.let { stamp(it) + " → " }
                                            ?: "") + stamp(line.ms),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                trailingContent = {
                                    IconButton(
                                        enabled = seekable,
                                        onClick = { song?.let { viewModel.previewLyricLine(it, line.ms) } },
                                    ) {
                                        Icon(
                                            MaterialSymbolIcon("play_arrow"),
                                            context.getString(R.string.discovery_seek_line),
                                            Modifier.size(24.dp),
                                        )
                                    }
                                },
                                modifier =
                                    Modifier.clickable(enabled = seekable) {
                                        song?.let { viewModel.previewLyricLine(it, line.ms) }
                                    },
                                colors = ListItemDefaults.colors(containerColor = if (index == activeLine) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                            )
                        }
                    }
                    if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    message?.let { text ->
                        item { Text(text, color = MaterialTheme.colorScheme.error) }
                    }
                }
                }
            },
            confirmButton = {
                if (proposal.state == "PENDING")
                    TextButton(enabled = !busy, onClick = { apply(proposal, false) }) {
                        Text(context.getString(R.string.discovery_accept))
                    }
                else if (proposal.state == "ACCEPTED")
                    TextButton(enabled = !busy, onClick = { apply(proposal, true) }) {
                        Text(context.getString(R.string.discovery_undo))
                    }
            },
            dismissButton = {
                if (proposal.state == "PENDING")
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        store.decide(proposal.id, "REJECTED")
                                        EnrichmentLearningWorker.enqueue(context)
                                    }
                                    selected = null
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    message = context.getString(R.string.discovery_operation_failed)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) {
                        Text(context.getString(R.string.discovery_reject))
                    }
                else
                    TextButton(onClick = { selected = null }) {
                        Text(context.getString(android.R.string.cancel))
                    }
            },
        )
        }
    }
}
