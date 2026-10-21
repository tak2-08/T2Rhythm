// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import kr.co.metadata.crawler.*
import org.junit.Assert.*
import org.junit.Test

class CatalogImportChoiceTest {
    private fun value(field: CatalogField, text: String) = CatalogValue(field, text, "https://www.melon.com/song/detail.htm?songId=1")
    @Test fun ordinaryImportKeepsLyricsUnchecked() {
        val document = CatalogDocument(EntityKind.TRACK, listOf(value(CatalogField.TRACK_TITLE, "노래"), value(CatalogField.TRACK_IMAGE, "https://example.com/a.jpg"), value(CatalogField.TRACK_LYRICS, "가사")))
        assertEquals(setOf(CatalogField.TRACK_TITLE, CatalogField.TRACK_IMAGE), CatalogSelection.defaults(document, false))
        assertTrue(CatalogSelection.selectable(CatalogField.TRACK_LYRICS, false))
        assertFalse(CatalogSelection.selectable(CatalogField.TRACK_DURATION_MS, false))
    }
    @Test fun lyricsOnlyImportSelectsOnlyTheLyrics() {
        val document = CatalogDocument(EntityKind.TRACK, listOf(value(CatalogField.TRACK_TITLE, "노래"), value(CatalogField.TRACK_LYRICS, "가사")))
        assertEquals(setOf(CatalogField.TRACK_LYRICS), CatalogSelection.defaults(document, true))
    }
    @Test fun missingAndEmptyLyricsAreNeverPreselected() {
        val document = CatalogDocument(EntityKind.TRACK, listOf(value(CatalogField.TRACK_LYRICS, "-"), value(CatalogField.TRACK_TITLE, "")))
        assertTrue(CatalogSelection.defaults(document, true).isEmpty())
        assertTrue(CatalogSelection.defaults(null, false).isEmpty())
    }
    @Test fun plainLyricsPreserveLineBreaksAndSource() {
        val data = CatalogLyricImport.data(value(CatalogField.TRACK_LYRICS, "\uFEFF첫 줄\r\n둘째 줄\r셋째 줄"))
        assertEquals("첫 줄\n둘째 줄\n셋째 줄", data.plainLyrics)
        assertNull(data.syncedLyrics); assertEquals(true, data.isCorrected)
        assertTrue(data.source!!.contains("melon.com"))
    }
    @Test fun timedLyricsKeepTimingAndProducePlainLyrics() {
        val text = "[00:01.25]첫 줄\n[00:03.40]둘째 줄"
        val data = CatalogLyricImport.data(value(CatalogField.TRACK_LYRICS, text))
        assertEquals(text, data.syncedLyrics); assertEquals("첫 줄\n둘째 줄", data.plainLyrics)
    }
    @Test fun unrelatedOrOversizedContentCannotBecomeLyrics() {
        assertTrue(runCatching { CatalogLyricImport.data(value(CatalogField.TRACK_TITLE, "제목")) }.isFailure)
        assertTrue(runCatching { CatalogLyricImport.data(value(CatalogField.TRACK_LYRICS, "가".repeat(250001))) }.isFailure)
    }
}
