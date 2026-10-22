// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import chromahub.rhythm.app.shared.data.model.Song
import kr.co.metadata.crawler.CatalogIdentity

/** Library indexes are built off the UI thread once per library revision. */
class ReportLibraryProjection(songs: List<Song>) {
    private val byUri = songs.associateBy { it.uri.toString() }
    private val byIdentity = songs.groupBy { identity(it.title, it.artist) }
    private val byId = songs.associateBy { it.id }
    companion object {
        private fun identity(title: String, artist: String) = CatalogIdentity.normalize(title) + "|" + CatalogIdentity.normalize(artist)
    }
    fun resolve(track: ReportTrack): Song? {
        // A tag edit must not disconnect the same file from its listening history.
        byUri[track.key]?.let { return it }
        byId[track.songId]?.takeIf { identity(it.title, it.artist) == identity(track.title, track.artist) }?.let { return it }
        val matches = byIdentity[identity(track.title, track.artist)].orEmpty()
        return matches.filter { CatalogIdentity.normalize(it.album) == CatalogIdentity.normalize(track.album) }.singleOrNull() ?: matches.singleOrNull()
    }
    fun project(report: ListeningReport): ListeningReport = ListeningReports.summarize(report.copy(tracks = report.tracks.map { track ->
        resolve(track)?.let { track.copy(title = it.title, artist = it.artist, album = it.album, genre = it.genre) } ?: track
    }))
}
