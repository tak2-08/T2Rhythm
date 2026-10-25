package chromahub.rhythm.app.util

import org.junit.Assert.*
import org.junit.Test

class HomeSectionOrderTest {
    @Test fun olderSavedOrdersRetainEveryCurrentEditorEntry() {
        val order = HomeSectionOrder.normalize(listOf("RECENTLY_PLAYED", "RECOMMENDED"))
        assertEquals(listOf("RECENTLY_PLAYED", "RECOMMENDED"), order.take(2))
        assertEquals(HomeSectionOrder.defaults.toSet(), order.toSet())
        assertTrue("ARTISTS" in order)
    }
    @Test fun artistAliasesAndRetiredSectionsCannotHideOrDuplicateTheRealSection() {
        val order = HomeSectionOrder.normalize(listOf("TOP_ARTISTS", "FAVORITE_ARTISTS", "MOOD", "GREETING", "ARTISTS"))
        assertEquals("ARTISTS", order.first())
        assertEquals(1, order.count { it == "ARTISTS" })
        assertEquals(HomeSectionOrder.defaults.size, order.size)
    }
}
