// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

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
import chromahub.rhythm.app.features.local.data.video.AudioDownload
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import kotlinx.coroutines.*
import kr.co.metadata.crawler.ChartTrack
import kr.co.metadata.crawler.Provider

@Composable
fun DownloadSearch(albumMode: Boolean, viewModel: MusicViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("YOUTUBE") }
    var mobile by remember { mutableStateOf(false) }
    var songs by remember(albumMode) { mutableStateOf(emptyList<AudioSourceCandidate>()) }
    var albums by remember(albumMode) { mutableStateOf(emptyList<AudioAlbumCandidate>()) }
    var selected by remember(albumMode) { mutableStateOf<AudioAlbumCandidate?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { searchJob?.cancel() } }
    fun work(block: suspend () -> Unit) {
        searchJob?.cancel()
        searchJob = scope.launch {
            busy = true; message = null
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = context.getString(R.string.discovery_operation_failed) }
            finally { busy = false }
        }
    }
    suspend fun enqueue(items: List<AudioSourceCandidate>) {
        val album = selected?.title.orEmpty()
        selected?.let { collection -> withContext(Dispatchers.IO) {
            chromahub.rhythm.app.features.local.data.video.DownloadAlbums.save(context, collection, songs)
        } }
        for (hit in items) {
            currentCoroutineContext().ensureActive()
            AudioDownload.enqueueSource(context, hit.url, hit.title, hit.artist, album, mobile)
        }
        message = context.getString(R.string.download_added, items.size)
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 650.dp).navigationBarsPadding(),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(stringResource(R.string.download_search_hint), style = MaterialTheme.typography.bodySmall) }
        item { OutlinedTextField(query, { query = it.take(240) }, label = { Text(stringResource(if (albumMode) R.string.download_album_name else R.string.download_song_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(artist, { artist = it.take(240) }, label = { Text(stringResource(R.string.download_artist_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("YOUTUBE" to "YouTube", "SOUNDCLOUD" to "SoundCloud").forEach { (value, title) ->
                    FilterChip(source == value, onClick = { source = value; songs = emptyList(); albums = emptyList(); selected = null }, enabled = !busy, label = { Text(title) })
                }
            }
            Row {
                Text(stringResource(R.string.download_use_mobile), Modifier.weight(1f))
                Switch(mobile, onCheckedChange = { mobile = it })
            }
            Button(enabled = !busy && (query.isNotBlank() || artist.isNotBlank()), onClick = {
                val q = "$query $artist".trim(); val provider = source
                work {
                    selected = null; songs = emptyList(); albums = emptyList()
                    if (albumMode) albums = AudioSources.albums(provider, q) else songs = AudioSources.search(provider, q)
                    if (songs.isEmpty() && albums.isEmpty()) message = context.getString(R.string.download_search_empty)
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.download_search_action)) }
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        message?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
        items(albums, key = { it.url }) { album ->
            OutlinedCard(onClick = { work { selected = album; songs = AudioSources.albumTracks(album); if (songs.isEmpty()) message = context.getString(R.string.download_search_empty) } }, enabled = !busy) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(album.title, style = MaterialTheme.typography.titleMedium); Text(album.artist, style = MaterialTheme.typography.bodySmall) }
            }
        }
        selected?.let { album -> item {
            Text(album.title, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.download_album_preview, songs.size), style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy && songs.isNotEmpty(), onClick = { val batch = songs.toList(); work { enqueue(batch) } }) { Text(stringResource(R.string.download_album_all)) }
        } }
        items(songs, key = { it.url }) { hit ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(hit.title, style = MaterialTheme.typography.titleMedium)
                    Text(hit.artist + " · %d:%02d".format(hit.durationSeconds / 60, hit.durationSeconds % 60), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy, onClick = { work {
                            val metadata = ChartTrack(0, Provider.BUGS_PC, hit.url, hit.title, hit.artist, selected?.title.orEmpty(), hit.url)
                            val song = StreamingAudio.song(metadata, hit)
                            viewModel.playSong(song)
                        } }) { Icon(MaterialSymbolIcon("play_arrow"), null, Modifier.size(20.dp)); Text(stringResource(R.string.download_listen)) }
                        TextButton(enabled = !busy, onClick = { work { enqueue(listOf(hit)) } }) { Icon(MaterialSymbolIcon("download"), null, Modifier.size(20.dp)); Text(stringResource(R.string.download_save_audio)) }
                    }
                }
            }
        }
    }
}
