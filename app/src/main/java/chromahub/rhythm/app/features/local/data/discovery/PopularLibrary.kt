// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import chromahub.rhythm.app.features.local.data.video.DownloadRecord
import chromahub.rhythm.app.shared.data.model.Song
import kr.co.metadata.crawler.CatalogIdentity
import kr.co.metadata.crawler.ChartTrack

/** Exact title/artist identity keeps live, remix and instrumental editions separate. */
internal class PopularLibrary(songs: List<Song>) {
    private val byIdentity =
        songs
            .filter { it.uri.scheme in setOf("content", "file") }
            .groupBy { identity(it.title, it.artist) }

    fun find(track: ChartTrack): Song? {
        if (CatalogIdentity.missing(track.title) || CatalogIdentity.missing(track.artist))
            return null
        val candidates = byIdentity[identity(track.title, track.artist)].orEmpty()
        val sameAlbum =
            candidates.filter {
                CatalogIdentity.normalize(it.album) == CatalogIdentity.normalize(track.album)
            }
        return sameAlbum.firstOrNull() ?: candidates.singleOrNull()
    }

    companion object {
        fun identity(title: String, artist: String) =
            CatalogIdentity.normalize(title) + "|" + CatalogIdentity.normalize(artist)

        fun active(record: DownloadRecord) =
            record.kind == "AUDIO" && record.state !in setOf("SUCCEEDED", "FAILED", "CANCELLED")

        fun matches(record: DownloadRecord, track: ChartTrack) =
            record.kind == "AUDIO" &&
                identity(record.title, record.artist) == identity(track.title, track.artist) &&
                (CatalogIdentity.missing(record.album) ||
                    CatalogIdentity.missing(track.album) ||
                    CatalogIdentity.normalize(record.album) ==
                        CatalogIdentity.normalize(track.album))
    }
}
