// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
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
    val lines = remember(lyrics) { LyricTimingInput.lines(lyrics) }
    if (song == null || lyrics == null || lines.isEmpty()) return
    val original = lyrics
    val local = song.uri.scheme in setOf("content", "file")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = SongArtworkOverrides.key(song)
    val jobs by remember(key) { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(LyricSuggestionWorker.name(key)) }.collectAsState(emptyList())
    val speechJobs by remember(key) { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("speech_alignment:${kr.co.metadata.crawler.CatalogFileStore.hash(song.uri.toString())}") }.collectAsState(emptyList())
    var speechPercent by remember(key) { mutableStateOf<Int?>(null) }
    LaunchedEffect(key, jobs, speechJobs) {
        speechPercent = withContext(Dispatchers.IO) {
            SpeechAlignment.read(context, song.uri.toString())?.takeIf {
                EnrichmentStore(context).mode() != "LIGHT" && it.signature == EnrichmentStore.signature(song)
            }?.let { (it.offsetMs * 100 / song.duration.coerceAtLeast(1)).toInt().coerceIn(0, 100) }
        }
    }
    val revision by EnrichmentStore.revision.collectAsState()
    var proposalId by remember(key, original) { mutableStateOf<String?>(null) }
    var review by remember(key) { mutableStateOf(false) }
    var options by remember(key) { mutableStateOf(false) }
    var starting by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, revision, original) {
        proposalId = withContext(Dispatchers.IO) {
            EnrichmentStore(context).list(1000).firstOrNull {
                it.songKey == song.uri.toString() && it.kind == "LYRICS" && it.state == "PENDING" &&
                    it.sourceSignature == EnrichmentStore.signature(song) && EnrichmentStore.sameLyrics(it.beforeLyrics, original)
            }?.id
        }
    }
    val latest = jobs.maxByOrNull { job -> job.tags.firstNotNullOfOrNull { it.removePrefix("lyric_request_").toLongOrNull() } ?: 0L }
    val active = starting || latest?.state?.isFinished == false
    val failed = !active && proposalId == null && (error || latest?.state == WorkInfo.State.FAILED)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("lyric-ai-action"),
            onClick = { if (proposalId != null) review = true else options = true },
            enabled = local && (!active || proposalId != null),
        ) {
            Icon(MaterialSymbolIcon("auto_awesome"), null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (proposalId != null) R.string.lyric_ai_review else if (active) R.string.lyric_ai_queued else R.string.lyric_ai_suggest))
        }
        if (!local) Text(stringResource(R.string.lyric_ai_local_required), style = MaterialTheme.typography.bodySmall)
        if (active) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            val stage = latest?.progress?.getString("stage")
            Text(when (stage) {
                "audio" -> stringResource(R.string.lyric_ai_analyzing_audio)
                "speech" -> stringResource(R.string.lyric_ai_analyzing_speech, (latest?.progress?.getInt("percent", 0) ?: 0))
                "matching" -> stringResource(R.string.lyric_ai_matching)
                else -> speechPercent?.let { percent ->
                    if (percent < 100) stringResource(R.string.lyric_ai_analyzing_speech, percent)
                    else stringResource(R.string.lyric_ai_matching)
                } ?: stringResource(R.string.lyric_ai_conditions)
            }, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { scope.launch { LyricSuggestionWorker.cancel(context, song) } }) { Text(stringResource(android.R.string.cancel)) }
        }
        if (failed) Text(stringResource(if (latest?.outputData?.getString("reason") == "model_missing") R.string.lyric_ai_model_required else R.string.lyric_ai_insufficient), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (options) AlertDialog(
        onDismissRequest = { options = false },
        title = { Text(stringResource(R.string.lyric_ai_suggest)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.lyric_ai_hint))
                if (lines.size !in 3..1000 || song.duration !in 15_000..1_200_000) Text(stringResource(R.string.lyric_ai_input_limits), color = MaterialTheme.colorScheme.error)
                LearningPreferences()
            }
        },
        confirmButton = { TextButton(enabled = lines.size in 3..1000 && song.duration in 15_000..1_200_000, onClick = {
            options = false; starting = true
            scope.launch {
                try { LyricSuggestionWorker.enqueue(context, song, original); error = false }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { error = true }
                finally { starting = false }
            }
        }) { Text(stringResource(R.string.lyric_ai_start)) } },
        dismissButton = { TextButton(onClick = { options = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
    if (review) EnrichmentReview(viewModel, songKey = song.uri.toString(), initialProposalId = proposalId, fallbackSong = song) { review = false }
}
