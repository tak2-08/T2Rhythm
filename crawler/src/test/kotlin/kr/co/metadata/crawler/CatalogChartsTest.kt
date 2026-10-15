// SPDX-License-Identifier: GPL-3.0-or-later
package kr.co.metadata.crawler

import kotlin.test.*
import org.junit.jupiter.api.Test

class CatalogChartsTest {
    @Test
    fun bugsNestedTitleAndProviderIdsRemainIntact() {
        val rows =
            CatalogCharts.parse(
                Provider.BUGS_PC,
                """<table class="trackList"><tbody><tr trackId="11"><td class="ranking"><strong>3</strong></td><td><p class="title"><a><span>노래 제목</span><span class="badge">NEW</span></a></p><p class="artist"><a href="/artist/21">가수</a></p><a class="album" href="/album/31">앨범</a><img src="https://example.org/a.webp"></td></tr></tbody></table>""",
            )
        val row = rows.single()
        assertEquals("노래 제목", row.title)
        assertEquals(3, row.rank)
        assertEquals("21", row.artistId)
        assertEquals("31", row.albumId)
    }

    @Test
    fun melonUsesSongNumberInsteadOfPlaybackJavascriptAndDropsDuplicateRows() {
        val row =
            """<tr data-song-no="123"><td class="rank">7</td><td><div class="rank01"><a href="javascript:play(999,123)">곡</a></div><div class="rank02"><a href="/artist/detail.htm?artistId=2">가수</a></div><div class="rank03"><a href="/album/detail.htm?albumId=3">앨범</a></div></td></tr>"""
        val rows = CatalogCharts.parse(Provider.MELON, "<table>$row$row</table>")
        assertEquals(1, rows.size)
        assertEquals("123", rows.single().id)
        assertEquals(7, rows.single().rank)
    }

    @Test
    fun genieDistinguishesRankFromChangeAndResolvesProtocolRelativeArtwork() {
        val rows =
            CatalogCharts.parse(
                Provider.GENIE,
                """<table><tr class="list" songid="10"><td class="number">9<span class="rank">2 상승</span></td><td><img src="//image.genie.co.kr/cover.jpg"><a class="title"><span>곡 이름</span></a><a class="artist" onclick="fnViewArtist('20')">가수</a><a class="albumtitle" onclick="fnViewAlbumLayer('30')">앨범</a></td></tr></table>""",
            )
        assertEquals(9, rows.single().rank)
        assertEquals("곡 이름", rows.single().title)
        assertEquals("30", rows.single().albumId)
        assertTrue(rows.single().artwork!!.startsWith("https://"))
    }

    @Test
    fun albumGroupingUnitesCompilationTracksButSeparatesSameTitlesAcrossAlbums() {
        fun track(id: String, artist: String, albumId: String) =
            ChartTrack(
                id.toInt(),
                Provider.BUGS_PC,
                id,
                "곡",
                artist,
                "Greatest Hits",
                "https://example.org/$id",
                albumId = albumId,
            )
        val tracks = listOf(track("1", "A", "10"), track("2", "B", "10"), track("3", "A", "11"))
        val entities = CatalogCharts.entities(tracks, true)
        assertEquals(2, entities.size)
        assertEquals(2, entities.first().appearances)
        assertEquals("10", entities.first().providerId)
        assertNotEquals(
            CatalogCharts.entityKey(tracks.first(), true),
            CatalogCharts.entityKey(tracks.last(), true),
        )
    }

    @Test
    fun unavailableOrOversizedMarkupIsNeverInventedIntoTracks() {
        assertTrue(CatalogCharts.parse(Provider.MELON, "<html>접근이 제한되었습니다</html>").isEmpty())
        assertFailsWith<IllegalArgumentException> {
            CatalogCharts.parse(Provider.GENIE, " ".repeat(5 * 1024 * 1024 + 1))
        }
    }
}
