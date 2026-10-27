// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** The part of this Compose view actually obscured by the IME, including on pre-R windows.
 * A resized view already ends above the keyboard; subtracting the full IME again would
 * shrink it twice. Read the visible frame in screen coordinates to handle both cases.
 */
@Composable
internal fun rememberKeyboardOverlap(): Dp {
    val view = LocalView.current
    var overlap by remember(view) { mutableIntStateOf(0) }
    DisposableEffect(view) {
        val frame = Rect()
        val position = IntArray(2)
        fun update() {
            val visible = ViewCompat.getRootWindowInsets(view)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            view.getWindowVisibleDisplayFrame(frame)
            view.getLocationOnScreen(position)
            overlap = if (visible && frame.height() > 0)
                (position[1] + view.height - frame.bottom).coerceIn(0, view.height)
            else 0
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
    return with(LocalDensity.current) { overlap.toDp() }
}
