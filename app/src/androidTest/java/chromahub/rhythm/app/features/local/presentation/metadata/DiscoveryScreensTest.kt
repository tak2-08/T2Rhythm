// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.discovery.*
import chromahub.rhythm.app.features.local.data.video.*
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.Song
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import kr.co.metadata.crawler.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DiscoveryScreensTest {
    @get:Rule val compose = createComposeRule()
    private val context by lazy {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        base.createConfigurationContext(
            android.content.res.Configuration(base.resources.configuration).apply {
                setLocale(java.util.Locale.KOREAN)
            }
        )
    }

    @Test
    fun searchChartsAndReviewUseOneNativeFlowWithDismissibleSourceSelection() {
        val keyword = java.util.concurrent.atomic.AtomicReference<String>()
        val song =
            Song(
                "discovery-ui",
                "검토할 나의 노래",
                "나의 가수",
                "나의 앨범",
                duration = 200000,
                uri = Uri.parse("content://tests/discovery-ui"),
                genre = "Pop",
            )
        val backups = mutableMapOf<File, ByteArray?>()
        for (provider in listOf(Provider.BUGS_PC, Provider.MELON, Provider.GENIE)) {
            val file = File(context.filesDir, "music_metadata/charts/${provider.name}.json")
            backups[file] = file.takeIf { it.exists() }?.readBytes()
            file.parentFile!!.mkdirs()
            val tracks =
                listOf(
                    ChartTrack(
                        1,
                        provider,
                        "100",
                        "인기 음악 테스트",
                        "아티스트 테스트",
                        "인기 앨범 테스트",
                        "https://example.org/track",
                        albumId = "200",
                        artistId = "300",
                    )
                )
            file.writeText(
                Gson().toJson(ChartSnapshot(provider, System.currentTimeMillis(), tracks))
            )
        }
        val store = EnrichmentStore(context)
        val proposal = store.proposal(song, "GENRE", "R&B", .82, "local_neighbors", 4)
        store.propose(proposal)
        try {
            compose.setContent {
                val activityResults =
                    checkNotNull(androidx.activity.compose.LocalActivityResultRegistryOwner.current)
                CompositionLocalProvider(
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides
                        activityResults,
                    LocalContext provides context,
                    LocalConfiguration provides context.resources.configuration,
                ) {
                    MaterialTheme {
                        val vm: MusicViewModel =
                            androidx.lifecycle.viewmodel.compose.viewModel(
                                factory =
                                    androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(
                                        context.applicationContext as Application
                                    )
                            )
                        Column(Modifier.fillMaxWidth().padding(24.dp)) {
                            SearchDiscoveryPanel(listOf(song), emptyList(), vm) { keyword.set(it) }
                        }
                    }
                }
            }
            compose.onNodeWithText(context.getString(R.string.discovery_charts)).assertIsDisplayed()
            compose.waitUntil(15000) {
                compose.onAllNodesWithText(song.artist).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(song.artist).performClick()
            assertEquals(song.artist, keyword.get())
            capture("discovery-search.png")
            compose.onNodeWithText(context.getString(R.string.discovery_charts)).performClick()
            compose.waitUntil(15000) {
                compose.onAllNodesWithText("인기 음악 테스트").fetchSemanticsNodes().isNotEmpty()
            }
            capture("discovery-charts.png")
            compose.onNodeWithText(context.getString(R.string.discovery_albums)).performClick()
            compose.onNodeWithText("인기 앨범 테스트").performClick()
            compose
                .onNodeWithContentDescription(context.getString(R.string.discovery_download_audio))
                .performClick()
            compose.waitUntil(10000) {
                compose
                    .onAllNodesWithText(context.getString(R.string.discovery_audio_source_hint))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("SoundCloud").assertIsEnabled().performClick()
            compose.onNodeWithText(context.getString(R.string.discovery_direct)).performClick()
            compose.onNodeWithText(context.getString(R.string.discovery_direct_url)).assertExists()
            capture("discovery-source-picker.png")
            compose
                .onNodeWithText(context.getString(android.R.string.cancel))
                .assertIsEnabled()
                .performClick()
            compose.onNodeWithText("인기 음악 테스트").assertExists()
            // Back dismisses the sheet while keeping search state.
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                )
            compose.waitForIdle()
            compose
                .onNodeWithText(context.getString(R.string.discovery_review_short))
                .performClick()
            compose.waitUntil(15000) {
                compose.onAllNodesWithText(song.title).fetchSemanticsNodes().isNotEmpty()
            }
            capture("discovery-review.png")
            compose.onNodeWithTag("proposal:${proposal.id}").performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.discovery_reject)).performClick()
            compose.waitUntil(10000) { store.get(proposal.id)?.state == "REJECTED" }
            compose.onNodeWithText(context.getString(R.string.discovery_history)).performClick()
            compose.onNodeWithTag("proposal:${proposal.id}").performScrollTo().assertIsDisplayed()
        } finally {
            backups.forEach { (file, data) ->
                if (data == null) file.delete() else file.writeBytes(data)
            }
            File(
                    context.filesDir,
                    "music_metadata/personalization/learning/proposals/${proposal.id}.json",
                )
                .delete()
        }
    }

    @Test
    fun downloadManagerShowsProgressCancellationHistoryAndSize() {
        val active =
            DownloadRecord(
                UUID.randomUUID().toString(),
                "VIDEO",
                "ui-test",
                "abcdefghijk",
                "다운로드 중인 뮤직비디오",
                bytes = 12 * 1024 * 1024,
                totalBytes = 40 * 1024 * 1024,
                state = "RUNNING",
            )
        val done =
            DownloadRecord(
                UUID.randomUUID().toString(),
                "AUDIO",
                "ui-audio",
                "source",
                "저장한 음악",
                "아티스트",
                state = "SUCCEEDED",
                bytes = 4 * 1024 * 1024,
                totalBytes = 4 * 1024 * 1024,
                fileBytes = 4 * 1024 * 1024,
            )
        DownloadRegistry.save(context, active)
        DownloadRegistry.save(context, done)
        try {
            compose.setContent {
                val activityResults =
                    checkNotNull(androidx.activity.compose.LocalActivityResultRegistryOwner.current)
                CompositionLocalProvider(
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides
                        activityResults,
                    LocalContext provides context,
                    LocalConfiguration provides context.resources.configuration,
                ) {
                    MaterialTheme { DownloadCenter {} }
                }
            }
            compose.waitUntil(10000) {
                compose.onAllNodesWithText(active.title).fetchSemanticsNodes().isNotEmpty()
            }
            capture("discovery-downloads.png")
            compose.onNodeWithTag("download:${active.id}").performScrollTo()
            compose
                .onNode(
                    hasContentDescription(context.getString(R.string.discovery_cancel_download)) and
                        hasAnyAncestor(hasTestTag("download:${active.id}"))
                )
                .performClick()
            compose.waitUntil(10000) {
                DownloadRegistry.get(context, active.id)?.state == "CANCELLED"
            }
            compose.waitUntil(10000) {
                compose
                    .onAllNodesWithText(context.getString(R.string.discovery_download_cancelled))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("download:${done.id}").performScrollTo()
            compose.onNodeWithText(done.title).assertExists()
        } finally {
            listOf(active, done).forEach {
                File(context.filesDir, "music_metadata/downloads/${it.id}.json").delete()
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
