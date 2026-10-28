// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

internal data class KeyboardOverlap(val bottomPadding: Dp, val visible: Boolean)

/** Keep IME measurement independent of ViewRoot's temporary pan of the Compose view. */
@Composable
internal fun rememberKeyboardOverlap(
    bottomInRoot: Float,
    inputBoundsInRoot: androidx.compose.ui.geometry.Rect?,
): KeyboardOverlap {
    val view = LocalView.current
    val latestBottom by rememberUpdatedState(bottomInRoot)
    val latestInput by rememberUpdatedState(inputBoundsInRoot)
    var overlap by remember(view) { mutableIntStateOf(0) }
    var keyboardVisible by remember(view) { mutableStateOf(false) }
    DisposableEffect(view) {
        val frame = Rect()
        val screen = IntArray(2)
        val rootScreen = IntArray(2)
        val rootWindow = IntArray(2)
        val inputRect = Rect()
        var lastDiagnostic = 0L
        fun update() {
            val insets = ViewCompat.getRootWindowInsets(view)
            val visible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
            val imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            view.getWindowVisibleDisplayFrame(frame)
            view.getLocationOnScreen(screen)
            val root = view.rootView
            root.getLocationOnScreen(rootScreen)
            root.getLocationInWindow(rootWindow)
            // Both locations include ViewRoot's pan. Their difference is the real window
            // origin; adding the pan again would apply a second, much larger IME padding.
            val geometry = calculateSearchKeyboardGeometry(
                bottomInRoot = latestBottom, viewTopOnScreen = screen[1],
                decorTopOnScreen = rootScreen[1], decorTopInWindow = rootWindow[1],
                decorHeight = root.height, viewHeight = view.height, visibleBottom = frame.bottom,
                explicitImeBottom = imeBottom.takeIf { android.os.Build.VERSION.SDK_INT >= 30 && visible && it > 0 },
            )
            val viewOrigin = geometry.viewOrigin
            val keyboardTop = geometry.keyboardTop
            keyboardVisible = visible
            overlap = if (visible && frame.height() > 0) geometry.overlap else 0

            // Compose-only placement need not resize the Android View. ViewRoot can retain
            // the pan it computed from the field's pre-IME focus rectangle. Once the current
            // row fits above the keyboard, ask it to reevaluate that actual rectangle. This
            // uses the normal public relocation API and leaves child list scrolling intact.
            val input = latestInput
            if (visible && rootWindow[1] < 0 && input != null &&
                input.height > 0 && input.top + viewOrigin >= frame.top - 1 &&
                input.bottom + viewOrigin <= keyboardTop + 1) {
                inputRect.set(input.left.roundToInt(), input.top.roundToInt(),
                    input.right.roundToInt(), input.bottom.roundToInt())
                view.requestRectangleOnScreen(inputRect, true)
            }
            if (chromahub.rhythm.app.BuildConfig.DEBUG &&
                android.os.SystemClock.elapsedRealtime() - lastDiagnostic >= 1000) {
                lastDiagnostic = android.os.SystemClock.elapsedRealtime()
                android.util.Log.d("SearchKeyboardLayout",
                    "visible=" + visible + " bottom=" + latestBottom + " frame=" + frame +
                        " ime=" + imeBottom + " top=" + keyboardTop + " origin=" + viewOrigin +
                        " pan=" + rootWindow[1] + " view=" + view.height + " overlap=" + overlap + " input=" + input)
            }
        }
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener { update(); true }
        observer.addOnPreDrawListener(listener)
        update()
        onDispose {
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            else view.viewTreeObserver.removeOnPreDrawListener(listener)
        }
    }
    return KeyboardOverlap(with(LocalDensity.current) { overlap.toDp() }, keyboardVisible)
}
