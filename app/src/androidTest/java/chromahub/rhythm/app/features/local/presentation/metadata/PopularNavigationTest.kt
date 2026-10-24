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
class PopularNavigationTest {
    private val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private val originals = mutableMapOf<File, ByteArray?>()

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
                context.getSharedPreferences("installed_release_notes", 0).edit()
                    .putInt("seen", chromahub.rhythm.app.BuildConfig.VERSION_CODE).commit()
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
                    file.writeText(Gson().toJson(ChartSnapshot(provider, now, tracks)))
                }
            }

            override fun after() {
                shell("settings put system font_scale 1.0")
                originals.forEach { (file, data) ->
                    if (data == null) file.delete() else file.writeBytes(data)
                }
            }
        }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(preparation).around(activity)

    @Test
    fun popularTabKeepsPlayerNavigationAndSupportsFiltersAndDownloadActions() {
        // Use the real Android frame clock. An accelerated Compose test clock can starve
        // service callbacks while driving a complete activity on a software-only emulator.
        awaitNode("인기", description = true)
        click("보관함", description = true)
        awaitNode("라이브러리")
        capture("performance-library-floating.png")
        click("인기", description = true)
        awaitNode("함께 듣는 곡")
        awaitNode("인기 리스트")
        capture("performance-popular-floating.png")

        click("앨범")
        click("함께 듣는 앨범")
        awaitNode(label(R.string.discovery_clear_filter))
        awaitNode("함께 듣는 곡")
        assertNull(locate("새로운 인기곡"))
        capture("performance-popular-filter.png")
        click(label(R.string.discovery_clear_filter))
        awaitNode("새로운 인기곡")
        click(label(R.string.discovery_download_audio), description = true)
        click(label(R.string.discovery_direct))
        awaitNode(label(R.string.discovery_direct_url))
        capture("performance-popular-download.png")
        click(label(android.R.string.cancel))

        activity.scenario.onActivity {
            AppSettings.getInstance(context).setFloatingNavigationBar(false)
        }
        awaitNode("설정")
        capture("performance-popular-docked.png")
        shell("settings put system font_scale 1.3")
        val deadline = SystemClock.elapsedRealtime() + 60000
        var scale = 1f
        while (scale < 1.25f && SystemClock.elapsedRealtime() < deadline) {
            activity.scenario.onActivity { scale = it.resources.configuration.fontScale }
            SystemClock.sleep(100)
        }
        assertTrue("System font scale did not change", scale >= 1.25f)
        awaitNode("함께 듣는 곡")
        capture("performance-popular-large-font.png")
        awaitNode("인기 리스트")
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
                if (description) listOf(node.contentDescription)
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
        throw AssertionError("No visible accessibility node: $label")
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
