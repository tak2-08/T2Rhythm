// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.shared.data.model.Song
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PrivateStreamingTransportTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val unique = UUID.randomUUID().toString()
    private val namespace = "stream-test-$unique"
    private val alias = "stream-test-$unique"
    private lateinit var store: StreamingStore
    private lateinit var access: StreamingAccess
    private lateinit var catalog: StreamingCatalog
    private lateinit var server: StreamingServer
    private lateinit var identity: StreamingIdentity
    private lateinit var audio: File
    private lateinit var song: Song
    private lateinit var token: String
    private lateinit var connection: StreamConnection
    private lateinit var peer: StreamPeer
    @Before fun setup() {
        if (android.os.Build.VERSION.SDK_INT >= 37) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(base.packageName, "android.permission.ACCESS_LOCAL_NETWORK")
        store = StreamingStore(base, namespace); access = StreamingAccess(store.peers()) { store.savePeers(it) }
        catalog = StreamingCatalog(base, store.serverId); identity = StreamingIdentity(alias)
        audio = File(base.cacheDir, "$namespace.m4a")
        InstrumentationRegistry.getInstrumentation().context.assets.open("audio-test.m4a").use { input -> audio.outputStream().use { input.copyTo(it) } }
        song = Song("test-$unique", "밤의 정원", "노을", "우리의 계절", duration = 90_000, uri = Uri.fromFile(audio), genre = "INDIE, POP", year = 2026, path = audio.path)
        catalog.replace(listOf(song), null)
        server = StreamingServer(0, identity.serverContext(), access, catalog, artwork = { byteArrayOf(1, 2, 3) }); server.start(10_000, true)
        token = StreamingProtocol.token()
        peer = access.pair(access.invite().first, token, "메인폰")!!.peer; access.approve(peer.id)
        connection = StreamConnection(store.serverId, "서브폰", "https://127.0.0.1:${server.listeningPort}", identity.pin, token)
    }
    @After fun cleanup() {
        if (::server.isInitialized) server.stop()
        if (::audio.isInitialized) audio.delete()
        File(base.noBackupFilesDir, namespace).deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
    }
    private fun request(path: String) = StreamingClient.request(connection, path)
    private fun response(path: String, range: String? = null, head: Boolean = false) = StreamingClient.http(connection).newCall(request(path).apply {
        if (range != null) header("Range", range); if (head) head()
    }.build()).execute()
    private val id get() = catalog.snapshot.entries.keys.single()

    @Test fun pinnedTlsRejectsAnonymousClientsAndWrongServerIdentity() = runBlocking {
        StreamingClient.http(connection).newCall(Request.Builder().url(connection.endpoint + "/v1/library").build()).execute().use {
            assertEquals(404, it.code); assertFalse(it.body!!.string().contains(song.title)); assertEquals("private, no-store", it.header("Cache-Control"))
        }
        val wrong = StreamConnection(connection.server, connection.name, connection.endpoint, "0".repeat(64), token)
        try { StreamingClient.page(wrong); fail("An unpinned certificate must never connect") } catch (_: javax.net.ssl.SSLException) { }
        val page = StreamingClient.page(connection); assertEquals(song.title, page.tracks.single().title)
        assertFalse(page.tracks.single().json().toString().contains(audio.path)); Unit
    }
    @Test fun invitationRequiresHostApprovalAndCannotBeReplayed() = runBlocking {
        val (secret, expires) = access.invite()
        val invite = StreamingProtocol.Invite(store.serverId, "서브폰", connection.endpoint, identity.pin, secret, expires)
        val newToken = StreamingProtocol.token()
        val waiting = StreamingClient.pair(invite, "친구의 폰", newToken)
        assertEquals("pending", StreamingClient.status(waiting)); assertNull(access.authorized(newToken))
        response("/v1/audio/$id").use { assertEquals(200, it.code) }
        StreamingClient.http(waiting).newCall(StreamingClient.request(waiting, "/v1/library").build()).execute().use { assertEquals(404, it.code) }
        try { StreamingClient.pair(invite, "Another phone", StreamingProtocol.token()); fail("Invitation replay") } catch (_: IOException) { }
        val pending = access.requests().single(); assertEquals(StreamingProtocol.code(newToken), pending.code)
        access.approve(pending.peer.id); assertEquals("approved", StreamingClient.status(waiting))
        assertEquals(1, StreamingClient.page(waiting).tracks.size); Unit
    }
    @Test fun rangesAndHeadMatchOriginalBytesAndRejectInvalidRanges() {
        val bytes = audio.readBytes()
        response("/v1/audio/$id", "bytes=17-119").use {
            assertEquals(206, it.code); assertEquals("bytes 17-119/${bytes.size}", it.header("Content-Range"))
            assertArrayEquals(bytes.copyOfRange(17, 120), it.body!!.bytes())
        }
        response("/v1/audio/$id", "bytes=-53").use { assertArrayEquals(bytes.takeLast(53).toByteArray(), it.body!!.bytes()) }
        response("/v1/audio/$id", head = true).use { assertEquals(200, it.code); assertEquals(bytes.size.toString(), it.header("Content-Length")); assertEquals(0, it.body!!.bytes().size) }
        listOf("bytes=${bytes.size}-", "bytes=0-2,4-6", "bytes=-0").forEach { range -> response("/v1/audio/$id", range).use { assertEquals(416, it.code); assertEquals("bytes */${bytes.size}", it.header("Content-Range")) } }
    }
    @Test fun filePathsAndUploadsAreNeverEndpoints() {
        listOf("/", "/robots.txt", "/v1/audio/${audio.path}", "/v1/audio/" + "f".repeat(64), "/v1/../host.json").forEach { path -> response(path).use { assertEquals(404, it.code) } }
        StreamingClient.http(connection).newCall(request("/v1/audio/$id").post("not an upload".toRequestBody()).build()).execute().use { assertEquals(405, it.code) }
    }
    @Test fun catalogChangesInvalidateProjectionAndKeepStableIdentityAndSelection() = runBlocking {
        val initial = StreamingClient.page(connection); val track = initial.tracks.single()
        val other = song.copy(id = "other", uri = Uri.parse("file:///not-shared.m4a"), title = "비공개 음악")
        catalog.replace(listOf(song.copy(title = "바꾼 제목", album = "새 앨범", artist = "새 가수", genre = "ROCK", year = 2024), other), setOf(song.uri.toString()))
        val updated = StreamingClient.page(connection)
        assertNotEquals(initial.revision, updated.revision); assertEquals(track.id, updated.tracks.single().id)
        assertEquals("새 앨범", updated.tracks.single().album); assertEquals("새 가수", updated.tracks.single().artist)
        assertEquals(2024, updated.tracks.single().year); assertEquals(listOf("ROCK"), updated.genres)
        assertEquals(updated.revision, StreamingClient.revision(connection))
        assertEquals(0, StreamingClient.page(connection, "비공개").matched)
        assertEquals(1, StreamingClient.page(connection, "바꾼").matched)
        assertEquals(0, StreamingClient.page(connection, genre = "POP").matched); Unit
    }
    @Test fun savedCredentialsAreEncryptedAndHostApprovalSurvivesRestart() {
        store.save(connection)
        val bytes = File(base.noBackupFilesDir, "$namespace/connections.enc").readBytes().decodeToString()
        assertFalse(bytes.contains(token)); assertFalse(bytes.contains(connection.endpoint))
        val restored = StreamingStore(base, namespace)
        assertEquals(token, restored.connection(store.serverId)!!.token)
        assertEquals(peer.id, StreamingAccess(restored.peers()).authorized(token)!!.id)
        val persisted = File(base.noBackupFilesDir, "$namespace/host.json").readText()
        assertFalse(persisted.contains(token)); assertTrue(persisted.contains(StreamingProtocol.hash(token)))
    }
    @Test fun playerRoutesThroughPinnedAuthenticationAndNeverTheGeneralAudioCache() {
        store.save(connection)
        val fallback = DataSource.Factory { error("Private audio must bypass the ordinary cache/data source") }
        val source = PrivateStreamDataSource(store, fallback)
        val uri = catalog.snapshot.entries.values.single().track.song(store.serverId).uri
        assertFalse(uri.toString().contains(token)); assertFalse(uri.toString().contains("127.0.0.1"))
        source.open(DataSpec.Builder().setUri(uri).setPosition(23).setLength(70).build())
        try {
            val bytes = ByteArray(70); var at = 0
            while (at < bytes.size) { val n = source.read(bytes, at, bytes.size - at); assertTrue(n > 0); at += n }
            assertArrayEquals(audio.readBytes().copyOfRange(23, 93), bytes); assertEquals(uri, source.uri)
            store.forget(store.serverId)
            assertThrows(IOException::class.java) { source.read(ByteArray(1), 0, 1) }
        } finally { source.close() }
    }
    @Test fun revokingApprovalCutsAnOngoingTransferAndSubsequentRequests() {
        val large = File(base.cacheDir, "$namespace-large.m4a")
        java.io.RandomAccessFile(large, "rw").use { it.setLength(64L * 1024 * 1024) }
        try {
            catalog.replace(listOf(song.copy(uri = Uri.fromFile(large))), null)
            response("/v1/audio/$id").use { response ->
                assertEquals(200, response.code)
                val input = response.body!!.byteStream(); val buffer = ByteArray(8192)
                var received = input.read(buffer).toLong(); assertTrue(received > 0)
                access.revoke(peer.id)
                try { while (true) { val n = input.read(buffer); if (n < 0) break; received += n } } catch (_: IOException) { }
                assertTrue("Already-buffered bytes may finish, but the complete file must not be sent", received < large.length())
            }
            response("/v1/library").use { assertEquals(404, it.code) }
        } finally { large.delete() }
    }
    @Test fun unsharingATrackStopsAccessWithoutRevokingTheDevice() {
        val old = id; catalog.replace(listOf(song), emptySet())
        assertNotNull(access.authorized(token))
        response("/v1/audio/$old").use { assertEquals(404, it.code) }
    }
    @Test fun hostAcceptsItsLanAddressWithTheSamePinnedIdentity() = runBlocking {
        val address = PrivateStreaming.addresses().first { !it.contains('[') }
        val host = java.net.URI(address).host
        val lan = connection.at("https://$host:${server.listeningPort}")
        assertEquals(song.title, StreamingClient.page(lan).tracks.single().title); Unit
    }
    @Test fun paginationAndGenreQueriesDoNotMixRevisions() = runBlocking {
        val many = (0..146).map { i -> song.copy(id = "song-$i", title = "Song ${i.toString().padStart(3, '0')}", uri = Uri.parse("file:///page-$i.m4a"), genre = if (i % 2 == 0) "POP" else "ROCK") }
        catalog.replace(many, null)
        val first = StreamingClient.page(connection); val next = StreamingClient.page(connection, offset = first.tracks.size)
        assertEquals(147, first.total); assertEquals(100, first.tracks.size); assertEquals(47, next.tracks.size)
        assertEquals(first.revision, next.revision); assertEquals(147, (first.tracks + next.tracks).map { it.id }.toSet().size)
        assertEquals(74, StreamingClient.page(connection, genre = "POP").matched)
        assertEquals(1, StreamingClient.page(connection, query = "song 146").matched); Unit
    }
    @Test fun media3ActuallyPreparesAndSeeksThroughThePrivateTransport() {
        store.save(connection)
        val ready = java.util.concurrent.CountDownLatch(1)
        val error = java.util.concurrent.atomic.AtomicReference<androidx.media3.common.PlaybackException?>()
        lateinit var player: androidx.media3.exoplayer.ExoPlayer
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val fallback = DataSource.Factory { error("Private playback cannot use the public cache") }
            val factory = DataSource.Factory { PrivateStreamDataSource(store, fallback) }
            player = androidx.media3.exoplayer.ExoPlayer.Builder(base)
                .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(base).setDataSourceFactory(factory)).build()
            player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == androidx.media3.common.Player.STATE_READY) ready.countDown() }
                override fun onPlayerError(e: androidx.media3.common.PlaybackException) { error.set(e); ready.countDown() }
            })
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(catalog.snapshot.entries.values.single().track.song(store.serverId).uri))
            player.prepare()
        }
        try {
            assertTrue("Media3 preparation timed out", ready.await(20, java.util.concurrent.TimeUnit.SECONDS)); assertNull(error.get())
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertEquals(androidx.media3.common.Player.STATE_READY, player.playbackState)
                assertTrue(player.duration > 0); player.seekTo(minOf(500, player.duration / 2)); player.play()
            }
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { player.release() } }
    }
    @Test fun listeningReportsCanReplayPrivateHistoryOnlyWhileItsServerIsConnected() {
        val remote = catalog.snapshot.entries.values.single().track.song(store.serverId)
        val track = chromahub.rhythm.app.features.local.data.experience.ReportTrack(remote.uri.toString(), remote.id, remote.title, remote.artist, remote.album, 60_000, 1, remote.genre)
        val connected = chromahub.rhythm.app.features.local.data.experience.ReportLibraryProjection(emptyList(), setOf(store.serverId))
        assertEquals(remote.uri, connected.resolve(track)!!.uri)
        assertEquals(remote.genre, connected.resolve(track)!!.genre)
        val lookalike = song.copy(title = remote.title, artist = remote.artist, album = remote.album)
        val disconnected = chromahub.rhythm.app.features.local.data.experience.ReportLibraryProjection(listOf(lookalike))
        assertNull(disconnected.resolve(track))
        assertNull(chromahub.rhythm.app.features.local.data.experience.ReportLibraryProjection(listOf(remote)).resolve(track))
        assertNull(connected.resolve(track.copy(songId = "wrong")))
    }
}
