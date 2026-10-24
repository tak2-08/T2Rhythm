// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import org.junit.Assert.*
import org.junit.Test

class GenreBrowseIndexTest {
    private data class Item(val id: String, val title: String, val genre: String)
    private fun song(id: String, title: String, genre: String) = Item(id, title, genre)
    private val songs = listOf(song("1", "Rock song title", "JAZZ"),
        song("2", "Quiet", "ROCK;FOLK;rock"), song("3", "Loud", "HARD ROCK"),
        song("4", "Soft", "인디 록"), song("5", "No tag", "알 수 없음"))

    @Test fun searchesGenresRatherThanSongMetadataAndSupportsKoreanAliases() {
        val index = GenreBrowseIndex.build(songs, { it.genre })
        assertEquals(setOf("ROCK", "HARD ROCK", "인디 록"), index.search("록").toSet())
        assertEquals(index.search("록").toSet(), index.search("rock").toSet())
        assertEquals(listOf("HARD ROCK"), index.search("하드록"))
        assertEquals(listOf("인디 록"), index.search("indierock"))
        assertTrue(index.search("Quiet").isEmpty())
        assertFalse(index.genres.contains("알 수 없음"))
        assertEquals(1, index.count("ROCK"))
    }
    @Test fun drillDownSeparatesChildrenFromRelatedTagsAndKeepsExactSongMembership() {
        val index = GenreBrowseIndex.build(songs, { it.genre })
        assertEquals(setOf("HARD ROCK", "인디 록"), index.children("ROCK").toSet())
        assertEquals(listOf("FOLK"), index.related("ROCK"))
        assertEquals(listOf("2"), index.songs("ROCK").map { it.id })
        assertEquals(listOf("3"), index.songs("HARD ROCK").map { it.id })
        assertEquals(setOf("ROCK", "인디 록"), index.related("HARD ROCK").toSet())
    }
    @Test fun unknownEmptyAndRebuiltLibrariesDoNotRetainStaleGenres() {
        assertTrue(GenreBrowseIndex.build(songs, { it.genre }).songs(null).isEmpty())
        assertTrue(GenreBrowseIndex.build(emptyList<Item>(), { it.genre }).search("").isEmpty())
        val updated = GenreBrowseIndex.build(listOf(song("6", "New", "포크 록")), { it.genre })
        assertEquals(listOf("포크 록"), updated.search("ㅍㅋ"))
        assertTrue(updated.songs("ROCK").isEmpty())
        assertTrue(updated.children("ROCK").contains("포크 록"))
    }
}
