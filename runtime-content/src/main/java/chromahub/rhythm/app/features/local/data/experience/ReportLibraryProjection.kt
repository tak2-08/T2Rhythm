// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import chromahub.rhythm.app.shared.data.model.Song
import kr.co.metadata.crawler.CatalogIdentity

/** Library indexes are built off the UI thread once per library revision. */
class ReportLibraryProjection(songs: List<Song>, private val connectedServers: Set<String> = emptySet()) {
    private val byUri = songs.associateBy { it.uri.toString() }
    private val byIdentity = songs.groupBy { identity(it.title, it.artist) }
    private val byId = songs.associateBy { it.id }
    companion object {
        private fun identity(title: String, artist: String) = CatalogIdentity.normalize(title) + "|" + CatalogIdentity.normalize(artist)
    }
    fun resolve(track: ReportTrack): Song? {
        // A tag edit must not disconnect the same file from its listening history.
        // A connected private server can replay its stable history URI without a local audio copy.
        // Never fall back to a similarly named local song for disconnected private history.
        if (track.key.startsWith("https://${chromahub.rhythm.app.features.streaming.StreamingProtocol.MEDIA_HOST}/")) {
            val uri = android.net.Uri.parse(track.key); val parts = uri.pathSegments
            if (parts.size != 3 || parts[1] != "audio" || parts[0] !in connectedServers ||
                !chromahub.rhythm.app.features.streaming.StreamingProtocol.validId(parts[2]) || track.songId != "private:${parts[0]}:${parts[2]}") return null
            return byUri[track.key] ?: Song(track.songId, track.title, track.artist, track.album, duration = 0, uri = uri, genre = track.genre)
        }
        byUri[track.key]?.let { return it }
        byId[track.songId]?.takeIf { identity(it.title, it.artist) == identity(track.title, track.artist) }?.let { return it }
        val matches = byIdentity[identity(track.title, track.artist)].orEmpty()
        return matches.filter { CatalogIdentity.normalize(it.album) == CatalogIdentity.normalize(track.album) }.singleOrNull() ?: matches.singleOrNull()
    }
    fun project(report: ListeningReport): ListeningReport = ListeningReports.summarize(report.copy(tracks = report.tracks.map { track ->
        resolve(track)?.let { track.copy(title = it.title, artist = it.artist, album = it.album, genre = it.genre) } ?: track
    }))
}
