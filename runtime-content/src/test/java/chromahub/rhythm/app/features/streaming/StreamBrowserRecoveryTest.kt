// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StreamBrowserRecoveryTest {
    private val connection = StreamConnection("server", "Phone", "https://phone:8898", "pin", "token")
    private fun track(id: String) = StreamTrack(id, id, "artist", "album", 1000, "", 0, "", 0, 0, "mp3", "")
    private fun page(vararg tracks: StreamTrack) = StreamPage("revision", 2, 2, 0, tracks.toList(), emptyList())
    @Test fun failedNextPagePreservesLoadedTracksAndRetriesSameOffset() = runBlocking {
        var fail = true
        val offsets = mutableListOf<Int>()
        val client = object : StreamBrowserClient {
            override suspend fun page(connection: StreamConnection, query: String, genre: String, offset: Int): StreamPage {
                offsets += offset
                if (fail) throw IOException("offline")
                return page(track("second"))
            }
            override suspend fun playlists(connection: StreamConnection) = emptyList<StreamPlaylist>()
            override suspend fun revision(connection: StreamConnection) = "revision"
        }
        val state = StreamNetworkBrowserState(listOf(connection), this, client)
        val first = track("first")
        state.sources[connection.server] = NetworkSource(connection, page(first), listOf(first))
        state.more(connection.server)
        yield()
        assertEquals(listOf("first"), state.tracks.map { it.track.id })
        assertNotNull(state.sources[connection.server]!!.pageError)
        assertFalse(state.busy)
        fail = false
        state.retry(connection.server)
        yield()
        assertEquals(listOf(1, 1), offsets)
        assertEquals(listOf("first", "second"), state.tracks.map { it.track.id })
        assertNull(state.sources[connection.server]!!.pageError)
        state.cancel()
    }
    @Test fun cancelledPageCannotReplaceNewSearchResults() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val client = object : StreamBrowserClient {
            override suspend fun page(connection: StreamConnection, query: String, genre: String, offset: Int): StreamPage {
                withContext(NonCancellable) { gate.await() }
                return page(track("stale"))
            }
            override suspend fun playlists(connection: StreamConnection) = emptyList<StreamPlaylist>()
            override suspend fun revision(connection: StreamConnection) = "revision"
        }
        val state = StreamNetworkBrowserState(listOf(connection), this, client)
        state.sources[connection.server] = NetworkSource(connection, page(track("first")), listOf(track("first")))
        state.more(connection.server)
        yield()
        state.cancel()
        state.sources[connection.server] = NetworkSource(connection, page(track("new")), listOf(track("new")))
        gate.complete(Unit)
        yield()
        assertEquals(listOf("new"), state.tracks.map { it.track.id })
    }
}
