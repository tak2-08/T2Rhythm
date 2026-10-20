/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.util

/**
 * Utility methods for parsing and matching multi-genre metadata.
 *
 * Supports comma, semicolon, and newline-separated values, e.g.:
 * - "Mahur, Shur"
 * - "Mahur;Shur"
 * - "Mahur\nShur"
 */
object GenreUtils {
    private val genreSplitRegex = Regex("[,;\\n\\r]+")

    /** Missing tags are never classes, including legacy placeholders already in a model. */
    fun isKnownGenre(value: String?): Boolean {
        val label = value?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
        return label.any { it.isLetterOrDigit() } && label !in setOf(
            "unknown", "<unknown>", "(unknown)", "null", "none", "n/a", "na", "undefined",
            "unspecified", "not set", "알 수 없음", "알수없음", "미지정", "장르 없음", "없음",
        )
    }

    fun normalizedGenres(rawGenre: String?): Set<String> =
        splitGenres(rawGenre).toSet()

    /** Use real library labels, using one uppercase spelling for all case variants. */
    fun availableGenres(tags: Iterable<String?>): List<String> {
        val seen = mutableSetOf<String>()
        return tags.flatMap(::splitGenres).filter { it.length <= 80 &&
            seen.add(it.lowercase(java.util.Locale.ROOT)) }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    /** An empty selection means all; otherwise match any of the selected genres. */
    fun matchesAny(rawGenre: String?, selected: Set<String>): Boolean {
        val targets = selected.flatMap { normalizedGenres(it) }.toSet()
        return targets.isEmpty() || normalizedGenres(rawGenre).any { it in targets }
    }

    fun splitGenres(rawGenre: String?): List<String> {
        if (rawGenre.isNullOrBlank()) return emptyList()

        // Some tags use NUL separators; normalize them to newline before splitting.
        val normalized = rawGenre.replace('\u0000', '\n')
        val seen = LinkedHashSet<String>()
        val genres = mutableListOf<String>()

        normalized
            .split(genreSplitRegex)
            .map { it.trim().uppercase(java.util.Locale.ROOT) }
            .filter(::isKnownGenre)
            .forEach { genre ->
                val key = genre.lowercase(java.util.Locale.ROOT)
                if (seen.add(key)) {
                    genres.add(genre)
                }
            }

        return genres
    }

    fun matchesGenre(rawGenre: String?, genre: String): Boolean {
        val target = genre.trim()
        if (target.isEmpty()) return false
        return splitGenres(rawGenre).any { it.equals(target, ignoreCase = true) }
    }

    fun matchesGenreQuery(rawGenre: String?, query: String): Boolean {
        val normalizedQuery = query.trim().lowercase(java.util.Locale.ROOT)
        if (normalizedQuery.isEmpty()) return false

        return splitGenres(rawGenre).any { genre ->
            val normalizedGenre = genre.lowercase(java.util.Locale.ROOT)
            normalizedGenre == normalizedQuery ||
                normalizedGenre.startsWith(normalizedQuery) ||
                normalizedGenre.contains(normalizedQuery)
        }
    }
}
