// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.room.withTransaction
import chromahub.rhythm.app.features.local.data.database.RhythmDatabase
import chromahub.rhythm.app.features.local.data.database.entity.toSong
import coil.imageLoader
import coil.request.ImageRequest
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class StreamHostState(val running: Boolean = false, val starting: Boolean = false, val addresses: List<String> = emptyList(),
    val tracks: Int = 0, val peers: List<StreamPeer> = emptyList(), val pending: List<PairRequest> = emptyList(), val bytes: Long = 0, val error: String? = null)

class PrivateStreaming private constructor(private val context: Context) {
    val store = StreamingStore(context)
    val access = StreamingAccess(store.peers()) { store.savePeers(it) }
    val catalog = StreamingCatalog(context, store.serverId)
    private val _state = MutableStateFlow(StreamHostState(peers = access.peers()))
    val state = _state.asStateFlow()
    @Volatile private var server: StreamingServer? = null
    @Volatile private var identity: StreamingIdentity? = null
    private var updates: Job? = null
    private val lifecycle = Any()
    private var generation = 0
    private val thumbs = object : LinkedHashMap<String, ByteArray>(24, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 24
    }
    suspend fun start(scope: CoroutineScope) = withContext(Dispatchers.IO) {
        if (server != null) return@withContext
        val attempt = synchronized(lifecycle) { ++generation }
        _state.update { it.copy(starting = true, error = null) }
        var candidate: StreamingServer? = null
        try {
            val tls = StreamingIdentity(); identity = tls
            val database = RhythmDatabase.getInstance(context)
            rebuildCatalog(database)
            currentCoroutineContext().ensureActive()
            candidate = StreamingServer(StreamingProtocol.PORT, tls.serverContext(), access, catalog, ::refresh, ::artwork)
            candidate.start(10_000, true)
            currentCoroutineContext().ensureActive()
            synchronized(lifecycle) {
                if (attempt != generation) throw CancellationException("Hosting was stopped")
                server = candidate
                _state.update { it.copy(running = true, starting = false, tracks = catalog.snapshot.sharedCount, addresses = addresses(), error = null) }
            }
            updates = scope.launch(Dispatchers.IO) {
                merge(database.invalidationTracker.createFlow("songs", "playlists", "playlist_songs").map { Unit },
                    chromahub.rhythm.app.util.ArtworkInvalidation.changes.map { Unit }).debounce(300).collect {
                    val before = catalog.snapshot.revision
                    rebuildCatalog(database)
                    if (before != catalog.snapshot.revision) synchronized(thumbs) { thumbs.clear() }
                    refresh()
                }
            }
        } catch (e: CancellationException) { candidate?.stop(); throw e }
        catch (_: Exception) {
            candidate?.stop(); _state.update { it.copy(starting = false, running = false, error = "서버를 시작하지 못했습니다. 음악 접근 권한과 네트워크를 확인해 주세요. 다른 앱이 8898 포트를 쓰고 있는지도 확인해 주세요.") }
        }
    }
    private suspend fun rebuildCatalog(database: RhythmDatabase) {
        val (songs, rows) = database.withTransaction {
            database.songDao().getAllSongs().map { it.toSong() } to database.playlistDao().getSharedPlaylistRows(store.sharedPlaylists().toList())
        }
        val playlists = rows.groupBy { it.playlistId }.map { (id, items) -> SharePlaylist(id, items.first().name, items.map { it.uri }) }
        val selection = store.selection()?.let { fixed -> if (store.sharedPlaylists().isEmpty()) fixed else rows.map { it.uri }.toSet() }
        catalog.replace(songs, selection, playlists)
    }
    fun stop() {
        updates?.cancel(); updates = null
        val previous = synchronized(lifecycle) { generation++; server.also { server = null }
            .also { _state.update { state -> state.copy(running = false, starting = false, pending = emptyList(), addresses = emptyList()) } } }
        previous?.stop()
        access.closePairing(); synchronized(thumbs) { thumbs.clear() }
    }
    fun fail(message: String) { _state.update { it.copy(error = message, starting = false) } }
    fun refresh() { _state.update { it.copy(peers = access.peers(), pending = access.requests(), tracks = catalog.snapshot.sharedCount,
        bytes = server?.bytesSent?.get() ?: it.bytes, addresses = if (it.running) addresses() else emptyList()) } }
    fun invite(endpoint: String): StreamingProtocol.Invite {
        check(_state.value.running)
        val (secret, expires) = access.invite()
        return StreamingProtocol.Invite(store.serverId, StreamingProtocol.name(android.os.Build.MODEL), StreamingProtocol.endpoint(endpoint), requireNotNull(identity).pin, secret, expires)
    }
    fun approve(id: String) { access.approve(id); refresh() }
    fun revoke(id: String) { access.revoke(id); refresh() }
    private fun artwork(id: String): ByteArray? {
        val entry = catalog.snapshot.entries[id] ?: return null
        if (entry.track.art.isEmpty()) return null
        val key = "$id:${entry.track.art}"
        synchronized(thumbs) { thumbs[key] }?.let { return it }
        val uri = entry.source.artworkUri ?: return null
        val result = runBlocking(Dispatchers.IO) { context.imageLoader.execute(ImageRequest.Builder(context).data(uri).size(320).allowHardware(false).build()) }
        val image = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.WEBP, 80, it) }.toByteArray()
        if (bytes.size > 512 * 1024) return null
        synchronized(thumbs) { thumbs[key] = bytes }; return bytes
    }
    companion object {
        @Volatile private var instance: PrivateStreaming? = null
        fun get(context: Context): PrivateStreaming = instance ?: synchronized(this) { instance ?: PrivateStreaming(context.applicationContext).also { instance = it } }
        fun addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isMulticastAddress }
                .mapNotNull(InetAddress::getHostAddress).filterNot { '%' in it }.distinct().sortedBy { ':' in it }
                .map { "https://${if (':' in it) "[$it]" else it}:${StreamingProtocol.PORT}" }.take(12)
        }.getOrDefault(emptyList())
    }
}
