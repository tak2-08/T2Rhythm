// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.features.local.data.database.RhythmDatabase
import chromahub.rhythm.app.features.local.data.database.entity.*
import chromahub.rhythm.app.shared.data.model.Song
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PeerNetworkRegressionTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val unique = UUID.randomUUID().toString()
    private val nodes = mutableListOf<Node>()
    private lateinit var audio: File
    private lateinit var original: Song
    private data class Node(val namespace: String, val store: StreamingStore, val catalog: StreamingCatalog, val access: StreamingAccess,
        val server: StreamingServer, val identity: StreamingIdentity, val connection: StreamConnection, val peer: StreamPeer)
    @Before fun setup() {
        if (android.os.Build.VERSION.SDK_INT >= 37) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(base.packageName, "android.permission.ACCESS_LOCAL_NETWORK")
        audio = File(base.cacheDir, "network-$unique.m4a")
        InstrumentationRegistry.getInstrumentation().context.assets.open("audio-test.m4a").use { input -> audio.outputStream().use { input.copyTo(it) } }
        original = Song("network-$unique", "함께 듣는 밤", "밤의 정원", "첫 계절", duration = 90_000, uri = Uri.fromFile(audio), path = audio.path, genre = "INDIE")
    }
    private fun node(name: String): Node {
        val namespace = "network-$unique-${nodes.size}"; val store = StreamingStore(base, namespace)
        val catalog = StreamingCatalog(base, store.serverId).apply { replace(listOf(original.copy(title = name)), null) }
        val identity = StreamingIdentity(namespace); val access = StreamingAccess()
        val server = StreamingServer(0, identity.serverContext(), access, catalog, artwork = { null }, identityPin = identity.pin).apply { start(10_000, true) }
        val token = StreamingProtocol.token(); val peer = access.pair(access.invite().first, token, "듣는 폰")!!.peer; access.approve(peer.id)
        return Node(namespace, store, catalog, access, server, identity,
            StreamConnection(store.serverId, name, "https://127.0.0.1:${server.listeningPort}", identity.pin, token), peer).also { nodes += it }
    }
    @After fun cleanup() {
        nodes.forEach { it.server.stop(); File(base.noBackupFilesDir, it.namespace).deleteRecursively() }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); nodes.forEach { if (containsAlias(it.namespace)) deleteEntry(it.namespace) } }
        if (::audio.isInitialized) audio.delete()
    }
    private suspend fun awaitBrowser(state: StreamNetworkBrowserState, count: Int) {
        withTimeout(25_000) { while (withContext(Dispatchers.Main) { state.sources.size != count || state.busy }) delay(50) }
    }
    @Test fun searchAcrossPeersRetainsOriginsAndOneOfflineDeviceDoesNotBlockTheOthers() = runBlocking {
        val a = node("거실의 노래"); val b = node("서재의 노래")
        val offline = StreamConnection(UUID.randomUUID().toString(), "꺼진 폰", "https://127.0.0.1:1", "0".repeat(64), StreamingProtocol.token())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val state = withContext(Dispatchers.Main) { StreamNetworkBrowserState(listOf(a.connection, b.connection, offline), scope).also { it.reload() } }
        try {
            awaitBrowser(state, 3)
            withContext(Dispatchers.Main) {
                assertEquals(setOf(a.store.serverId, b.store.serverId), state.tracks.map { it.song().uri.pathSegments[0] }.toSet())
                assertNotNull(state.sources[offline.server]!!.error)
                state.query = "서재"; state.reload()
            }
            awaitBrowser(state, 3)
            withContext(Dispatchers.Main) { assertEquals("서재의 노래", state.tracks.single().song().title); state.query = ""; state.reload() }
            awaitBrowser(state, 3)
            a.access.revoke(a.peer.id)
            withContext(Dispatchers.Main) { state.reload() }; awaitBrowser(state, 3)
            withContext(Dispatchers.Main) { assertEquals(b.store.serverId, state.tracks.single().source.server); assertNotNull(state.sources[a.store.serverId]!!.error) }
        } finally { withContext(Dispatchers.Main) { state.cancel() }; scope.cancel() }
    }
    @Test fun sharedPlaylistCarriesOrderedOriginReferencesWithoutRehostingOrExposingCredentials() = runBlocking {
        val a = node("음악 A"); val b = node("음악 B")
        val remote = b.catalog.snapshot.ordered.single().track.song(b.store.serverId)
        val hidden = original.copy(id = "hidden-$unique", title = "공유 안 한 음악", uri = Uri.parse("file:///hidden-$unique.m4a"))
        val publicSong = original.copy(uri = Uri.parse("https://example.com/not-a-peer.mp3"))
        a.catalog.replace(listOf(original, hidden, remote, publicSong), setOf(original.uri.toString()),
            listOf(SharePlaylist("selected", "우리의 플레이리스트", listOf(remote.uri.toString(), original.uri.toString(), hidden.uri.toString(), publicSong.uri.toString()))))
        val list = StreamingClient.playlists(a.connection).single(); assertEquals(2, list.count)
        val songs = StreamingClient.playlistSongs(a.connection, list.id)
        assertEquals(listOf(b.store.serverId, a.store.serverId), songs.map { it.uri.pathSegments[0] })
        assertEquals(1, StreamingClient.page(a.connection).matched)
        val payload = a.catalog.page("", "", 0, 100, list.id).toString()
        listOf(audio.path, a.connection.token, b.connection.token, a.connection.endpoint, b.connection.endpoint, hidden.title).forEach { assertFalse(payload.contains(it)) }
        val remoteId = remote.uri.lastPathSegment!!
        StreamingClient.http(a.connection).newCall(StreamingClient.request(a.connection, "/v1/audio/$remoteId").build()).execute().use { assertEquals(404, it.code) }
        StreamingClient.http(a.connection).newCall(okhttp3.Request.Builder().url(a.connection.endpoint + "/v1/playlists").build()).execute().use { assertEquals(404, it.code) }
        val resolve = StreamOrigins.resolver(listOf(original), a.store.serverId, listOf(b.connection))
        assertEquals(original.uri, resolve(songs[1])!!.uri); assertEquals(remote.uri, resolve(songs[0])!!.uri)
        assertNull(StreamOrigins.resolver(emptyList(), a.store.serverId, emptyList())(songs[0]))
        b.access.revoke(b.peer.id); a.store.save(a.connection); a.store.save(b.connection)
        val source = PrivateStreamDataSource(a.store, DataSource.Factory { error("Never route a private origin through the public transport") })
        try { source.open(DataSpec(songs[0].uri)); fail("Sharing a playlist must not transfer the original peer's approval") }
        catch (_: java.io.IOException) { } finally { source.close() }
        assertEquals(1, StreamingClient.page(a.connection).tracks.size)
    }
    @Test fun playlistRenameReorderAndRemovalChangeRevisionAndRetireTheOldListing() = runBlocking {
        val a = node("음악 A"); val other = original.copy(id = "other-$unique", uri = Uri.parse("file:///other-$unique.m4a"), title = "다음 곡")
        fun project(name: String, order: List<Song>) = a.catalog.replace(listOf(original, other), null, listOf(SharePlaylist("stable", name, order.map { it.uri.toString() })))
        project("첫 이름", listOf(original, other)); val before = StreamingClient.page(a.connection); val list = StreamingClient.playlists(a.connection).single()
        project("바꾼 이름", listOf(other, original)); val after = StreamingClient.playlists(a.connection).single()
        assertEquals(list.id, after.id); assertEquals("바꾼 이름", after.name); assertNotEquals(before.revision, StreamingClient.revision(a.connection))
        assertEquals(listOf(other.title, original.title), StreamingClient.playlistSongs(a.connection, after.id).map { it.title })
        a.catalog.replace(listOf(original, other), null)
        assertTrue(StreamingClient.playlists(a.connection).isEmpty())
        try { StreamingClient.playlistSongs(a.connection, after.id); fail("Unshared playlist must disappear") } catch (_: java.io.IOException) { }
    }
    @Test fun playlistImportRejectsARevisionChangeBetweenPages() = runBlocking {
        val a = node("음악 A")
        val songs = (0 until 205).map { original.copy(id = "song-$it-$unique", uri = original.uri.buildUpon().fragment(it.toString()).build(), title = "노래 $it") }
        val uris = songs.map { it.uri.toString() }
        a.catalog.replace(songs, null, listOf(SharePlaylist("stable", "목록", uris)))
        val id = StreamingClient.playlists(a.connection).single().id
        val changing = object : NanoHTTPD(0) {
            init { makeSecure(a.identity.serverContext().serverSocketFactory, null) }
            override fun serve(session: IHTTPSession): Response {
                val offset = session.parameters["offset"]?.firstOrNull()?.toIntOrNull() ?: 0
                if (offset > 0) a.catalog.replace(songs, null, listOf(SharePlaylist("stable", "수정된 목록", uris.reversed())))
                return newFixedLengthResponse(Response.Status.OK, "application/json", a.catalog.page("", "", offset, 100, id).toString())
            }
        }.apply { start(10_000, true) }
        try {
            val connection = a.connection.at("https://127.0.0.1:${changing.listeningPort}")
            try { StreamingClient.playlistSongs(connection, id); fail("Different playlist revisions must not be merged") } catch (_: StreamLibraryChangedException) { }
        } finally { changing.stop() }
    }
    @Test fun roomPlaylistChangesRefreshTheRunningHostWithoutOpeningItsScreen() = runBlocking {
        val manager = PrivateStreaming.get(base); val db = RhythmDatabase.getInstance(base)
        val oldSelection = manager.store.selection(); val oldLists = manager.store.sharedPlaylists()
        val id = "shared-$unique"; val second = original.copy(id = "second-$unique", uri = original.uri.buildUpon().fragment("second").build(), title = "추가한 곡")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); var peer: String? = null
        try {
            db.withTransaction {
                db.songDao().insertMissing(listOf(original.toEntity(), second.toEntity()))
                db.playlistDao().insertPlaylist(PlaylistEntity(id, "공유 목록", 1, 1, null))
                db.playlistDao().insertPlaylistSongs(listOf(PlaylistSongEntity(id, original.id, 0)))
            }
            manager.store.select(setOf(original.uri.toString()), setOf(id)); manager.start(scope); assertTrue(manager.state.value.running)
            val connection = StreamingClient.pair(manager.invite("https://127.0.0.1:8898"), "확인용 폰", StreamingProtocol.token())
            peer = manager.access.requests().single().peer.id; manager.approve(requireNotNull(peer))
            val before = StreamingClient.page(connection).revision
            db.withTransaction {
                db.playlistDao().insertPlaylist(PlaylistEntity(id, "바꾼 공유 목록", 1, 2, null))
                db.playlistDao().updatePlaylistSongs(id, listOf(PlaylistSongEntity(id, second.id, 0), PlaylistSongEntity(id, original.id, 1)))
            }
            withTimeout(15_000) { while (StreamingClient.revision(connection) == before) delay(100) }
            val list = StreamingClient.playlists(connection).single(); assertEquals("바꾼 공유 목록", list.name)
            assertEquals(listOf(second.title, original.title), StreamingClient.playlistSongs(connection, list.id).map { it.title })
            assertEquals(2, StreamingClient.page(connection).matched)
        } finally {
            manager.stop(); scope.cancel(); peer?.let(manager::revoke); manager.store.select(oldSelection, oldLists)
            db.withTransaction { db.playlistDao().deleteSongsFromPlaylist(id); db.playlistDao().deletePlaylistById(id); db.songDao().deleteByIds(listOf(original.id, second.id)) }
        }
    }
    @Test fun media3QueueCanSwitchDirectlyBetweenTwoPinnedOrigins() {
        val a = node("첫 기기"); val b = node("두 번째 기기"); a.store.save(a.connection); a.store.save(b.connection)
        val originalSongs = listOf(a, b).map { it.catalog.snapshot.ordered.single().track.song(it.store.serverId) }
        val playlistOwner = node("플레이리스트를 공유한 세 번째 기기")
        playlistOwner.catalog.replace(originalSongs, emptySet(), listOf(SharePlaylist("mixed", "세 기기의 음악", originalSongs.map { it.uri.toString() })))
        val songs = runBlocking { StreamingClient.playlistSongs(playlistOwner.connection, StreamingClient.playlists(playlistOwner.connection).single().id) }
        playlistOwner.server.stop() // Playback must contact the two origins directly, without a relay.
        val ready = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<androidx.media3.common.PlaybackException?>()
        lateinit var player: androidx.media3.exoplayer.ExoPlayer
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val factory = DataSource.Factory { PrivateStreamDataSource(a.store, DataSource.Factory { error("Private queue cannot use public transport") }) }
            player = androidx.media3.exoplayer.ExoPlayer.Builder(base).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(base).setDataSourceFactory(factory)).build()
            player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == androidx.media3.common.Player.STATE_READY) ready.countDown() }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failure.set(error); ready.countDown() }
            })
            player.setMediaItems(songs.map { androidx.media3.common.MediaItem.fromUri(it.uri) }); player.prepare()
        }
        try {
            assertTrue(ready.await(20, java.util.concurrent.TimeUnit.SECONDS)); assertNull(failure.get())
            instrumentation.runOnMainSync { player.seekToNextMediaItem(); player.play() }
            var secondReady = false; val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
            while (!secondReady && failure.get() == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { secondReady = player.currentMediaItemIndex == 1 && player.playbackState == androidx.media3.common.Player.STATE_READY }
                if (!secondReady) android.os.SystemClock.sleep(50)
            }
            assertNull(failure.get()); assertTrue(secondReady); assertTrue(a.server.bytesSent.get() > 0); assertTrue(b.server.bytesSent.get() > 0)
        } finally { instrumentation.runOnMainSync { player.release() } }
    }
}
