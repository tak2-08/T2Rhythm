// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chromahub.rhythm.app.R
import chromahub.rhythm.app.shared.data.model.Playlist
import chromahub.rhythm.app.shared.data.model.Song
import kotlinx.coroutines.CancellationException

data class StreamSaveRequest(val name: String, val songs: List<Song>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NetworkPlaylistDialog(item: NetworkPlaylist, local: List<Song>, onDismiss: () -> Unit,
    onPlay: (List<Song>, Int, Boolean) -> Unit, onSave: (String, List<Song>) -> Unit) {
    var songs by remember(item) { mutableStateOf(emptyList<Song>()) }
    var busy by remember(item) { mutableStateOf(true) }
    var error by remember(item) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val manager = PrivateStreaming.get(LocalContext.current)
    val connections by manager.store.connections.collectAsState()
    val resolve = remember(local, connections) { StreamOrigins.resolver(local, manager.store.serverId, connections) }
    val playable = remember(songs, resolve) { songs.mapNotNull(resolve) }
    val missing = songs.size - playable.size
    LaunchedEffect(item, retry) {
        busy = true; error = null
        try { songs = StreamingClient.playlistSongs(item.source, item.playlist.id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = StreamingClient.friendlyError(e); songs = emptyList() }
        finally { busy = false }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(12.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column {
                TopAppBar(title = { Text(item.playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
                    IconButton(onDismiss) { NetworkIcon("close", stringResource(R.string.streaming_done)) }
                })
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
                    item {
                        Text(stringResource(R.string.network_playlist_source, item.source.name, if (busy) item.playlist.count else songs.size))
                        if (item.playlist.truncated) Text(stringResource(R.string.network_playlist_truncated), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.network_save_hint), style = MaterialTheme.typography.bodySmall)
                        if (missing > 0) Text(stringResource(R.string.network_missing_origins, missing), color = MaterialTheme.colorScheme.primary)
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp))
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton({ retry++ }) { Text(stringResource(R.string.streaming_retry)) } }
                    }
                    itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                        val resolved = resolve(song)
                        val origin = song.uri.pathSegments.firstOrNull()
                        val originName = if (origin == manager.store.serverId) stringResource(R.string.network_this_device)
                            else connections.firstOrNull { it.server == origin }?.name
                        ListItem(headlineContent = { Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Column {
                                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                originName?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                            } },
                            trailingContent = { NetworkIcon(if (resolved == null) "link_off" else "play_arrow") }, modifier = Modifier.clickable(enabled = resolved != null) {
                                onPlay(playable, playable.indexOfFirst { it.id == resolved?.id }.coerceAtLeast(0), false); onDismiss()
                            })
                    }
                }
                FlowRow(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ onPlay(playable, 0, false); onDismiss() }, enabled = !busy && playable.isNotEmpty()) { Text(stringResource(R.string.network_play_playlist)) }
                    OutlinedButton({ onPlay(playable, 0, true); onDismiss() }, enabled = !busy && playable.isNotEmpty()) { Text(stringResource(R.string.streaming_shuffle)) }
                    FilledTonalButton({ onSave(item.playlist.name, songs.map { resolve(it) ?: it }) }, enabled = !busy && songs.isNotEmpty()) { Text(stringResource(R.string.network_save_playlist)) }
                }
            }
        }
    }
}

@Composable fun StreamSaveDialog(request: StreamSaveRequest, playlists: List<Playlist>, onDismiss: () -> Unit, onSave: (String?, String) -> Unit) {
    var name by remember(request) { mutableStateOf(request.name) }
    var target by remember(request) { mutableStateOf<String?>(null) }
    val defaultName = stringResource(R.string.network_default_playlist)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.network_save_playlist)) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.network_save_hint), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().clickable { target = null }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(target == null, null); Text(stringResource(R.string.network_new_playlist), Modifier.weight(1f))
            }
            if (target == null) OutlinedTextField(name, { name = it.take(160) }, label = { Text(stringResource(R.string.network_playlist_name)) },
                placeholder = { Text(defaultName) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (playlists.isNotEmpty()) {
                Text(stringResource(R.string.network_existing_playlist), style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    itemsIndexed(playlists.filterNot { it.isDefault && it.id != "1" }, key = { _, list -> list.id }) { _, list ->
                        Row(Modifier.fillMaxWidth().clickable { target = list.id }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(target == list.id, null); Text(list.name, Modifier.weight(1f).padding(8.dp))
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton({ onSave(target, name.trim().ifBlank { defaultName }) }) { Text(stringResource(R.string.network_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.streaming_cancel)) } })
}
