// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import java.util.Locale

/** Only real library labels are results. Aliases aid discovery without rewriting file metadata. */
internal class GenreBrowseIndex<T> private constructor(
    private val byGenre: Map<String, List<T>>,
    private val neighbors: Map<String, Set<String>>,
    private val index: LibrarySearchIndex<String>,
) {
    val genres: List<String> get() = index.source
    fun search(query: String): List<String> {
        val matches = if (query.isBlank()) genres else (index.search(query) + index.search(canonical(query.take(256)))).distinct()
        val (available, vocabulary) = matches.partition { count(it) > 0 }
        return available + vocabulary
    }
    fun songs(genre: String?): List<T> = if (genre == null) emptyList() else byGenre[genre].orEmpty()
    fun count(genre: String): Int = songs(genre).size

    fun children(genre: String): List<String> {
        val parent = canonical(genre)
        return genres.filter { it != genre && canonical(it).let { label -> label != parent && label.contains(parent) } }
    }

    fun related(genre: String): List<String> {
        val children = children(genre).toSet()
        val selectedFamilies = families(genre)
        return genres.filter { candidate -> candidate != genre && candidate !in children &&
            (candidate in neighbors[genre].orEmpty() || families(candidate).any { it in selectedFamilies }) }
    }

    companion object {
        // Common Korean/English spellings; no synthetic genres or inferred song assignments.
        private val aliases = listOf(
            listOf("rock", "록", "락"), listOf("pop", "팝"), listOf("jazz", "재즈"),
            listOf("hip hop", "hip-hop", "hiphop", "힙합"), listOf("folk", "포크"),
            listOf("metal", "메탈"), listOf("punk", "펑크"), listOf("blues", "블루스"),
            listOf("classical", "클래식"), listOf("electronic", "일렉트로닉"),
            listOf("indie", "인디"), listOf("hard", "하드"), listOf("soul", "소울"),
            listOf("country", "컨트리"), listOf("reggae", "레게"), listOf("ballad", "발라드"),
            listOf("r&b", "rnb", "알앤비", "알엔비"), listOf("k-pop", "kpop", "케이팝"),
            listOf("dance", "댄스"), listOf("ambient", "앰비언트"), listOf("soundtrack", "ost", "사운드트랙"),
            listOf("new age", "newage", "뉴에이지"), listOf("house", "하우스"), listOf("techno", "테크노"),
        )
        private fun canonical(label: String): String {
            var value = label.lowercase(Locale.ROOT)
            aliases.forEach { group -> group.drop(1).forEach { value = value.replace(it, group.first()) } }
            return value.filter { it.isLetterOrDigit() }
        }
        private fun families(label: String): Set<String> {
            val name = canonical(label)
            return aliases.map { it.first().filter(Char::isLetterOrDigit) }.filter { name.contains(it) }.toSet()
        }

        fun <T> build(songs: List<T>, genreOf: (T) -> String?, checkActive: () -> Unit = {}, knownGenres: List<String> = emptyList()): GenreBrowseIndex<T> {
            val grouped = sortedMapOf<String, MutableList<T>>()
            val neighbors = mutableMapOf<String, MutableSet<String>>()
            songs.forEachIndexed { i, song ->
                if (i % 128 == 0) checkActive()
                val labels = GenreUtils.splitGenres(genreOf(song))
                labels.forEach { genre ->
                    grouped.getOrPut(genre) { mutableListOf() }.add(song)
                    neighbors.getOrPut(genre) { mutableSetOf() }.addAll(labels.filter { it != genre })
                }
            }
            // A translated vocabulary label must not shadow an equivalent real library tag with zero tracks.
            val existing = grouped.keys.map(::canonical).toMutableSet()
            knownGenres.flatMap(GenreUtils::splitGenres).forEach {
                if (existing.add(canonical(it))) grouped.getOrPut(it) { mutableListOf() }
            }
            val labels = grouped.keys.filter { grouped[it]?.isNotEmpty() == true } + grouped.keys.filter { grouped[it].isNullOrEmpty() }
            return GenreBrowseIndex(grouped, neighbors, LibrarySearchIndex.build(labels, { it }, { label ->
                val family = families(label)
                sequenceOf(label, canonical(label)) + aliases.filter { it.first().filter(Char::isLetterOrDigit) in family }
                    .asSequence().flatten()
            }, checkActive))
        }
    }
}
