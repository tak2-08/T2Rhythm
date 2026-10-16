// SPDX-License-Identifier: GPL-3.0-or-later
package kr.co.metadata.crawler

data class PopularTrack(
    val rank: Int,
    val key: String,
    val track: ChartTrack,
    val providers: List<Provider>,
    val score: Double,
)

/**
 * Chart identity is deliberately strict: live/remix versions and different credits stay separate.
 */
object PopularRanking {
    val weights = linkedMapOf(Provider.MELON to .5, Provider.BUGS_PC to .3, Provider.GENIE to .2)

    fun identity(track: ChartTrack): String =
        CatalogIdentity.normalize(track.title) + "|" + CatalogIdentity.normalize(track.artist)

    fun rank(tracks: List<ChartTrack>): List<PopularTrack> =
        tracks
            .asSequence()
            .filter {
                it.rank in 1..100 &&
                    !CatalogIdentity.missing(it.title) &&
                    !CatalogIdentity.missing(it.artist)
            }
            .groupBy(::identity)
            .map { (key, rows) ->
                val sources =
                    rows
                        .groupBy {
                            if (it.provider == Provider.BUGS_MOBILE) Provider.BUGS_PC
                            else it.provider
                        }
                        .mapValues { (provider, values) ->
                            values.minWith(
                                compareBy<ChartTrack> { it.rank }
                                    .thenBy { if (it.provider == provider) 0 else 1 }
                                    .thenBy { it.id }
                            )
                        }
                val providers = weights.keys.filter { it in sources }
                val representative = sources.getValue(providers.first())
                val score =
                    sources.entries.sumOf { (provider, row) ->
                        weights.getValue(provider) * (101 - row.rank) / 100.0
                    } + .10 * (sources.size - 1)
                PopularTrack(0, key, representative, providers, score)
            }
            .sortedWith(compareByDescending<PopularTrack> { it.score }.thenBy { it.key })
            .take(100)
            .mapIndexed { index, row -> row.copy(rank = index + 1) }
}
