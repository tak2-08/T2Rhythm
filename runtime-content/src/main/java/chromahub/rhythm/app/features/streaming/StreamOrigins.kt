// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import chromahub.rhythm.app.shared.data.model.Song
import java.util.UUID

/** A shared playlist carries origin IDs and metadata, never another peer's token or address. */
object StreamOrigins {
    fun reference(song: Song): StreamTrack? = runCatching {
        if (!PrivateStreamDataSource.isPrivate(song.uri)) return null
        val parts = song.uri.pathSegments
        require(parts.size == 3 && parts[1] == "audio" && StreamingProtocol.validId(parts[2]))
        val origin = parts[0]; require(UUID.fromString(origin).toString() == origin)
        val art = song.artworkUri?.getQueryParameter("v")?.takeIf(StreamingProtocol::validId).orEmpty()
        val suffix = song.path?.substringAfterLast('.')?.takeIf { Regex("[a-z0-9]{1,8}").matches(it) } ?: "audio"
        StreamTrack(parts[2], song.title.take(2048), song.artist.take(2048), song.album.take(2048), song.duration,
            song.genre.orEmpty().take(2048), song.year, song.albumArtist.orEmpty().take(2048), song.trackNumber, song.discNumber, suffix, art, origin)
    }.getOrNull()
    fun resolver(local: List<Song>, self: String, connections: List<StreamConnection>): (Song) -> Song? {
        val localById = local.filter { it.uri.scheme in setOf("content", "file") }.associateBy { StreamingProtocol.hash("$self|${it.uri}") }
        val connected = connections.map { it.server }.toSet()
        return { song ->
            val parts = song.uri.pathSegments
            when {
                !PrivateStreamDataSource.isPrivate(song.uri) -> null
                parts.size != 3 || parts[1] != "audio" || !StreamingProtocol.validId(parts[2]) -> null
                parts[0] == self -> localById[parts[2]]
                parts[0] in connected -> song
                else -> null
            }
        }
    }
}
