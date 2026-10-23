// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.Manifest
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.R
import chromahub.rhythm.app.activities.MainActivity
import chromahub.rhythm.app.features.local.data.discovery.ChartSnapshot
import chromahub.rhythm.app.features.local.presentation.components.settings.LanguageHelper
import chromahub.rhythm.app.shared.data.model.AppSettings
import com.google.gson.Gson
import java.io.File
import kr.co.metadata.crawler.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Real MainActivity/navigation, not a separate copy of its navigation controls. */
@RunWith(AndroidJUnit4::class)
class PopularPlaybackNavigationTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val activity get() = compose.activityRule
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private var publishedUri: android.net.Uri? = null
    private var recordFile: File? = null
    private val originals = mutableMapOf<File, ByteArray?>()

    private fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command)
            )
            .use { it.readBytes() }

    private val preparation =
        object : ExternalResource() {
            override fun before() {
                // Only this package's synthetic published WAV fixtures, never user media.
                if (android.os.Build.VERSION.SDK_INT >= 29)
                    context.contentResolver.delete(
                        android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        "title = ? AND artist = ? AND owner_package_name = ? AND mime_type = ?",
                        arrayOf("함께 듣는 곡", "함께 듣는 가수", context.packageName, "audio/wav"),
                    )
                val permission =
                    if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                    else Manifest.permission.READ_EXTERNAL_STORAGE
                shell("pm grant ${context.packageName} $permission")
                val settings = AppSettings.getInstance(context)
                settings.setOnboardingCompleted(true)
                settings.setDefaultScreen("home")
                settings.setFloatingNavigationBar(true)
                settings.setInitialStreamingRoute(null)
                instrumentation.runOnMainSync { LanguageHelper.setLanguage(context, "ko") }
                val now = System.currentTimeMillis()
                PopularRanking.weights.keys.forEach { provider ->
                    val file = File(context.filesDir, "music_metadata/charts/${provider.name}.json")
                    originals[file] = file.takeIf { it.exists() }?.readBytes()
                    file.parentFile!!.mkdirs()
                    val tracks =
                        mutableListOf(
                            ChartTrack(
                                60,
                                provider,
                                "shared",
                                "함께 듣는 곡",
                                "함께 듣는 가수",
                                "함께 듣는 앨범",
                                "https://example.com/shared",
                            )
                        )
                    if (provider == Provider.MELON)
                        tracks +=
                            ChartTrack(
                                1,
                                provider,
                                "melon",
                                "새로운 인기곡",
                                "새로운 가수",
                                "새로운 앨범",
                                "https://example.com/melon",
                            )
                    tracks +=
                        ChartTrack(
                            80,
                            provider,
                            "extra",
                            "바로 들을 인기곡",
                            "새 가수",
                            "새 앨범",
                            "https://example.com/extra",
                        )
                    file.writeText(Gson().toJson(ChartSnapshot(provider, now, tracks)))
                }
            }

            override fun after() {
                publishedUri?.let { context.contentResolver.delete(it, null, null) }
                recordFile?.delete()
                shell("settings put system font_scale 1.0")
                originals.forEach { (file, data) ->
                    if (data == null) file.delete() else file.writeBytes(data)
                }
            }
        }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(preparation).around(compose)

    @Test
    fun popularSongsPlayExistingAudioAndExposeStreamingAndPersistentDownloadStatus() {
        activity.scenario.onActivity { LanguageHelper.setLanguage(it, "ko") }
        awaitNode("인기", description = true)
        click("홈", description = true)
        lateinit var vm: chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
        activity.scenario.onActivity { screen ->
            vm =
                androidx.lifecycle
                    .ViewModelProvider(screen)[
                        chromahub.rhythm.app.features.local.presentation.viewmodel
                                .MusicViewModel::class
                            .java]
        }
        val ready = SystemClock.elapsedRealtime() + 90_000
        while (
            (!vm.isInitialized.value || vm.isMediaScanning.value) &&
                SystemClock.elapsedRealtime() < ready
        ) SystemClock.sleep(100)
        assertTrue(vm.isInitialized.value)
        // Use the real library rather than inserting fictional IDs that a MediaStore rescan
        // removes.
        val candidates =
            vm.songs.value.filter {
                it.uri.scheme == "content" &&
                    !CatalogIdentity.missing(it.artist) &&
                    !CatalogIdentity.missing(it.album)
            }
        val song =
            candidates.firstOrNull { it.title == "Archive test track" } ?: candidates.firstOrNull()
        org.junit.Assume.assumeNotNull(song)
        val local = requireNotNull(song)
        activity.scenario.onActivity { vm.pauseMusic() }
        val paused = SystemClock.elapsedRealtime() + 15_000
        while (vm.isPlaying.value && SystemClock.elapsedRealtime() < paused) SystemClock.sleep(100)
        val now = System.currentTimeMillis()
        PopularRanking.weights.keys.forEach { provider ->
            val file = File(context.filesDir, "music_metadata/charts/${provider.name}.json")
            val tracks =
                mutableListOf(
                    ChartTrack(
                        60,
                        provider,
                        "shared",
                        local.title,
                        local.artist,
                        local.album,
                        "https://example.com/shared",
                    ),
                    ChartTrack(
                        80,
                        provider,
                        "extra",
                        "바로 들을 인기곡",
                        "새 가수",
                        "새 앨범",
                        "https://example.com/extra",
                    ),
                )
            if (provider == Provider.MELON)
                tracks +=
                    ChartTrack(
                        1,
                        provider,
                        "melon",
                        "새로운 인기곡",
                        "새로운 가수",
                        "새로운 앨범",
                        "https://example.com/melon",
                    )
            file.writeText(Gson().toJson(ChartSnapshot(provider, now, tracks)))
        }
        val record =
            chromahub.rhythm.app.features.local.data.video.DownloadRecord(
                java.util.UUID.randomUUID().toString(),
                "AUDIO",
                "popular-ui",
                "popular-ui",
                "새로운 인기곡",
                "새로운 가수",
                state = "RUNNING",
                totalBytes = 100,
                bytes = 40,
                album = "새로운 앨범",
            )
        chromahub.rhythm.app.features.local.data.video.DownloadRegistry.save(context, record)
        recordFile = File(context.filesDir, "music_metadata/downloads/${record.id}.json")
        click("인기", description = true)
        awaitNode(local.title)
        awaitNode(label(R.string.popular_play_local), description = true)
        scrollTo("새로운 인기곡")
        awaitNode(label(R.string.popular_downloading), description = true)
        capture("popular-playback-rows.png")
        scrollTo(local.title)
        click(local.title)
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (locate(label(R.string.dialog_clear_and_play)) != null)
                click(label(R.string.dialog_clear_and_play))
            if (vm.currentSong.value?.id == local.id && vm.isPlaying.value) break
            SystemClock.sleep(100)
        }
        assertEquals(local.id, vm.currentSong.value?.id)
        assertTrue("Matched local track did not start playing", vm.isPlaying.value)
        assertNull(locate(label(R.string.discovery_source_search)))
        click("앨범")
        scrollTo("새로운 앨범")
        awaitNode(label(R.string.popular_downloading), description = true)
        capture("popular-playback-albums.png")
        scrollTo("새 앨범")
        click("새 앨범")
        click(label(R.string.discovery_download_audio), description = true)
        click(label(R.string.discovery_direct))
        awaitNode(label(R.string.popular_stream))
        capture("popular-playback-source.png")
        click(label(android.R.string.cancel))
        shell("settings put system font_scale 1.3")
        SystemClock.sleep(1500)
        scrollTo("바로 들을 인기곡")
        awaitNode("바로 들을 인기곡")
        capture("popular-playback-large-font.png")
        activity.scenario.onActivity { screen ->
            androidx.lifecycle
                .ViewModelProvider(screen)[
                    chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel::class
                        .java]
                .clearQueue()
        }
    }

    private fun scrollTo(label: String) {
        val list = compose.onAllNodes(hasScrollToIndexAction()).onFirst()
        list.performScrollToNode(hasText(label))
        val node = compose.onAllNodes(hasText(label) and hasAnyAncestor(hasScrollAction())).onFirst()
        // Bring the row above the persistent mini player before injecting an actual touch.
        val shift = node.getUnclippedBoundsInRoot().top - (list.getUnclippedBoundsInRoot().top + 80.dp)
        if (shift > 0.dp) list.performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, with(compose.density) { shift.toPx() })
        }
        compose.waitForIdle()
        node.assertIsDisplayed()
    }

    private fun label(resource: Int): String {
        // AppCompat applies the selected language to the activity, not the instrumentation context.
        var text = ""
        activity.scenario.onActivity { text = it.getString(resource) }
        return text
    }

    private fun matching(label: String, description: Boolean) =
        if (description) hasContentDescription(label) or hasText(label) else hasText(label)

    private fun locate(label: String, description: Boolean = false): SemanticsNodeInteraction? {
        val nodes = compose.onAllNodes(matching(label, description), useUnmergedTree = true)
        return if (nodes.fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()) nodes.onFirst() else null
    }

    private fun awaitNode(label: String, description: Boolean = false): SemanticsNodeInteraction {
        val found = runCatching { compose.waitUntil(90_000) { locate(label, description) != null } }.isSuccess
        if (found) return locate(label, description)!!.assertIsDisplayed()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "popular-playback-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        throw AssertionError("No visible accessibility node: $label")
    }

    private fun click(label: String, description: Boolean = false) {
        awaitNode(label, description)
        compose.onAllNodes(matching(label, description)).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        instrumentation.uiAutomation.waitForIdle(100, 10000)
        val root = instrumentation.uiAutomation.rootInActiveWindow
        assertNotNull("No active app window", root)
        assertEquals(
            "A system dialog obscures the app",
            context.packageName,
            root!!.packageName.toString(),
        )
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
