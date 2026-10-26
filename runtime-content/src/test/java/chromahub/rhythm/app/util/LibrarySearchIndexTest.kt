// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class LibrarySearchIndexTest {
    private data class Item(val title: String, val artist: String = "", val genre: String = "")

    private fun index(vararg items: Item) =
        LibrarySearchIndex.build(
            items.toList(),
            { it.title },
            { sequenceOf(it.title, it.artist, it.genre) },
        )

    @Test
    fun koreanInitialsAndArtistTitleTokensWorkTogether() {
        val song = Item("서른 즈음에", "김광석", "Folk")
        val catalog = index(song, Item("서른 즈음에", "다른 가수"))
        assertEquals(listOf(song), catalog.search("ㄱㄱㅅ ㅅㄹㅈㅇㅇ"))
        assertEquals(listOf(song), catalog.search("김광석 즈음"))
        assertEquals(listOf(song), catalog.search("김광석 Folk"))
    }

    @Test
    fun mixedSyllablesAndInitialsMatchWithinEachToken() {
        val song = Item("서른 즈음에", "김광석", "Folk")
        val catalog = index(song, Item("서른 즈음에", "김광수"), Item("사랑 즈음에", "김광석"))
        assertEquals(listOf(song), catalog.search("김ㄱ석 서른ㅈㅇㅇ"))
        assertEquals(listOf(song), catalog.search("ㄱ광ㅅ 서ㄹ즈ㅇ에 Folk"))
        assertTrue(catalog.search("김ㄱ석 서른ㅈ아ㅇ").isEmpty())
    }

    @Test
    fun mixedQueryKeepsLatinDigitsAndPunctuationLiteral() {
        val song = Item("봄날 (Live 2026)")
        val catalog = index(song, Item("밤낮 (Live 2026)"), Item("봄날 [Live 2026]"))
        assertEquals(listOf(song), catalog.search("봄ㄴ(ＬＩＶＥ２０２６)"))
        assertTrue(catalog.search("봄ㄴ(live2025)").isEmpty())
        assertTrue(catalog.search("봄ㄴ.*").isEmpty())
    }

    @Test
    fun mixedQuerySupportsCanonicalJamoAndEveryModernInitial() {
        val song = Item("서른 즈음에")
        val query = Normalizer.normalize("서른", Normalizer.Form.NFD) + "\u110c\u110b\u110b"
        assertEquals(listOf(song), index(song).search(query))
        val initials = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        initials.forEachIndexed { i, initial ->
            val syllable = ('가'.code + i * 588).toChar()
            val item = Item("노래${syllable}끝")
            assertEquals(initial.toString(), listOf(item), index(item).search("노래${initial}끝"))
        }
        assertTrue(index(Item("까치")).search("가ㅊ").isEmpty())
    }

    @Test
    fun mixedQueryUsesTitleRelevanceAndStableLibraryOrder() {
        val metadata = Item("다른 노래", "서른 즈음에")
        val contains = Item("다시 서른 즈음에")
        val prefix = Item("서른 즈음에 (Live)")
        val exact = Item("서른 즈음에")
        val otherPrefix = Item("서른 즈음에 (Acoustic)")
        val catalog = index(metadata, contains, prefix, exact, otherPrefix)
        listOf("서른ㅈㅇㅇ", "ㅅㄹㅈㅇㅇ", "서른즈음에").forEach { query ->
            assertEquals(
                query, listOf(exact, prefix, otherPrefix, contains, metadata), catalog.search(query)
            )
        }
    }

    @Test
    fun mixedQueryChecksLaterCandidatesWithoutLosingCompletedSyllables() {
        val song = Item("사랑 서른 즈음에")
        assertEquals(listOf(song), index(song).search("ㅅ른"))
        assertTrue(index(Item("사랑 즈음에")).search("서ㄹ").isEmpty())
    }

    @Test
    fun mixedQueryCannotCrossMetadataBoundariesOrIgnoreMissingWords() {
        val song = Item("서른", "즈음에")
        val catalog = index(song)
        assertTrue(catalog.search("서른ㅈㅇㅇ").isEmpty())
        assertTrue(catalog.search("서ㄹ missing").isEmpty())
        assertEquals(listOf(song), catalog.search("서ㄹ 즈ㅇ에"))
    }

    @Test
    fun optionalSpacesAndDecomposedHangulFindTheSameTitle() {
        val song = Item("서른 즈음에")
        val catalog = index(song)
        assertEquals(listOf(song), catalog.search("서른즈음에"))
        assertEquals(listOf(song), catalog.search("서 른 즈 음 에"))
        assertEquals(
            listOf(song),
            catalog.search(Normalizer.normalize(song.title, Normalizer.Form.NFD)),
        )
        assertEquals(listOf(song), catalog.search("\u1109\u1105\u110c\u110b\u110b"))
    }

    @Test
    fun widthAndCaseAreIndependentOfTheDeviceLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val song = Item("INFINITE", "LilBOI")
            assertEquals(listOf(song), index(song).search("ｉｎｆｉｎｉｔｅ"))
            assertEquals(listOf(song), index(song).search("lilboi infinite"))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun titleRelevanceWinsAndEqualMatchesKeepLibraryOrder() {
        val metadata = Item("Another", "Blue")
        val contains = Item("My Blue")
        val prefix = Item("Blue Moon")
        val exact = Item("Blue")
        val otherPrefix = Item("Blue Sky")
        assertEquals(
            listOf(exact, prefix, otherPrefix, contains, metadata),
            index(metadata, contains, prefix, exact, otherPrefix).search("blue"),
        )
    }

    @Test
    fun textCannotCrossMetadataBoundariesAndEveryWordIsRequired() {
        val catalog = index(Item("abc", "def"), Item("Blue", "Artist"))
        assertTrue(catalog.search("cde").isEmpty())
        assertTrue(catalog.search("blue missing").isEmpty())
        assertTrue(catalog.search("  \u00a0  ").isEmpty())
        assertEquals(1, catalog.search("blue\u00a0artist").size)
    }

    @Test
    fun cancelledBuildAndQueryStopBeforeVisitingTheWholeLibrary() {
        val songs = List(50000) { Item("노래 $it", "가수 $it") }
        var checks = 0
        try {
            LibrarySearchIndex.build(songs, { it.title }, { sequenceOf(it.title) }) {
                if (++checks == 3) throw CancellationException()
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(3, checks)
        }
        val catalog =
            LibrarySearchIndex.build(songs, { it.title }, { sequenceOf(it.title, it.artist) })
        checks = 0
        try {
            catalog.search("노ㄹ") { if (++checks == 2) throw CancellationException() }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(2, checks)
        }
        assertEquals(songs.last(), catalog.search("노래49999 가수49999").single())
        assertEquals(songs.last(), catalog.search("노ㄹ49999 가ㅅ49999").single())
    }
    @Test
    fun genreMetadataIndexDoesNotMatchTitlesAndSplitsMultiGenreTags() {
        val titleOnly = Item("Rock the world", genre = "Jazz")
        val actual = Item("Quiet song", genre = "Folk; Rock")
        val unknown = Item("Rock", genre = "알 수 없음")
        val catalog = LibrarySearchIndex.build(listOf(titleOnly, actual, unknown), { it.title },
            { sequenceOf(GenreUtils.splitGenres(it.genre).joinToString("\u0001")) })
        assertEquals(listOf(actual), catalog.search("rock"))
        assertEquals(listOf(actual), catalog.search("folk"))
        assertTrue(catalog.search("알 수 없음").isEmpty())
        assertFalse(GenreUtils.matchesGenre("Hard Rock", "Rock"))
        assertTrue(GenreUtils.matchesGenre(actual.genre, "rock"))
    }

}
