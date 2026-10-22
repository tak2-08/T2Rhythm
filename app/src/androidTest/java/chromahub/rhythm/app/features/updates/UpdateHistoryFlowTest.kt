// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.updates

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class UpdateHistoryFlowTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "update-ui-${UUID.randomUUID()}"
    private val file get() = File(base.noBackupFilesDir, "$namespace.json")
    @After fun cleanup() { file.delete(); File(file.path + ".bak").delete() }
    private fun show(scale: Float = 1f): UpdateJournal {
        val records = JSONArray()
        repeat(2) { index -> records.put(JSONObject().put("id", "test-$index").put("version", "5.5.499.${1324-index}-gh").put("code", 554991324-index)
            .put("time", 1_792_700_000_000L-index*1000).put("stage", if (index == 0) "INSTALL_REQUESTED" else "INSTALLED").put("removed", index == 1)
            .put("notes", (1..80).joinToString("\n") { "변경 내용 $it · 음악과 업데이트 기능을 다듬었습니다." })) }
        file.writeText(JSONObject().put("records", records).toString()); val journal = UpdateJournal.isolated(base, namespace)
        compose.setContent {
            val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.KOREAN) }
            @Suppress("DEPRECATION") compose.activity.resources.updateConfiguration(config, compose.activity.resources.displayMetrics)
            CompositionLocalProvider(LocalContext provides compose.activity, LocalConfiguration provides config,
                LocalDensity provides Density(compose.activity.resources.displayMetrics.density, scale)) {
                MaterialTheme { UpdateHistoryPanel(journal) }
            }
        }
        compose.onNodeWithText("업데이트 내역 관리").performClick()
        return journal
    }
    private fun screenshot(name: String) {
        compose.waitForIdle(); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); android.os.SystemClock.sleep(200)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(base.getExternalFilesDir(null), "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    @Test fun historyShowsRealStagesAndSupportsIndividualAndWholeHistoryRemoval() {
        val journal = show()
        compose.onNodeWithText("설치 화면 열림 · 완료 확인 대기").assertExists()
        compose.onNodeWithText("설치 APK 정리 완료").assertExists(); screenshot("updates-history")
        compose.onAllNodesWithContentDescription("기록 삭제").onFirst().performClick()
        compose.onNodeWithText("기록만 삭제합니다.", substring = true).assertExists()
        compose.onAllNodesWithText("기록 삭제", substring = false).onLast().performClick()
        compose.waitUntil(5000) { journal.records.value.size == 1 }
        compose.onNodeWithContentDescription("전체 기록 지우기").performClick()
        compose.onAllNodesWithText("기록 삭제", substring = false).onLast().performClick()
        compose.waitUntil(5000) { journal.records.value.isEmpty() }; compose.onNodeWithText("아직 기록된 업데이트가 없습니다.").assertExists()
    }
    @Test fun largeTextHistoryAndReleaseNotesRemainScrollableAndClosable() {
        show(1.6f)
        compose.onNodeWithText("5.5.499.1324-gh").performClick()
        compose.onNodeWithText("변경 내용 1 ·", substring = true).performTouchInput { swipeUp() }
        compose.onNodeWithText("완료", substring = false).assertIsDisplayed(); screenshot("updates-history-large-text")
        compose.onNodeWithText("완료", substring = false).performClick()
        compose.onNodeWithContentDescription("완료").assertIsDisplayed()
    }
}
