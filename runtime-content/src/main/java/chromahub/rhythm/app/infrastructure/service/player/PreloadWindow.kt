// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.infrastructure.service.player

/** Bound work by the warm window, independent of library/queue size, including shuffle/repeat. */
internal object PreloadWindow {
    fun upcoming(count: Int, current: Int, limit: Int, next: (Int) -> Int): List<Int> {
        if (current !in 0 until count) return emptyList()
        val result = mutableListOf<Int>()
        var index = current
        repeat(limit.coerceIn(0, 2)) {
            index = next(index)
            if (index !in 0 until count || index == current || index in result) return result
            result += index
        }
        return result
    }
}
