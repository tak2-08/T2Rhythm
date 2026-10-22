// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.Manifest
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.experience.*
import chromahub.rhythm.app.features.local.data.metadata.*
import chromahub.rhythm.app.features.local.di.LocalMusicModule
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.Song
import java.io.File
import java.time.*
import java.util.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class ListeningRecapFlowTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ledger get() = ListeningLedger.get(base)
    private val token = UUID.randomUUID().toString()
    private val ids = mutableListOf<String>()
    private val files = mutableListOf<File>()
    private var integrations = false
    @Before @Suppress("DEPRECATION") fun setup() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(base.packageName,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
        integrations = chromahub.rhythm.app.shared.data.model.AppSettings.getInstance(base).integrationsEnabled.value
        chromahub.rhythm.app.shared.data.model.AppSettings.getInstance(base).setIntegrationsEnabled(false)
        compose.runOnUiThread {
            Locale.setDefault(Locale.KOREAN)
            listOf(base.resources, compose.activity.resources).forEach { r -> r.updateConfiguration(Configuration(r.configuration).apply { setLocale(Locale.KOREAN); fontScale = 1f }, r.displayMetrics) }
        }
    }
    @After @Suppress("DEPRECATION") fun cleanup() {
        ids.forEach { ledger.writableDatabase.delete("records", "id=?", arrayOf(it)) }; ledger.invalidate()
        files.forEach(File::delete)
        compose.runOnUiThread { listOf(base.resources, compose.activity.resources).forEach { r -> r.updateConfiguration(Configuration(r.configuration).apply { fontScale = 1f }, r.displayMetrics) } }
        chromahub.rhythm.app.shared.data.model.AppSettings.getInstance(base).setIntegrationsEnabled(integrations)
    }
    private fun record(song: Song, started: Long, ended: Long, listened: Long = ended - started, play: String = UUID.randomUUID().toString()) {
        val id = "recap-$token-${ids.size}"; ids += id
        ledger.record(ListeningRecord(id, play, "segment", song.id, song.uri.toString(), song.title, song.artist, song.album,
            song.genre, started, ended, listened, song.duration, 0, listened, 1f, "pause"))
    }
    private fun song(i: Int = 0) = Song("recap-$token-$i", listOf("달빛 아래 걷는 밤", "잠깐의 여름", "우리가 남긴 빛")[i],
        if (i == 2) "노을" else "밤의 정원", "우리의 계절", duration = 180_000, uri = Uri.parse("file:///recap-$token-$i.m4a"), genre = if (i == 2) "R&B" else "INDIE, POP")
    @Test fun cacheInvalidatesAfterNewHistoryAndDailyTotalsHandleMidnight() {
        val zone = ZoneId.of("Asia/Seoul")
        val window = ReportPeriod.WEEK.window(LocalDate.of(2002, 2, 6), zone = zone)
        val midnight = window.first.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val first = song(); val play = "same-play-$token"
        record(first, midnight - 1000, midnight + 1000, 1001, play)
        val a = ListeningReports.read(ledger, window, window.endMs)
        assertSame(a, ListeningReports.read(ledger, window, window.endMs))
        assertEquals(1001L, a.listenedMs); assertEquals(1, a.plays)
        assertEquals(listOf(500L, 501L), a.days.take(2).map { it.listenedMs })
        assertEquals(a.listenedMs, a.days.sumOf { it.listenedMs })
        record(first, midnight + 1000, midnight + 3000, 1000, play)
        val b = ListeningReports.read(ledger, window, window.endMs)
        assertNotSame(a, b); assertEquals(2001L, b.listenedMs); assertEquals(1, b.plays)
        assertEquals(2001L, b.genres.sumOf { it.second })
    }
    @Test fun changedLibraryTagsStayConnectedAndAmbiguousMissingFilesAreNotGuessed() {
        val window = ReportPeriod.WEEK.window(LocalDate.of(2002, 2, 6))
        val first = song(); val track = ReportTrack(first.uri.toString(), first.id, "옛 제목", "옛 가수", "옛 앨범", 600_000, 4, "ROCK")
        val report = ListeningReport(window, 600_000, 4, 1, listOf(track), emptyList())
        val projection = ReportLibraryProjection(listOf(first))
        assertEquals(first, projection.resolve(track))
        val updated = projection.project(report)
        assertEquals(first.title, updated.tracks.single().title); assertEquals(first.artist, updated.artists.single().name)
        assertEquals(setOf("INDIE", "POP"), updated.genres.map { it.first }.toSet())
        val removed = track.copy(key = "missing", songId = "missing", title = first.title, artist = first.artist, album = "missing")
        val duplicate = first.copy(id = "duplicate", uri = Uri.parse("file:///duplicate.m4a"), album = "다른 앨범")
        assertNull(ReportLibraryProjection(listOf(first, duplicate)).resolve(removed))
        assertNull(ReportLibraryProjection(emptyList()).resolve(track))
    }
    private fun seed(): List<Song> {
        val window = ReportPeriod.WEEK.window(LocalDate.now())
        val totalDays = java.time.temporal.ChronoUnit.DAYS.between(window.first, LocalDate.now()).toInt()
        val tracks = (0..2).map { index ->
            val file = File(base.cacheDir, "recap-$token-$index.m4a"); files += file
            InstrumentationRegistry.getInstrumentation().context.assets.open("audio-test.m4a").use { input -> file.outputStream().use { input.copyTo(it) } }
            val art = File(base.cacheDir, "recap-$token-$index.png"); files += art
            Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888).also { image ->
                image.eraseColor(intArrayOf(0xff386d75.toInt(),0xffb77765.toInt(),0xff6855a0.toInt())[index])
                val canvas = android.graphics.Canvas(image); val paint = android.graphics.Paint().apply { color = 0xffead9a4.toInt() }
                canvas.drawCircle(100f,65f,36f,paint)
                art.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
            }
            song(index).copy(uri = Uri.fromFile(file), path = file.path, artworkUri = Uri.fromFile(art))
        }
        for (day in 0..totalDays) tracks.forEachIndexed { i, track ->
            val start = window.first.plusDays(day.toLong()).atStartOfDay(window.zone).toInstant().toEpochMilli()
            repeat(4 - i) { play -> record(track, start + play * 180_000, start + (play + 1) * 180_000) }
        }
        tracks.forEach { track -> record(track, window.startMs - 86_400_000, window.startMs - 86_400_000 + 180_000) }
        runBlocking { LocalMusicModule.provideMusicRepository(base).persistEditedSongs(tracks) }
        return tracks
    }
    @Composable private fun report(vm: MusicViewModel, read: suspend (ListeningLedger, ReportWindow) -> ListeningReport = { l,w -> ListeningReports.read(l,w) }) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalContext provides compose.activity, LocalConfiguration provides compose.activity.resources.configuration,
            LocalDensity provides Density(density.density, compose.activity.resources.configuration.fontScale)) {
            MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { ListeningReportPanel(vm, read) } }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle(); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); android.os.SystemClock.sleep(200)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(base.getExternalFilesDir(null), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
    }
    @Test fun nativeReportShowsHighlightsAndSavesPlayableTracksAsAPlaylist() {
        val tracks = seed(); lateinit var vm: MusicViewModel
        compose.setContent { vm = androidx.lifecycle.viewmodel.compose.viewModel(); report(vm) }
        compose.waitUntil(30_000) { compose.onAllNodesWithText(base.getString(R.string.report_number_one, tracks[0].title)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("report-save").fetchSemanticsNodes().singleOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false }
        capture("recap-overview.png")
        compose.onNodeWithTag("report-save").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("나의 음악 이야기-$token")
        compose.onAllNodesWithText(base.getString(R.string.report_save_playlist)).onLast().performClick()
        compose.waitUntil(10_000) { vm.playlists.value.any { it.name == "나의 음악 이야기-$token" } }
        assertEquals(tracks.map { it.uri }.toSet(), vm.playlists.value.single { it.name == "나의 음악 이야기-$token" }.songs.map { it.uri }.toSet())
        compose.onNodeWithTag("report-timeline").performScrollTo()
        capture("recap-highlights.png")
        compose.onNodeWithText(base.getString(R.string.report_genres)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(base.getString(R.string.report_month)).performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("report-hero").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test @Suppress("DEPRECATION") fun largeTextCanReachTimelineAndMethodologyWithoutClippingControls() {
        seed()
        compose.runOnUiThread { listOf(base.resources, compose.activity.resources).forEach { r -> r.updateConfiguration(Configuration(r.configuration).apply { fontScale = 1.6f },r.displayMetrics) } }
        compose.setContent { val vm: MusicViewModel = androidx.lifecycle.viewmodel.compose.viewModel(); report(vm) }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("report-hero").fetchSemanticsNodes().isNotEmpty() }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(base.getString(R.string.report_title)).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().layoutInput.density.fontScale >= 1.59f)
        capture("recap-large-text.png")
        compose.onNodeWithTag("report-timeline").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(base.getString(R.string.report_how)).performScrollTo().performClick()
        compose.onNodeWithText(base.getString(R.string.report_method)).assertExists()
        compose.onNodeWithText(base.getString(android.R.string.ok)).assertIsDisplayed().performClick()
    }
    @Test fun emptyReportKeepsNavigationAndExplainsHowToStart() {
        compose.setContent { val vm: MusicViewModel = androidx.lifecycle.viewmodel.compose.viewModel(); report(vm) { _,w -> ListeningReport(w,0,0,0,emptyList(),emptyList()) } }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("report-empty").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("report-play").assertDoesNotExist()
        compose.onNodeWithText(base.getString(R.string.report_empty_detail)).performScrollTo().assertIsDisplayed()
        capture("recap-empty.png")
        compose.onNodeWithText(base.getString(R.string.report_quarter)).performScrollTo().performClick()
    }
}
