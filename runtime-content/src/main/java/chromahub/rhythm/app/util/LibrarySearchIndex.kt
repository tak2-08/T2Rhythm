// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import java.text.Normalizer
import java.util.Locale

/** Screen-scoped, immutable index. Build and query on Default, never during composition. */
class LibrarySearchIndex<T>
private constructor(val source: List<T>, private val entries: List<Entry<T>>) {
    private data class Text(val plain: String, val initials: String)

    private data class Query(val plain: String, val initialPattern: String?)

    private data class Entry<T>(val value: T, val title: Text, val fields: Text)

    fun search(query: String, checkActive: () -> Unit = {}): List<T> {
        val raw = query.take(256).trim()
        if (raw.isEmpty()) return emptyList()
        val words = raw.split(WORDS).map(::query).filter { it.plain.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val compact = query(raw)
        // Four stable buckets avoid sorting a large set of matches on each keystroke.
        val buckets = Array(4) { ArrayList<T>() }
        entries.forEachIndexed { index, entry ->
            if (index % 128 == 0) checkActive()
            if (words.all { matches(entry.fields, it) }) {
                val rank =
                    when {
                        entry.title.plain.length == compact.plain.length &&
                            matchesAt(entry.title, compact, 0) -> 0
                        matchesAt(entry.title, compact, 0) -> 1
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
            return Text(plain, initials(plain))
        }

        private fun initials(plain: String): String {
            if (plain.none { it in '가'..'힣' }) return plain
            return buildString(plain.length) {
                plain.forEach { c ->
                    append(if (c in '가'..'힣') INITIALS[(c - '가') / 588] else c)
                }
            }
        }

        private fun query(value: String): Query {
            val plain = normalize(value)
            // Prepare once per token, not once per library item. Ordinary queries keep indexOf.
            return Query(plain, if (plain.any { it in INITIALS }) initials(plain) else null)
        }

        private fun matchesAt(text: Text, word: Query, offset: Int): Boolean {
            if (text.plain.startsWith(word.plain, offset)) return true
            val pattern = word.initialPattern ?: return false
            if (pattern == word.plain) return text.initials.startsWith(pattern, offset)
            if (offset + word.plain.length > text.plain.length) return false
            return word.plain.indices.all { i ->
                val c = word.plain[i]
                // Only an initial can stand in for a syllable; completed syllables stay exact.
                c == text.plain[offset + i] || c == text.initials[offset + i]
            }
        }

        private fun matches(text: Text, word: Query): Boolean {
            if (text.plain.contains(word.plain)) return true
            val pattern = word.initialPattern ?: return false
            if (pattern == word.plain) return text.initials.contains(pattern)
            // Initials narrow the candidates; verify the completed syllables at each position.
            var offset = text.initials.indexOf(pattern)
            while (offset >= 0) {
                if (matchesAt(text, word, offset)) return true
                offset = text.initials.indexOf(pattern, offset + 1)
            }
            return false
        }

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
