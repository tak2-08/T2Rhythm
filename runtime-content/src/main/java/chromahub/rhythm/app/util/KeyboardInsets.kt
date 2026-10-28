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
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

internal data class KeyboardOverlap(val bottomPadding: Dp, val visible: Boolean)

/** The part of the destination obscured by the IME. Legacy windows can already be resized;
 * modern search windows use adjustNothing so platform panning cannot duplicate our padding.
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
        val rootScreen = IntArray(2)
        var lastDiagnostic = 0L
        fun update() {
            val insets = ViewCompat.getRootWindowInsets(view)
            val visible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
            val imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            view.getWindowVisibleDisplayFrame(frame)
            view.getLocationOnScreen(screen)
            view.getLocationInWindow(window)
            val root = view.rootView
            root.getLocationOnScreen(rootScreen)
            // adjustNothing does not promise a resized visible display frame. On R+ the
            // explicit IME inset gives the actual keyboard edge without asking Android to pan.
            val keyboardTop = if (android.os.Build.VERSION.SDK_INT >= 30 && visible && imeBottom > 0)
                minOf(frame.bottom, rootScreen[1] + root.height - imeBottom)
            else frame.bottom
            keyboardVisible = visible
            // The Compose view can span the full window while this destination already
            // has resized constraints. Measure the destination, not the entire view.
            overlap = if (visible && frame.height() > 0)
                (bottomInWindow + screen[1] - window[1] - keyboardTop)
                    .roundToInt().coerceIn(0, view.height)
            else 0
            if (chromahub.rhythm.app.BuildConfig.DEBUG &&
                android.os.SystemClock.elapsedRealtime() - lastDiagnostic >= 1000) {
                lastDiagnostic = android.os.SystemClock.elapsedRealtime()
                android.util.Log.d("SearchKeyboardLayout",
                    "visible=$visible bottom=$bottomInWindow frame=$frame ime=$imeBottom top=$keyboardTop screen=${screen[1]} window=${window[1]} view=${view.height} overlap=$overlap mode=${view.context.findActivity()?.window?.attributes?.softInputMode} rootMode=${(root.layoutParams as? android.view.WindowManager.LayoutParams)?.softInputMode} scroll=${root.scrollY}")
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

/**
 * Search already fits its list and bottom controls above the keyboard. Child scroll containers
 * handle relocation first; the Android owner only needs to see the full destination viewport.
 * Forwarding the text field's old rectangle while IME padding is settling can make ViewRoot pan
 * the entire window, independently of softInputMode, and leave the search controls off screen.
 */
internal fun Modifier.relocateSearchWithinViewport(): Modifier = then(SearchViewportElement)

private data object SearchViewportElement : ModifierNodeElement<SearchViewportNode>() {
    override fun create() = SearchViewportNode()
    override fun update(node: SearchViewportNode) = Unit
    override fun InspectorInfo.inspectableProperties() { name = "relocateSearchWithinViewport" }
}

private class SearchViewportNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(
        childCoordinates: LayoutCoordinates,
        boundsProvider: () -> androidx.compose.ui.geometry.Rect?,
    ) {
        // Propagate our stable viewport, rather than the pre-IME position of a focused child.
        // Lazy lists below this node still receive and satisfy their own scrolling requests.
        bringIntoView()
    }
}
