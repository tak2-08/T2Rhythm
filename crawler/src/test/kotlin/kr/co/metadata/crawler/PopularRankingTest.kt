// SPDX-License-Identifier: GPL-3.0-or-later
package kr.co.metadata.crawler

import kotlin.test.*

class PopularRankingTest {
    private fun row(source: Provider, title: String, rank: Int = 1, artist: String = "가수") =
        ChartTrack(rank, source, "$source-$title-$rank", title, artist, "앨범", "https://example.com")

    @Test
    fun providerPriorityAndAgreementBothAffectRanking() {
        val rows =
            listOf(row(Provider.GENIE, "G"), row(Provider.BUGS_PC, "B"), row(Provider.MELON, "M"))
        assertEquals(listOf("M", "B", "G"), PopularRanking.rank(rows).map { it.track.title })
        val shared = PopularRanking.weights.keys.map { row(it, "공통", 60) }
        val result = PopularRanking.rank(rows + shared)
        assertEquals("공통", result.first().track.title)
        assertEquals(3, result.first().providers.size)
        assertEquals(Provider.MELON, result.first().track.provider)
    }

    @Test
    fun duplicatedRowsAndMobileBugsCannotManufactureAgreement() {
        val original = row(Provider.BUGS_PC, "곡", 10)
        val result =
            PopularRanking.rank(
                listOf(original, original, original.copy(provider = Provider.BUGS_MOBILE))
            )
        assertEquals(listOf(Provider.BUGS_PC), result.single().providers)
        assertEquals(PopularRanking.rank(listOf(original)), result)
    }

    @Test
    fun versionsAndCreditsRemainDistinctAndOrderingIsDeterministic() {
        val rows =
            listOf(
                row(Provider.MELON, "곡"),
                row(Provider.BUGS_PC, "곡 (Live)"),
                row(Provider.GENIE, "곡", artist = "다른 가수"),
            )
        assertEquals(3, PopularRanking.rank(rows).size)
        assertEquals(PopularRanking.rank(rows), PopularRanking.rank(rows.reversed()))
        assertEquals(listOf(1, 2, 3), PopularRanking.rank(rows).map { it.rank })
    }
}
