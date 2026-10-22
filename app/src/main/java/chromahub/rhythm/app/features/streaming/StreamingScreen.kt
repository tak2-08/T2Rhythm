// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.Playlist
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import coil.compose.AsyncImage
import kotlinx.coroutines.*

@Composable
fun StreamingScreen(onBackClick: () -> Unit, musicViewModel: MusicViewModel) {
    val songs by musicViewModel.songs.collectAsState()
    val playlists by musicViewModel.playlists.collectAsState()
    val current by musicViewModel.currentSong.collectAsState()
    val playing by musicViewModel.isPlaying.collectAsState()
    val progress by musicViewModel.progress.collectAsState()
    StreamingContent(onBackClick, songs, playlists,
        onPlay = { list, index, shuffle -> musicViewModel.playQueue(list, enableShuffle = shuffle, startIndex = index, pinStartIndex = !shuffle) },
        current = current, playing = playing, progress = progress, onToggle = musicViewModel::togglePlayPause,
        onNext = musicViewModel::skipToNext, onSeek = { musicViewModel.seekTo(it) },
        onDisconnect = { server -> if (current?.uri?.pathSegments?.firstOrNull() == server && current?.uri?.host == StreamingProtocol.MEDIA_HOST) musicViewModel.pauseMusic() })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamingContent(onBackClick: () -> Unit, songs: List<Song>, playlists: List<Playlist>, onPlay: (List<Song>, Int, Boolean) -> Unit,
    manager: PrivateStreaming = PrivateStreaming.get(LocalContext.current), current: Song? = null, playing: Boolean = false,
    progress: Float = 0f, onToggle: () -> Unit = {}, onNext: () -> Unit = {}, onSeek: (Float) -> Unit = {}, onDisconnect: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host by manager.state.collectAsState()
    val connections by manager.store.connections.collectAsState()
    var hostTab by rememberSaveable { mutableStateOf(true) }
    var selectedPlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    var choosePlaylist by remember { mutableStateOf(false) }
    var invitation by remember { mutableStateOf<StreamingProtocol.Invite?>(null) }
    var addressDialog by remember { mutableStateOf(false) }
    var outside by remember { mutableStateOf(false) }
    var inviteText by remember { mutableStateOf("") }
    var deviceName by rememberSaveable { mutableStateOf(StreamingProtocol.name(Build.MODEL)) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<StreamConnection?>(null) }
    var pairJob by remember { mutableStateOf<Job?>(null) }
    var selectedServer by rememberSaveable { mutableStateOf<String?>(null) }
    var revoke by remember { mutableStateOf<StreamPeer?>(null) }
    var forget by remember { mutableStateOf<StreamConnection?>(null) }
    var editAddress by remember { mutableStateOf<StreamConnection?>(null) }
    var checkingAddress by remember { mutableStateOf(false) }
    val local = remember(songs) { songs.filter { it.uri.scheme in setOf("file", "content") } }
    val chosen = playlists.firstOrNull { it.id == selectedPlaylist }
    val sharedCount = if (selectedPlaylist == null) local.size else chosen?.songs?.count { it.uri.scheme in setOf("file", "content") } ?: 0
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val required = grants.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
        val action = afterPermission; afterPermission = null
        if (required.values.all { it }) action?.invoke() else error = context.getString(R.string.streaming_permission)
    }
    fun permitted(hosting: Boolean, action: () -> Unit) {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
            if (hosting) add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
            if (hosting && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else { afterPermission = action; permissions.launch(missing.toTypedArray()) }
    }
    LaunchedEffect(host.running) { while (host.running) { manager.refresh(); delay(3000) } }
    LaunchedEffect(invitation) { invitation?.let { delay((it.expires - System.currentTimeMillis()).coerceAtLeast(0)); invitation = null } }
    LaunchedEffect(connections) { if (selectedServer !in connections.map { it.server }) selectedServer = connections.firstOrNull()?.server }
    val activeConnection = connections.firstOrNull { it.server == selectedServer }
    val browser = activeConnection?.let { rememberStreamBrowser(it) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.streaming_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBackClick) { StreamIcon("arrow_back", stringResource(R.string.streaming_done)) } }) },
        bottomBar = {
            if (current?.uri?.host == StreamingProtocol.MEDIA_HOST) Surface(tonalElevation = 4.dp) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(current.artworkUri, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        Column(Modifier.weight(1f).padding(12.dp)) { Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(current.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        IconButton(onClick = onToggle) { StreamIcon(if (playing) "pause" else "play_arrow", stringResource(R.string.streaming_play_pause)) }
                        IconButton(onClick = onNext) { StreamIcon("skip_next", stringResource(R.string.streaming_next)) }
                    }
                    Slider(value = progress.coerceIn(0f, 1f), onValueChange = onSeek, modifier = Modifier.fillMaxWidth())
                }
            }
        }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(hostTab, { hostTab = true }, { Text(stringResource(R.string.streaming_host)) }, leadingIcon = { StreamIcon("dns") })
                    FilterChip(!hostTab, { hostTab = false }, { Text(stringResource(R.string.streaming_connect)) }, leadingIcon = { StreamIcon("devices") })
                }
            }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer))).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StreamIcon(if (hostTab) "phonelink_lock" else "headphones", size = 32)
                    Text(stringResource(R.string.streaming_hint), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(if (hostTab) R.string.streaming_host_intro else R.string.streaming_client_intro), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (error != null || (hostTab && host.error != null)) item { StreamError(error ?: host.error.orEmpty()) }
            if (hostTab) {
                item {
                    StreamCard {
                        Text(stringResource(when { host.starting -> R.string.streaming_starting; host.running -> R.string.streaming_running; else -> R.string.streaming_stopped }), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.streaming_status, if (host.running) host.tracks else sharedCount, host.peers.size))
                        if (host.starting) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (!host.running && !host.starting) {
                            Text(stringResource(R.string.streaming_scope), style = MaterialTheme.typography.labelLarge)
                            FilterChip(selectedPlaylist == null, { selectedPlaylist = null }, { Text(stringResource(R.string.streaming_all_music)) })
                            OutlinedButton({ choosePlaylist = true }, Modifier.fillMaxWidth()) { Text(chosen?.name ?: stringResource(R.string.streaming_choose_playlist)) }
                            if (sharedCount == 0) Text(stringResource(R.string.streaming_empty_local), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { permitted(true) { scope.launch {
                                try { withContext(Dispatchers.IO) { manager.store.select(chosen?.songs?.map { it.uri.toString() }?.toSet()) }; StreamingHostService.start(context) }
                                catch (_: Exception) { error = context.getString(R.string.streaming_start_error) }
                            } } }, enabled = sharedCount > 0, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.streaming_start)) }
                        } else if (host.running) {
                            Text(stringResource(R.string.streaming_scope_running), style = MaterialTheme.typography.bodySmall)
                            Button({ addressDialog = true }, Modifier.fillMaxWidth(), enabled = host.peers.size < StreamingProtocol.MAX_PEERS) { Text(stringResource(R.string.streaming_invite)) }
                            OutlinedButton({ StreamingHostService.stop(context); invitation = null }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.streaming_stop)) }
                            Text(stringResource(R.string.streaming_session_data, android.text.format.Formatter.formatShortFileSize(context, host.bytes)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (host.pending.isNotEmpty()) item { Text(stringResource(R.string.streaming_pending), style = MaterialTheme.typography.titleMedium) }
                items(host.pending, key = { "pending:${it.peer.id}" }) { request ->
                    StreamCard {
                        Text(request.peer.name, style = MaterialTheme.typography.titleMedium)
                        Text(request.code, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ scope.launch { try { withContext(Dispatchers.IO) { manager.approve(request.peer.id) } } catch (_: Exception) { error = context.getString(R.string.streaming_start_error) } } }) { Text(stringResource(R.string.streaming_approve)) }
                            TextButton({ scope.launch(Dispatchers.IO) { manager.revoke(request.peer.id) } }) { Text(stringResource(R.string.streaming_reject)) }
                        }
                    }
                }
                if (host.peers.isNotEmpty()) item { Text(stringResource(R.string.streaming_peers), style = MaterialTheme.typography.titleMedium) }
                items(host.peers, key = { "peer:${it.id}" }) { peer ->
                    StreamCard { Text(peer.name, style = MaterialTheme.typography.titleMedium); TextButton({ revoke = peer }) { Text(stringResource(R.string.streaming_revoke)) } }
                }
                item { Text(stringResource(R.string.streaming_power_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item {
                    StreamCard {
                        OutlinedTextField(inviteText, { if (it.length <= 8192) inviteText = it }, label = { Text(stringResource(R.string.streaming_invite_input)) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, enabled = !busy)
                        OutlinedTextField(deviceName, { deviceName = it.take(48) }, label = { Text(stringResource(R.string.streaming_device_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (pending != null) {
                            Text(stringResource(R.string.streaming_waiting), style = MaterialTheme.typography.titleMedium)
                            Text(StreamingProtocol.code(pending!!.token), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.streaming_verify_code, StreamingProtocol.code(pending!!.token)))
                            TextButton({ pairJob?.cancel(); pending = null; busy = false }) { Text(stringResource(R.string.streaming_cancel)) }
                        } else Button(onClick = { permitted(false) {
                            val invite = runCatching { StreamingProtocol.Invite.decode(inviteText) }.getOrNull()
                            if (invite == null) error = context.getString(R.string.streaming_invalid_invite) else {
                                error = null; busy = true
                                pairJob = scope.launch {
                                    try {
                                        val connection = StreamingClient.pair(invite, deviceName, StreamingProtocol.token()); pending = connection
                                        withTimeout(5 * 60_000L) { while (StreamingClient.status(connection) != "approved") delay(2000) }
                                        StreamingClient.page(connection)
                                        withContext(Dispatchers.IO) { manager.store.save(connection) }
                                        selectedServer = connection.server; inviteText = ""
                                    } catch (e: TimeoutCancellationException) { error = context.getString(R.string.streaming_pair_timeout) }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = StreamingClient.friendlyError(e) }
                                    finally { val abandoned = pending; busy = false; pending = null
                                        if (abandoned != null) withContext(NonCancellable + Dispatchers.IO) { runCatching { StreamingClient.cancelPairing(abandoned) } }
                                    }
                                }
                            }
                        } }, enabled = inviteText.isNotBlank() && deviceName.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.streaming_request)) }
                    }
                }
                item { Text(stringResource(R.string.streaming_servers), style = MaterialTheme.typography.titleLarge) }
                if (connections.isEmpty()) item { Text(stringResource(R.string.streaming_no_servers), style = MaterialTheme.typography.bodyMedium) }
                items(connections, key = { "server:${it.server}" }) { connection ->
                    StreamCard {
                        Row(Modifier.fillMaxWidth().clickable { selectedServer = connection.server }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selectedServer == connection.server, { selectedServer = connection.server })
                            Column(Modifier.weight(1f)) { Text(connection.name, fontWeight = FontWeight.Bold); Text(connection.endpoint, style = MaterialTheme.typography.bodySmall) }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton({ editAddress = connection }) { Text(stringResource(R.string.streaming_change_address)) }
                            TextButton({ forget = connection }) { Text(stringResource(R.string.streaming_forget)) }
                        }
                    }
                }
                browser?.let { streamingBrowser(it, onPlay) }
            }
            item { OutlinedButton({ outside = true }, Modifier.fillMaxWidth()) { StreamIcon("public"); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.streaming_outside_title)) } }
            item { Text(stringResource(R.string.streaming_privacy_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (choosePlaylist) AlertDialog(onDismissRequest = { choosePlaylist = false }, title = { Text(stringResource(R.string.streaming_choose_playlist)) }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) { items(playlists.filter { it.songs.isNotEmpty() }, key = { it.id }) { playlist ->
            ListItem(headlineContent = { Text(playlist.name) }, supportingContent = { Text("${playlist.songs.size}") }, modifier = Modifier.clickable { selectedPlaylist = playlist.id; choosePlaylist = false })
        } }
    }, confirmButton = { TextButton({ choosePlaylist = false }) { Text(stringResource(R.string.streaming_cancel)) } })
    if (addressDialog) StreamAddressDialog(host.addresses.firstOrNull().orEmpty(), host.addresses, R.string.streaming_invite_address_hint, R.string.streaming_create_invite,
        onDismiss = { addressDialog = false }, onSave = { address -> invitation = manager.invite(address); addressDialog = false })
    invitation?.let { invite -> AlertDialog(onDismissRequest = { invitation = null }, title = { Text(stringResource(R.string.streaming_invite_ready)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.streaming_invite_private)); SelectionContainer { Text(invite.endpoint, style = MaterialTheme.typography.bodyMedium) }
            Button({ (context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(ClipData.newPlainText("T2Rhythm invitation", invite.encode())); Toast.makeText(context, R.string.streaming_copied, Toast.LENGTH_SHORT).show() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.streaming_copy)) }
            OutlinedButton({ context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "T2Rhythm Streaming\n${invite.encode()}"), null)) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.streaming_share)) }
        }
    }, confirmButton = { TextButton({ invitation = null }) { Text(stringResource(R.string.streaming_done)) } }) }
    if (outside) AlertDialog(onDismissRequest = { outside = false }, title = { Text(stringResource(R.string.streaming_outside_title)) }, text = {
        androidx.compose.foundation.rememberScrollState().let { scroll -> Column(Modifier.heightIn(max = 440.dp).verticalScroll(scroll)) { Text(stringResource(R.string.streaming_outside_steps)) } }
    }, confirmButton = { TextButton({ outside = false }) { Text(stringResource(R.string.streaming_done)) } })
    revoke?.let { peer -> ConfirmStreamAction(peer.name, R.string.streaming_revoke_confirm, R.string.streaming_revoke, { revoke = null }) {
        scope.launch { try { withContext(Dispatchers.IO) { manager.revoke(peer.id) }; revoke = null } catch (_: Exception) { error = context.getString(R.string.streaming_start_error) } }
    } }
    forget?.let { connection -> ConfirmStreamAction(connection.name, R.string.streaming_forget_confirm, R.string.streaming_forget, { forget = null }) {
        scope.launch { withContext(Dispatchers.IO) { manager.store.forget(connection.server) }; onDisconnect(connection.server); forget = null
            runCatching { StreamingClient.disconnect(connection) } }
    } }
    editAddress?.let { connection -> StreamAddressDialog(connection.endpoint, emptyList(), R.string.streaming_address_check, R.string.streaming_save,
        onDismiss = { if (!checkingAddress) editAddress = null }, busy = checkingAddress, onSave = { address ->
            checkingAddress = true
            scope.launch { try { val updated = connection.at(address); StreamingClient.page(updated); withContext(Dispatchers.IO) { manager.store.save(updated) }; editAddress = null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = StreamingClient.friendlyError(e); editAddress = null }
                finally { checkingAddress = false } }
        }) }
}

@Composable private fun ConfirmStreamAction(title: String, body: Int, action: Int, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(stringResource(body)) }, confirmButton = { TextButton(confirm) { Text(stringResource(action)) } }, dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.streaming_cancel)) } })
}
@Composable private fun StreamAddressDialog(initial: String, alternatives: List<String>, hint: Int, action: Int, onDismiss: () -> Unit, busy: Boolean = false, onSave: (String) -> Unit) {
    var address by remember { mutableStateOf(initial) }; var invalid by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.streaming_address)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(address, { address = it.take(300); invalid = false }, label = { Text("HTTPS") }, modifier = Modifier.fillMaxWidth(), isError = invalid, singleLine = true, enabled = !busy)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (invalid) Text(stringResource(R.string.streaming_invalid_address), color = MaterialTheme.colorScheme.error)
            LazyColumn(Modifier.heightIn(max = 140.dp)) { items(alternatives) { value -> TextButton({ address = value }) { Text(value) } } }
        }
    }, confirmButton = { TextButton({ val normalized = runCatching { StreamingProtocol.endpoint(address) }.getOrNull(); if (normalized == null) invalid = true else onSave(normalized) }, enabled = !busy) { Text(stringResource(action)) } }, dismissButton = { TextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.streaming_cancel)) } })
}

@Stable
private class StreamBrowserState(val connection: StreamConnection, private val scope: CoroutineScope) {
    var query by mutableStateOf("")
    var genre by mutableStateOf("")
    var page by mutableStateOf<StreamPage?>(null)
    var tracks by mutableStateOf(emptyList<StreamTrack>())
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    private var generation = 0
    private var job: Job? = null
    fun cancel() { generation++; job?.cancel() }
    fun reload() {
        val request = ++generation; job?.cancel(); busy = true; error = null; tracks = emptyList(); page = null
        job = scope.launch {
            try { delay(350); val result = StreamingClient.page(connection, query, genre)
                if (request == generation) { page = result; tracks = result.tracks } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { if (request == generation) error = StreamingClient.friendlyError(e) }
            finally { if (request == generation) busy = false }
        }
    }
    fun more() {
        if (busy) return
        val request = generation; busy = true; error = null
        job = scope.launch {
            try { var result = StreamingClient.page(connection, query, genre, tracks.size)
                val reset = result.revision != page?.revision
                if (reset) result = StreamingClient.page(connection, query, genre)
                if (request == generation) { page = result; tracks = if (reset) result.tracks else (tracks + result.tracks).distinctBy { it.id } }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (request == generation) error = StreamingClient.friendlyError(e) }
            finally { if (request == generation) busy = false }
        }
    }
}
@Composable private fun rememberStreamBrowser(connection: StreamConnection): StreamBrowserState {
    val scope = rememberCoroutineScope()
    val state = remember(connection) { StreamBrowserState(connection, scope) }
    LaunchedEffect(state, state.query, state.genre) { state.reload() }
    LaunchedEffect(state) {
        while (true) { delay(30_000)
            if (!state.busy && state.page != null) runCatching { StreamingClient.revision(connection) }.getOrNull()?.let { revision ->
                if (revision != state.page?.revision) state.reload()
            }
        }
    }
    DisposableEffect(state) { onDispose { state.cancel() } }
    return state
}
private fun LazyListScope.streamingBrowser(state: StreamBrowserState, onPlay: (List<Song>, Int, Boolean) -> Unit) {
    val connection = state.connection
    item(key = "remote-search") {
        OutlinedTextField(state.query, { state.query = it.take(100) }, label = { Text(stringResource(R.string.streaming_search)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            trailingIcon = { if (state.query.isNotEmpty()) IconButton({ state.query = "" }) { StreamIcon("close", stringResource(R.string.streaming_clear_search)) } })
    }
    item(key = "remote-summary") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.page?.let { result ->
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(state.genre.isEmpty(), { state.genre = "" }, { Text(stringResource(R.string.streaming_all_music)) }) }
                    items(result.genres) { g -> FilterChip(g == state.genre, { state.genre = if (state.genre == g) "" else g }, { Text(g) }) }
                }
                Text("${result.matched} / ${result.total}", style = MaterialTheme.typography.labelLarge)
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.error != null) { StreamError(state.error!!); OutlinedButton(state::reload) { Text(stringResource(R.string.streaming_retry)) } }
            if (state.tracks.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button({ onPlay(state.tracks.map { it.song(connection.server) }, 0, false) }, enabled = !state.busy) { Text(stringResource(R.string.streaming_play_visible, state.tracks.size)) }
                    OutlinedButton({ onPlay(state.tracks.map { it.song(connection.server) }, 0, true) }, enabled = !state.busy) { Text(stringResource(R.string.streaming_shuffle)) }
                }
            }
        }
    }
    itemsIndexed(state.tracks, key = { _, track -> "remote:${connection.server}:${track.id}" }) { index, track ->
        val song = remember(track, connection.server) { track.song(connection.server) }
        ListItem(headlineContent = { Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            leadingContent = { AsyncImage(song.artworkUri, null, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentScale = ContentScale.Crop) },
            trailingContent = { StreamIcon("play_arrow") }, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = !state.busy) { onPlay(state.tracks.map { it.song(connection.server) }, index, false) })
    }
    item(key = "remote-footer") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.page != null && state.tracks.isEmpty() && !state.busy) Text(stringResource(R.string.streaming_no_results))
            if (state.tracks.size < (state.page?.matched ?: 0) && state.tracks.size < 2000) OutlinedButton(state::more, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.streaming_more)) }
            if (state.tracks.size >= 2000 && state.tracks.size < (state.page?.matched ?: 0)) Text(stringResource(R.string.streaming_search_more), style = MaterialTheme.typography.bodySmall)
            TextButton(state::reload, enabled = !state.busy) { Text(stringResource(R.string.streaming_refresh)) }
        }
    }
}

@Composable private fun StreamCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable private fun StreamError(message: String) { Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) { Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer) } }
@Composable private fun StreamIcon(name: String, description: String? = null, size: Int = 24) { Icon(MaterialSymbolIcon(name), description, Modifier.size(size.dp)) }
