// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class NetworkTrack(val source: StreamConnection, val track: StreamTrack) { fun song() = track.song(source.server) }
data class NetworkPlaylist(val source: StreamConnection, val playlist: StreamPlaylist)
data class NetworkSource(val connection: StreamConnection, val page: StreamPage? = null, val tracks: List<StreamTrack> = emptyList(),
    val playlists: List<StreamPlaylist> = emptyList(), val error: String? = null, val busy: Boolean = false, val playlistError: Boolean = false)

/** Independent, bounded requests let reachable peers work while another device is offline. */
@Stable class StreamNetworkBrowserState(val connections: List<StreamConnection>, private val scope: CoroutineScope) {
    var query by mutableStateOf("")
    var genre by mutableStateOf("")
    val sources = mutableStateMapOf<String, NetworkSource>()
    private val permits = Semaphore(3)
    private var generation = 0
    private val jobs = mutableListOf<Job>()
    val tracks: List<NetworkTrack> get() = connections.flatMap { c -> sources[c.server]?.takeIf { it.error == null }?.tracks.orEmpty().map { NetworkTrack(c, it) } }
    val playlists: List<NetworkPlaylist> get() = connections.flatMap { c -> sources[c.server]?.takeIf { it.error == null }?.playlists.orEmpty().map { NetworkPlaylist(c, it) } }
        .filter { query.isBlank() || it.playlist.name.contains(query.trim(), true) || it.source.name.contains(query.trim(), true) }
    val busy get() = sources.values.any { it.busy }
    fun cancel() { generation++; jobs.forEach { it.cancel() }; jobs.clear() }
    fun reload() {
        cancel(); val attempt = generation; sources.clear()
        connections.forEach { sources[it.server] = NetworkSource(it, busy = true) }
        jobs += scope.launch { delay(350); coroutineScope { connections.map { c -> launch { fetch(c, attempt) } }.joinAll() } }
    }
    private suspend fun fetch(c: StreamConnection, attempt: Int) = permits.withPermit { fetchInside(c, attempt) }
    private suspend fun fetchInside(c: StreamConnection, attempt: Int) {
        try {
            val page = withTimeout(12_000) { StreamingClient.page(c, query, genre) }
            if (attempt != generation) return
            sources[c.server] = NetworkSource(c, page, page.tracks, busy = true)
            var listError = false
            val lists = if ("playlists-v1" in page.features) try { withTimeout(8_000) { StreamingClient.playlists(c) } }
                catch (e: TimeoutCancellationException) { listError = true; emptyList() } catch (e: CancellationException) { throw e } catch (_: Exception) { listError = true; emptyList() } else emptyList()
            if (attempt == generation) sources[c.server] = sources.getValue(c.server).copy(playlists = lists, busy = false, playlistError = listError)
        } catch (e: TimeoutCancellationException) { failed(c, attempt, e) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failed(c, attempt, e) }
    }
    private fun failed(c: StreamConnection, attempt: Int, error: Exception) {
        if (attempt == generation) sources[c.server] = NetworkSource(c, error = StreamingClient.friendlyError(error))
    }
    fun more(server: String) {
        val old = sources[server] ?: return
        if (old.busy || old.tracks.size >= 2000) return
        val attempt = generation; sources[server] = old.copy(busy = true)
        jobs += scope.launch { permits.withPermit {
            try {
                var next = withTimeout(12_000) { StreamingClient.page(old.connection, query, genre, old.tracks.size) }
                val reset = next.revision != old.page?.revision
                if (reset) { fetchInside(old.connection, attempt); return@withPermit }
                if (attempt == generation) sources[server] = old.copy(page = next,
                    tracks = if (reset) next.tracks else (old.tracks + next.tracks).distinctBy { it.id }, busy = false)
            } catch (e: TimeoutCancellationException) { failed(old.connection, attempt, e) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed(old.connection, attempt, e) }
        } }
    }
    suspend fun refreshChanges() = coroutineScope {
        val attempt = generation
        connections.map { c -> launch {
            val old = sources[c.server] ?: return@launch
            if (old.busy) return@launch
            val changed = try { permits.withPermit { withTimeout(4_000) { StreamingClient.revision(c) } } != old.page?.revision }
                catch (e: TimeoutCancellationException) { true } catch (e: CancellationException) { throw e } catch (_: Exception) { true }
            if (changed || old.error != null) fetch(c, attempt)
        } }.joinAll()
    }
}

@Composable fun rememberStreamNetworkBrowser(connections: List<StreamConnection>): StreamNetworkBrowserState {
    val scope = rememberCoroutineScope()
    var savedQuery by rememberSaveable { mutableStateOf("") }
    var savedGenre by rememberSaveable { mutableStateOf("") }
    val state = remember(connections) { StreamNetworkBrowserState(connections, scope).also { it.query = savedQuery; it.genre = savedGenre } }
    SideEffect { savedQuery = state.query; savedGenre = state.genre }
    LaunchedEffect(state, state.query, state.genre) { state.reload() }
    LaunchedEffect(state) { while (true) { delay(30_000); state.refreshChanges() } }
    DisposableEffect(state) { onDispose { state.cancel() } }
    return state
}

fun LazyListScope.streamNetworkBrowser(state: StreamNetworkBrowserState, playlistsTab: Boolean, onTab: (Boolean) -> Unit,
    onPlay: (List<Song>, Int, Boolean) -> Unit, onSave: (String, List<Song>) -> Unit, onPlaylist: (NetworkPlaylist) -> Unit) {
    item(key = "network-search") {
        OutlinedTextField(state.query, { state.query = it.take(100) }, label = { Text(stringResource(R.string.streaming_search)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            trailingIcon = { if (state.query.isNotEmpty()) IconButton({ state.query = "" }) { NetworkIcon("close", stringResource(R.string.streaming_clear_search)) } })
    }
    item(key = "network-tabs") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!playlistsTab, { onTab(false) }, { Text(stringResource(R.string.network_songs)) })
            FilterChip(playlistsTab, { onTab(true) }, { Text(stringResource(R.string.network_playlists)) })
        }
    }
    item(key = "network-status") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.connections.forEach { connection -> state.sources[connection.server]?.let { source ->
                if (source.error != null) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(connection.name, fontWeight = FontWeight.Bold); Text(source.error, style = MaterialTheme.typography.bodySmall) }
                }
                if (playlistsTab && source.playlistError) Text(stringResource(R.string.network_playlist_error, connection.name), color = MaterialTheme.colorScheme.error)
            } }
            if (!playlistsTab) {
                val genres = state.connections.flatMap { state.sources[it.server]?.page?.genres.orEmpty() }.distinct().take(24)
                if (genres.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(state.genre.isBlank(), { state.genre = "" }, { Text(stringResource(R.string.network_all_genres)) }) }
                    items(genres) { g -> FilterChip(state.genre == g, { state.genre = if (state.genre == g) "" else g }, { Text(g) }) }
                }
                val tracks = state.tracks
                if (tracks.isNotEmpty()) {
                    Text(stringResource(R.string.network_visible_tracks, tracks.size), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button({ onPlay(tracks.map { it.song() }, 0, false) }) { Text(stringResource(R.string.streaming_play_visible, tracks.size)) }
                        OutlinedButton({ onPlay(tracks.map { it.song() }, 0, true) }) { Text(stringResource(R.string.streaming_shuffle)) }
                        TextButton({ onSave("", tracks.map { it.song() }) }) { Text(stringResource(R.string.network_save_visible)) }
                    }
                }
            }
        }
    }
    if (playlistsTab) {
        val lists = state.playlists
        items(lists, key = { "network-playlist:${it.source.server}:${it.playlist.id}" }) { list ->
            ListItem(headlineContent = { Text(list.playlist.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(stringResource(R.string.network_playlist_source, list.source.name, list.playlist.count)) },
                leadingContent = { NetworkIcon("queue_music") }, trailingContent = { NetworkIcon("chevron_right") },
                modifier = Modifier.clip(MaterialTheme.shapes.large).clickable { onPlaylist(list) })
        }
        if (lists.isEmpty() && !state.busy) item { Text(stringResource(R.string.network_no_playlists)) }
    } else {
        val tracks = state.tracks
        itemsIndexed(tracks, key = { _, value -> "network-track:${value.source.server}:${value.track.id}" }) { index, value ->
            val song = remember(value) { value.song() }
            ListItem(headlineContent = { Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Column { Text(listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(value.source.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } },
                leadingContent = { Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    NetworkIcon("music_note"); AsyncImage(song.artworkUri, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                } }, trailingContent = { IconButton({ onSave(song.title, listOf(song)) }) { NetworkIcon("playlist_add", stringResource(R.string.network_save_song)) } },
                modifier = Modifier.clip(MaterialTheme.shapes.large).clickable { onPlay(tracks.map { it.song() }, index, false) })
        }
        if (tracks.isEmpty() && !state.busy) item { Text(stringResource(R.string.streaming_no_results)) }
        state.connections.forEach { connection -> val source = state.sources[connection.server]
            if (source?.page != null && source.tracks.size < source.page.matched) item(key = "network-more:${connection.server}") {
                if (source.tracks.size < 2000) OutlinedButton({ state.more(connection.server) }, enabled = !source.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.network_more_from, connection.name))
                } else Text(stringResource(R.string.streaming_search_more), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    item(key = "network-refresh") { TextButton(state::reload, enabled = !state.busy) { Text(stringResource(R.string.streaming_refresh)) } }
}
@Composable internal fun NetworkIcon(name: String, description: String? = null) { Icon(MaterialSymbolIcon(name), description, Modifier.size(24.dp)) }
