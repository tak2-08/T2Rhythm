// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.testing

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Failure-only evidence; never changes the assertion or application state. */
internal fun snapshotTestUi(activity: Activity, name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
    val description = StringBuilder()
    instrumentation.runOnMainSync {
        val view = activity.window.decorView
        val insets = view.rootWindowInsets?.let { androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it, view) }
        description.appendLine("activity=${activity.resources.configuration}")
        description.appendLine("target=${instrumentation.targetContext.resources.configuration}")
        description.appendLine("window=${view.width}x${view.height}, ime=${insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())}, softInput=${activity.window.attributes.softInputMode}")
    }
    fun describe(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 16) return
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        description.appendLine("${" ".repeat(depth)}${node.className} text=${node.text} desc=${node.contentDescription} visible=${node.isVisibleToUser} bounds=$bounds")
        repeat(node.childCount) { index -> node.getChild(index)?.let { describe(it, depth + 1) } }
    }
    instrumentation.uiAutomation.rootInActiveWindow?.let { describe(it, 0) }
    File(directory, "$name-ui.txt").writeText(description.toString())
    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
