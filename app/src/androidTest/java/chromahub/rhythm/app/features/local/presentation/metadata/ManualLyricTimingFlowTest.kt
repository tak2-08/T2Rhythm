// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.ContentValues
import android.content.res.Configuration
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.WorkInfo
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.discovery.*
import chromahub.rhythm.app.features.local.data.experience.*
import chromahub.rhythm.app.features.local.data.metadata.AutomaticLyricsStore
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.features.local.di.LocalMusicModule
import chromahub.rhythm.app.shared.data.model.*
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ManualLyricTimingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val text = "첫 번째 노래\n잠시 쉬었다가\n마지막을 맞춰요"
    private fun song() = Song("manual-${UUID.randomUUID()}", "직접 맞춘 노래", "리듬", "시간", duration = 20_000,
        uri = Uri.fromFile(File(context.cacheDir, "manual-${UUID.randomUUID()}.wav")))
    private fun isolated(root: File) = object : ContextWrapper(context) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }
    @Before @Suppress("DEPRECATION") fun setup() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_EXTERNAL_STORAGE)
        compose.runOnUiThread {
            Locale.setDefault(Locale.KOREAN)
            listOf(context.resources, compose.activity.resources).forEach { resources ->
                resources.updateConfiguration(Configuration(resources.configuration).apply { setLocale(Locale.KOREAN) }, resources.displayMetrics)
            }
        }
    }
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(2000); compose.waitForIdle(); android.os.SystemClock.sleep(1200)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun manualCaptureSavesExactStartsAndEndsOnlyAfterExplicitSave() {
        val track = song(); val original = LyricsData(text, null); val now = AtomicLong(0)
        var closed by mutableStateOf(false)
        val transport = TimingPlayback({ now.get() }, { false }, { now.set(it) }, {}, { now.set(it) })
        compose.setContent {
            val vm: MusicViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            MaterialTheme { if (!closed) LyricTimingEditor(track, original, vm, playbackOverride = transport,
                applyOverride = { proposal -> AutomaticLyricsStore.get(context).saveFetched(track,
                    LyricsData(text, proposal.value, source = "T2Rhythm reviewed alignment", isCorrected = true), embed = false, reviewed = true) }) { closed = true } }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("timing-mark-start").fetchSemanticsNodes().isNotEmpty() }
        val starts = listOf(1234L, 6789L, 12_345L); val ends = listOf(3456L, 8999L, 19_876L)
        for (i in starts.indices) {
            now.set(starts[i]); compose.onNodeWithTag("timing-mark-start").performClick()
            now.set(ends[i]); compose.onNodeWithTag("timing-mark-end").performClick()
        }
        assertNull(AutomaticLyricsStore.get(context).read(track))
        compose.onNodeWithTag("timing-save").assertIsEnabled()
        screenshot("manual-lyrics-editor.png")
        compose.onNodeWithTag("timing-save").performClick()
        compose.waitUntil(10_000) { closed }
        val saved = AutomaticLyricsStore.get(context).read(track)!!
        val timed = LocalLyricAlignment.parse(saved.syncedLyrics!!)
        assertEquals(starts, timed.map { it.ms }); assertEquals(ends, timed.map { it.endMs })
        val feedback = LyricTimingFeedback(context).read().first { it.example.key == track.uri.toString() }
        assertTrue(feedback.pending); assertEquals(starts, feedback.example.lines.map { it.ms })
        assertNull(LyricTimingDraftStore(context).read(track, original, null))
        WorkManager.getInstance(context).cancelUniqueWork(TimingLearningWorker.NAME)
    }
    @Test fun unfinishedCaptureRestoresDraftWithoutChangingOriginal() {
        val track = song(); val original = LyricsData(text, null); val now = AtomicLong(1234)
        var open by mutableStateOf(true)
        compose.setContent {
            val vm: MusicViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            MaterialTheme { if (open) LyricTimingEditor(track, original, vm,
                playbackOverride = TimingPlayback({ now.get() }, { false }, { now.set(it) }, {}, { now.set(it) })) { open = false }
                else Text("draft closed") }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("timing-mark-start").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("timing-mark-start").performClick(); now.set(3456)
        compose.onNodeWithTag("timing-mark-end").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.timing_close)).performClick()
        compose.waitUntil(10_000) { !open }
        assertNull(AutomaticLyricsStore.get(context).read(track))
        assertEquals(1234L, LyricTimingDraftStore(context).read(track, original, null)!!.rows.first().start)
        compose.runOnIdle { open = true }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("timing-mark-start").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("timing-lines").performScrollToIndex(0)
        compose.onNodeWithText("00:01.234 → 00:03.456").assertExists()
        LyricTimingDraftStore(context).delete(track)
    }
    @Test fun manualEntryIsAvailableForPlainAndAlreadyTimedLyrics() {
        val track = song(); var lyrics by mutableStateOf(LyricsData(text, null))
        compose.setContent { val vm: MusicViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            MaterialTheme { CompositionLocalProvider(LocalContext provides compose.activity) { LyricSuggestionButton(track, lyrics, vm) } } }
        compose.onNodeWithTag("lyric-manual-action").assertIsDisplayed()
        compose.onNodeWithTag("lyric-ai-action").assertIsDisplayed()
        compose.runOnIdle { lyrics = LyricsData(text, "[00:01]첫 번째 노래\n[00:05]잠시 쉬었다가\n[00:10]마지막을 맞춰요") }
        compose.onNodeWithTag("lyric-manual-action").assertIsDisplayed()
        compose.onNodeWithTag("lyric-ai-action").assertDoesNotExist()
    }
    @Test fun savedManualLabelsSurviveMissingAnalysisAndResolveToOneRecordingVote() {
        val root = File(context.cacheDir, "manual-labels-${UUID.randomUUID()}").apply { mkdirs() }
        val ctx = isolated(root); val track = song(); val store = EnrichmentStore(ctx)
        val lines = listOf(TimedLine(1000, "처음", 3000), TimedLine(5000, "다음", 8000), TimedLine(12_000, "마지막", 18_000))
        try {
            val proposal = store.proposal(track, "LYRICS", LocalLyricAlignment.lrc(lines), 1.0, "manual_timing", 3, LyricsData(text, null))
            assertTrue(store.propose(proposal)); store.decide(proposal.id, "ACCEPTED")
            val feedback = LyricTimingFeedback(ctx)
            assertTrue(feedback.read().single().pending); assertTrue(feedback.examples().isEmpty())
            val audio = AudioFeatures(songKey = track.uri.toString(), pcmSha256 = "manual-recording", sampleRate = 16000, channels = 1,
                decodedDurationMs = 20_000, rmsDb = -10.0, peakDb = -1.0, zeroCrossingRate = .1,
                spectralBands = List(8) { .1 }, waveform = List(256) { .2 }, alignmentEnvelope = List(100) { .2 })
            ListeningLedger.get(ctx).saveFeature(track.uri.toString(), "${track.dateModified}:${track.duration}", audio)
            try {
                feedback.resolveAll(listOf(track)) { throw kotlinx.coroutines.CancellationException("stop") }
                fail("Canceled learning must not commit a partial label update")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            assertTrue(feedback.read().single().pending)
            feedback.resolve(track)
            assertFalse(feedback.read().single().pending)
            assertEquals("manual-recording", feedback.examples().single().pcm)
            assertEquals(lines, feedback.examples().single().lines)
            val other = track.copy(id = "another", uri = Uri.fromFile(File(ctx.cacheDir, "copy.wav")))
            ListeningLedger.get(ctx).saveFeature(other.uri.toString(), "${other.dateModified}:${other.duration}", audio.copy(songKey = other.uri.toString()))
            val copy = store.proposal(other, "LYRICS", proposal.value, 1.0, "manual_timing", 3, LyricsData(text, null))
            assertTrue(store.propose(copy)); store.decide(copy.id, "ACCEPTED")
            assertEquals(1, feedback.examples().size)
            val longSong = track.copy(id = "long-manual", uri = Uri.fromFile(File(ctx.cacheDir, "long.wav")), duration = 600_000)
            val longLines = (0 until 500).map { TimedLine(1000L + it * 1000, "수동 가사 $it", 1500L + it * 1000) }
            val longLabel = store.proposal(longSong, "LYRICS", LocalLyricAlignment.lrc(longLines), 1.0, "manual_timing", 500, LyricsData(longLines.joinToString("\n") { it.text }, null))
            store.propose(longLabel); store.decide(longLabel.id, "ACCEPTED")
            assertEquals(500, feedback.read().first { it.id == longLabel.id }.example.lines.size)
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectedOutputCannotTeachItselfOrReappearWithoutANewModel() {
        val root = File(context.cacheDir, "manual-reject-${UUID.randomUUID()}").apply { mkdirs() }
        val ctx = isolated(root); val track = song(); val store = EnrichmentStore(ctx)
        try {
            val original = LyricsData(text, null)
            val old = store.proposal(track, "LYRICS", "[00:01]처음\n[00:05]다음\n[00:12]마지막", .2, "waveform_estimate", 0, original, learningRevision = "old")
            assertTrue(store.propose(old)); store.decide(old.id, "REJECTED")
            assertTrue(LyricTimingFeedback(ctx).examples().isEmpty())
            val changed = old.value.replace("[00:01]", "[00:02]")
            assertFalse(store.propose(store.proposal(track, "LYRICS", changed, .3, "learned_estimate", 4, original, learningRevision = "old")))
            assertFalse(store.propose(store.proposal(track, "LYRICS", old.value, .3, "learned_estimate", 4, original, learningRevision = "new")))
            val next = store.proposal(track, "LYRICS", changed, .3, "learned_estimate", 4, original, learningRevision = "new")
            assertTrue(store.propose(next))
            store.decide(next.id, "ACCEPTED")
            assertTrue("An unchanged accepted estimate must not become its own training target", LyricTimingFeedback(ctx).read().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun retriesAreBoundedPerModelAndPerDay() {
        val root = File(context.cacheDir, "manual-retries-${UUID.randomUUID()}").apply { mkdirs() }
        val ctx = isolated(root); val track = song(); val store = EnrichmentStore(ctx)
        try {
            val proposal = store.proposal(track, "LYRICS", "[00:01]처음\n[00:05]다음\n[00:12]마지막", .2, "waveform_estimate", 0, LyricsData(text, null), learningRevision = "first")
            store.propose(proposal); val rejected = store.decide(proposal.id, "REJECTED")
            val queue = TimingRetryQueue(ctx); queue.reject(rejected)
            queue.schedule(EnrichmentModel(timingRevision = "second"))
            queue.schedule(EnrichmentModel(timingRevision = "second"))
            assertEquals(1, queue.read().single().attempts)
            for (i in 3..8) queue.schedule(EnrichmentModel(timingRevision = "model$i"))
            assertEquals(3, queue.read().single().attempts)
            assertTrue(LyricTimingFeedback(ctx).examples().isEmpty())
        } finally {
            WorkManager.getInstance(context).cancelUniqueWork(LyricSuggestionWorker.name(track.uri.toString()))
            WorkManager.getInstance(context).cancelUniqueWork(TimingLearningWorker.NAME)
            root.deleteRecursively()
        }
    }
    @Test fun realAudioManualLabelsTrainAndRegenerateARejectedSuggestion() {
        val resolver = context.contentResolver
        val uris = mutableListOf<Uri>()
        val store = EnrichmentStore(context); val previousModel = store.model(); val mode = store.mode()
        val created = mutableListOf<EnrichmentProposal>()
        fun shell(command: String) { InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        } }
        fun addAudio(frequency: Double): Uri {
            val name = "manual-training-${UUID.randomUUID()}.wav"
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name); put(MediaStore.Audio.Media.TITLE, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav"); put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.DURATION, 20_000); put(MediaStore.Audio.Media.IS_PENDING, 1)
            })!!
            uris += uri
            val rate = 16000; val count = rate * 20
            val bytes = java.nio.ByteBuffer.allocate(44 + count * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(count * 2)
            for (i in 0 until count) {
                val seconds = i.toDouble() / rate; val amplitude = if (seconds in 2.0..18.0) .2 else .0001
                bytes.putShort((32767 * amplitude * kotlin.math.sin(seconds * frequency * 2 * Math.PI)).toInt().toShort())
            }
            resolver.openOutputStream(uri)!!.use { it.write(bytes.array()) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0); put(MediaStore.Audio.Media.DURATION, 20_000) }, null, null)
            return uri
        }
        try {
            WorkManager.getInstance(context).cancelUniqueWork(TimingLearningWorker.NAME).result.get()
            shell("dumpsys battery set ac 0"); shell("dumpsys battery set usb 0"); shell("dumpsys battery set wireless 0"); shell("dumpsys battery set level 55")
            store.mode("LIGHT"); store.saveModel(EnrichmentModel())
            repeat(5) { addAudio(330.0 + it * 73) }
            val tracks = runBlocking { LocalMusicModule.provideMusicRepository(context).loadSongs(forceRefresh = true).filter { it.uri in uris } }
            assertEquals(5, tracks.size)
            val truth = text.lines().mapIndexed { i, line -> TimedLine(2000L + i * 6000, line, 5000L + i * 6000) }
            val original = LyricsData(text, null, source = "manual test text")
            val target = tracks.first { it.uri == uris.last() }
            runBlocking { AutomaticLyricsStore.get(context).saveFetched(target, original, embed = false, reviewed = true) }
            runBlocking { LyricSuggestionWorker.enqueue(context, target, original) }
            val targetWork = LyricSuggestionWorker.name(chromahub.rhythm.app.features.local.data.metadata.SongArtworkOverrides.key(target))
            compose.waitUntil(90_000) { WorkManager.getInstance(context).getWorkInfosForUniqueWork(targetWork).get().any { it.state == WorkInfo.State.SUCCEEDED } }
            val first = store.list(1000).first { it.songKey == target.uri.toString() && it.state == "PENDING" }
            created += first
            for (teacher in tracks.filter { it.uri != target.uri }) {
                val label = store.proposal(teacher, "LYRICS", LocalLyricAlignment.lrc(truth), 1.0, "manual_timing", 3, original)
                store.propose(label); store.decide(label.id, "ACCEPTED"); created += label
            }
            val rejected = store.decide(first.id, "REJECTED")
            TimingRetryQueue(context).reject(rejected)
            compose.waitUntil(180_000) {
                store.list(1000).any { it.songKey == target.uri.toString() && it.state == "PENDING" && it.id != first.id }
            }
            val next = store.list(1000).first { it.songKey == target.uri.toString() && it.state == "PENDING" }
            created += next
            assertNotEquals(first.learningRevision, next.learningRevision)
            assertTrue(TimingRetryPolicy.changed(first.value, next.value))
            assertTrue(TimingValidation.read(context)!!.promoted)
            assertTrue(LyricTimingFeedback(context).examples().count { it.key in tracks.map { song -> song.uri.toString() } } >= 4)
            assertEquals(original.plainLyrics, AutomaticLyricsStore.get(context).read(target)!!.plainLyrics)
            assertNull(AutomaticLyricsStore.get(context).read(target)!!.syncedLyrics)
            assertEquals("REJECTED", store.get(first.id)!!.state)
        } finally {
            shell("dumpsys battery set level 100"); shell("dumpsys battery set ac 1")
            WorkManager.getInstance(context).cancelUniqueWork(TimingLearningWorker.NAME)
            for (uri in uris) {
                WorkManager.getInstance(context).cancelUniqueWork(LyricSuggestionWorker.name(uri.toString()))
                TimingRetryQueue(context).remove(uri.toString()); resolver.delete(uri, null, null)
            }
            for (proposal in created) {
                LyricTimingFeedback(context).discard(proposal.id)
                File(context.filesDir, "music_metadata/personalization/learning/proposals/${proposal.id}.json").delete()
            }
            store.saveModel(previousModel); store.mode(mode)
        }
    }
}
