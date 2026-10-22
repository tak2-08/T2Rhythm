// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.experience.*
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import chromahub.rhythm.app.util.M3ImageUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.debounce
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private data class ReportLoad(val window: ReportWindow, val report: ListeningReport? = null, val loading: Boolean = true, val failed: Boolean = false)

@OptIn(ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun ListeningReportPanel(viewModel: MusicViewModel,
    readReport: suspend (ListeningLedger, ReportWindow) -> ListeningReport = { ledger, window -> ListeningReports.read(ledger, window) }) {
    val context = LocalContext.current
    val ledger = remember(context) { ListeningLedger.get(context) }
    val revision by remember(ledger) { ledger.changes.debounce(400) }.collectAsState(ledger.changes.value)
    val songs by viewModel.songs.collectAsState()
    var period by rememberSaveable { mutableStateOf(ReportPeriod.WEEK) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    var zone by remember { mutableStateOf(ZoneId.systemDefault()) }
    // The current screen also rolls over when left open across midnight.
    LaunchedEffect(Unit) { while (isActive) { delay(30_000); today = LocalDate.now(); zone = ZoneId.systemDefault() } }
    val window = remember(period, offset, today, zone) { period.window(today, offset, zone) }
    var loaded by remember { mutableStateOf<ReportLoad?>(null) }
    val current = loaded?.takeIf { it.window == window }
    val busy = current?.loading != false
    var retry by remember { mutableIntStateOf(0) }
    var playlist by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var mix by remember { mutableStateOf(false) }
    var methodology by remember { mutableStateOf(false) }
    LaunchedEffect(window, revision, retry) {
        val previous = loaded?.takeIf { it.window == window }?.report
        loaded = ReportLoad(window, previous)
        try { loaded = ReportLoad(window, withContext(Dispatchers.IO) { readReport(ledger, window) }, loading = false) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { loaded = ReportLoad(window, previous, loading = false, failed = true) }
    }
    val projection by produceState<ReportLibraryProjection?>(null, songs) {
        value = withContext(Dispatchers.Default) { ReportLibraryProjection(songs) }
    }
    val report by produceState(current?.report, current?.report, projection) {
        value = current?.report?.let { source -> withContext(Dispatchers.Default) { projection?.project(source) ?: source } }
    }
    // Never show a different period's statistics while a new projection is loading.
    val visibleReport = report?.takeIf { it.window == window }
    val resolved = remember(visibleReport, projection) { visibleReport?.tracks.orEmpty().take(40).map { it to projection?.resolve(it) } }
    val queue = resolved.mapNotNull { it.second }.distinctBy { it.uri }
    Card(Modifier.fillMaxWidth().testTag("listening-report"), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.report_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { methodology = true }) { Icon(MaterialSymbolIcon("info"), stringResource(R.string.report_how), Modifier.size(24.dp)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportPeriod.entries.forEach { value ->
                    FilterChip(period == value, onClick = { period = value; offset = 0 }, label = { Text(stringResource(when (value) { ReportPeriod.WEEK -> R.string.report_week; ReportPeriod.MONTH -> R.string.report_month; ReportPeriod.QUARTER -> R.string.report_quarter })) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = !busy && offset > -520, onClick = { offset-- }) { Icon(MaterialSymbolIcon("chevron_left"), stringResource(R.string.report_previous), Modifier.size(24.dp)) }
                Text("${window.first} — ${window.until.minusDays(1)}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                IconButton(enabled = !busy && offset < 0, onClick = { offset++ }) { Icon(MaterialSymbolIcon("chevron_right"), stringResource(R.string.report_next), Modifier.size(24.dp)) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (current?.failed == true) {
                Text(stringResource(R.string.usability_load_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { retry++ }) { Text(stringResource(R.string.mv_retry)) }
            }
            visibleReport?.let { data ->
                ReportHero(data, resolved.firstOrNull()?.second)
                if (data.listenedMs <= 0) {
                    Text(stringResource(R.string.report_empty), Modifier.testTag("report-empty"))
                    Text(stringResource(R.string.report_empty_detail), style = MaterialTheme.typography.bodySmall)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilledTonalButton(enabled = !busy && queue.isNotEmpty(), onClick = { viewModel.playQueue(queue, enableShuffle = false) }, modifier = Modifier.testTag("report-play")) {
                            Icon(MaterialSymbolIcon("play_arrow"), null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.download_listen))
                        }
                        OutlinedButton(enabled = !busy && queue.isNotEmpty(), onClick = { playlistName = context.getString(R.string.report_playlist_name, data.window.first.toString()); playlist = true }, modifier = Modifier.testTag("report-save")) { Text(stringResource(R.string.report_save_playlist)) }
                        OutlinedButton(enabled = !busy && queue.size >= 2, onClick = { mix = true }) { Text(stringResource(R.string.report_mix)) }
                        TextButton(onClick = {
                            val text = buildString {
                                append(context.getString(R.string.report_title)); append("\n${window.first} — ${window.until.minusDays(1)}\n")
                                append(context.getString(R.string.report_minutes, data.listenedMs / 60_000)); append(" · "); append(context.getString(R.string.report_summary, data.songs, data.plays))
                                data.tracks.take(5).forEachIndexed { i, t -> append("\n${i + 1}. ${t.title} · ${t.artist}") }
                            }
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, context.getString(R.string.report_share)))
                        }) { Text(stringResource(R.string.report_share)) }
                    }
                    Text(stringResource(R.string.report_available, queue.size, resolved.size), style = MaterialTheme.typography.bodySmall)
                    data.comparison?.let { ReportChange(it, data.window) }
                    if (data.plays < 3) Text(stringResource(R.string.report_gathering), style = MaterialTheme.typography.bodySmall)
                    if (data.days.any { it.listenedMs > 0 }) ReportTimeline(data.days)
                    if (data.artists.isNotEmpty()) {
                        Column(Modifier.testTag("report-artists"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.report_artists), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        data.artists.take(3).forEachIndexed { index, artist ->
                            val art = resolved.firstOrNull { it.first.artist == artist.name }?.second?.artworkUri
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                M3ImageUtils.M3MediaImage(data = art, contentDescription = null, modifier = Modifier.size(52.dp), shape = RoundedCornerShape(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(artist.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(stringResource(R.string.report_artist_stats, artist.tracks, artist.listenedMs / 60_000), style = MaterialTheme.typography.bodySmall)
                                }
                                Text("%02d".format(index + 1), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        }
                    }
                    if (data.genres.isNotEmpty()) {
                        Text(stringResource(R.string.report_genres), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        data.genres.forEach { (genre, ms) ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(genre, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                Text("${(ms.toDouble() / data.listenedMs * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                            }
                            LinearProgressIndicator(progress = { (ms.toFloat() / data.listenedMs.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Text(stringResource(R.string.report_tracks), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    resolved.take(10).forEachIndexed { i, (track, song) ->
                        ListItem(headlineContent = { Text(track.title.ifBlank { track.key.substringAfterLast('/') }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(track.artist + " · " + stringResource(R.string.report_track_stats, track.plays, track.listenedMs / 60_000)) },
                            leadingContent = { Text("%02d".format(i + 1), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) },
                            trailingContent = { IconButton(enabled = song != null && !busy, onClick = { song?.let(viewModel::playSong) }) { Icon(MaterialSymbolIcon("play_arrow"), stringResource(R.string.download_listen), Modifier.size(24.dp)) } })
                    }
                }
            }
        }
    }
    if (methodology) AlertDialog(onDismissRequest = { methodology = false }, title = { Text(stringResource(R.string.report_how)) },
        text = { Text(stringResource(R.string.report_method), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { methodology = false }) { Text(stringResource(android.R.string.ok)) } })
    if (playlist) AlertDialog(onDismissRequest = { playlist = false }, title = { Text(stringResource(R.string.report_save_playlist)) }, text = { OutlinedTextField(playlistName, { playlistName = it.take(120) }, singleLine = true) },
        confirmButton = { TextButton(enabled = playlistName.isNotBlank() && !busy && queue.isNotEmpty(), onClick = { viewModel.createPlaylist(playlistName.trim(), queue); playlist = false }) { Text(stringResource(R.string.report_save_playlist)) } },
        dismissButton = { TextButton(onClick = { playlist = false }) { Text(stringResource(android.R.string.cancel)) } })
    if (mix) ReportMixDialog(queue.take(8), viewModel) { mix = false }
}

@Composable
private fun ReportHero(data: ListeningReport, first: Song?) {
    Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF373063), Color(0xFF134D56))), RoundedCornerShape(24.dp)).padding(22.dp).testTag("report-hero"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.app_name).uppercase(), color = Color(0xFFBCE9DD), style = MaterialTheme.typography.labelMedium, letterSpacing = 3.sp)
        Text(stringResource(R.string.report_minutes, data.listenedMs / 60_000), color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.report_summary, data.songs, data.plays), color = Color(0xFFE2E8EE), style = MaterialTheme.typography.bodyMedium)
        data.tracks.firstOrNull()?.let { track ->
            HorizontalDivider(color = Color.White.copy(alpha = .18f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (first != null) M3ImageUtils.M3MediaImage(data = first.artworkUri, contentDescription = null, modifier = Modifier.size(76.dp), shape = RoundedCornerShape(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.report_number_one, track.title), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = Color(0xFFC7DADB), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ReportChange(comparison: ReportComparison, window: ReportWindow) {
    val delta = comparison.currentMs - comparison.previousMs
    val headline = if (comparison.previousMs == 0L) stringResource(R.string.report_no_baseline)
        else if (abs(delta) < 1000 || abs(delta).toDouble() / comparison.previousMs < .005) stringResource(R.string.report_change_same)
        else stringResource(if (delta > 0) R.string.report_change_more else R.string.report_change_less,
            (abs(delta).toDouble() / comparison.previousMs * 100).roundToLong())
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    val previousFirst = Instant.ofEpochMilli(comparison.previousStartMs).atZone(window.zone).toLocalDate()
    val previousLast = Instant.ofEpochMilli((comparison.previousEndMs - 1).coerceAtLeast(comparison.previousStartMs)).atZone(window.zone).toLocalDate()
    Column(Modifier.testTag("report-comparison"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(headline, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.report_comparison_detail, previousFirst.format(format), previousLast.format(format), comparison.previousMs / 60_000), style = MaterialTheme.typography.bodySmall)
        if (comparison.currentEndMs < window.endMs) Text(stringResource(R.string.report_comparison_partial), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ReportTimeline(days: List<ReportDay>) {
    val active = days.count { it.listenedMs > 0 }
    val peak = days.maxByOrNull { it.listenedMs } ?: return
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("report-timeline")) {
        Text(stringResource(R.string.report_daily_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.report_active_days, active, peak.date.format(format), peak.listenedMs / 60_000), style = MaterialTheme.typography.bodySmall)
        val color = MaterialTheme.colorScheme.primary
        val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)
        val context = LocalContext.current
        val description = days.joinToString("; ") { "${it.date}: ${context.getString(R.string.report_minutes, it.listenedMs / 60_000)}" }
        Canvas(Modifier.fillMaxWidth().height(84.dp).semantics { contentDescription = description }) {
            val step = size.width / days.size.coerceAtLeast(1)
            val max = peak.listenedMs.coerceAtLeast(1)
            days.forEachIndexed { i, day ->
                val height = (day.listenedMs.toDouble() / max * size.height).toFloat().coerceAtLeast(3.dp.toPx())
                drawRoundRect(if (day.listenedMs > 0) color else muted, Offset(i * step, size.height - height), Size((step * .68f).coerceAtLeast(1f), height), CornerRadius(3.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(days.first().date.toString(), style = MaterialTheme.typography.labelSmall)
            Text(days.last().date.toString(), style = MaterialTheme.typography.labelSmall)
        }
    }
}
