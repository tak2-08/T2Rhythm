// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.Manifest
import android.content.ContentValues
import android.content.res.Configuration
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.discovery.*
import chromahub.rhythm.app.features.local.data.experience.*
import chromahub.rhythm.app.features.local.data.metadata.AutomaticLyricsStore
import chromahub.rhythm.app.features.local.data.metadata.SongArtworkOverrides
import chromahub.rhythm.app.features.local.di.LocalMusicModule
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.*
import chromahub.rhythm.app.shared.presentation.components.lyrics.FullScreenLyricsView
import chromahub.rhythm.app.shared.presentation.screens.player.ExpressivePlayerScreen
import chromahub.rhythm.app.shared.presentation.screens.player.MaterialPlayerScreen
import chromahub.rhythm.app.shared.presentation.screens.settings.UpdatesSettingsScreen
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LyricsEntryAndUpdatesTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ctx get() = compose.activity
    private val text = "밤의 문장을 따라\n천천히 걸어가\n서로의 시간을 맞춰\n우리의 노래가 돼"
    private fun song() = Song("lyrics-ui-${UUID.randomUUID()}", "밤의 문장", "리듬", "기억의 조각", duration = 180_000, uri = Uri.fromFile(File(base.cacheDir, "lyrics-ui.m4a")))
    @Before @Suppress("DEPRECATION") fun koreanActivityAndDialogs() {
        chromahub.rhythm.app.testing.grantTestPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        compose.runOnUiThread {
            Locale.setDefault(Locale.KOREAN)
            listOf(ctx.resources, base.resources).forEach { r ->
                val config = Configuration(r.configuration).apply { setLocale(Locale.KOREAN) }
                r.updateConfiguration(config, r.displayMetrics)
            }
        }
    }
    @Composable private fun Frame(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalContext provides ctx, LocalConfiguration provides ctx.resources.configuration) { MaterialTheme { content() } }
    }
    private fun capture(name: String) {
        // Semantics can be placed while the entrance graphics layer is still transparent.
        // Advance Compose animation time, then allow Android windows to commit their frames.
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        android.os.SystemClock.sleep(2200)
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(base.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun expressiveLyricsShowsTheRealAiActionAndOptions() {
        val track = song(); val original = LyricsData(text, null)
        compose.setContent {
            val vm: MusicViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            Frame {
                ExpressivePlayerScreen(track, false, false, { .23f }, "0:42", "3:00", 1, 1, false, 0,
                    showLyricsView = true, showLyrics = true, lyrics = original, isLoadingLyrics = false, onlineOnlyLyrics = false,
                    onLyricsSeek = {}, onRetryLyrics = {}, onShowLyricsEditor = {}, onPickLyricsFile = {}, isMediaLoading = false, isSeeking = false,
                    onPlayPause = {}, onSeek = {}, onSkipPrevious = {}, onSkipNext = {}, onToggleFavorite = {}, onToggleShuffle = {}, onToggleRepeat = {},
                    onToggleLyrics = {}, onSongInfoClick = {}, onShowAlbumBottomSheet = {}, onShowArtist = {}, onMoreClick = {}, onDeviceClick = {}, onQueueClick = {}, onBack = {},
                    location = null, appSettings = AppSettings.getInstance(ctx), musicViewModel = vm,
                    lyricSuggestion = { LyricSuggestionButton(track, original, vm) })
            }
        }
        compose.onNodeWithTag("lyric-ai-action").assertIsDisplayed()
        capture("lyrics-ai-entry.png")
        compose.onNodeWithTag("lyric-ai-action").performClick()
        compose.onNodeWithText(ctx.getString(R.string.lyric_ai_start)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.lyric_ai_hint)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.discovery_auto_refresh, 6)).assertDoesNotExist()
        capture("lyrics-ai-options.png")
        assertNull(AutomaticLyricsStore.get(base).read(track))
    }
    @Test fun materialLyricsDoesNotCoverTheAiActionWithItsText() {
        val track = song()
        compose.setContent {
            val vm: MusicViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            val nav = rememberNavController()
            Frame { MaterialPlayerScreen(track, false, { 0f }, null, {}, {}, {}, {}, {}, {}, {}, AppSettings.getInstance(ctx), vm, nav,
                lyrics = LyricsData(text, null), showLyrics = true) }
        }
        compose.onNodeWithText(ctx.getString(R.string.player_chip_lyrics)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("lyric-ai-action").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("lyric-ai-action").assertIsDisplayed()
        capture("lyrics-ai-material.png")
        compose.onNodeWithTag("lyric-manual-action").assertDoesNotExist()
        compose.onNodeWithTag("lyric-ai-action").assertWidthIsAtLeast(48.dp)
        assertTrue(compose.onNodeWithTag("lyric-ai-action").fetchSemanticsNode().boundsInRoot.height <= 80 * ctx.resources.displayMetrics.density)
        compose.onNodeWithText(text).assertIsDisplayed()
    }
    @Test fun fullscreenKeepsAiVisibleAfterAutoHideWithPlainTextInTheSyncedField() {
        val settings = AppSettings.getInstance(ctx); val old = settings.autoHideLyricsControls.value
        settings.setAutoHideLyricsControls(true)
        try {
            val track = song(); val screenText = text + "\nHold on to the night"; val original = LyricsData(null, screenText)
            compose.setContent {
                val vm: MusicViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                Frame { FullScreenLyricsView(track, false, 0, original, false, {}, {}, {}, {}, {}, {}, {}, {}, {},
                    lyricSuggestion = { LyricSuggestionButton(track, original, vm) }) }
            }
            compose.mainClock.advanceTimeBy(6500)
            compose.waitUntil(8000) { compose.onAllNodesWithContentDescription(ctx.getString(R.string.edit_lyrics)).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("lyric-ai-action").assertIsDisplayed()
            compose.onNodeWithText(screenText).assertIsDisplayed()
            capture("lyrics-ai-fullscreen.png")
        } finally { settings.setAutoHideLyricsControls(old) }
    }
    @Test fun recommendationCheckShowsFailureLatestAndAppliedAsDifferentResults() {
        var calls = 0
        val version = ExperienceProfile.read(base).version
        compose.setContent { Frame { SoftUpdatePanel {
            when (++calls) {
                1 -> throw java.io.IOException("offline")
                2 -> ExperienceRulesCheck(ExperienceRulesStatus.CURRENT, version)
                else -> ExperienceRulesCheck(ExperienceRulesStatus.UPDATED, version + 1)
            }
        } } }
        compose.onNodeWithText(ctx.getString(R.string.soft_update_check)).performClick()
        compose.onNodeWithText(ctx.getString(R.string.soft_update_error)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.soft_update_current, version)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.soft_update_check)).performClick()
        compose.onNodeWithText(ctx.getString(R.string.soft_update_latest)).assertIsDisplayed()
        capture("recommendation-settings.png")
        compose.onNodeWithText(ctx.getString(R.string.soft_update_check)).performClick()
        compose.onNodeWithText(ctx.getString(R.string.soft_update_done)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.soft_update_current, version + 1)).assertExists()
    }
    @Test fun appUpdatesStartsWithAppStatusAndHasNoOverlappingRulesCard() {
        compose.setContent { Frame { UpdatesSettingsScreen {} } }
        compose.onNodeWithTag("recommendation-rules-card").assertDoesNotExist()
        compose.onNodeWithText(ctx.getString(R.string.soft_update_check)).assertDoesNotExist()
        compose.onRoot().assertIsDisplayed()
        capture("app-updates-clean.png")
    }
    @Test fun previewActuallyStartsAtTheRequestedLineAndPreservesTheQueue() {
        val file = File(base.cacheDir, "lyric-preview-${UUID.randomUUID()}.m4a")
        InstrumentationRegistry.getInstrumentation().context.assets.open("audio-test.m4a").use { input -> file.outputStream().use { input.copyTo(it) } }
        var player: ExoPlayer? = null
        try {
            compose.runOnUiThread {
                val activePlayer = ExoPlayer.Builder(ctx).build(); player = activePlayer
                activePlayer.setMediaItems(listOf("first", "target").map { MediaItem.Builder().setMediaId(it).setUri(Uri.fromFile(file)).build() })
                assertTrue(LyricPreviewPlayback.start(activePlayer, "target", 400, 2000) { fail("Existing queue entry must be reused") })
                assertEquals(2, activePlayer.mediaItemCount)
                assertEquals("target", activePlayer.currentMediaItem?.mediaId)
                assertTrue(activePlayer.playWhenReady)
                assertTrue(activePlayer.currentPosition >= 400)
            }
        } finally { compose.runOnUiThread { player?.release() }; file.delete() }
    }
    @Test fun lyricReviewOpensTheProposalDirectlyAndAdjustsOnlyItsCopy() {
        val track = song(); val original = LyricsData(null, text)
        val store = EnrichmentStore(base)
        val lines = text.lines().mapIndexed { i, line -> TimedLine(30_000L * (i + 1), line) }
        val proposal = store.proposal(track, "LYRICS", LocalLyricAlignment.lrc(lines), .18, "waveform_estimate", 0, original)
        store.propose(proposal)
        try {
            compose.setContent {
                val vm: MusicViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                Frame { LyricSuggestionButton(track, original, vm) }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(ctx.getString(R.string.lyric_ai_review)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("lyric-ai-action").performClick()
            compose.onNodeWithContentDescription(ctx.getString(R.string.usability_timing_tools)).performClick()
            compose.onNodeWithText(ctx.getString(R.string.usability_timing_help)).performClick()
            compose.onNodeWithText(ctx.getString(R.string.timing_editor_hint)).assertIsDisplayed()
            compose.onNodeWithText(ctx.getString(android.R.string.ok)).performClick()
            compose.onNodeWithTag("timing-later").performScrollTo().performClick()
            compose.waitUntil(10_000) { LyricTimingDraftStore(base).read(track, original, proposal.id)?.rows?.firstOrNull()?.start == 30_050L }
            assertEquals(30_000L, LocalLyricAlignment.parse(store.get(proposal.id)!!.value).first().ms)
            assertEquals(original, store.get(proposal.id)!!.beforeLyrics)
            assertNull(AutomaticLyricsStore.get(base).read(track))
            capture("lyrics-ai-review.png")
            compose.onNodeWithTag("timing-reject").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { store.get(proposal.id)?.state == "REJECTED" }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("timing-editor").fetchSemanticsNodes().isEmpty() }
            assertTrue(LyricTimingFeedback(base).read().none { it.id == proposal.id })
            assertNull(AutomaticLyricsStore.get(base).read(track))
        } finally {
            WorkManager.getInstance(base).cancelUniqueWork(TimingLearningWorker.NAME)
            File(base.filesDir, "music_metadata/personalization/learning/proposals/${proposal.id}.json").delete(); LyricTimingDraftStore(base).delete(track)
        }
    }
    @Test fun explicitAnalysisProducesASeparateProposalAtFiftyFivePercentUnplugged() {
        val resolver = base.contentResolver
        val name = "lyric-analysis-${UUID.randomUUID()}.wav"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name); put(MediaStore.Audio.Media.TITLE, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav"); put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.DURATION, 20_000); put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        val store = EnrichmentStore(base); val previousMode = store.mode()
        fun shell(command: String) { InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() } } }
        var proposal: EnrichmentProposal? = null
        try {
            val rate = 16000; val count = rate * 20
            val buffer = java.nio.ByteBuffer.allocate(44 + count * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(count * 2)
            for (i in 0 until count) {
                val t = i.toDouble() / rate
                val amplitude = if (t < 2 || t > 18) .002 else .25 + .08 * kotlin.math.sin(t * 3)
                buffer.putShort((32767 * amplitude * kotlin.math.sin(t * 440 * 2 * Math.PI)).toInt().toShort())
            }
            resolver.openOutputStream(uri)!!.use { it.write(buffer.array()) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0); put(MediaStore.Audio.Media.DURATION, 20_000) }, null, null)
            val track = runBlocking { LocalMusicModule.provideMusicRepository(base).loadSongs(forceRefresh = true).first { it.uri == uri } }
            val original = LyricsData(null, text)
            shell("dumpsys battery set ac 0"); shell("dumpsys battery set usb 0"); shell("dumpsys battery set wireless 0"); shell("dumpsys battery set level 55")
            store.mode("LIGHT")
            runBlocking { LyricSuggestionWorker.enqueue(base, track, original) }
            val workName = LyricSuggestionWorker.name(SongArtworkOverrides.key(track))
            compose.waitUntil(90_000) { WorkManager.getInstance(base).getWorkInfosForUniqueWork(workName).get().any { it.state.isFinished } }
            val work = WorkManager.getInstance(base).getWorkInfosForUniqueWork(workName).get().first { it.state.isFinished }
            assertEquals(work.outputData.toString(), WorkInfo.State.SUCCEEDED, work.state)
            proposal = store.list(1000).first { it.songKey == uri.toString() && it.kind == "LYRICS" }
            assertEquals("PENDING", proposal!!.state); assertEquals(original, proposal!!.beforeLyrics)
            assertTrue(LocalLyricAlignment.valid(LocalLyricAlignment.parse(proposal!!.value), track.duration))
            assertNull(AutomaticLyricsStore.get(base).read(track))
        } finally {
            shell("dumpsys battery set level 100"); shell("dumpsys battery set ac 1"); store.mode(previousMode)
            resolver.delete(uri, null, null)
            proposal?.let { File(base.filesDir, "music_metadata/personalization/learning/proposals/${it.id}.json").delete() }
        }
    }
}
