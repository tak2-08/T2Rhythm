// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.shared.data.model.Playlist
import chromahub.rhythm.app.shared.data.model.Song
import java.io.File
import java.security.KeyStore
import java.util.Locale
import java.util.UUID
import org.junit.*
import org.junit.Assert.*

class PeerNetworkFlowTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = PrivateStreaming.get(base)
    private val unique = UUID.randomUUID().toString()
    private val nodes = mutableListOf<Node>()
    private val files = mutableListOf<File>()
    private data class Node(val alias: String, val connection: StreamConnection, val server: StreamingServer, val catalog: StreamingCatalog)
    @Before @Suppress("DEPRECATION") fun setup() {
        if (android.os.Build.VERSION.SDK_INT >= 37) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(base.packageName, "android.permission.ACCESS_LOCAL_NETWORK")
        compose.runOnUiThread {
            Locale.setDefault(Locale.KOREAN)
            listOf(base.resources, compose.activity.resources).forEach { r -> r.updateConfiguration(Configuration(r.configuration).apply { setLocale(Locale.KOREAN); fontScale = 1f }, r.displayMetrics) }
        }
    }
    @After fun cleanup() {
        nodes.forEach { manager.store.forget(it.connection.server); it.server.stop() }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); nodes.forEach { if (containsAlias(it.alias)) deleteEntry(it.alias) } }
        files.forEach { it.delete() }
    }
    private fun song(title: String): Song {
        val file = File(base.cacheDir, "network-ui-$unique-${files.size}.m4a"); files += file
        InstrumentationRegistry.getInstrumentation().context.assets.open("audio-test.m4a").use { input -> file.outputStream().use { input.copyTo(it) } }
        return Song("network-ui-$unique-${files.size}", title, "함께 듣는 사람들", "우리의 시간", duration = 90_000, uri = Uri.fromFile(file), path = file.path, genre = "INDIE")
    }
    private fun node(name: String, song: Song, register: Boolean = true): Node {
        val alias = "network-ui-$unique-${nodes.size}"; val serverId = UUID.randomUUID().toString()
        val catalog = StreamingCatalog(base, serverId).apply { replace(listOf(song), null) }
        val identity = StreamingIdentity(alias); val access = StreamingAccess(); val token = StreamingProtocol.token()
        access.approve(access.pair(access.invite().first, token, "내 폰")!!.peer.id)
        val server = StreamingServer(0, identity.serverContext(), access, catalog, artwork = { null }).apply { start(10_000, true) }
        val connection = StreamConnection(serverId, name, "https://127.0.0.1:${server.listeningPort}", identity.pin, token)
        if (register) manager.store.save(connection)
        return Node(alias, connection, server, catalog).also { nodes += it }
    }
    private fun screen(songs: List<Song> = emptyList(), lists: List<Playlist> = emptyList(),
        play: (List<Song>, Int, Boolean) -> Unit = { _, _, _ -> }, save: (String, List<Song>) -> Unit = { _, _ -> }) {
        compose.setContent { CompositionLocalProvider(LocalContext provides compose.activity) {
            MaterialTheme { StreamingContent({}, songs, lists, play, onCreatePlaylist = save) }
        } }
    }
    private fun scroll(text: String) { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text)) }
    private fun connectTab() { compose.onNodeWithText("연결하기").performClick() }
    private fun screenshot(name: String) {
        compose.waitForIdle(); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); android.os.SystemClock.sleep(200)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(base.getExternalFilesDir(null), "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    @Test fun allDevicesBuildOneQueueWithEachSongsOriginalServer() {
        val a = node("거실폰", song("거실의 밤")); val b = node("서재폰", song("서재의 아침"))
        var played = emptyList<Song>(); var index = -1
        screen(play = { list, chosen, _ -> played = list; index = chosen }); connectTab()
        scroll("노래 · 아티스트 · 앨범 검색")
        compose.waitUntil(20_000) { compose.onAllNodesWithText("서재의 아침").fetchSemanticsNodes().isNotEmpty() }
        scroll("서재의 아침"); screenshot("streaming-all-devices")
        compose.onNodeWithText("서재의 아침").performClick()
        assertEquals(2, played.size); assertEquals(setOf(a.connection.server, b.connection.server), played.map { it.uri.pathSegments[0] }.toSet())
        assertEquals("서재의 아침", played[index].title)
    }
    @Test fun sharedMixedPlaylistCanBeReviewedAndSavedWithoutCopyingCredentials() {
        val own = song("거실의 밤"); val a = node("거실폰", own); val b = node("서재폰", song("서재의 아침"))
        val remote = b.catalog.snapshot.ordered.single().track.song(b.connection.server)
        a.catalog.replace(listOf(own, remote), null, listOf(SharePlaylist("mixed", "서로의 음악", listOf(remote.uri.toString(), own.uri.toString()))))
        var saved = emptyList<Song>(); var name = ""
        screen(save = { title, songs -> name = title; saved = songs }); connectTab()
        scroll("플레이리스트"); compose.onNodeWithText("플레이리스트").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("서로의 음악").fetchSemanticsNodes().isNotEmpty() }
        scroll("서로의 음악"); compose.onNodeWithText("서로의 음악").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("플레이리스트 재생") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        screenshot("streaming-shared-playlist")
        compose.onNodeWithText("보관함에 담기").performClick(); compose.onNodeWithText("담기", substring = false).performClick()
        assertEquals("서로의 음악", name); assertEquals(listOf(b.connection.server, a.connection.server), saved.map { it.uri.pathSegments[0] })
        saved.forEach { assertFalse(it.uri.toString().contains(a.connection.token)); assertFalse(it.uri.toString().contains(b.connection.token)) }
    }
    @Test fun missingOriginIsExplainedAndCanBeSavedWithoutGrantingPlayback() {
        val own = song("로컬 음악"); val a = node("공유한 폰", own); val b = node("원래 폰", song("연결 필요한 곡"), register = false)
        val remote = b.catalog.snapshot.ordered.single().track.song(b.connection.server)
        a.catalog.replace(listOf(own, remote), null, listOf(SharePlaylist("missing", "나중에 듣기", listOf(remote.uri.toString()))))
        var saved = emptyList<Song>()
        screen(save = { _, songs -> saved = songs }); connectTab()
        scroll("플레이리스트"); compose.onNodeWithText("플레이리스트").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("나중에 듣기").fetchSemanticsNodes().isNotEmpty() }
        scroll("나중에 듣기"); compose.onNodeWithText("나중에 듣기").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("1곡은 음악을", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("플레이리스트 재생").assertIsNotEnabled(); screenshot("streaming-origin-needed")
        compose.onNodeWithText("보관함에 담기").performClick(); compose.onNodeWithText("담기", substring = false).performClick()
        assertEquals(b.connection.server, saved.single().uri.pathSegments[0]); assertNull(manager.store.connection(b.connection.server))
    }
    @Test fun sharingSelectionAllowsSeveralPlaylistsAtOnce() {
        val a = song("첫 곡"); val b = song("두 번째 곡")
        screen(listOf(a, b), listOf(Playlist("first-$unique", "아침 목록", listOf(a)), Playlist("second-$unique", "저녁 목록", listOf(b))))
        scroll("플레이리스트 선택"); compose.onNodeWithText("플레이리스트 선택").performClick()
        compose.onNodeWithText("아침 목록").performClick(); compose.onNodeWithText("저녁 목록").performClick(); compose.onNodeWithText("적용").performClick()
        compose.onNodeWithText("플레이리스트 2개 선택됨").assertExists(); compose.onNodeWithText("음악 서버 시작").assertIsEnabled()
        screenshot("streaming-multiple-playlists")
    }
}
