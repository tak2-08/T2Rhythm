// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import android.content.Context
import android.net.Uri
import chromahub.rhythm.app.shared.data.model.Song
import java.io.Closeable
import java.io.FileInputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class StreamTrack(val id: String, val title: String, val artist: String, val album: String, val duration: Long,
    val genre: String, val year: Int, val albumArtist: String, val track: Int, val disc: Int, val suffix: String, val art: String, val origin: String? = null) {
    fun json(): JSONObject = JSONObject().put("id", id).put("title", title).put("artist", artist).put("album", album).put("duration", duration)
        .put("genre", genre).put("year", year).put("albumArtist", albumArtist).put("track", track).put("disc", disc).put("suffix", suffix).put("art", art).put("origin", origin ?: JSONObject.NULL)
    fun song(server: String): Song {
        val server = origin ?: server
        return Song("private:$server:$id", title, artist, album, "private:$server:${StreamingProtocol.hash("$album|$albumArtist")}", duration,
        Uri.parse("https://${StreamingProtocol.MEDIA_HOST}/$server/audio/$id"),
        artworkUri = art.takeIf { it.isNotEmpty() }?.let { Uri.parse("https://${StreamingProtocol.MEDIA_HOST}/$server/art/$id?v=$it") },
        genre = genre, year = year, albumArtist = albumArtist, trackNumber = track, discNumber = disc,
        path = "private.$suffix")
    }
    companion object {
        fun parse(j: JSONObject): StreamTrack {
            val id = j.getString("id"); require(StreamingProtocol.validId(id))
            fun text(key: String) = j.optString(key).take(2048)
            val suffix = text("suffix").takeIf { Regex("[a-z0-9]{1,8}").matches(it) } ?: "audio"
            val art = text("art").takeIf(StreamingProtocol::validId) ?: ""
            val origin = if (j.isNull("origin")) null else j.getString("origin").also { require(java.util.UUID.fromString(it).toString() == it) }
            return StreamTrack(id, text("title"), text("artist"), text("album"), j.optLong("duration").coerceIn(0, 604_800_000), text("genre"),
                j.optInt("year").coerceIn(0, 9999), text("albumArtist"), j.optInt("track").coerceAtLeast(0), j.optInt("disc", 1).coerceAtLeast(1), suffix, art, origin)
        }
    }
}

/** Immutable projection is replaced when Room emits; HTTP handlers never rescan files. */
class StreamingCatalog(private val context: Context, private val server: String) {
    val serverId get() = server
    data class Entry(val source: Song, val track: StreamTrack, val search: String)
    data class PlaylistEntry(val info: StreamPlaylist, val tracks: List<StreamTrack>)
    data class Snapshot(val revision: String, val entries: Map<String, Entry>, val ordered: List<Entry>, val genres: List<String>, val playlists: List<PlaylistEntry> = emptyList(), val sharedCount: Int = entries.size)
    @Volatile var snapshot = Snapshot("empty", emptyMap(), emptyList(), emptyList()); private set
    fun replace(songs: List<Song>, selection: Set<String>?, playlists: List<SharePlaylist> = emptyList()) {
        val entries = songs.asSequence().filter { it.uri.scheme in setOf("content", "file") && (selection == null || it.uri.toString() in selection) }
            .distinctBy { it.uri.toString() }.map { song ->
                val id = StreamingProtocol.hash("$server|${song.uri}")
                val suffix = song.path?.substringAfterLast('.')?.lowercase(Locale.ROOT)?.takeIf { Regex("[a-z0-9]{1,8}").matches(it) } ?: "audio"
                val artworkSignature = song.artworkUri?.let { uri ->
                    val physical = if (uri.scheme == "file") uri.path?.let(::File) else null
                    "$uri|${song.dateModified}|${physical?.lastModified() ?: 0}|${physical?.length() ?: 0}"
                }
                val track = StreamTrack(id, song.title.take(2048), song.artist.take(2048), song.album.take(2048), song.duration,
                    song.genre.orEmpty().take(2048), song.year, song.albumArtist.orEmpty().take(2048), song.trackNumber, song.discNumber, suffix,
                    artworkSignature?.let(StreamingProtocol::hash).orEmpty())
                Entry(song, track, "${track.title}\n${track.artist}\n${track.album}\n${track.genre}".lowercase(Locale.ROOT))
            }.sortedWith(compareBy<Entry> { it.track.title.lowercase(Locale.ROOT) }.thenBy { it.track.id }).toList()
        val byUri = entries.associate { it.source.uri.toString() to it.track }
        val remoteByUri = songs.asSequence().mapNotNull { source -> StreamOrigins.reference(source)?.let { source.uri.toString() to it } }.toMap()
        val shared = playlists.take(100).mapNotNull { list ->
            val tracks = list.uris.mapNotNull { byUri[it] ?: remoteByUri[it] }.distinctBy { "${it.origin ?: server}:${it.id}" }
            if (tracks.isEmpty()) null else PlaylistEntry(StreamPlaylist(StreamingProtocol.hash("$server|playlist|${list.key}"), list.name.take(160), minOf(tracks.size, 5000), tracks.size > 5000), tracks.take(5000))
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun update(value: String) { digest.update(value.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
        entries.forEach { update(it.track.json().toString()); update(it.source.dateModified.toString()) }
        shared.forEach { update(it.info.json().toString()); it.tracks.forEach { track -> update(track.json().toString()) } }
        val revision = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val genres = entries.flatMap { chromahub.rhythm.app.util.GenreUtils.splitGenres(it.track.genre) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(16).map { it.key }
        val sharedCount = entries.size + shared.asSequence().flatMap { it.tracks.asSequence() }.filter { it.origin != null }.distinctBy { "${it.origin}:${it.id}" }.count()
        snapshot = Snapshot(revision, entries.associateBy { it.track.id }, entries, genres, shared, sharedCount)
    }
    fun page(query: String, genre: String, offset: Int, limit: Int, playlist: String? = null): JSONObject {
        val current = snapshot; val search = query.trim().take(100).lowercase(Locale.ROOT)
        val playlistTracks = if (playlist == null) emptyList() else current.playlists.firstOrNull { it.info.id == playlist }?.tracks.orEmpty()
        val total = if (playlist == null) current.ordered.size else playlistTracks.size
        val matched: Int
        val tracks: List<StreamTrack>
        if (playlist == null) {
            val filtered = if (search.isEmpty() && genre.isEmpty()) current.ordered else current.ordered.filter {
                (search.isEmpty() || search in it.search) && (genre.isEmpty() || genre in chromahub.rhythm.app.util.GenreUtils.splitGenres(it.track.genre))
            }
            matched = filtered.size; tracks = filtered.asSequence().drop(offset).take(limit).map { it.track }.toList()
        } else {
            val filtered = if (search.isEmpty() && genre.isEmpty()) playlistTracks else playlistTracks.filter {
                (search.isEmpty() || search in "${it.title}\n${it.artist}\n${it.album}\n${it.genre}".lowercase(Locale.ROOT)) &&
                    (genre.isEmpty() || genre in chromahub.rhythm.app.util.GenreUtils.splitGenres(it.genre))
            }
            matched = filtered.size; tracks = filtered.drop(offset).take(limit)
        }
        return JSONObject().put("version", 1).put("server", server).put("revision", current.revision).put("total", total)
            .put("matched", matched).put("offset", offset).put("genres", JSONArray(current.genres))
            .put("features", JSONArray(listOf("playlists-v1"))).put("playlist", playlist ?: JSONObject.NULL)
            .put("tracks", JSONArray().apply { tracks.forEach { put(it.json()) } })
    }
    fun playlists(): JSONObject {
        val current = snapshot
        return JSONObject().put("version", 1).put("server", server).put("revision", current.revision)
            .put("playlists", JSONArray().apply { current.playlists.forEach { put(it.info.json()) } })
    }
    class Audio(val file: FileInputStream, val offset: Long, val size: Long, val mime: String) : Closeable {
        override fun close() = file.close()
    }
    fun open(id: String): Audio {
        val song = snapshot.entries[id]?.source ?: throw IOException("Unavailable track")
        val asset = context.contentResolver.openAssetFileDescriptor(song.uri, "r") ?: throw IOException("Unavailable track")
        try {
            val stream = asset.createInputStream()
            val size = if (asset.declaredLength >= 0) asset.declaredLength else stream.channel.size() - asset.startOffset
            if (size < 0) { stream.close(); throw IOException("Track is not seekable") }
            val mime = context.contentResolver.getType(song.uri)?.takeIf { it.startsWith("audio/") || it == "application/ogg" }
                ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(snapshot.entries[id]?.track?.suffix)?.takeIf { it.startsWith("audio/") } ?: "application/octet-stream"
            return Audio(stream, asset.startOffset, size, mime)
        } catch (e: Exception) { asset.close(); throw e }
    }
}
