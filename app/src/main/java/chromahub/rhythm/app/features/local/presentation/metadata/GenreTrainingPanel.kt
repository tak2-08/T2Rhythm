// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.discovery.*
import chromahub.rhythm.app.features.local.data.experience.AudioAnalysisWorker
import chromahub.rhythm.app.features.local.di.LocalMusicModule
import chromahub.rhythm.app.shared.data.model.Song
import kotlinx.coroutines.*

@Composable
fun GenreTrainingPanel(loadSongs: (suspend () -> List<Song>)? = null,
    scheduleTraining: (android.content.Context) -> Unit = { AudioAnalysisWorker.enqueue(it); EnrichmentLearningWorker.enqueue(it) }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { GenreCorrections(context) }
    val revision by GenreCorrections.revision.collectAsState()
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var corrections by remember { mutableStateOf(emptyMap<String, GenreCorrection>()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var missingOnly by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Song?>(null) }
    var genre by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision, retry) {
        loading = true
        try {
            val data = withContext(Dispatchers.IO) {
                (loadSongs?.invoke() ?: LocalMusicModule.provideMusicRepository(context).loadSongs()) to store.read().associateBy { it.songKey }
            }
            songs = data.first; corrections = data.second
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { message = context.getString(R.string.usability_load_failed) }
        finally { loading = false }
    }
    fun update(remove: Boolean) {
        val song = selected ?: return
        busy = true; message = null
        val label = genre
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (remove) store.remove(song.uri.toString()) else store.put(song, label)
                    store.refreshModel(songs, song)
                    scheduleTraining(context)
                }
                message = context.getString(if (remove) R.string.genre_removed else R.string.genre_saved)
                selected = null
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = context.getString(R.string.discovery_operation_failed) }
            finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.genre_training_hint), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.genre_training_count, corrections.size), style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(query, onValueChange = { query = it }, singleLine = true, enabled = !busy,
            label = { Text(stringResource(R.string.genre_song_search)) }, modifier = Modifier.fillMaxWidth())
        FilterChip(missingOnly, onClick = { missingOnly = !missingOnly }, label = { Text(stringResource(R.string.genre_missing_only)) })
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!loading && songs.isEmpty()) Text(stringResource(R.string.personal_collection_empty))
        val visible = remember(songs, corrections, query, missingOnly) {
            songs.filter { song ->
                val label = corrections[song.uri.toString()]?.genre ?: song.genre
                (!missingOnly || chromahub.rhythm.app.util.GenreUtils.splitGenres(label).isEmpty()) &&
                    (query.isBlank() || song.title.contains(query.trim(), true) || song.artist.contains(query.trim(), true))
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
            items(visible, key = { it.uri.toString() }) { song ->
                ListItem(headlineContent = { Text(song.title, maxLines = 2) },
                    supportingContent = { Text(song.artist + " · " + (corrections[song.uri.toString()]?.genre
                        ?: song.genre?.takeIf(chromahub.rhythm.app.util.GenreUtils::isKnownGenre) ?: stringResource(R.string.genre_missing))) },
                    modifier = Modifier.clickable(enabled = !busy) {
                        selected = song; genre = corrections[song.uri.toString()]?.genre
                            ?: chromahub.rhythm.app.util.GenreUtils.splitGenres(song.genre).joinToString(", ")
                    })
            }
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
            if (songs.isEmpty()) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.mv_retry)) }
        }
    }
    if (selected != null) AlertDialog(onDismissRequest = { if (!busy) selected = null },
        title = { Text(selected!!.title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.genre_edit_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(genre, onValueChange = { genre = it.take(640) }, enabled = !busy,
                label = { Text(stringResource(R.string.genre_correct_label)) }, modifier = Modifier.fillMaxWidth())
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (selected!!.uri.toString() in corrections) TextButton(enabled = !busy, onClick = { update(true) }) { Text(stringResource(R.string.genre_forget)) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !busy && chromahub.rhythm.app.util.GenreUtils.splitGenres(genre).let { it.size in 1..8 && it.all { label -> label.length <= 80 } },
            onClick = { update(false) }) { Text(stringResource(R.string.genre_teach)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { selected = null }) { Text(stringResource(android.R.string.cancel)) } })
}
