// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.Manifest
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.R
import chromahub.rhythm.app.activities.MainActivity
import chromahub.rhythm.app.features.local.presentation.components.settings.LanguageHelper
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Real MainActivity/navigation, not a separate copy of its navigation controls. */
@RunWith(AndroidJUnit4::class)
class LibrarySearchNavigationTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val activity get() = compose.activityRule
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command)
            )
            .use { it.readBytes() }

    private val preparation =
        object : ExternalResource() {
            override fun before() {
                val permission =
                    if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                    else Manifest.permission.READ_EXTERNAL_STORAGE
                shell("pm grant ${context.packageName} $permission")
                val settings = AppSettings.getInstance(context)
                settings.setOnboardingCompleted(true)
                context.getSharedPreferences("installed_release_notes", 0).edit().putInt("seen", chromahub.rhythm.app.BuildConfig.VERSION_CODE).commit()
                settings.setDefaultScreen("home")
                settings.setFloatingNavigationBar(true)
                settings.setInitialStreamingRoute(null)
                instrumentation.runOnMainSync { LanguageHelper.setLanguage(context, "ko") }
                settings.setShowKeyboardOnSearchOpen(false)
            }

            override fun after() {
                shell("settings put system font_scale 1.0")
                AppSettings.getInstance(context).setShowKeyboardOnSearchOpen(true)
            }
        }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(preparation).around(compose)

    @Test
    fun koreanSearchFiltersAndRestorationUseTheRealAppNavigation() {
        // On API 33+, AppCompat's first delegate can sync its empty legacy locale store.
        // Apply the requested language after that delegate exists, as the settings UI does.
        activity.scenario.onActivity { LanguageHelper.setLanguage(it, "ko") }
        awaitNode("보관함", description = true)
        capture("home-before-library-navigation.png")
        click("보관함", description = true)
        awaitNode("라이브러리")
        val songs =
            listOf(
                Song(
                    "search-fixture-a",
                    "서른 즈음에",
                    "김광석",
                    "다시 듣기",
                    duration = 180000,
                    uri = android.net.Uri.parse("content://test/search-a"),
                    genre = "Folk;Folk Rock",
                    bitrate = 192000,
                    codec = "MP3",
                ),
                Song(
                    "search-fixture-b",
                    "이등병의 편지",
                    "김광석",
                    "다시 듣기",
                    duration = 240000,
                    uri = android.net.Uri.parse("content://test/search-b"),
                    genre = "Folk;Pop",
                    bitrate = 192000,
                    codec = "MP3",
                ),
                Song(
                    "search-fixture-c",
                    "기억의 습작",
                    "전람회",
                    "전람회",
                    duration = 300000,
                    uri = android.net.Uri.parse("content://test/search-c"),
                    genre = "Pop",
                    bitrate = 192000,
                    codec = "MP3",
                ),
            )
        lateinit var vm: MusicViewModel
        activity.scenario.onActivity {
            vm = androidx.lifecycle.ViewModelProvider(it)[MusicViewModel::class.java]
        }
        // The real startup performs a delayed MediaStore staleness check. Let it inspect the
        // real library before injecting this screen-only fixture with a different track count.
        instrumentation.waitForIdleSync()
        val deadline = SystemClock.elapsedRealtime() + 60000
        while (
            (!vm.isInitialized.value || vm.isMediaScanning.value || vm.isLibraryRefreshing.value) &&
                SystemClock.elapsedRealtime() < deadline
        ) SystemClock.sleep(100)
        assertTrue(vm.isInitialized.value)
        assertFalse(vm.isLibraryRefreshing.value)
        // The delayed check is scheduled after initialization, which can be slow on cold starts.
        SystemClock.sleep(5000)
        activity.scenario.onActivity {
            // Keep this search fixture independent of the previous test's persistent mini player.
            vm.clearQueue()
            seed(vm, "_songs", songs)
            seed(
                vm,
                "_albums",
                listOf(
                    Album("search-album", "다시 듣기", "김광석", songs = songs.take(2)),
                    Album("search-album-b", "전람회", "전람회", songs = songs.takeLast(1)),
                ),
            )
            seed(
                vm,
                "_artists",
                listOf(
                    Artist("search-artist", "김광석", songs = songs.take(2)),
                    Artist("search-artist-b", "전람회", songs = songs.takeLast(1)),
                ),
            )
        }
        awaitNode("서른 즈음에")
        awaitNode("손실 압축")
        awaitNode("표준 음질")
        capture("library-search-filters.png")
        click("검색", description = true)
        awaitNode(label(R.string.local_search_hint))
        awaitNode("김광석")
        capture("library-search-discover.png")
        enter("ㅅㄹㅈㅇㅇ")
        awaitNode("서른 즈음에")
        assertNull(locate("이등병의 편지"))
        capture("library-search-initials.png")
        enter("김광석 편지")
        awaitNode("이등병의 편지")
        assertNull(locate("서른 즈음에"))
        capture("library-search-tokens.png")
        click(label(R.string.genre_search_tab))
        enter("folk")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("genre-result:FOLK").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("genre-result:FOLK").assertIsDisplayed()
        compose.onNodeWithTag("genre-result:FOLK ROCK").assertIsDisplayed()
        assertNull(locate("서른 즈음에"))
        assertNull(locate("이등병의 편지"))
        assertNull(locate("기억의 습작"))
        capture("library-search-genre-tags.png")
        compose.runOnUiThread {
            androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.onNodeWithTag("genre-result:FOLK").performClick()
        awaitSelectedGenre("FOLK")
        awaitNode(label(R.string.genre_browse_children))
        compose.onNodeWithTag("genre-result:FOLK ROCK").assertIsDisplayed().performClick()
        awaitSelectedGenre("FOLK ROCK")
        compose.onNodeWithTag("genre-open-songs").performClick()
        awaitNode("서른 즈음에")
        assertNull(locate("이등병의 편지"))
        capture("library-search-genre-songs.png")
        androidx.test.espresso.Espresso.pressBack()
        val songsTitle = label(R.string.settings_tab_songs)
        runCatching { compose.waitUntil(10_000) { compose.onAllNodesWithText(songsTitle).fetchSemanticsNodes().isEmpty() } }
            .onFailure { chromahub.rhythm.app.testing.snapshotTestUi(compose.activity, "library-search-back-failure") }.getOrThrow()
        awaitSelectedGenre("FOLK ROCK")
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        awaitSelectedGenre("FOLK")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("genre-result:POP"))
        compose.onNodeWithTag("genre-result:POP").assertIsDisplayed()
        capture("library-search-related-genres.png")
        compose.onNodeWithTag("genre-open-songs").performScrollTo().performClick()
        awaitNode("이등병의 편지")
        awaitNode("서른 즈음에")
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        click(label(R.string.genre_search_all))
        // Switching tabs restores the original general search, rather than reusing a genre query.
        awaitNode("이등병의 편지")
        assertNull(locate("서른 즈음에"))
        enter("존재하지않는노래")
        awaitNode(label(R.string.no_results_found))
        assertNull(locate("Connect to a streaming service first"))
        activity.scenario.recreate()
        awaitNode(label(R.string.no_results_found))
        assertNotNull(locate("존재하지않는노래"))
        shell("settings put system font_scale 1.3")
        SystemClock.sleep(1500)
        awaitNode(label(R.string.no_results_found))
        capture("library-search-large-font.png")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> seed(vm: MusicViewModel, field: String, value: T) {
        val flow =
            MusicViewModel::class.java.getDeclaredField(field).apply { isAccessible = true }.get(vm)
                as kotlinx.coroutines.flow.MutableStateFlow<T>
        flow.value = value
    }

    private fun enter(text: String) {
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement(text)
        compose.waitForIdle()
    }

    private fun awaitSelectedGenre(genre: String) {
        // Window insets finish on Android's clock after IME dismissal, outside Compose's clock.
        val node = compose.onNode(hasText(genre) and isSelected())
        val visible = runCatching {
            compose.waitUntil(10_000) { runCatching { node.assertIsDisplayed() }.isSuccess }
        }.isSuccess
        if (!visible) capture("library-search-selected-genre-failure.png")
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("Selected genre chip must not cover the screen", bounds.bottom - bounds.top <= 56.dp)
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
        // Compose controls coroutine time. Advance its clock while waiting for debounced
        // searches even when a nonblank-to-nonblank edit starts no UI transition.
        val found = runCatching { compose.waitUntil(90_000) { locate(label, description) != null } }.isSuccess
        if (found) return locate(label, description)!!
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "library-search-failure.png")
                .outputStream()
                .use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        var state = ""
        activity.scenario.onActivity { screen ->
            val vm = androidx.lifecycle.ViewModelProvider(screen)[MusicViewModel::class.java]
            state =
                "songs=" +
                    vm.songs.value.map { it.id } +
                    ", filtered=" +
                    vm.filteredSongs.value.map { it.id }
        }
        throw AssertionError("No visible accessibility node: $label; $state")
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
