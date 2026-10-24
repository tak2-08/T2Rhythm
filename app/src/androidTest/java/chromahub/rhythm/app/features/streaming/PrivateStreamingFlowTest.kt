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
import chromahub.rhythm.app.R
import androidx.compose.ui.graphics.asAndroidBitmap
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
            androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).isAppearanceLightStatusBars = true
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
        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); android.os.SystemClock.sleep(200)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(base.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun hostSelectsAPlaylistWithoutExposingStorageControls() {
        val song = song(); screen(listOf(song), listOf(Playlist("test-$unique", "친구와 듣는 밤", listOf(song))))
        scroll("플레이리스트 선택"); compose.onNodeWithText("플레이리스트 선택").performClick()
        compose.onNodeWithText("친구와 듣는 밤").performClick()
        compose.onNodeWithText("적용").performClick()
        compose.onNodeWithText("친구와 듣는 밤").assertExists(); compose.onNodeWithText("음악 서버 시작").assertIsEnabled()
        screenshot("streaming-host-ready")
    }
    @Test fun emptyLibraryCannotStartAndInvalidInvitationsExplainRecovery() {
        screen(emptyList())
        scroll("음악 서버 시작"); compose.onNodeWithText("음악 서버 시작").assertIsNotEnabled()
        scroll("연결하기"); compose.onNodeWithText("연결하기", useUnmergedTree = false).performClick()
        scroll("초대 붙여넣기"); compose.onNodeWithText("초대 붙여넣기").performTextInput("T2S1.invalid")
        compose.onNodeWithText("연결 요청").performScrollTo().performClick()
        compose.onNodeWithText("초대가 올바르지 않거나 만료되었습니다. 서버에서 새 초대를 받아 주세요.").assertIsDisplayed()
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
        compose.onAllNodesWithText("접근 취소", substring = false).onLast().performClick()
        compose.waitUntil(10_000) { manager.access.authorized(connection.token) == null }
        scroll("서버 중지"); compose.onNodeWithText("서버 중지").performClick()
        compose.waitUntil(10_000) { !manager.state.value.running }; Unit
    }
    @Test fun connectedLibrarySearchUpdatesMetadataAndPlaysTheChosenQueue() = runBlocking {
        val song = song(); val serverId = UUID.randomUUID().toString(); testServer = serverId
        val access = StreamingAccess(); val catalog = StreamingCatalog(base, serverId); catalog.replace(listOf(song), null)
        val identity = StreamingIdentity()
        val server = StreamingServer(0, identity.serverContext(), access, catalog, artwork = { null }, identityPin = identity.pin); transport = server; server.start(10_000, true)
        val token = StreamingProtocol.token(); access.approve(access.pair(access.invite().first, token, "메인폰")!!.peer.id)
        manager.store.save(StreamConnection(serverId, "나의 음악 서브폰", "https://127.0.0.1:${server.listeningPort}", identity.pin, token))
        var played = emptyList<Song>(); var chosen = -1
        screen(emptyList()) { list, index, _ -> played = list; chosen = index }
        compose.onNodeWithText("연결하기").performClick()
        scroll("노래 · 아티스트 · 앨범 검색")
        compose.waitUntil(20_000) { runCatching { scroll(song.title); compose.onAllNodesWithText(song.title).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        scroll(song.title); screenshot("streaming-remote-library")
        compose.onNodeWithText("T2Rhythm Streaming").assertIsDisplayed()
        compose.onNodeWithText(song.title).performClick()
        assertEquals(0, chosen); assertEquals(song.title, played.single().title)
        assertEquals(StreamingProtocol.MEDIA_HOST, played.single().uri.host); assertFalse(played.single().uri.toString().contains(token))
        catalog.replace(listOf(song.copy(title = "새로 고친 노래", artist = "새 아티스트", genre = "ROCK")), null)
        scroll("라이브러리 새로고침"); compose.onNodeWithText("라이브러리 새로고침").performClick()
        compose.waitUntil(20_000) { runCatching { scroll("새로 고친 노래"); compose.onAllNodesWithText("새로 고친 노래").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
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
    @Test fun remotePlaybackControlsCommitOneSeekWhenTheDragEnds() {
        val current = song().copy(uri = Uri.parse("https://${StreamingProtocol.MEDIA_HOST}/server/audio/${"a".repeat(64)}"))
        var seeks = 0; var position = 0f; var toggles = 0; var next = 0
        compose.setContent { MaterialTheme { StreamingContent({}, emptyList(), emptyList(), { _, _, _ -> }, current = current,
            onSeek = { seeks++; position = it }, onToggle = { toggles++ }, onNext = { next++ }) } }
        compose.onNodeWithTag("streaming-seek").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(width * .2f, height / 2f))
            moveTo(androidx.compose.ui.geometry.Offset(width * .5f, height / 2f))
            moveTo(androidx.compose.ui.geometry.Offset(width * .8f, height / 2f)); up()
        }
        assertEquals(1, seeks); assertTrue(position > .6f)
        compose.onNodeWithContentDescription("재생 또는 일시 정지").performClick(); compose.onNodeWithContentDescription("다음 곡").performClick()
        assertEquals(1, toggles); assertEquals(1, next); screenshot("streaming-playback-controls")
        compose.onNodeWithText("T2Rhythm Streaming").assertIsDisplayed()
    }
    @Test fun privateSongInformationShowsServerTagsWithoutOfferingPhysicalFileEditing() {
        val original = song()
        val remote = original.copy(id = "private-$unique", uri = Uri.parse("https://${StreamingProtocol.MEDIA_HOST}/server/audio/${"a".repeat(64)}"))
        compose.setContent { MaterialTheme {
            chromahub.rhythm.app.shared.presentation.components.bottomsheets.SongInfoBottomSheet(remote, {},
                chromahub.rhythm.app.shared.data.model.AppSettings.getInstance(base),
                onEditSong = { _, _, _, _, _, _, _, _, _, _, _, _ -> fail("A remote song cannot edit a device file") }, startInEditMode = true)
        } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(remote.title).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(remote.artist).onFirst().assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        screenshot("streaming-song-info")
    }
    @Test fun manualInvitationStillWorksAfterLeavingAndReturningToTheHostScreen() = runBlocking {
        val track = song(); inserted = track.id
        RhythmDatabase.getInstance(base).songDao().insertMissing(listOf(track.toEntity()))
        var visible by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides compose.activity) {
                MaterialTheme { if (visible) StreamingContent({}, listOf(track), emptyList(), { _, _, _ -> }) else androidx.compose.material3.Text("Another screen") }
            }
        }
        scroll("음악 서버 시작"); compose.onNodeWithText("음악 서버 시작").performClick()
        compose.waitUntil(30_000) { manager.state.value.running }
        scroll(base.getString(R.string.nearby_show)); compose.onNodeWithText(base.getString(R.string.nearby_show)).performClick()
        val invite = manager.invite("https://127.0.0.1:8898")
        scroll(base.getString(R.string.nearby_hide)); compose.onNodeWithText(base.getString(R.string.nearby_hide)).performClick()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        assertTrue(manager.state.value.running)
        val connection = StreamingClient.pair(invite, "화면 밖 초대", StreamingProtocol.token())
        testPeer = manager.access.requests().single().peer.id
        compose.runOnIdle { visible = true }
        scroll("승인"); compose.onNodeWithText("승인").performClick()
        compose.waitUntil(10_000) { manager.access.authorized(connection.token) != null }
        assertEquals(track.title, StreamingClient.page(connection).tracks.single().title)
    }
    @Test @Suppress("DEPRECATION") fun largeTextQrDecodesAndLegacyInvitationActionsStayReachable() = runBlocking {
        val track = song(); inserted = track.id
        RhythmDatabase.getInstance(base).songDao().insertMissing(listOf(track.toEntity()))
        compose.runOnUiThread { listOf(base.resources, compose.activity.resources).forEach { r -> r.updateConfiguration(Configuration(r.configuration).apply { fontScale = 1.6f }, r.displayMetrics) } }
        screen(listOf(track))
        scroll("음악 서버 시작"); compose.onNodeWithText("음악 서버 시작").performClick()
        compose.waitUntil(30_000) { manager.state.value.running }
        scroll(base.getString(R.string.streaming_invite)); compose.onNodeWithText(base.getString(R.string.streaming_invite)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("https://127.0.0.1:8898")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText(base.getString(R.string.streaming_create_invite)).performClick()
        val qr = compose.onNodeWithContentDescription(base.getString(R.string.streaming_qr_description))
        compose.waitUntil(10_000) { runCatching { qr.fetchSemanticsNode() }.isSuccess }
        qr.performScrollTo()
        val image = qr.captureToImage().asAndroidBitmap()
        screenshot("streaming-qr-invitation-large-text-before-decode")
        File(base.getExternalFilesDir(null), "streaming-qr-captured-node.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        assertEquals("The entire square QR must fit in the captured viewport", image.width, image.height)
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val decoded = com.google.zxing.qrcode.QRCodeReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(
            com.google.zxing.RGBLuminanceSource(image.width, image.height, pixels))))
        val invite = StreamingProtocol.Invite.decode(decoded.text)
        assertEquals(manager.store.serverId, invite.server); assertEquals(2, invite.binding)
        screenshot("streaming-qr-invitation-large-text")
        compose.onNodeWithText(base.getString(R.string.streaming_copy)).performScrollTo().performClick()
        // Android's clipboard overlay may temporarily own focus after a copy.
        // Return to the dialog before reading, as background reads are denied.
        qr.performScrollTo().performTouchInput { click() }
        val clipboard = base.getSystemService(android.content.ClipboardManager::class.java)
        var copied: String? = null
        compose.waitUntil(5_000) {
            copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            copied != null
        }
        assertEquals(invite, StreamingProtocol.Invite.decode(requireNotNull(copied)))
        compose.onNodeWithText(base.getString(R.string.streaming_share)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(base.getString(R.string.streaming_done)).performClick(); Unit
    }

}
