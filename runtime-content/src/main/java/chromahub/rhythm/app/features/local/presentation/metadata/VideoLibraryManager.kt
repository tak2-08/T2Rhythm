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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.video.*
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.Song
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoLibraryManager(onDismiss: () -> Unit, viewModel: MusicViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { MusicVideoRepository(context) }
    val songs by viewModel.songs.collectAsState()
    val revision by MusicVideoRepository.revision.collectAsState()
    var videos by remember { mutableStateOf(emptyList<MusicVideo>()) }
    var links by remember { mutableStateOf(emptyMap<String, Int>()) }
    var target by remember { mutableStateOf<MusicVideo?>(null) }
    var deleting by remember { mutableStateOf<MusicVideo?>(null) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(revision, songs) {
        val data = withContext(Dispatchers.IO) {
            val connected = songs.mapNotNull { repository.get(it)?.fileName }.groupingBy { it }.eachCount()
            repository.library() to connected
        }
        videos = data.first; links = data.second
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 700.dp).navigationBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.video_library_title), style = MaterialTheme.typography.headlineSmall); Text(stringResource(R.string.video_library_hint), style = MaterialTheme.typography.bodySmall) }
            item { Text(Formatter.formatShortFileSize(context, videos.sumOf { it.bytes }), style = MaterialTheme.typography.titleLarge) }
            if (error) item { Text(stringResource(R.string.discovery_operation_failed), color = MaterialTheme.colorScheme.error) }
            if (videos.isEmpty()) item { Text(stringResource(R.string.video_library_empty)) }
            items(videos, key = { it.fileName!! }) { video ->
                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text(video.title, maxLines = 2, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.video_library_linked, links[video.fileName] ?: 0) + " · " + Formatter.formatShortFileSize(context, video.bytes), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(enabled = !busy, onClick = { target = video; query = "" }) { Text(stringResource(R.string.video_library_attach)) }
                        TextButton(enabled = !busy, onClick = { deleting = video }) { Text(stringResource(R.string.video_library_delete)) }
                    }
                } }
            }
        }
    }
    target?.let { video -> AlertDialog(onDismissRequest = { target = null }, title = { Text(stringResource(R.string.video_library_attach)) }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.download_song_name)) }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 350.dp)) {
                items(songs.filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) }.take(100), key = { it.uri.toString() }) { song ->
                    TextButton(enabled = !busy, onClick = { scope.launch {
                        busy = true
                        try { withContext(Dispatchers.IO) { repository.attach(song, video) }; target = null }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { error = true }
                        finally { busy = false }
                    } }) { Text(song.title + " · " + song.artist) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { target = null }) { Text(stringResource(android.R.string.cancel)) } }) }
    deleting?.let { video -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.video_library_delete)) }, text = { Text(stringResource(R.string.video_library_delete_hint)) }, confirmButton = {
        TextButton(enabled = !busy, onClick = { scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { repository.deleteFile(video) }; deleting = null }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = true }
            finally { busy = false }
        } }) { Text(stringResource(R.string.video_library_delete)) }
    }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(android.R.string.cancel)) } }) }
}
