// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.discovery.*
import chromahub.rhythm.app.features.local.data.metadata.SongArtworkOverrides
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.LyricsData
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import kotlinx.coroutines.*

@Composable
fun LyricSuggestionButton(song: Song?, lyrics: LyricsData?, viewModel: MusicViewModel) {
    if (song == null || lyrics == null || lyrics.plainLyrics.isNullOrBlank() || !lyrics.syncedLyrics.isNullOrBlank() || !lyrics.wordByWordLyrics.isNullOrBlank() || song.uri.scheme !in setOf("content", "file")) return
    val original = lyrics
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = SongArtworkOverrides.key(song)
    val jobs by remember(key) { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(LyricSuggestionWorker.name(key)) }.collectAsState(emptyList())
    val revision by EnrichmentStore.revision.collectAsState()
    var available by remember(key) { mutableStateOf(false) }
    var review by remember(key) { mutableStateOf(false) }
    var options by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, revision, original) { available = withContext(Dispatchers.IO) { EnrichmentStore(context).list(1000).any {
        it.songKey == song.uri.toString() && it.kind == "LYRICS" && it.state == "PENDING" &&
            it.sourceSignature == EnrichmentStore.signature(song) && EnrichmentStore.sameLyrics(it.beforeLyrics, original)
    } } }
    val active = jobs.any { !it.state.isFinished }
    Column {
        FilledTonalButton(onClick = { if (available) review = true else options = true }, enabled = !active || available) {
            Icon(MaterialSymbolIcon("auto_awesome"), null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (available) R.string.lyric_ai_review else if (active) R.string.lyric_ai_queued else R.string.lyric_ai_suggest))
        }
        if (active) Text(stringResource(R.string.lyric_ai_conditions), style = MaterialTheme.typography.labelSmall)
        if (error || jobs.lastOrNull()?.state == androidx.work.WorkInfo.State.FAILED) Text(stringResource(R.string.lyric_ai_insufficient), style = MaterialTheme.typography.labelSmall)
    }
    if (options) AlertDialog(onDismissRequest = { options = false }, title = { Text(stringResource(R.string.lyric_ai_suggest)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.lyric_ai_hint))
            LearningPreferences()
        }
    }, confirmButton = { TextButton(onClick = { options = false; scope.launch {
        try { LyricSuggestionWorker.enqueue(context, song, original); error = false }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = true }
    } }) { Text(stringResource(R.string.lyric_ai_start)) } }, dismissButton = { TextButton(onClick = { options = false }) { Text(stringResource(android.R.string.cancel)) } })
    if (review) EnrichmentReview(viewModel, songKey = song.uri.toString()) { review = false }
}
