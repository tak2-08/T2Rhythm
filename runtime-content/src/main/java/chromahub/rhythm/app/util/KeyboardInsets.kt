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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

internal data class KeyboardOverlap(val bottomPadding: Dp, val visible: Boolean)

/** The part of this Compose view actually obscured by the IME, including on pre-R windows.
 * A resized view already ends above the keyboard; subtracting the full IME again would
 * shrink it twice. Read the visible frame in screen coordinates to handle both cases.
 */
@Composable
internal fun rememberKeyboardOverlap(bottomInWindow: Float): KeyboardOverlap {
    val view = LocalView.current
    var overlap by remember(view) { mutableIntStateOf(0) }
    var keyboardVisible by remember(view) { mutableStateOf(false) }
    DisposableEffect(view, bottomInWindow) {
        val frame = Rect()
        val screen = IntArray(2)
        val window = IntArray(2)
        var lastDiagnostic = 0L
        fun update() {
            val visible = ViewCompat.getRootWindowInsets(view)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            view.getWindowVisibleDisplayFrame(frame)
            view.getLocationOnScreen(screen)
            view.getLocationInWindow(window)
            keyboardVisible = visible
            // The Compose view can span the full window while this destination already
            // has resized constraints. Measure the destination, not the entire view.
            overlap = if (visible && frame.height() > 0)
                (bottomInWindow + screen[1] - window[1] - frame.bottom)
                    .roundToInt().coerceIn(0, view.height)
            else 0
            if (chromahub.rhythm.app.BuildConfig.DEBUG &&
                android.os.SystemClock.elapsedRealtime() - lastDiagnostic >= 1000) {
                lastDiagnostic = android.os.SystemClock.elapsedRealtime()
                android.util.Log.d("SearchKeyboardLayout",
                    "visible=$visible bottom=$bottomInWindow frame=$frame screen=${screen[1]} window=${window[1]} view=${view.height} overlap=$overlap")
            }
        }
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnGlobalLayoutListener { update() }
        observer.addOnGlobalLayoutListener(listener)
        update()
        onDispose {
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
            else view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }
    return KeyboardOverlap(with(LocalDensity.current) { overlap.toDp() }, keyboardVisible)
}
