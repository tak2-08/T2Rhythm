// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.experience.*
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import java.io.File
import java.util.Locale
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.Before

class PersonalInsightsScreensTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val context get() = compose.activity
    @Before
    @Suppress("DEPRECATION")
    fun configureActivityAndDialogLocale() {
        // Dialogs create their own Compose root from the Activity's resources. A LocalContext
        // override alone only translates inline content, leaving dialog buttons in English.
        compose.runOnUiThread {
            Locale.setDefault(Locale.KOREAN)
            listOf(context.resources, base.resources).forEach { resources ->
                val config = Configuration(resources.configuration).apply { setLocale(Locale.KOREAN) }
                resources.updateConfiguration(config, resources.displayMetrics)
            }
        }
    }
    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(base.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun downloadTabsExposeTitleAndArtistSearch() {
        val ctx = context
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx, LocalConfiguration provides ctx.resources.configuration) { MaterialTheme { DownloadCenter {} } } }
        compose.onNodeWithText(ctx.getString(R.string.discovery_songs)).performClick()
        compose.onNodeWithText(ctx.getString(R.string.download_song_name)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.download_artist_name)).assertIsDisplayed()
        capture("insights-download-search.png")
        compose.onNodeWithText(ctx.getString(R.string.discovery_albums)).performClick()
        compose.onNodeWithText(ctx.getString(R.string.download_album_name)).assertIsDisplayed()
    }
    @Test fun algorithmTrainingIsSeparateFromServiceHistory() {
        val ctx = context
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx, LocalConfiguration provides ctx.resources.configuration) { MaterialTheme { AlgorithmManagement(training = true) {} } } }
        compose.onNodeWithText(ctx.getString(R.string.algorithm_training_title)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.algorithm_learning)).performClick()
        compose.onNodeWithText(ctx.getString(R.string.algorithm_limits)).assertExists()
        capture("insights-algorithms.png")
    }
    @Test fun listeningReportShowsCalendarPeriodsAndRealLedgerTotals() {
        val ctx = context
        val ledger = ListeningLedger.get(base)
        val id = "report-ui-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        ledger.record(ListeningRecord(id, id, "segment", id, id, "밤의 산책", "리듬", "도시의 빛", "ambient", now - 180_000, now, 180_000, 200_000, 0, 180_000, 1f, "pause"))
        try {
            compose.setContent {
                val vm: MusicViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                CompositionLocalProvider(LocalContext provides ctx, LocalConfiguration provides ctx.resources.configuration) {
                    MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { ListeningReportPanel(vm) } }
                }
            }
            compose.waitUntil(30000) { compose.onAllNodesWithText(ctx.getString(R.string.report_number_one, "밤의 산책")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(ctx.getString(R.string.report_quarter)).assertIsDisplayed()
            capture("insights-listening-report.png")
        } finally { ledger.writableDatabase.delete("records", "id=?", arrayOf(id)) }
    }
    @Test fun highlightMixPlaysLocalExcerptsAndCreatesNoExtraAudioFile() {
        val ctx = context
        val root = File(base.cacheDir, "mix-ui-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val tracks = (0..1).map { i ->
                val file = File(root, "$i.m4a")
                InstrumentationRegistry.getInstrumentation().context.assets.open("audio-test.m4a").use { input -> file.outputStream().use { input.copyTo(it) } }
                val retriever = android.media.MediaMetadataRetriever()
                val duration = try { retriever.setDataSource(file.path); retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() } finally { retriever.release() }
                chromahub.rhythm.app.shared.data.model.Song("mix$i", "음악 $i", "가수", "앨범", duration = duration, uri = android.net.Uri.fromFile(file))
            }
            compose.setContent {
                val vm: MusicViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                CompositionLocalProvider(LocalContext provides ctx, LocalConfiguration provides ctx.resources.configuration) { MaterialTheme { ReportMixDialog(tracks, vm) {} } }
            }
            compose.onNodeWithText(ctx.getString(R.string.download_listen)).performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText(ctx.getString(R.string.report_mix_stop)).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(60000) { compose.onAllNodesWithText(ctx.getString(R.string.download_listen)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(ctx.getString(R.string.discovery_operation_failed)).assertDoesNotExist()
            org.junit.Assert.assertEquals(2, root.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }
}
