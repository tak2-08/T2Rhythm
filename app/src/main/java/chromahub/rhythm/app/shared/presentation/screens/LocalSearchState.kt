// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.shared.presentation.screens

import androidx.compose.runtime.*
import chromahub.rhythm.app.shared.data.model.*
import chromahub.rhythm.app.util.GenreUtils
import chromahub.rhythm.app.util.LibrarySearchIndex
import java.util.Locale
import kotlinx.coroutines.*

// Foreground search remains responsive without four index builders competing for every CPU core.
private val searchDispatcher = Dispatchers.Default.limitedParallelism(1)

internal data class LocalSearchResults(
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
)

internal data class LocalSearchState(
    val results: LocalSearchResults,
    val searching: Boolean,
    val genreCounts: Map<String, Int>,
    val genresReady: Boolean,
)

private data class SongIndex(val index: LibrarySearchIndex<Song>, val genreIndex: LibrarySearchIndex<Song>, val genres: Map<String, Int>)

private data class Request(
    val query: String,
    val songs: LibrarySearchIndex<Song>?,
    val albums: LibrarySearchIndex<Album>?,
    val artists: LibrarySearchIndex<Artist>?,
    val playlists: LibrarySearchIndex<Playlist>?,
    val ready: Boolean,
    val genreOnly: Boolean,
    val selectedGenre: String?,
)

/**
 * Source changes invalidate the relevant index; typing only runs cancellable, debounced queries.
 */
@Composable
internal fun rememberLocalSearch(
    query: String,
    songs: List<Song>,
    albums: List<Album>,
    artists: List<Artist>,
    playlists: List<Playlist>,
    filterSongs: Boolean,
    filterAlbums: Boolean,
    filterArtists: Boolean,
    filterPlaylists: Boolean,
    genreOnly: Boolean = false,
    selectedGenre: String? = null,
): LocalSearchState {
    val songIndex by
        produceState<SongIndex?>(null, songs) {
            value =
                withContext(searchDispatcher) {
                    val active = currentCoroutineContext()
                    val counts = linkedMapOf<String, Pair<String, Int>>()
                    songs.forEachIndexed { i, song ->
                        if (i % 128 == 0) active.ensureActive()
                        GenreUtils.splitGenres(song.genre).forEach { genre ->
                            val key = genre.lowercase(Locale.ROOT)
                            val old = counts[key]
                            counts[key] = (old?.first ?: genre) to ((old?.second ?: 0) + 1)
                        }
                    }
                    SongIndex(
                        LibrarySearchIndex.build(
                            songs,
                            { it.title },
                            { sequenceOf(it.title, it.artist, it.album, it.genre.orEmpty()) },
                        ) {
                            active.ensureActive()
                        },
                        LibrarySearchIndex.build(songs, { it.title }, { sequenceOf(GenreUtils.splitGenres(it.genre).joinToString("\u0001")) }) { active.ensureActive() },
                        counts.toSortedMap().values.associate { it },
                    )
                }
        }
    val albumIndex by
        produceState<LibrarySearchIndex<Album>?>(null, albums) {
            value =
                withContext(searchDispatcher) {
                    val active = currentCoroutineContext()
                    LibrarySearchIndex.build(
                        albums,
                        { it.title },
                        {
                            sequenceOf(it.title, it.artist) +
                                it.songs.asSequence().map { song -> song.title }
                        },
                    ) {
                        active.ensureActive()
                    }
                }
        }
    val artistIndex by
        produceState<LibrarySearchIndex<Artist>?>(null, artists) {
            value =
                withContext(searchDispatcher) {
                    val active = currentCoroutineContext()
                    LibrarySearchIndex.build(artists, { it.name }, { sequenceOf(it.name) }) {
                        active.ensureActive()
                    }
                }
        }
    val playlistIndex by
        produceState<LibrarySearchIndex<Playlist>?>(null, playlists) {
            value =
                withContext(searchDispatcher) {
                    val active = currentCoroutineContext()
                    LibrarySearchIndex.build(playlists, { it.name }, { sequenceOf(it.name) }) {
                        active.ensureActive()
                    }
                }
        }
    val ready = if (genreOnly) songIndex?.index?.source == songs else
        (!filterSongs || songIndex?.index?.source == songs) &&
            (!filterAlbums || albumIndex?.source == albums) &&
            (!filterArtists || artistIndex?.source == artists) &&
            (!filterPlaylists || playlistIndex?.source == playlists)
    val request =
        Request(
            query,
            (if (genreOnly && selectedGenre == null) songIndex?.genreIndex else songIndex?.index).takeIf { filterSongs || genreOnly },
            albumIndex.takeIf { filterAlbums && !genreOnly },
            artistIndex.takeIf { filterArtists && !genreOnly },
            playlistIndex.takeIf { filterPlaylists && !genreOnly },
            ready && (!genreOnly || songIndex?.index?.source == songs),
            genreOnly,
            selectedGenre,
        )
    val completed by
        produceState<Pair<Request, LocalSearchResults>?>(null, request) {
            value = null // Release the preceding source/results even when the query is cleared.
            if ((query.isBlank() && selectedGenre == null) || !request.ready) return@produceState
            delay(120)
            value =
                withContext(searchDispatcher) {
                    val active = currentCoroutineContext()
                    request to
                        LocalSearchResults(
                            (if (selectedGenre != null && query.isBlank()) request.songs?.source.orEmpty()
                            else request.songs?.search(query) { active.ensureActive() }.orEmpty()).filterIndexed { i, song ->
                                if (i % 128 == 0) active.ensureActive()
                                selectedGenre == null || GenreUtils.matchesGenre(song.genre, selectedGenre)
                            },
                            request.albums?.search(query) { active.ensureActive() }.orEmpty(),
                            request.artists?.search(query) { active.ensureActive() }.orEmpty(),
                            request.playlists?.search(query) { active.ensureActive() }.orEmpty(),
                        )
                }
        }
    val current = completed?.takeIf { it.first == request }?.second
    return LocalSearchState(
        current ?: LocalSearchResults(),
        (query.isNotBlank() || selectedGenre != null) && current == null,
        songIndex?.takeIf { it.index.source == songs }?.genres.orEmpty(),
        songIndex?.index?.source == songs,
    )
}
