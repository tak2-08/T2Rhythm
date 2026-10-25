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
}
