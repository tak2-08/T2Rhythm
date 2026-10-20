// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.R
import chromahub.rhythm.app.activities.MainActivity
import chromahub.rhythm.app.features.local.data.experience.*
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.*
import chromahub.rhythm.app.shared.presentation.screens.settings.SettingsScreen
import java.io.File
import java.util.Locale
import java.util.UUID
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Reproducible use of the shipped components, including large text and a short landscape window. */
class UsabilityAuditTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val lyrics = LyricsData("첫 번째 가사의 소리를 들으며 시작과 끝을 맞춥니다\n두 번째 줄도 순서대로 직접 맞춰 보세요\n마지막 줄은 끝나는 소리까지 확인하세요", null)
    private fun song() = Song("audit-${UUID.randomUUID()}", "화면과 조작을 확인하는 노래", "리듬", "사용성 점검", duration = 180_000,
        uri = Uri.fromFile(File(base.cacheDir, "audit-${UUID.randomUUID()}.wav")))

    @Before @Suppress("DEPRECATION") fun setup() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(base.packageName, Manifest.permission.READ_EXTERNAL_STORAGE)
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        compose.runOnUiThread {
            Locale.setDefault(Locale.KOREAN)
            listOf(base.resources, compose.activity.resources).forEach { resources ->
                resources.updateConfiguration(Configuration(resources.configuration).apply { setLocale(Locale.KOREAN) }, resources.displayMetrics)
            }
        }
    }
    private fun content(fontScale: Float = 1f, block: @Composable () -> Unit) = compose.setContent {
        val density = LocalDensity.current
        MaterialTheme {
            CompositionLocalProvider(LocalContext provides compose.activity,
                LocalConfiguration provides compose.activity.resources.configuration,
                LocalDensity provides Density(density.density, fontScale)) { block() }
        }
    }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        android.os.SystemClock.sleep(700)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(base.getExternalFilesDir(null), name).outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun manualTimingWithLargeText() {
        val track = song()
        content(1.6f) { val vm: MusicViewModel = viewModel()
            LyricTimingEditor(track, lyrics, vm, playbackOverride = TimingPlayback({ 1234 }, { false }, {}, {}, {})) {} }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("timing-mark-start").fetchSemanticsNodes().isNotEmpty() }
        capture("audit-01-timing-large-text.png")
    }
    @Test fun manualTimingInLandscape() {
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        val track = song()
        content { val vm: MusicViewModel = viewModel()
            LyricTimingEditor(track, lyrics, vm, playbackOverride = TimingPlayback({ 1234 }, { false }, {}, {}, {})) {} }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("timing-mark-start").fetchSemanticsNodes().isNotEmpty() }
        capture("audit-02-timing-landscape.png")
    }
    @Test fun downloadSearchWithLargeTextAndKeyboard() {
        content(1.6f) { DownloadCenter(initialTab = 0) {} }
        compose.onNodeWithText(base.getString(R.string.download_song_name)).performClick()
        compose.onNode(hasSetTextAction() and hasText(base.getString(R.string.download_song_name))).performTextInput("노래 제목")
        capture("audit-03-download-search-large-text.png")
    }
    @Test fun findingAlgorithmManagementThroughSettingsSearch() {
        content { SettingsScreen(onBackClick = {}, onNavigateTo = {}) }
        compose.onNode(hasSetTextAction()).performTextInput("알고리즘")
        capture("audit-04-settings-search.png")
    }
    @Test fun listeningReportWithLargeText() {
        val ledger = ListeningLedger.get(base)
        val id = "audit-report-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        ledger.record(ListeningRecord(id, id, "segment", id, id, "오래 기억하고 싶은 노래", "리듬", "사용성 점검", "ambient", now - 180_000, now, 180_000, 200_000, 0, 180_000, 1f, "pause"))
        try {
            content(1.6f) { val vm: MusicViewModel = viewModel()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { ListeningReportPanel(vm) } }
            compose.waitUntil(30_000) { compose.onAllNodesWithText(base.getString(R.string.report_number_one, "오래 기억하고 싶은 노래")).fetchSemanticsNodes().isNotEmpty() }
            capture("audit-05-listening-report-large-text.png")
        } finally { ledger.writableDatabase.delete("records", "id=?", arrayOf(id)) }
    }
    @Test fun actualAppHomeToSettings() {
        val settings = AppSettings.getInstance(base)
        settings.setOnboardingCompleted(true)
        settings.setHasShownBetaPopup(true)
        settings.setUpdatesEnabled(false)
        settings.setDefaultScreen("home")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var settingsDescription = ""
            scenario.onActivity { settingsDescription = it.getString(R.string.home_settings_cd) }
            compose.waitUntil(45_000) { compose.onAllNodesWithContentDescription(settingsDescription).fetchSemanticsNodes().isNotEmpty() }
            capture("audit-06-actual-home.png")
            compose.onNodeWithContentDescription(settingsDescription).performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            capture("audit-07-actual-settings.png")
        }
    }
}
