// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadSearch(
    albumMode: Boolean,
    viewModel: MusicViewModel,
    state: DownloadSearchState = rememberSaveable(saver = DownloadSearchState.Saver) { DownloadSearchState() },
    requestScope: CoroutineScope = rememberCoroutineScope(),
    dataSource: DownloadSearchSource = DownloadSearchSource,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun work(block: suspend () -> Unit) = state.work(requestScope, context.getString(R.string.discovery_operation_failed), block)
    fun search() {
        if (state.busy || state.query.isBlank() && state.artist.isBlank()) return
        val q = "${state.query} ${state.artist}".trim()
        val provider = state.source
        focus.clearFocus(); keyboard?.hide()
        work {
            state.selected = null; state.songs = emptyList(); state.albums = emptyList()
            if (albumMode) {
                val hits = dataSource.albums(provider, q)
                currentCoroutineContext().ensureActive()
                state.albums = hits.distinctBy { it.url }
            } else {
                val hits = dataSource.songs(provider, q)
                currentCoroutineContext().ensureActive()
                state.songs = hits.distinctBy { it.url }
            }
            if (state.songs.isEmpty() && state.albums.isEmpty()) state.message = context.getString(R.string.download_search_empty)
        }
    }
    suspend fun enqueue(items: List<AudioSourceCandidate>) {
        val collection = state.selected
        collection?.let { album -> withContext(Dispatchers.IO) {
            chromahub.rhythm.app.features.local.data.video.DownloadAlbums.save(context, album, state.songs)
        } }
        for (hit in items) {
            currentCoroutineContext().ensureActive()
            AudioDownload.enqueueSource(context, hit.url, hit.title, hit.artist, collection?.title.orEmpty(), state.mobile)
        }
        state.message = context.getString(R.string.download_added, items.size)
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 650.dp).imePadding().navigationBarsPadding().testTag("download-search-list"), state = state.listState,
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(stringResource(R.string.download_search_hint), style = MaterialTheme.typography.bodySmall) }
        item {
            OutlinedTextField(state.query, { state.query = it.take(240) },
                label = { Text(stringResource(if (albumMode) R.string.download_album_name else R.string.download_song_name)) },
                singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { state.query = "" }) {
                    Icon(MaterialSymbolIcon("close"), stringResource(R.string.usability_clear_title))
                } }, modifier = Modifier.fillMaxWidth().testTag("download-search-title"))
        }
        item {
            OutlinedTextField(state.artist, { state.artist = it.take(240) }, label = { Text(stringResource(R.string.download_artist_name)) },
                singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.fillMaxWidth().testTag("download-search-artist"))
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("YOUTUBE" to "YouTube", "SOUNDCLOUD" to "SoundCloud").forEach { (value, title) ->
                    FilterChip(state.source == value, onClick = { state.source = value; state.songs = emptyList(); state.albums = emptyList(); state.selected = null }, enabled = !state.busy, label = { Text(title) })
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(state.mobile, role = Role.Switch, onValueChange = { state.mobile = it }),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.download_use_mobile), Modifier.weight(1f))
                Switch(state.mobile, onCheckedChange = null)
            }
            Button(enabled = !state.busy && (state.query.isNotBlank() || state.artist.isNotBlank()), onClick = ::search,
                modifier = Modifier.fillMaxWidth().testTag("download-search-execute")) { Text(stringResource(R.string.download_search_action)) }
        }
        if (state.busy) item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { state.cancel(context.getString(R.string.usability_search_cancelled)) }, modifier = Modifier.testTag("download-search-cancel")) {
                Text(stringResource(android.R.string.cancel))
            }
        }
        state.message?.let { text -> item {
            Text(text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium)
        } }
        if (state.selected == null) items(state.albums, key = { it.url }) { album ->
            OutlinedCard(onClick = { work {
                state.selected = album; state.songs = emptyList()
                val tracks = dataSource.tracks(album)
                currentCoroutineContext().ensureActive()
                state.songs = tracks.distinctBy { it.url }
                if (state.songs.isEmpty()) state.message = context.getString(R.string.download_search_empty)
            } }, enabled = !state.busy) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(album.title, style = MaterialTheme.typography.titleMedium); Text(album.artist, style = MaterialTheme.typography.bodySmall) }
            }
        }
        state.selected?.let { album -> item {
            TextButton(enabled = !state.busy, onClick = { state.selected = null; state.songs = emptyList(); state.message = null }) {
                Icon(MaterialSymbolIcon("arrow_back"), null, Modifier.size(20.dp))
                Text(stringResource(R.string.usability_back_to_albums))
            }
            Text(album.title, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.download_album_preview, state.songs.size), style = MaterialTheme.typography.bodySmall)
            Button(enabled = !state.busy && state.songs.isNotEmpty(), onClick = { val batch = state.songs.toList(); work { enqueue(batch) } }) { Text(stringResource(R.string.download_album_all)) }
        } }
        items(state.songs, key = { it.url }) { hit ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(hit.title, style = MaterialTheme.typography.titleMedium)
                    Text(hit.artist + " · %d:%02d".format(hit.durationSeconds / 60, hit.durationSeconds % 60), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !state.busy, onClick = { work {
                            val metadata = ChartTrack(0, Provider.BUGS_PC, hit.url, hit.title, hit.artist, state.selected?.title.orEmpty(), hit.url)
                            viewModel.playSong(StreamingAudio.song(metadata, hit))
                        } }) { Icon(MaterialSymbolIcon("play_arrow"), null, Modifier.size(20.dp)); Text(stringResource(R.string.download_listen)) }
                        TextButton(enabled = !state.busy, onClick = { work { enqueue(listOf(hit)) } }) { Icon(MaterialSymbolIcon("download"), null, Modifier.size(20.dp)); Text(stringResource(R.string.download_save_audio)) }
                    }
                }
            }
        }
    }
}
