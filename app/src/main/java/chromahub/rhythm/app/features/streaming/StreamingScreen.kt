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
import androidx.compose.ui.platform.testTag
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
    val context = LocalContext.current
    val songs by musicViewModel.songs.collectAsState()
    val playlists by musicViewModel.playlists.collectAsState()
    val current by musicViewModel.currentSong.collectAsState()
    val playing by musicViewModel.isPlaying.collectAsState()
    val progress by musicViewModel.progress.collectAsState()
    StreamingContent(onBackClick, songs, playlists,
        onPlay = { list, index, shuffle -> musicViewModel.playQueue(list, enableShuffle = shuffle, startIndex = index, pinStartIndex = !shuffle) },
        current = current, playing = playing, progress = progress, onToggle = musicViewModel::togglePlayPause,
        onNext = musicViewModel::skipToNext, onSeek = { musicViewModel.seekTo(it) },
        onDisconnect = { server -> if (current?.uri?.pathSegments?.firstOrNull() == server && current?.uri?.host == StreamingProtocol.MEDIA_HOST) musicViewModel.pauseMusic() },
        onCreatePlaylist = { name, list -> musicViewModel.createPlaylist(name, list) { Toast.makeText(context, R.string.network_saved, Toast.LENGTH_SHORT).show() } },
        onAddToPlaylist = { id, list -> musicViewModel.addSongsToPlaylist(list, id); Toast.makeText(context, R.string.network_saved, Toast.LENGTH_SHORT).show() })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamingContent(onBackClick: () -> Unit, songs: List<Song>, playlists: List<Playlist>, onPlay: (List<Song>, Int, Boolean) -> Unit,
    manager: PrivateStreaming = PrivateStreaming.get(LocalContext.current), current: Song? = null, playing: Boolean = false,
    progress: Float = 0f, onToggle: () -> Unit = {}, onNext: () -> Unit = {}, onSeek: (Float) -> Unit = {}, onDisconnect: (String) -> Unit = {},
    onCreatePlaylist: (String, List<Song>) -> Unit = { _, _ -> }, onAddToPlaylist: (String, List<Song>) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nearby = remember(manager) { NearbyStreaming(context.applicationContext, manager.store.serverId) }
    val nearbyDevices by nearby.devices.collectAsState()
    val searchingNearby by nearby.searching.collectAsState()
    val advertisingNearby by nearby.advertising.collectAsState()
    val nearbyFailed by nearby.failed.collectAsState()
    var bluetoothSharing by remember { mutableStateOf(false) }
    DisposableEffect(nearby) { onDispose { nearby.close(); BluetoothStreaming.hideInvitation(); manager.access.cancelInvite() } }
    val host by manager.state.collectAsState()
    val connections by manager.store.connections.collectAsState()
    var hostTab by rememberSaveable { mutableStateOf(true) }
    var shareAll by rememberSaveable { mutableStateOf(manager.store.selection().let { it == null || (it.isEmpty() && manager.store.sharedPlaylists().isEmpty()) }) }
    var selectedPlaylists by rememberSaveable { mutableStateOf(manager.store.sharedPlaylists()) }
    var choosePlaylist by remember { mutableStateOf(false) }
    var invitation by remember { mutableStateOf<StreamingProtocol.Invite?>(null) }
    var addressDialog by remember { mutableStateOf(false) }
    var outside by remember { mutableStateOf(false) }
    var inviteText by remember { mutableStateOf("") }
    var deviceName by rememberSaveable { mutableStateOf(StreamingProtocol.name(Build.MODEL)) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<StreamConnection?>(null) }
    var pairingBinding by remember { mutableStateOf(1) }
    var pairJob by remember { mutableStateOf<Job?>(null) }
    var selectedServer by rememberSaveable { mutableStateOf<String?>(null) }
    var showPairing by remember { mutableStateOf(connections.isEmpty()) }
    var manageDevices by remember { mutableStateOf(false) }
    var playlistsTab by rememberSaveable { mutableStateOf(false) }
    var openedPlaylist by remember { mutableStateOf<NetworkPlaylist?>(null) }
    var saveSongs by remember { mutableStateOf<StreamSaveRequest?>(null) }
    var revoke by remember { mutableStateOf<StreamPeer?>(null) }
    var forget by remember { mutableStateOf<StreamConnection?>(null) }
    var editAddress by remember { mutableStateOf<StreamConnection?>(null) }
    var checkingAddress by remember { mutableStateOf(false) }
    var networkAccess by remember { mutableStateOf(Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED) }
    val local = remember(songs) { songs.filter { it.uri.scheme in setOf("file", "content") } }
    var scrubPosition by remember(current?.id) { mutableStateOf<Float?>(null) }
    val chosen = playlists.filter { it.id in selectedPlaylists }
    val sharedSongs = chosen.flatMap { it.songs }.filter { it.uri.scheme in setOf("file", "content") }.distinctBy { it.uri.toString() }
    val sharedCount = if (shareAll) local.size + chosen.flatMap { it.songs }.count { StreamOrigins.reference(it) != null } else sharedSongs.size + chosen.flatMap { it.songs }.count { StreamOrigins.reference(it) != null }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        networkAccess = Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED
        val required = grants.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
        val action = afterPermission; afterPermission = null
        if (required.values.all { it }) action?.invoke() else error = context.getString(R.string.streaming_permission)
    }
    fun permitted(hosting: Boolean, bluetooth: Boolean = false, action: () -> Unit) {
        val missing = buildList {
            if (bluetooth) {
                if (Build.VERSION.SDK_INT >= 31) {
                    add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_ADVERTISE)
                } else add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
            if (hosting) add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
            if (hosting && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { networkAccess = true; action() } else { afterPermission = action; permissions.launch(missing.toTypedArray()) }
    }
    fun requestPair(invite: StreamingProtocol.Invite) {
        if (busy) return
        if (invite.server == manager.store.serverId) { error = context.getString(R.string.network_self_invite); return }
        val existing = connections.firstOrNull { it.server == invite.server }
        if (existing != null) {
            busy = true; error = null
            pairJob = scope.launch {
                try {
                    require(StreamingProtocol.equal(existing.pin, invite.pin))
                    val updated = existing.at(invite.endpoint)
                    StreamingClient.page(updated)
                    withContext(Dispatchers.IO) { manager.store.save(updated) }
                    if (existing.endpoint != updated.endpoint) BluetoothStreaming.forget(existing.endpoint)
                    selectedServer = existing.server
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = StreamingClient.friendlyError(e) }
                finally { busy = false }
            }
            return
        }
        if (connections.size >= StreamingProtocol.MAX_PEERS) { error = context.getString(R.string.nearby_device_limit); return }
        error = null; busy = true; pairingBinding = invite.binding
        pairJob = scope.launch {
            var saved = false
            try {
                val connection = StreamingClient.pair(invite, deviceName, StreamingProtocol.token()); pending = connection
                withTimeout(5 * 60_000L) { while (StreamingClient.status(connection) != "approved") delay(2000) }
                StreamingClient.page(connection)
                withContext(Dispatchers.IO) { manager.store.save(connection) }
                saved = true; selectedServer = null; inviteText = ""; showPairing = false
            } catch (e: TimeoutCancellationException) { error = context.getString(R.string.streaming_pair_timeout) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = StreamingClient.friendlyError(e) }
            finally { val abandoned = pending; busy = false; pending = null
                if (abandoned != null && !saved) withContext(NonCancellable + Dispatchers.IO) {
                    try { withTimeout(3000) { runCatching { StreamingClient.disconnect(abandoned) }; StreamingClient.cancelPairing(abandoned) } } catch (_: Exception) { }
                }
            }
        }
    }
    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode <= 0) { BluetoothStreaming.hideInvitation(); manager.access.cancelInvite(); bluetoothSharing = false }
    }
    val qrScanner = rememberLauncherForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
        result.contents?.let { text ->
            val invite = runCatching { StreamingProtocol.Invite.decode(text) }.getOrNull()
            if (invite == null) error = context.getString(R.string.streaming_invalid_invite)
            else permitted(false) { requestPair(invite) }
        }
    }
    LaunchedEffect(bluetoothSharing) { if (bluetoothSharing) { delay(120_000); BluetoothStreaming.hideInvitation(); bluetoothSharing = false } }
    LaunchedEffect(host.running) { if (!host.running) { invitation = null; addressDialog = false; nearby.stopAdvertising(); BluetoothStreaming.stopHosting(); bluetoothSharing = false }; while (host.running) { manager.refresh(); delay(3000) } }
    LaunchedEffect(invitation) { invitation?.let { delay((it.expires - System.currentTimeMillis()).coerceAtLeast(0)); invitation = null } }
    LaunchedEffect(connections) {
        if (selectedServer != null && selectedServer !in connections.map { it.server }) selectedServer = null
        if (openedPlaylist?.source?.server !in connections.map { it.server }) openedPlaylist = null
    }
    val activeConnections = if (hostTab || !networkAccess) emptyList() else connections.filter { selectedServer == null || it.server == selectedServer }
    val browser = rememberStreamNetworkBrowser(activeConnections)
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.streaming_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBackClick) { StreamIcon("arrow_back", stringResource(R.string.streaming_done)) } }) },
        bottomBar = {
            if (current?.uri?.host == StreamingProtocol.MEDIA_HOST) Surface(tonalElevation = 4.dp) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                            StreamIcon("music_note"); AsyncImage(current.artworkUri, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                        }
                        Column(Modifier.weight(1f).padding(12.dp)) { Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(current.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        IconButton(onClick = onToggle) { StreamIcon(if (playing) "pause" else "play_arrow", stringResource(R.string.streaming_play_pause)) }
                        IconButton(onClick = onNext) { StreamIcon("skip_next", stringResource(R.string.streaming_next)) }
                    }
                    Slider(value = (scrubPosition ?: progress).coerceIn(0f, 1f), onValueChange = { scrubPosition = it },
                        onValueChangeFinished = { scrubPosition?.let(onSeek); scrubPosition = null }, modifier = Modifier.fillMaxWidth().testTag("streaming-seek"))
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
                    Text(stringResource(R.string.streaming_hero_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
                            FilterChip(shareAll, { shareAll = true }, { Text(stringResource(R.string.streaming_all_music)) })
                            OutlinedButton({ choosePlaylist = true }, Modifier.fillMaxWidth()) {
                                Text(if (chosen.size == 1) chosen.single().name else if (chosen.isNotEmpty()) stringResource(R.string.network_selected_playlists, chosen.size) else stringResource(R.string.streaming_choose_playlist))
                            }
                            Text(stringResource(R.string.network_share_playlists_hint), style = MaterialTheme.typography.bodySmall)
                            if (sharedCount == 0) Text(stringResource(R.string.streaming_empty_local), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { permitted(true) { scope.launch {
                                try { withContext(Dispatchers.IO) { manager.store.select(if (shareAll) null else sharedSongs.map { it.uri.toString() }.toSet(), chosen.map { it.id }.toSet()) }; StreamingHostService.start(context) }
                                catch (_: Exception) { error = context.getString(R.string.streaming_start_error) }
                            } } }, enabled = sharedCount > 0, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.streaming_start)) }
                        } else if (host.running) {
                            Text(stringResource(R.string.streaming_scope_running), style = MaterialTheme.typography.bodySmall)
                            Button({ addressDialog = true }, Modifier.fillMaxWidth(), enabled = host.peers.size < StreamingProtocol.MAX_PEERS) { Text(stringResource(R.string.streaming_invite)) }
                            OutlinedButton({
                                if (advertisingNearby) { nearby.stopAdvertising(); manager.access.cancelInvite() }
                                else runCatching {
                                    BluetoothStreaming.hideInvitation(); bluetoothSharing = false
                                    nearby.advertise(manager.invite("https://127.0.0.1:${StreamingProtocol.PORT}", durationMillis = 120_000))
                                }.onFailure { error = context.getString(R.string.streaming_start_error) }
                            }, Modifier.fillMaxWidth()) { Text(stringResource(if (advertisingNearby) R.string.nearby_hide else R.string.nearby_show)) }
                            OutlinedButton({ permitted(false, bluetooth = true) {
                                if (bluetoothSharing) { BluetoothStreaming.hideInvitation(); manager.access.cancelInvite(); bluetoothSharing = false }
                                else runCatching {
                                    nearby.stopAdvertising()
                                    BluetoothStreaming.host(manager.invite("https://127.0.0.1:${StreamingProtocol.PORT}", durationMillis = 120_000))
                                    bluetoothSharing = true
                                    discoverable.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                        .putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
                                }.onFailure { error = context.getString(R.string.nearby_bluetooth_error); BluetoothStreaming.hideInvitation(); bluetoothSharing = false }
                            } }, Modifier.fillMaxWidth()) { Text(stringResource(if (bluetoothSharing) R.string.nearby_bluetooth_hide else R.string.nearby_bluetooth_show)) }
                            if (nearbyFailed) Text(stringResource(R.string.nearby_error))
                            Text(stringResource(R.string.nearby_visibility_hint), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton({ StreamingHostService.stop(context); invitation = null }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.streaming_stop)) }
                            Text(stringResource(R.string.streaming_session_data, android.text.format.Formatter.formatShortFileSize(context, host.bytes)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (host.pending.isNotEmpty()) item { Text(stringResource(R.string.streaming_pending), style = MaterialTheme.typography.titleMedium) }
                items(host.pending, key = { "pending:${it.peer.id}" }) { request ->
                    StreamCard {
                        Text(request.peer.name, style = MaterialTheme.typography.titleMedium)
                        StreamPairCode(request.code)
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton({ showPairing = !showPairing }, enabled = !busy && connections.size < StreamingProtocol.MAX_PEERS) { Text(stringResource(R.string.network_add_device)) }
                        OutlinedButton({ hostTab = true }) { Text(stringResource(R.string.network_share_mine)) }
                    }
                    Text(stringResource(R.string.network_reciprocal_hint), style = MaterialTheme.typography.bodySmall)
                }
                item {
                    StreamCard {
                        Text(stringResource(R.string.nearby_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.nearby_connect_hint), style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton({ qrScanner.launch(com.journeyapps.barcodescanner.ScanOptions()
                            .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                            .setPrompt(context.getString(R.string.streaming_qr_scan_hint)).setBeepEnabled(false).setOrientationLocked(false)) },
                            enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.streaming_qr_scan)) }
                        OutlinedButton({ permitted(false) { if (searchingNearby) nearby.stopDiscovery() else nearby.discover() } }, enabled = !busy) {
                            Text(stringResource(if (searchingNearby) R.string.nearby_search_stop else R.string.nearby_search))
                        }
                        if (searchingNearby) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (nearbyFailed) Text(stringResource(R.string.nearby_error))
                        nearbyDevices.forEach { candidate ->
                            FilledTonalButton({ permitted(false) { nearby.stopDiscovery(); requestPair(candidate) } }, enabled = !busy && deviceName.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(candidate.name) }
                        }
                        BluetoothDiscovery(onPermission = { action -> permitted(false, bluetooth = true, action = action) }, enabled = !busy) { address ->
                            pairJob = scope.launch {
                                busy = true; error = null
                                try { val candidate = BluetoothStreaming.invite(address); busy = false; requestPair(candidate) }
                                catch (e: CancellationException) { busy = false; throw e }
                                catch (_: Exception) { busy = false; error = context.getString(R.string.nearby_bluetooth_error) }
                            }
                        }
                    }
                }
                if (showPairing || busy) item {
                    StreamCard {
                        OutlinedTextField(inviteText, { if (it.length <= 8192) inviteText = it }, label = { Text(stringResource(R.string.streaming_invite_input)) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, enabled = !busy)
                        OutlinedTextField(deviceName, { deviceName = it.take(48) }, label = { Text(stringResource(R.string.streaming_device_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (pending != null) {
                            Text(stringResource(R.string.streaming_waiting), style = MaterialTheme.typography.titleMedium)
                            StreamPairCode(StreamingProtocol.pairCode(pending!!.token, pending!!.pin, pairingBinding))
                            Text(stringResource(R.string.streaming_verify_code, StreamingProtocol.pairCode(pending!!.token, pending!!.pin, pairingBinding)))
                            TextButton({ pairJob?.cancel() }) { Text(stringResource(R.string.streaming_cancel)) }
                        } else Button(onClick = { permitted(false) {
                            val invite = runCatching { StreamingProtocol.Invite.decode(inviteText) }.getOrNull()
                            if (invite == null) error = context.getString(R.string.streaming_invalid_invite)
                            else if (invite.server == manager.store.serverId) error = context.getString(R.string.network_self_invite)
                            else requestPair(invite)
                        } }, enabled = inviteText.isNotBlank() && deviceName.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.streaming_request)) }
                        if (busy && pending == null) TextButton({ pairJob?.cancel() }) { Text(stringResource(R.string.streaming_cancel)) }
                    }
                }
                item { Text(stringResource(R.string.streaming_servers), style = MaterialTheme.typography.titleLarge) }
                if (connections.isEmpty()) item { Text(stringResource(R.string.streaming_no_servers), style = MaterialTheme.typography.bodyMedium) }
                if (!networkAccess && connections.isNotEmpty()) item {
                    StreamCard {
                        Text(stringResource(R.string.streaming_permission))
                        Button({ permitted(false) { networkAccess = true } }) { Text(stringResource(R.string.streaming_allow_network)) }
                    }
                }
                if (connections.isNotEmpty()) item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selectedServer == null, { selectedServer = null }, { Text(stringResource(R.string.network_all_devices)) }) }
                        items(connections, key = { it.server }) { c -> FilterChip(selectedServer == c.server, { selectedServer = c.server }, { Text(c.name) }) }
                    }
                    TextButton({ manageDevices = true }) { Text(stringResource(R.string.network_manage_devices, connections.size)) }
                }
                if (networkAccess && connections.isNotEmpty()) streamNetworkBrowser(browser, playlistsTab, { playlistsTab = it }, onPlay,
                    onSave = { name, list -> saveSongs = StreamSaveRequest(name, list) }, onPlaylist = { openedPlaylist = it })
            }
            item { OutlinedButton({ outside = true }, Modifier.fillMaxWidth()) { StreamIcon("public"); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.streaming_outside_title)) } }
            item { Text(stringResource(R.string.nearby_tls_hint), style = MaterialTheme.typography.bodySmall) }
            item { Text(stringResource(R.string.streaming_privacy_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (choosePlaylist) {
        var selection by remember { mutableStateOf(selectedPlaylists) }
        AlertDialog(onDismissRequest = { choosePlaylist = false }, title = { Text(stringResource(R.string.streaming_choose_playlist)) }, text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item { Text(stringResource(R.string.network_share_playlist_limit), style = MaterialTheme.typography.bodySmall) }
                items(playlists.filter { it.songs.any { song -> song.uri.scheme in setOf("file", "content") || StreamOrigins.reference(song) != null } }, key = { it.id }) { playlist ->
                    Row(Modifier.fillMaxWidth().clickable { selection = if (playlist.id in selection) selection - playlist.id else if (selection.size < 100) selection + playlist.id else selection }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(playlist.id in selection, null)
                        Text(playlist.name, Modifier.weight(1f).padding(8.dp))
                    }
                }
            }
        }, confirmButton = { TextButton({ selectedPlaylists = selection; shareAll = false; choosePlaylist = false }) { Text(stringResource(R.string.network_apply)) } },
            dismissButton = { TextButton({ choosePlaylist = false }) { Text(stringResource(R.string.streaming_cancel)) } })
    }
    if (manageDevices) AlertDialog(onDismissRequest = { manageDevices = false }, title = { Text(stringResource(R.string.network_devices_title)) }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp)) { items(connections, key = { it.server }) { connection ->
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(connection.name, fontWeight = FontWeight.Bold)
                Text(connection.endpoint, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ editAddress = connection; manageDevices = false }) { Text(stringResource(R.string.streaming_change_address)) }
                    TextButton({ forget = connection; manageDevices = false }) { Text(stringResource(R.string.streaming_forget)) }
                }
            }
        } }
    }, confirmButton = { TextButton({ manageDevices = false }) { Text(stringResource(R.string.streaming_done)) } })
    openedPlaylist?.let { item -> NetworkPlaylistDialog(item, local = local, onDismiss = { openedPlaylist = null }, onPlay = onPlay,
        onSave = { name, list -> openedPlaylist = null; saveSongs = StreamSaveRequest(name, list) }) }
    saveSongs?.let { request -> StreamSaveDialog(request, playlists, onDismiss = { saveSongs = null }, onSave = { id, name ->
        if (id == null) onCreatePlaylist(name, request.songs) else onAddToPlaylist(id, request.songs)
        saveSongs = null
    }) }
    if (addressDialog) StreamAddressDialog(host.addresses.firstOrNull().orEmpty(), host.addresses, R.string.streaming_invite_address_hint, R.string.streaming_create_invite,
        onDismiss = { addressDialog = false }, onSave = { address ->
            nearby.stopAdvertising(); BluetoothStreaming.hideInvitation(); bluetoothSharing = false
            runCatching { manager.invite(address) }.onSuccess { invitation = it }.onFailure { error = context.getString(R.string.streaming_start_error) }; addressDialog = false
        })
    invitation?.let { invite -> AlertDialog(onDismissRequest = { invitation = null }, title = { Text(stringResource(R.string.streaming_invite_ready)) }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StreamingInviteQr(invite)
            Text(stringResource(R.string.streaming_qr_hint), style = MaterialTheme.typography.bodySmall)
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
        scope.launch {
            try { withContext(Dispatchers.IO) { manager.store.forget(connection.server); BluetoothStreaming.forget(connection.endpoint) }; onDisconnect(connection.server); forget = null
                runCatching { StreamingClient.disconnect(connection) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = context.getString(R.string.streaming_start_error); forget = null }
        }
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

@Composable private fun StreamCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable private fun StreamError(message: String) { Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) { Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer) } }
@Composable private fun StreamIcon(name: String, description: String? = null, size: Int = 24) { Icon(MaterialSymbolIcon(name), description, Modifier.size(size.dp)) }

@Composable private fun StreamPairCode(code: String) {
    Text(code, style = if (code.length > 6) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
}
