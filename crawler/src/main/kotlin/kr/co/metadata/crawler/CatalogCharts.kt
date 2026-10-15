// SPDX-License-Identifier: GPL-3.0-or-later
package kr.co.metadata.crawler

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup

@Serializable
data class ChartTrack(
    val rank: Int,
    val provider: Provider,
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val url: String,
    val artwork: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
)

@Serializable
data class ChartEntity(
    val title: String,
    val artist: String,
    val appearances: Int,
    val bestRank: Int,
    val artwork: String?,
    val providerId: String?,
)

/** Public song charts, one page per refresh. Album/artist views are explicitly chart-derived. */
class CatalogCharts(private val pages: PageClient = PageClient()) {
    companion object {
        fun url(provider: Provider) =
            when (provider) {
                Provider.BUGS_PC,
                Provider.BUGS_MOBILE -> "https://music.bugs.co.kr/chart"
                Provider.MELON -> "https://www.melon.com/chart/index.htm"
                Provider.GENIE -> "https://www.genie.co.kr/chart/top200?ditc=D&rtm=Y&pg=1"
            }

        fun parse(provider: Provider, html: String): List<ChartTrack> {
            require(html.length <= 5 * 1024 * 1024)
            val doc = Jsoup.parse(html, url(provider))
            val rows =
                doc.select(
                    when (provider) {
                        Provider.BUGS_PC,
                        Provider.BUGS_MOBILE -> "table.trackList tbody tr, tr[trackid]"
                        Provider.MELON -> "tr[data-song-no]"
                        Provider.GENIE -> "tr.list, tr[songid]"
                    }
                )
            fun id(text: String, kind: String): String? {
                val pattern =
                    when (provider) {
                        Provider.BUGS_PC,
                        Provider.BUGS_MOBILE -> Regex("/$kind/(\\d+)")
                        Provider.MELON ->
                            Regex(
                                when (kind) {
                                    "track" -> "(?:songId=|goSongDetail\\(['\"])(\\d+)"
                                    "album" -> "(?:albumId=|goAlbumDetail\\(['\"])(\\d+)"
                                    else -> "(?:artistId=|goArtistDetail\\(['\"])(\\d+)"
                                }
                            )
                        Provider.GENIE ->
                            Regex(
                                when (kind) {
                                    "track" -> "(?:xgnm=|fnViewSongInfo\\(['\"])(\\d+)"
                                    "album" -> "(?:axnm=|fnViewAlbum(?:Info|Layer)?\\(['\"])(\\d+)"
                                    else -> "(?:xxnm=|fnViewArtist(?:Info)?\\(['\"])(\\d+)"
                                }
                            )
                    }
                return pattern.find(text)?.groupValues?.get(1)
            }
            fun clean(text: String) = text.replace(Regex("\\s+"), " ").trim().take(250)
            return rows
                .mapIndexedNotNull { index, row ->
                    fun node(kind: String) =
                        row.selectFirst(
                            when (provider) {
                                Provider.BUGS_PC,
                                Provider.BUGS_MOBILE ->
                                    when (kind) {
                                        "track" -> "p.title a"
                                        "artist" -> "p.artist a"
                                        else -> "a.album"
                                    }
                                Provider.MELON ->
                                    when (kind) {
                                        "track" -> ".rank01 a"
                                        "artist" -> ".rank02 a"
                                        else -> ".rank03 a"
                                    }
                                Provider.GENIE ->
                                    when (kind) {
                                        "track" -> "a.title"
                                        "artist" -> "a.artist"
                                        else -> "a.albumtitle"
                                    }
                            }
                        )
                    val title = node("track") ?: return@mapIndexedNotNull null
                    val trackId =
                        row.attr("data-song-no")
                            .ifBlank { row.attr("trackid") }
                            .ifBlank { row.attr("songid") }
                            .takeIf { it.matches(Regex("\\d+")) }
                            ?: id(title.outerHtml(), "track")
                            ?: return@mapIndexedNotNull null
                    val text =
                        clean(
                            title
                                .clone()
                                .apply {
                                    select(".icon, .badge, .icon-title, .icon-list, .none, .hide")
                                        .remove()
                                }
                                .text()
                        )
                    if (text.isBlank()) return@mapIndexedNotNull null
                    val artist = node("artist")
                    val album = node("album")
                    val rankNode = row.selectFirst(".ranking strong, .rank, .number")
                    val rank =
                        rankNode
                            ?.ownText()
                            ?.ifBlank { rankNode.text() }
                            ?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
                            ?.takeIf { it in 1..200 } ?: index + 1
                    val detail =
                        when (provider) {
                            Provider.BUGS_PC,
                            Provider.BUGS_MOBILE -> "https://music.bugs.co.kr/track/$trackId"
                            Provider.MELON ->
                                "https://www.melon.com/song/detail.htm?songId=$trackId"
                            Provider.GENIE ->
                                "https://www.genie.co.kr/detail/songInfo?xgnm=$trackId"
                        }
                    ChartTrack(
                        rank,
                        provider,
                        trackId,
                        text,
                        clean(artist?.text().orEmpty()),
                        clean(album?.text().orEmpty()),
                        detail,
                        row.selectFirst("img[src]")?.absUrl("src")?.takeIf {
                            it.startsWith("https://")
                        },
                        album?.let { id(it.outerHtml(), "album") },
                        artist?.let { id(it.outerHtml(), "artist") },
                    )
                }
                .distinctBy { it.id }
                .take(100)
        }

        fun entityKey(track: ChartTrack, albums: Boolean): String =
            track.provider.name +
                ":" +
                (if (albums)
                    track.albumId?.let { "album:$it" }
                        ?: "album:${track.album.lowercase(java.util.Locale.ROOT)}:${track.artist.lowercase(java.util.Locale.ROOT)}"
                else
                    track.artistId?.let { "artist:$it" }
                        ?: "artist:${track.artist.lowercase(java.util.Locale.ROOT)}")

        fun entities(tracks: List<ChartTrack>, albums: Boolean): List<ChartEntity> =
            tracks
                .filter { (if (albums) it.album else it.artist).isNotBlank() }
                .groupBy { entityKey(it, albums) }
                .values
                .map { rows ->
                    val first = rows.minBy { it.rank }
                    ChartEntity(
                        if (albums) first.album else first.artist,
                        if (albums) first.artist else "",
                        rows.size,
                        first.rank,
                        first.artwork,
                        if (albums) first.albumId else first.artistId,
                    )
                }
                .sortedWith(
                    compareByDescending<ChartEntity> { it.appearances }.thenBy { it.bestRank }
                )
    }

    suspend fun fetch(provider: Provider): List<ChartTrack> {
        require(provider != Provider.BUGS_MOBILE)
        return pages.get(url(provider))?.let { parse(provider, it) }.orEmpty()
    }
}
