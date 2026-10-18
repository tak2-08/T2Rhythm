// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import java.time.LocalDate
import kr.co.metadata.crawler.CatalogIdentity

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListeningReportPanel(viewModel: MusicViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ledger = remember { ListeningLedger.get(context) }
    val revision by ledger.changes.collectAsState()
    val songs by viewModel.songs.collectAsState()
    var period by remember { mutableStateOf(ReportPeriod.WEEK) }
    var offset by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<ListeningReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var playlist by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var mix by remember { mutableStateOf(false) }
    LaunchedEffect(period, offset, revision) {
        busy = true
        try { report = withContext(Dispatchers.IO) { ListeningReports.read(ledger, period.window(LocalDate.now(), offset)) }; failed = false }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { failed = true }
        finally { busy = false }
    }
    val resolved = remember(report, songs) {
        val byUri = songs.associateBy { it.uri.toString() }
        val byIdentity = songs.groupBy { CatalogIdentity.normalize(it.title) + "|" + CatalogIdentity.normalize(it.artist) }
        report?.tracks.orEmpty().map { track ->
            val direct = byUri[track.key]?.takeIf { CatalogIdentity.titleMatches(it.title, track.title) && CatalogIdentity.artistMatches(it.artist, track.artist) }
            val matches = byIdentity[CatalogIdentity.normalize(track.title) + "|" + CatalogIdentity.normalize(track.artist)].orEmpty()
            track to (direct ?: matches.filter { CatalogIdentity.normalize(it.album) == CatalogIdentity.normalize(track.album) }.singleOrNull() ?: matches.singleOrNull())
        }
    }
    val queue = resolved.mapNotNull { it.second }.distinctBy { it.uri }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.report_title), style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportPeriod.entries.forEach { value ->
                    FilterChip(period == value, onClick = { period = value; offset = 0 }, label = { Text(stringResource(when (value) { ReportPeriod.WEEK -> R.string.report_week; ReportPeriod.MONTH -> R.string.report_month; ReportPeriod.QUARTER -> R.string.report_quarter })) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = !busy && offset > -520, onClick = { offset-- }) { Icon(MaterialSymbolIcon("chevron_left"), stringResource(R.string.report_previous), Modifier.size(24.dp)) }
                Text(report?.window?.let { "${it.first} — ${it.until.minusDays(1)}" }.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                IconButton(enabled = !busy && offset < 0, onClick = { offset++ }) { Icon(MaterialSymbolIcon("chevron_right"), stringResource(R.string.report_next), Modifier.size(24.dp)) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) Text(stringResource(R.string.discovery_operation_failed), color = MaterialTheme.colorScheme.error)
            report?.let { data ->
                Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF403170), Color(0xFF164E60))), RoundedCornerShape(24.dp)).padding(24.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.app_name).uppercase(), color = Color(0xFFCEEBE2), style = MaterialTheme.typography.labelLarge, letterSpacing = 3.sp)
                        Text(stringResource(R.string.report_minutes, data.listenedMs / 60_000), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.report_summary, data.songs, data.plays), color = Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            resolved.mapNotNull { it.second }.take(3).forEach { song ->
                                M3ImageUtils.M3MediaImage(data = song.artworkUri, contentDescription = song.title, modifier = Modifier.size(72.dp), shape = RoundedCornerShape(14.dp))
                            }
                        }
                        data.tracks.firstOrNull()?.let { Text(stringResource(R.string.report_number_one, it.title), color = Color.White, style = MaterialTheme.typography.titleMedium) }
                    }
                }
                if (data.listenedMs == 0L) Text(stringResource(R.string.report_empty))
                data.genres.forEach { (genre, ms) ->
                    Text(genre + " · " + stringResource(R.string.report_minutes, ms / 60_000), style = MaterialTheme.typography.labelLarge)
                    LinearProgressIndicator(progress = { (ms.toFloat() / data.listenedMs.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(enabled = !busy && queue.isNotEmpty(), onClick = { viewModel.playQueue(queue, enableShuffle = false) }) { Text(stringResource(R.string.download_listen)) }
                    OutlinedButton(enabled = !busy && queue.isNotEmpty(), onClick = { playlistName = context.getString(R.string.report_playlist_name, data.window.first.toString()); playlist = true }) { Text(stringResource(R.string.report_save_playlist)) }
                    OutlinedButton(enabled = !busy && queue.size >= 2, onClick = { mix = true }) { Text(stringResource(R.string.report_mix)) }
                }
                Text(stringResource(R.string.report_available, queue.size, data.tracks.size), style = MaterialTheme.typography.bodySmall)
                resolved.take(10).forEachIndexed { i, (track, song) ->
                    ListItem(headlineContent = { Text(track.title.ifBlank { track.key.substringAfterLast('/') }, maxLines = 2) },
                        supportingContent = { Text(track.artist + " · " + stringResource(R.string.report_track_stats, track.plays, track.listenedMs / 60_000)) },
                        leadingContent = { Text("%02d".format(i + 1), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) },
                        trailingContent = { IconButton(enabled = song != null && !busy, onClick = { song?.let(viewModel::playSong) }) { Icon(MaterialSymbolIcon("play_arrow"), stringResource(R.string.download_listen), Modifier.size(24.dp)) } })
                }
            }
        }
    }
    if (playlist) AlertDialog(onDismissRequest = { playlist = false }, title = { Text(stringResource(R.string.report_save_playlist)) }, text = { OutlinedTextField(playlistName, { playlistName = it.take(120) }, singleLine = true) },
        confirmButton = { TextButton(enabled = playlistName.isNotBlank(), onClick = { viewModel.createPlaylist(playlistName.trim(), queue); playlist = false }) { Text(stringResource(R.string.report_save_playlist)) } },
        dismissButton = { TextButton(onClick = { playlist = false }) { Text(stringResource(android.R.string.cancel)) } })
    if (mix) ReportMixDialog(queue.take(8), viewModel) { mix = false }
}
