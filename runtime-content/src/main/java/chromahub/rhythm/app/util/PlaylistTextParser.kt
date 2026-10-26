// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

internal data class PlaylistTextEntry(val path: String, val title: String = "")

/** Tolerate BOMs, indentation, CRLF and case-insensitive PLS keys from other players. */
internal object PlaylistTextParser {
    fun m3u(content: String): List<PlaylistTextEntry> = buildList {
        var title = ""
        content.removePrefix("\uFEFF").lineSequence().map(String::trim).forEach { line ->
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF:", ignoreCase = true) -> title = line.substringAfter(',', "").trim()
                line.startsWith("#") -> Unit
                else -> { add(PlaylistTextEntry(line, title)); title = "" }
            }
        }
    }

    private val plsKey = Regex("^(file|title)([0-9]+)$", RegexOption.IGNORE_CASE)
    fun pls(content: String): List<PlaylistTextEntry> {
        val paths = sortedMapOf<Int, String>()
        val titles = mutableMapOf<Int, String>()
        content.removePrefix("\uFEFF").lineSequence().forEach { line ->
            val separator = line.indexOf('=')
            if (separator < 0) return@forEach
            val match = plsKey.matchEntire(line.substring(0, separator).trim()) ?: return@forEach
            val number = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
            val value = line.substring(separator + 1).trim()
            if (match.groupValues[1].equals("file", true)) paths[number] = value else titles[number] = value
        }
        return paths.filterValues(String::isNotBlank).map { (number, path) -> PlaylistTextEntry(path, titles[number].orEmpty()) }
    }
}
