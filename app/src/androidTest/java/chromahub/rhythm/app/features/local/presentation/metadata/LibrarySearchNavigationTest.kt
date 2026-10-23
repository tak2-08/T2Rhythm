// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.Manifest
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.rules.ActivityScenarioRule
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
    private val activity = ActivityScenarioRule(MainActivity::class.java)
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
    @get:Rule val rules: RuleChain = RuleChain.outerRule(preparation).around(activity)

    @Test
    fun koreanSearchFiltersAndRestorationUseTheRealAppNavigation() {
        awaitNode("보관함", description = true)
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
                    genre = "Folk",
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
        awaitNode("서른 즈음에")
        awaitNode("이등병의 편지")
        assertNull(locate("기억의 습작"))
        capture("library-search-genre-tags.png")
        enter("")
        click("FOLK")
        awaitNode("서른 즈음에")
        enter("편지")
        awaitNode("이등병의 편지")
        assertNull(locate("서른 즈음에"))
        capture("library-search-within-genre.png")
        click(label(R.string.genre_search_all))
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
        fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable) return node
            for (i in 0 until node.childCount) editable(node.getChild(i))?.let {
                return it
            }
            return null
        }
        val node = editable(instrumentation.uiAutomation.rootInActiveWindow)
        assertNotNull("Search field unavailable", node)
        assertTrue(
            node!!.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text,
                    )
                },
            )
        )
        instrumentation.uiAutomation.waitForIdle(100, 10000)
    }

    private fun label(resource: Int): String {
        // AppCompat applies the selected language to the activity, not the instrumentation context.
        var text = ""
        activity.scenario.onActivity { text = it.getString(resource) }
        return text
    }

    private fun locate(label: String, description: Boolean = false): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val values =
                if (description) listOf(node.contentDescription, node.text)
                else listOf(node.text, node.hintText)
            if (
                values.filterNotNull().any { value ->
                    value.toString().lineSequence().any { it.trim() == label }
                }
            )
                return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let {
                return it
            }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun awaitNode(label: String, description: Boolean = false): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 90000
        while (SystemClock.elapsedRealtime() < deadline) {
            locate(label, description)?.let {
                return it
            }
            SystemClock.sleep(100)
        }
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
        var node: AccessibilityNodeInfo? = awaitNode(label, description)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(
            "No clickable ancestor: $label",
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true,
        )
        instrumentation.uiAutomation.waitForIdle(100, 10000)
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
