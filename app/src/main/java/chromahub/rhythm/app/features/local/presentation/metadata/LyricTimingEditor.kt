// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.discovery.*
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.LyricsData
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import kotlinx.coroutines.*

/** The same transport is used for a blank manual sheet and an AI proposal under review. */
class TimingPlayback(val position: () -> Long?, val playing: () -> Boolean,
    val play: (Long) -> Unit, val pause: () -> Unit, val seek: (Long) -> Unit)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LyricTimingEditor(song: Song, original: LyricsData, viewModel: MusicViewModel,
    proposal: EnrichmentProposal? = null, playbackOverride: TimingPlayback? = null,
    applyOverride: (suspend (EnrichmentProposal) -> Unit)? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drafts = remember { LyricTimingDraftStore(context) }
    val store = remember { EnrichmentStore(context) }
    val playback = remember(song.uri, playbackOverride) { playbackOverride ?: TimingPlayback(
        { viewModel.lyricTimingPosition(song) },
        { viewModel.currentSong.value?.uri == song.uri && viewModel.isPlaying.value },
        { viewModel.previewLyricLine(song, it) },
        { if (viewModel.currentSong.value?.uri == song.uri && viewModel.isPlaying.value) viewModel.togglePlayPause() },
        { if (viewModel.lyricTimingPosition(song) != null) viewModel.seekTo(it) else viewModel.previewLyricLine(song, it) },
    ) }
    var rows by remember { mutableStateOf(emptyList<LyricTimingMark>()) }
    var selected by remember { mutableIntStateOf(0) }
    var endSelected by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf<Long?>(null) }
    var playing by remember { mutableStateOf(false) }
    var loop by remember { mutableStateOf(false) }
    var step by remember { mutableLongStateOf(50) }
    var message by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf<String?>(null) }
    var inputError by remember { mutableStateOf(false) }
    var seekPreview by remember { mutableStateOf<Float?>(null) }
    val undo = remember { mutableStateListOf<Triple<List<LyricTimingMark>, Int, Boolean>>() }
    val list = rememberLazyListState()
    val requireEnds = remember(original, proposal) { proposal == null && LocalLyricAlignment.parse(original.getBestLyrics().orEmpty()).isEmpty() }
    val ready = remember(rows, song.duration, requireEnds) { rows.isNotEmpty() && rows.all { it.start != null && (!requireEnds || it.end != null) } && ManualLyricTiming.valid(rows, song.duration) }
    fun snapshot() = LyricTimingDraft(EnrichmentStore.signature(song), original, proposal?.id, rows, selected)
    suspend fun persist() { if (rows.isNotEmpty()) { val draft = snapshot(); withContext(Dispatchers.IO) { drafts.save(song, draft) } } }
    fun close() { if (!busy) scope.launch {
        busy = true
        try { persist(); onDismiss() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { message = context.getString(R.string.timing_save_error); busy = false }
    } }
    fun change(edit: () -> List<LyricTimingMark>) {
        try {
            val changed = edit()
            require(ManualLyricTiming.valid(changed, song.duration))
            undo.add(Triple(rows, selected, endSelected)); if (undo.size > 64) undo.removeAt(0)
            rows = changed; message = null
        } catch (_: IllegalArgumentException) { message = context.getString(R.string.timing_order_error) }
    }
    fun mark(end: Boolean) {
        val now = playback.position() ?: run { message = context.getString(R.string.timing_play_first); return }
        val before = rows
        change { ManualLyricTiming.mark(rows, selected, end, now, song.duration) }
        if (rows != before) {
            if (end && selected < rows.lastIndex) { selected++; endSelected = false }
            else endSelected = !end
        }
    }
    LaunchedEffect(song.uri, proposal?.id) {
        val timed = LocalLyricAlignment.parse(proposal?.value ?: original.getBestLyrics().orEmpty())
        val restored = withContext(Dispatchers.IO) { drafts.read(song, original, proposal?.id) }
        rows = restored?.rows ?: if (timed.isNotEmpty()) ManualLyricTiming.fromTimed(timed)
            else ManualLyricTiming.fromText(LyricTimingInput.lines(original))
        selected = restored?.selected?.coerceIn(0, rows.lastIndex.coerceAtLeast(0)) ?: 0
        loading = false
    }
    LaunchedEffect(rows, selected, loading, busy) { if (!loading && !busy) {
        delay(400)
        try { persist() } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { message = context.getString(R.string.timing_save_error) }
    } }
    LaunchedEffect(selected, loading) { if (!loading && selected in rows.indices) list.animateScrollToItem(selected) }
    LaunchedEffect(playback, loop, selected, rows) {
        while (isActive) {
            position = playback.position(); playing = playback.playing()
            val row = rows.getOrNull(selected)
            val end = row?.end ?: rows.getOrNull(selected + 1)?.start
            if (loop && playing && row?.start != null && end != null && position != null && position!! >= end + 300)
                playback.play((row.start - 700).coerceAtLeast(0))
            delay(40)
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            try { withContext(Dispatchers.IO) {
                require(ready)
                context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(LocalLyricAlignment.lrc(ManualLyricTiming.lines(rows))) }
            } } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = context.getString(R.string.timing_save_error) }
        }
    }
    fun save() {
        if (!ready || busy) return
        busy = true; message = null
        scope.launch {
            try {
                val reviewed = withContext(Dispatchers.IO) {
                    require(ManualLyricTiming.valid(rows, song.duration))
                    if (proposal != null) store.adjustTiming(proposal.id, ManualLyricTiming.lines(rows), song.duration)
                    else store.proposal(song, "LYRICS", LocalLyricAlignment.lrc(ManualLyricTiming.lines(rows)), 1.0,
                        if (LocalLyricAlignment.parse(original.getBestLyrics().orEmpty()) != ManualLyricTiming.lines(rows)) "manual_timing" else "confirmed_timing",
                        rows.size, original).also { check(store.propose(it)) }
                }
                if (applyOverride != null) applyOverride(reviewed) else viewModel.applyReviewedLyrics(song, reviewed, false)
                withContext(Dispatchers.IO) {
                    LyricSuggestionWorker.cancel(context, song)
                    store.decide(reviewed.id, "ACCEPTED")
                    TimingRetryQueue(context).remove(song.uri.toString())
                    drafts.delete(song)
                    TimingLearningWorker.enqueue(context, userInitiated = true)
                }
                Toast.makeText(context, R.string.timing_saved, Toast.LENGTH_LONG).show()
                onDismiss()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = context.getString(R.string.timing_save_error) }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.fillMaxSize().testTag("timing-editor")) {
                TopAppBar(title = { Text(stringResource(R.string.timing_editor_title), maxLines = 1) },
                    navigationIcon = { IconButton(enabled = !busy, onClick = ::close) { Icon(MaterialSymbolIcon("close"), stringResource(R.string.timing_close)) } },
                    actions = {
                        IconButton(enabled = ready && !busy, onClick = { export.launch(song.title.take(80) + ".lrc") }) {
                            Icon(MaterialSymbolIcon("file_export"), stringResource(R.string.timing_export))
                        }
                        TextButton(enabled = ready && !busy, onClick = ::save, modifier = Modifier.testTag("timing-save")) { Text(stringResource(R.string.timing_save)) }
                    })
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(stringResource(R.string.timing_editor_hint), style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalIconButton(enabled = !busy, onClick = {
                            if (playing) playback.pause() else playback.play(position ?: rows.getOrNull(selected)?.start?.minus(700)?.coerceAtLeast(0) ?: 0)
                        }, modifier = Modifier.testTag("timing-play")) {
                            Icon(MaterialSymbolIcon(if (playing) "pause" else "play_arrow"), stringResource(if (playing) R.string.lyric_ai_pause_preview else R.string.discovery_play_review))
                        }
                        Text(ManualLyricTiming.stamp(position ?: 0) + " / " + ManualLyricTiming.stamp(song.duration), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        FilterChip(loop, onClick = { loop = !loop }, label = { Text(stringResource(R.string.timing_loop)) })
                    }
                    Slider(value = seekPreview ?: (position ?: 0).toFloat(), valueRange = 0f..song.duration.coerceAtLeast(1).toFloat(),
                        enabled = !busy, onValueChange = { seekPreview = it }, onValueChangeFinished = {
                            seekPreview?.let { playback.seek(it.toLong().coerceAtMost(song.duration - 1)) }; seekPreview = null
                        }, modifier = Modifier.testTag("timing-seek"))
                    if (message != null) Text(message!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (busy || loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("timing-lines"), state = list,
                    contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(rows) { i, row ->
                        val active = position != null && row.start != null && position!! >= row.start &&
                            position!! < (row.end ?: rows.getOrNull(i + 1)?.start ?: song.duration)
                        ListItem(headlineContent = { Text(row.text) },
                            overlineContent = { Text(stringResource(R.string.timing_line_number, i + 1, rows.size)) },
                            supportingContent = { Text(ManualLyricTiming.stamp(row.start) + " → " + ManualLyricTiming.stamp(row.end)) },
                            trailingContent = { IconButton(enabled = row.start != null && !busy, onClick = { selected = i; playback.play((row.start!! - 700).coerceAtLeast(0)) }) {
                                Icon(MaterialSymbolIcon("play_arrow"), stringResource(R.string.discovery_seek_line))
                            } },
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = i }.testTag("timing-line-$i"),
                            colors = ListItemDefaults.colors(containerColor = when { selected == i -> MaterialTheme.colorScheme.secondaryContainer
                                active -> MaterialTheme.colorScheme.tertiaryContainer; else -> MaterialTheme.colorScheme.surface }))
                    }
                }
                rows.getOrNull(selected)?.let { row ->
                    Surface(tonalElevation = 3.dp) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.timing_selected_line, selected + 1, row.text), maxLines = 2, style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !busy && position != null, onClick = { mark(false) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("timing-mark-start")) { Text(stringResource(R.string.timing_mark_start)) }
                                Button(enabled = !busy && position != null && row.start != null, onClick = { mark(true) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("timing-mark-end")) { Text(stringResource(R.string.timing_mark_end)) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(!endSelected, onClick = { endSelected = false }, label = { Text(stringResource(R.string.timing_start)) })
                                FilterChip(endSelected, onClick = { endSelected = true }, label = { Text(stringResource(R.string.timing_end)) })
                                TextButton(enabled = !busy, onClick = { input = ManualLyricTiming.stamp(if (endSelected) row.end else row.start).takeIf { it != "—" } ?: "00:00.000"; inputError = false }, modifier = Modifier.testTag("timing-exact")) {
                                    Text(ManualLyricTiming.stamp(if (endSelected) row.end else row.start))
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(10L, 50L, 100L).forEach { amount -> FilterChip(step == amount, onClick = { step = amount },
                                    label = { Text("${amount / 1000.0}s") }, modifier = Modifier.testTag("timing-step-$amount")) }
                                listOf(-1, 1).forEach { direction ->
                                    OutlinedButton(enabled = !busy && (if (endSelected) row.end else row.start) != null, onClick = {
                                        change { ManualLyricTiming.mark(rows, selected, endSelected, (if (endSelected) row.end else row.start)!! + step * direction, song.duration) }
                                    }, modifier = Modifier.testTag(if (direction < 0) "timing-earlier" else "timing-later")) { Text(if (direction < 0) "−" else "+") }
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(enabled = undo.isNotEmpty() && !busy, onClick = { val old = undo.removeAt(undo.lastIndex); rows = old.first; selected = old.second; endSelected = old.third; message = null }) { Text(stringResource(R.string.timing_undo)) }
                                TextButton(enabled = !busy, onClick = { change { rows.toMutableList().also { it[selected] = LyricTimingMark(row.text) } } }) { Text(stringResource(R.string.timing_redo_line)) }
                                TextButton(enabled = !busy, onClick = { change { ManualLyricTiming.shift(rows, -step, song.duration) } }) { Text(stringResource(R.string.timing_all_earlier)) }
                                TextButton(enabled = !busy, onClick = { change { ManualLyricTiming.shift(rows, step, song.duration) } }) { Text(stringResource(R.string.timing_all_later)) }
                            }
                            if (proposal != null) TextButton(enabled = !busy, onClick = {
                                busy = true
                                scope.launch {
                                    try { withContext(Dispatchers.IO) {
                                        val rejected = store.decide(proposal.id, "REJECTED")
                                        TimingRetryQueue(context).reject(rejected); drafts.delete(song)
                                    }; onDismiss() }
                                    catch (cancel: CancellationException) { throw cancel }
                                    catch (_: Exception) { message = context.getString(R.string.timing_save_error) }
                                    finally { busy = false }
                                }
                            }, modifier = Modifier.testTag("timing-reject")) { Text(stringResource(R.string.timing_reject_relearn)) }
                        }
                    }
                }
            }
        }
        if (input != null) AlertDialog(onDismissRequest = { input = null }, title = { Text(stringResource(if (endSelected) R.string.timing_end else R.string.timing_start)) },
            text = { OutlinedTextField(value = input!!, onValueChange = { input = it; inputError = false }, singleLine = true, isError = inputError,
                label = { Text("mm:ss.SSS") }, supportingText = { Text(stringResource(R.string.timing_exact_hint)) }, modifier = Modifier.testTag("timing-exact-input")) },
            confirmButton = { TextButton(onClick = {
                val value = ManualLyricTiming.parseTime(input!!)
                if (value == null) inputError = true else {
                    val old = rows
                    change { ManualLyricTiming.mark(rows, selected, endSelected, value, song.duration) }
                    if (rows != old || value == (if (endSelected) rows[selected].end else rows[selected].start)) input = null else inputError = true
                }
            }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { input = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
}
