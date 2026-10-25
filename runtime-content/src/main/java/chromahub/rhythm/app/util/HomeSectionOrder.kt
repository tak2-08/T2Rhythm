// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

/** Visibility has its own preferences; an old saved order must never hide an editor entry. */
internal object HomeSectionOrder {
    val defaults = listOf("RECOMMENDED", "RECENTLY_PLAYED", "DISCOVER", "RECENTLY_ADDED",
        "ARTISTS", "NEW_RELEASES", "STATS", "RHYTHM_GUARD")

    fun normalize(saved: List<String>): List<String> = (saved.map { when (it.trim()) {
        "TOP_ARTISTS", "FAVORITE_ARTISTS" -> "ARTISTS"
        else -> it.trim()
    } }.filter { it in defaults } + defaults).distinct()
}
