// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.ai

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AiSettingsFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editingModelPreservesStoredKeyAndCancellingKeyReplacementKeepsConnection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = AiStore(context)
        val previous = store.read()
        try {
            store.save(AiSettings(true, listOf(AiConnection("ui-test", AiProvider.CEREBRAS, "old-model", "private-test-key", false)), true, false))
            compose.setContent { MaterialTheme { AiSettingsScreen {} } }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("old-model").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("연결 수정").performScrollTo().performClick()
            compose.onNodeWithText("모델 이름").performTextReplacement("new-model")
            compose.onNodeWithText("private-test-key").assertDoesNotExist()
            compose.onNodeWithText("저장", useUnmergedTree = true).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("API 연결 수정").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("new-model").assertExists()
            val saved = store.read()
            assertEquals("private-test-key", saved.connections.single().key)
            assertEquals("ui-test", saved.connections.single().id)
            assertFalse(saved.connections.single().selected)
            assertTrue(saved.enabled); assertTrue(saved.rotate); assertFalse(saved.fallback)
            compose.onNodeWithText("연결 테스트").assertExists()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "ai-connection-edit.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            compose.onNodeWithText("연결 수정").performScrollTo().performClick()
            compose.onNodeWithText("새 API 키 (빈칸이면 기존 키 유지)").performTextReplacement("replacement-not-saved")
            compose.onNodeWithText("취소", useUnmergedTree = true).performClick()
            compose.waitForIdle()
            assertEquals("private-test-key", store.read().connections.single().key)
            assertEquals("new-model", store.read().connections.single().model)
        } finally { store.save(previous) }
    }
}
