// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import android.Manifest
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.features.local.data.database.RhythmDatabase
import chromahub.rhythm.app.features.local.data.database.entity.toEntity
import chromahub.rhythm.app.shared.data.model.Playlist
import chromahub.rhythm.app.shared.data.model.Song
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class PrivateStreamingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = PrivateStreaming.get(base)
    private val unique = UUID.randomUUID().toString()
    private val files = mutableListOf<File>()
    private var testPeer: String? = null
    private var testServer: String? = null
    private var transport: StreamingServer? = null
    private var inserted: String? = null
    @Before @Suppress("DEPRECATION") fun setup() {
        listOfNotNull(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null,
            if (Build.VERSION.SDK_INT >= 37) "android.permission.ACCESS_LOCAL_NETWORK" else null).forEach {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(base.packageName, it)
        }
        compose.runOnUiThread {
            Locale.setDefault(Locale.KOREAN)
            listOf(base.resources, compose.activity.resources).forEach { r -> r.updateConfiguration(Configuration(r.configuration).apply { setLocale(Locale.KOREAN); fontScale = 1f }, r.displayMetrics) }
        }
    }
    @After @Suppress("DEPRECATION") fun cleanup() {
        StreamingHostService.stop(base); manager.stop(); transport?.stop()
        testPeer?.let { manager.revoke(it) }; testServer?.let { manager.store.forget(it) }
        inserted?.let { runBlocking { RhythmDatabase.getInstance(base).songDao().deleteByIds(listOf(it)) } }
        files.forEach(File::delete)
        compose.runOnUiThread { listOf(base.resources, compose.activity.resources).forEach { r -> r.updateConfiguration(Configuration(r.configuration).apply { fontScale = 1f }, r.displayMetrics) } }
    }
    private fun song(): Song {
        val file = File(base.cacheDir, "stream-ui-$unique.m4a"); files += file
        InstrumentationRegistry.getInstrumentation().context.assets.open("audio-test.m4a").use { input -> file.outputStream().use { input.copyTo(it) } }
        return Song("stream-ui-$unique", "달빛 아래 걷는 밤", "밤의 정원", "우리의 계절", duration = 90_000, uri = Uri.fromFile(file), path = file.path, genre = "INDIE, POP", year = 2026)
    }
    private fun screen(songs: List<Song> = listOf(song()), playlists: List<Playlist> = emptyList(), onPlay: (List<Song>, Int, Boolean) -> Unit = { _, _, _ -> }) {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides compose.activity, LocalConfiguration provides compose.activity.resources.configuration,
                LocalDensity provides Density(compose.activity.resources.displayMetrics.density, compose.activity.resources.configuration.fontScale)) {
                MaterialTheme { StreamingContent({}, songs, playlists, onPlay) }
            }
        }
    }
    private fun scroll(text: String) { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text)) }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(base.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun hostSelectsAPlaylistWithoutExposingStorageControls() {
        val song = song(); screen(listOf(song), listOf(Playlist("test-$unique", "친구와 듣는 밤", listOf(song))))
        scroll("플레이리스트 선택"); compose.onNodeWithText("플레이리스트 선택").performClick()
        compose.onNodeWithText("친구와 듣는 밤").performClick()
        compose.onNodeWithText("친구와 듣는 밤").assertExists(); compose.onNodeWithText("음악 서버 시작").assertIsEnabled()
        screenshot("streaming-host-ready")
    }
    @Test fun emptyLibraryCannotStartAndInvalidInvitationsExplainRecovery() {
        screen(emptyList())
        scroll("음악 서버 시작"); compose.onNodeWithText("음악 서버 시작").assertIsNotEnabled()
        scroll("연결하기"); compose.onNodeWithText("연결하기", useUnmergedTree = false).performClick()
        scroll("초대 붙여넣기"); compose.onNodeWithText("초대 붙여넣기").performTextInput("T2S1.invalid")
        compose.onNodeWithText("연결 요청").performScrollTo().performClick()
        compose.onNodeWithText("초대가 올바르지 않거나 만료되었습니다. 서버에서 새 초대를 받아 주세요.").assertExists()
        screenshot("streaming-connect-validation")
    }
    @Test fun foregroundHostApprovesThenRevokesARealPinnedTlsConnection() = runBlocking {
        val song = song(); inserted = song.id
        RhythmDatabase.getInstance(base).songDao().insertMissing(listOf(song.toEntity()))
        screen(listOf(song))
        scroll("음악 서버 시작"); compose.onNodeWithText("음악 서버 시작").performClick()
        compose.waitUntil(30_000) { manager.state.value.running }
        val invite = manager.invite("https://127.0.0.1:8898")
        val connection = StreamingClient.pair(invite, "친구의 메인폰", StreamingProtocol.token())
        compose.waitUntil(10_000) { manager.state.value.pending.isNotEmpty() }
        testPeer = manager.state.value.pending.single().peer.id
        scroll("승인"); screenshot("streaming-pair-approval")
        compose.onNodeWithText("승인", substring = false).performClick()
        compose.waitUntil(10_000) { manager.access.authorized(connection.token) != null }
        assertEquals(song.title, StreamingClient.page(connection, song.title).tracks.single().title)
        scroll("접근 취소"); compose.onNodeWithText("접근 취소").performClick()
        compose.onNodeWithText("진행 중인", substring = true).assertExists()
        compose.onNodeWithText("접근 취소", substring = false).performClick()
        compose.waitUntil(10_000) { manager.access.authorized(connection.token) == null }
        scroll("서버 중지"); compose.onNodeWithText("서버 중지").performClick()
        compose.waitUntil(10_000) { !manager.state.value.running }; Unit
    }
    @Test fun connectedLibrarySearchUpdatesMetadataAndPlaysTheChosenQueue() = runBlocking {
        val song = song(); val serverId = UUID.randomUUID().toString(); testServer = serverId
        val access = StreamingAccess(); val catalog = StreamingCatalog(base, serverId); catalog.replace(listOf(song), null)
        val identity = StreamingIdentity()
        val server = StreamingServer(0, identity.serverContext(), access, catalog, artwork = { null }); transport = server; server.start(10_000, true)
        val token = StreamingProtocol.token(); access.approve(access.pair(access.invite().first, token, "메인폰")!!.peer.id)
        manager.store.save(StreamConnection(serverId, "나의 음악 서브폰", "https://127.0.0.1:${server.listeningPort}", identity.pin, token))
        var played = emptyList<Song>(); var chosen = -1
        screen(emptyList()) { list, index, _ -> played = list; chosen = index }
        compose.onNodeWithText("연결하기").performClick()
        scroll("노래 · 아티스트 · 앨범 검색")
        compose.waitUntil(20_000) { compose.onAllNodesWithText(song.title).fetchSemanticsNodes().isNotEmpty() }
        scroll(song.title); screenshot("streaming-remote-library")
        compose.onNodeWithText(song.title).performClick()
        assertEquals(0, chosen); assertEquals(song.title, played.single().title)
        assertEquals(StreamingProtocol.MEDIA_HOST, played.single().uri.host); assertFalse(played.single().uri.toString().contains(token))
        catalog.replace(listOf(song.copy(title = "새로 고친 노래", artist = "새 아티스트", genre = "ROCK")), null)
        scroll("라이브러리 새로고침"); compose.onNodeWithText("라이브러리 새로고침").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("새로 고친 노래").fetchSemanticsNodes().isNotEmpty() }
        scroll("새로 고친 노래"); compose.onNodeWithText("새 아티스트", substring = true).assertExists(); Unit
    }
    @Test @Suppress("DEPRECATION") fun largeTextConnectionHelpAndAddressInstructionsRemainScrollable() {
        compose.runOnUiThread { listOf(base.resources, compose.activity.resources).forEach { r -> r.updateConfiguration(Configuration(r.configuration).apply { fontScale = 1.6f }, r.displayMetrics) } }
        screen(emptyList())
        scroll("집 밖에서도 듣기"); compose.onNodeWithText("집 밖에서도 듣기").performClick()
        compose.onNodeWithText("이동통신망", substring = true).performScrollTo().assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("이동통신망", substring = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().layoutInput.density.fontScale >= 1.59f)
        screenshot("streaming-large-text-help")
        compose.onNodeWithText("완료").performClick(); Unit
    }
}
