package chromahub.rhythm.app.util

import org.junit.Assert.*
import org.junit.Test

class GenreCatalogTest {
    @Test fun vocabularyIsBrowsableWithoutInventingSongAssignments() {
        val index = GenreBrowseIndex.build(listOf("재즈", "인디 록"), { it }, knownGenres = GenreCatalog.labels)
        assertTrue(index.genres.size > 100)
        assertEquals(0, index.count("클래식"))
        assertEquals(listOf("재즈"), index.songs("재즈"))
        assertTrue("인디 록" in index.search("rock"))
        assertTrue("R&B" in index.search("알앤비"))
        assertTrue(index.songs("인디 록").contains("인디 록"))
    }
    @Test fun realLibraryGenresComeBeforeEmptyVocabularyAndTranslatedAliasesDoNotShadowThem() {
        val index = GenreBrowseIndex.build(listOf("ROCK", "HARD ROCK", "JAZZ"), { it }, knownGenres = GenreCatalog.labels)
        assertEquals(setOf("ROCK", "HARD ROCK"), index.search("록").take(2).toSet())
        assertFalse(index.genres.contains("록"))
        assertFalse(index.genres.contains("재즈"))
        assertEquals("JAZZ", index.search("재즈").first())
        assertEquals(1, index.count("ROCK"))
    }
}
