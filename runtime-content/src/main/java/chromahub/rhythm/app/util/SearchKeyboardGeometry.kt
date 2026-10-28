// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import kotlin.math.roundToInt

internal data class SearchKeyboardGeometry(val viewOrigin: Int, val keyboardTop: Int, val overlap: Int)

/** Coordinates before the platform's temporary focus pan, including already resized destinations. */
internal fun calculateSearchKeyboardGeometry(
    bottomInRoot: Float,
    viewTopOnScreen: Int,
    decorTopOnScreen: Int,
    decorTopInWindow: Int,
    decorHeight: Int,
    viewHeight: Int,
    visibleBottom: Int,
    explicitImeBottom: Int?,
): SearchKeyboardGeometry {
    val windowOrigin = decorTopOnScreen - decorTopInWindow
    val viewOrigin = viewTopOnScreen - decorTopInWindow
    val keyboardTop = explicitImeBottom?.let { minOf(visibleBottom, windowOrigin + decorHeight - it) }
        ?: visibleBottom
    return SearchKeyboardGeometry(viewOrigin, keyboardTop,
        (viewOrigin + bottomInRoot - keyboardTop).roundToInt().coerceIn(0, viewHeight))
}
