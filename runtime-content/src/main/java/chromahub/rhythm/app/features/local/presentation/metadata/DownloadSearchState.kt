// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import chromahub.rhythm.app.features.local.data.discovery.*
import kotlinx.coroutines.*

/** A song search and an album search keep independent results while their sheet is open. */
@Stable
class DownloadSearchState(query: String = "", artist: String = "", source: String = "YOUTUBE", mobile: Boolean = false) {
    var query by mutableStateOf(query)
    var artist by mutableStateOf(artist)
    var source by mutableStateOf(source)
    var mobile by mutableStateOf(mobile)
    var songs by mutableStateOf(emptyList<AudioSourceCandidate>())
    var albums by mutableStateOf(emptyList<AudioAlbumCandidate>())
    var selected by mutableStateOf<AudioAlbumCandidate?>(null)
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var failed by mutableStateOf(false)
        private set
    val listState by lazy { LazyListState() }
    private var request: Job? = null
    private var generation = 0L

    fun cancel(message: String? = null) {
        generation++
        request?.cancel()
        request = null
        busy = false
        failed = false
        this.message = message
    }

    fun work(scope: CoroutineScope, failureMessage: String, block: suspend () -> Unit) {
        cancel()
        val ticket = generation
        busy = true
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (ticket == generation) { message = failureMessage; failed = true }
            } finally {
                // A canceled request must not mark a newer search as finished.
                if (ticket == generation) { busy = false; request = null }
            }
        }
        // Install the job before starting it, including operations which complete immediately.
        request = next
        next.start()
    }

    companion object {
        val Saver = listSaver<DownloadSearchState, Any>(
            save = { listOf(it.query, it.artist, it.source, it.mobile) },
            restore = { DownloadSearchState(it[0] as String, it[1] as String, it[2] as String, it[3] as Boolean) },
        )
    }
}

interface DownloadSearchSource {
    suspend fun songs(provider: String, query: String): List<AudioSourceCandidate>
    suspend fun albums(provider: String, query: String): List<AudioAlbumCandidate>
    suspend fun tracks(album: AudioAlbumCandidate): List<AudioSourceCandidate>
    companion object : DownloadSearchSource {
        override suspend fun songs(provider: String, query: String) = AudioSources.search(provider, query)
        override suspend fun albums(provider: String, query: String) = AudioSources.albums(provider, query)
        override suspend fun tracks(album: AudioAlbumCandidate) = AudioSources.albumTracks(album)
    }
}
