// SPDX-License-Identifier: GPL-3.0-or-later
package kr.co.metadata.crawler

object CatalogQueries {
    fun withoutParentheses(value: String): String {
        var depth = 0
        return buildString {
            value.forEach { c -> when (c) {
                '(', '（' -> { depth++; if (depth == 1) append(' ') }
                ')', '）' -> if (depth > 0) depth-- else append(c)
                else -> if (depth == 0) append(c)
            } }
        }.replace(Regex("\\s+"), " ").trim()
    }
    fun variants(value: String): List<String> = (listOf(value.trim(), withoutParentheses(value)) +
        Regex("[（(]([^()（）]+)[）)]").findAll(value).map { it.groupValues[1].trim() }.toList())
        .map { it.replace(Regex("\\s+"), " ").take(300) }.filter(String::isNotBlank)
        .distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(4)

    fun queries(name: String, artist: String? = null): List<String> {
        val artists = artist?.let(::variants).orEmpty().ifEmpty { listOf("") }
        return variants(name).flatMap { title -> artists.map { "$title $it".trim() } }.distinct().take(9)
    }
}
