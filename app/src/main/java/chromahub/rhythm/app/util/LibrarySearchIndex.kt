// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import java.text.Normalizer
import java.util.Locale

/** Screen-scoped, immutable index. Build and query on Default, never during composition. */
class LibrarySearchIndex<T>
private constructor(val source: List<T>, private val entries: List<Entry<T>>) {
    private data class Text(val plain: String, val initials: String)

    private data class Entry<T>(val value: T, val title: Text, val fields: Text)

    fun search(query: String, checkActive: () -> Unit = {}): List<T> {
        val raw = query.take(256).trim()
        if (raw.isEmpty()) return emptyList()
        val words = raw.split(WORDS).map(::normalize).filter(String::isNotEmpty)
        if (words.isEmpty()) return emptyList()
        val compact = normalize(raw)
        // Four stable buckets avoid sorting a large set of matches on each keystroke.
        val buckets = Array(4) { ArrayList<T>() }
        entries.forEachIndexed { index, entry ->
            if (index % 128 == 0) checkActive()
            if (words.all { matches(entry.fields, it) }) {
                val rank =
                    when {
                        entry.title.plain == compact || entry.title.initials == compact -> 0
                        entry.title.plain.startsWith(compact) ||
                            entry.title.initials.startsWith(compact) -> 1
                        words.all { matches(entry.title, it) } -> 2
                        else -> 3
                    }
                buckets[rank].add(entry.value)
            }
        }
        checkActive()
        return buckets.flatMap { it }
    }

    companion object {
        private const val INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        private val WORDS = Regex("[\\s\\p{Z}]+")

        private fun normalize(value: String): String = buildString {
            Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).forEach { c ->
                if (!c.isWhitespace() && !Character.isSpaceChar(c)) {
                    append(if (c in '\u1100'..'\u1112') INITIALS[c - '\u1100'] else c)
                }
            }
        }

        private fun text(value: String): Text {
            val plain = normalize(value)
            if (plain.none { it in '가'..'힣' }) return Text(plain, plain)
            val initials =
                buildString(plain.length) {
                    plain.forEach { c ->
                        append(if (c in '가'..'힣') INITIALS[(c - '가') / 588] else c)
                    }
                }
            return Text(plain, initials)
        }

        private fun matches(text: Text, word: String): Boolean =
            text.plain.contains(word) || text.initials.contains(word)

        fun <T> build(
            source: List<T>,
            title: (T) -> String,
            fields: (T) -> Sequence<String>,
            checkActive: () -> Unit = {},
        ): LibrarySearchIndex<T> {
            val entries =
                source.mapIndexed { index, value ->
                    if (index % 128 == 0) checkActive()
                    val name = text(title(value))
                    // A separator prevents a substring from crossing unrelated metadata fields.
                    val searchable = text(fields(value).joinToString("\u0001"))
                    Entry(value, name, if (searchable == name) name else searchable)
                }
            checkActive()
            return LibrarySearchIndex(source, entries)
        }
    }
}
