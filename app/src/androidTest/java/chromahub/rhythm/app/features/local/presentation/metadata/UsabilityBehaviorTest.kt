// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.content.ContextWrapper
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.discovery.*
import chromahub.rhythm.app.features.local.data.experience.*
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.*
import chromahub.rhythm.app.shared.presentation.screens.settings.*
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class UsabilityBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before @Suppress("DEPRECATION") fun locale() {
        compose.runOnUiThread {
            Locale.setDefault(Locale.KOREAN)
            listOf(base.resources, compose.activity.resources).forEach { r ->
                r.updateConfiguration(Configuration(r.configuration).apply { setLocale(Locale.KOREAN) }, r.displayMetrics)
            }
        }
    }
    @Test fun searchInputsSurviveSongAlbumAndQueueTabs() {
        compose.setContent { CompositionLocalProvider(LocalContext provides compose.activity) { MaterialTheme { DownloadCenter(initialTab = 0) {} } } }
        compose.onNodeWithTag("download-search-title").performTextInput("노래 A")
        compose.onNodeWithTag("download-search-artist").performTextInput("가수 A")
        compose.onNodeWithText(base.getString(R.string.discovery_albums)).performClick()
        compose.onNodeWithTag("download-search-title").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithTag("download-search-title").performTextInput("앨범 B")
        compose.onNodeWithText(base.getString(R.string.discovery_songs)).performClick()
        compose.onNodeWithTag("download-search-title").assertTextContains("노래 A")
        compose.onNodeWithTag("download-search-artist").assertTextContains("가수 A")
        compose.onNodeWithText(base.getString(R.string.usability_download_queue)).performClick()
        compose.onNodeWithText(base.getString(R.string.discovery_songs)).performClick()
        compose.onNodeWithTag("download-search-title").assertTextContains("노래 A")
        compose.onNodeWithText(base.getString(R.string.discovery_albums)).performClick()
        compose.onNodeWithTag("download-search-title").assertTextContains("앨범 B")
    }
    @Test fun canceledProviderCannotOverwriteANewerKeyboardSearch() {
        val state = DownloadSearchState("처음")
        val old = CompletableDeferred<List<AudioSourceCandidate>>()
        val next = CompletableDeferred<List<AudioSourceCandidate>>()
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val source = object : DownloadSearchSource {
            override suspend fun songs(provider: String, query: String): List<AudioSourceCandidate> =
                if (calls.incrementAndGet() == 1) withContext(NonCancellable) { old.await() } else next.await()
            override suspend fun albums(provider: String, query: String) = emptyList<AudioAlbumCandidate>()
            override suspend fun tracks(album: AudioAlbumCandidate) = emptyList<AudioSourceCandidate>()
        }
        try {
            compose.setContent { val vm: MusicViewModel = viewModel()
                MaterialTheme { DownloadSearch(false, vm, state, dataSource = source) } }
            compose.onNodeWithTag("download-search-title").performImeAction()
            compose.waitUntil(10_000) { calls.get() == 1 }
            compose.onNodeWithTag("download-search-list").performScrollToNode(hasTestTag("download-search-cancel"))
            compose.onNodeWithTag("download-search-cancel").performClick()
            compose.onNodeWithTag("download-search-list").performScrollToIndex(0)
            compose.onNodeWithTag("download-search-title").performTextReplacement("다음")
            compose.onNodeWithTag("download-search-title").performImeAction()
            compose.waitUntil(10_000) { calls.get() == 2 }
            old.complete(listOf(AudioSourceCandidate("https://example.org/old", "오래된 결과", "가수", 180)))
            compose.waitForIdle()
            assertTrue(state.busy); assertTrue(state.songs.isEmpty())
            next.complete(listOf(AudioSourceCandidate("https://example.org/new", "새 결과", "가수", 180)))
            compose.waitUntil(10_000) { !state.busy }
            assertEquals("새 결과", state.songs.single().title)
        } finally { old.complete(emptyList()); next.complete(emptyList()) }
    }
    @Test fun settingsSearchOpensAlgorithmPanelAndKeepsQueryAfterBack() {
        compose.setContent { CompositionLocalProvider(LocalContext provides compose.activity) {
            MaterialTheme { SettingsScreen(onBackClick = {}, onNavigateTo = { error("Management search must open its working panel") }) }
        } }
        compose.onNode(hasSetTextAction()).performTextInput("알고리즘")
        compose.onNodeWithText(base.getString(R.string.algorithm_title)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(base.getString(R.string.algorithm_patterns)).fetchSemanticsNodes().isNotEmpty() }
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(base.getString(R.string.algorithm_patterns)).fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasSetTextAction()).assertTextContains("알고리즘")
    }
    @Test fun allNewManagementToolsCanBeFoundByTheirKoreanNames() {
        val index = buildSettingsSearchIndex(base)
        listOf("알고리즘" to SettingsRoutes.ALGORITHM_MANAGEMENT, "다운로드" to SettingsRoutes.DOWNLOAD_CENTER,
            "뮤직비디오" to SettingsRoutes.VIDEO_LIBRARY).forEach { (query, route) ->
            assertTrue("Missing searchable tool: $query", searchSettings(query, index).any { it.route == route })
        }
    }
    @Test fun lastEndTapKeepsFineAdjustmentOnTheEndOfTheLine() {
        val track = Song("fine-${UUID.randomUUID()}", "끝 시각 조정", "가수", "앨범", duration = 20_000,
            uri = Uri.fromFile(File(base.cacheDir, "fine-${UUID.randomUUID()}.wav")))
        val now = AtomicLong(1000)
        compose.setContent { val vm: MusicViewModel = viewModel()
            MaterialTheme { LyricTimingEditor(track, LyricsData("한 줄의 가사입니다", null), vm,
                playbackOverride = TimingPlayback({ now.get() }, { false }, {}, {}, {})) {} } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("timing-mark-start").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("timing-mark-start").performClick()
        now.set(3000); compose.onNodeWithTag("timing-mark-end").performClick()
        compose.onNodeWithTag("timing-exact").assertTextContains("00:03.000")
        compose.onNodeWithTag("timing-later").performScrollTo().performClick()
        compose.onNodeWithText("00:01.000 → 00:03.050").assertExists()
        LyricTimingDraftStore(base).delete(track)
    }
    @Test fun indexedProposalCannotReturnAnotherSongsHistory() {
        val root = File(base.cacheDir, "lookup-${UUID.randomUUID()}").apply { mkdirs() }
        val ctx = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        try {
            val store = EnrichmentStore(ctx)
            fun song(id: String) = Song(id, id, "가수", "앨범", duration = 180_000, uri = Uri.fromFile(File(root, "$id.wav")))
            val first = store.proposal(song("one"), "LYRICS", "[00:01]처음\n[00:05]다음\n[00:10]마지막", .5, "waveform_estimate", 0)
            val other = store.proposal(song("two"), "LYRICS", "[00:02]처음\n[00:06]다음\n[00:12]마지막", .5, "waveform_estimate", 0)
            assertTrue(store.propose(first, updateHints = false)); assertTrue(store.propose(other, updateHints = false))
            assertEquals(first.id, store.current(first.songKey, "LYRICS")!!.id)
            val pointer = File(ctx.filesDir, "music_metadata/personalization/learning/current-${kr.co.metadata.crawler.CatalogFileStore.hash(first.songKey + "LYRICS")}")
            pointer.writeText(other.id)
            assertNull(store.current(first.songKey, "LYRICS"))
        } finally { root.deleteRecursively() }
    }
    @Test fun switchingReportPeriodHidesPreviousPeriodsTotalsUntilNewDataArrives() {
        val next = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        try {
            compose.setContent { val vm: MusicViewModel = viewModel()
                MaterialTheme { Column(androidx.compose.ui.Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ListeningReportPanel(vm) { _, window ->
                        val call = calls.incrementAndGet()
                        if (call > 1) next.await()
                        ListeningReport(window, if (call == 1) 600_000 else 1_200_000, 2, 2, emptyList(), emptyList())
                    }
                } }
            }
            val old = base.getString(R.string.report_minutes, 10L)
            val fresh = base.getString(R.string.report_minutes, 20L)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(old).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(base.getString(R.string.report_month)).performClick()
            compose.waitUntil(10_000) { calls.get() >= 2 }
            compose.onNodeWithText(old).assertDoesNotExist()
            next.complete(Unit)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(fresh).fetchSemanticsNodes().isNotEmpty() }
        } finally { next.complete(Unit) }
    }
}
