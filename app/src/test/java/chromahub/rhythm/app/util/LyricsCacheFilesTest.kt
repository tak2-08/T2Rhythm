// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LyricsCacheFilesTest {
    @Test
    fun koreanLyricsAndFieldBoundariesHaveDistinctPaths() {
        assertNotEquals(LyricsCacheFiles.name("가수", "사랑"), LyricsCacheFiles.name("가수", "추억"))
        assertNotEquals(LyricsCacheFiles.name("가수", "노래"), LyricsCacheFiles.name("가수", "노래 (Live)"))
        assertNotEquals(LyricsCacheFiles.name("a_b", "c"), LyricsCacheFiles.name("a", "b_c"))
        assertFalse(LyricsCacheFiles.name("../가수", "/노래").contains('/'))
    }

    @Test
    fun newlySavedLyricsWinWhileLegacyFilesRemainIntact() {
        val root = Files.createTempDirectory("lyrics-cache-test").toFile()
        try {
            val old = java.io.File(root, "Artist_Title.json").apply { writeText("legacy") }
            assertEquals(old, LyricsCacheFiles.forReading(root, "Artist", "Title"))
            val edited =
                java.io.File(root, LyricsCacheFiles.name("Artist", "Title")).apply {
                    writeText("edited")
                }
            assertEquals(edited, LyricsCacheFiles.forReading(root, "Artist", "Title"))
            assertEquals("legacy", old.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
