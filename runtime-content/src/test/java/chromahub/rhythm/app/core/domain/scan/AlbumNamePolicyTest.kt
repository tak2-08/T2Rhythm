// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.core.domain.scan
import org.junit.Assert.*
import org.junit.Test
class AlbumNamePolicyTest {
    @Test fun missingAlbumDoesNotInheritFolderUnlessEnabled() {
        assertEquals("Unknown Album", AlbumNamePolicy.resolve("My downloads", "My downloads", null, false))
        assertEquals("My downloads", AlbumNamePolicy.resolve("Unknown Album", "My downloads", null, true))
        assertEquals("Unknown Album", AlbumNamePolicy.resolve("<unknown>", "My downloads", null, false))
    }
    @Test fun actualAlbumTagIsPreservedEvenWhenItMatchesTheFolder() {
        assertEquals("Real Album", AlbumNamePolicy.resolve("Real Album", "Real Album", "Real Album", false))
        assertEquals("Embedded Album", AlbumNamePolicy.resolve("Folder", "Folder", "Embedded Album", true))
    }
    @Test fun knownAlbumNamesAreNotReplacedAndPathsAreNeverDisplayed() {
        assertEquals("Album", AlbumNamePolicy.resolve("Album", "Downloads", null, false))
        assertEquals("Unknown Album", AlbumNamePolicy.resolve(null, null, null, true))
    }
}
