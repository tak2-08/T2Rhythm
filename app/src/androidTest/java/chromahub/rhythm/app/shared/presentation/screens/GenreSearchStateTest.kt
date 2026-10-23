// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.shared.presentation.screens

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import chromahub.rhythm.app.shared.data.model.Song
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GenreSearchStateTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private fun song(id: String, title: String, genre: String) = Song(id, title, "Artist", "Album", duration = 1000,
        uri = Uri.parse("content://test/$id"), genre = genre)

    @Test fun genreOnlySearchAndExactSelectionRemainIndependentOfOrdinaryFilters() {
        val songs = listOf(song("1", "Rock title only", "JAZZ"), song("2", "Quiet", "FOLK;ROCK"), song("3", "Loud", "HARD ROCK"))
        var query by mutableStateOf("rock")
        var selected by mutableStateOf<String?>(null)
        var genreOnly by mutableStateOf(true)
        var current: LocalSearchState? = null
        compose.setContent {
            val state = rememberLocalSearch(query, songs, emptyList(), emptyList(), emptyList(),
                filterSongs = false, filterAlbums = false, filterArtists = false, filterPlaylists = false,
                genreOnly = genreOnly, selectedGenre = selected)
            SideEffect { current = state }
        }
        compose.waitUntil(10_000) { current?.let { !it.searching && it.results.songs.size == 2 } == true }
        assertEquals(setOf("2", "3"), current!!.results.songs.map { it.id }.toSet())
        compose.runOnIdle { query = ""; selected = "ROCK" }
        compose.waitUntil(10_000) { current?.let { !it.searching && it.results.songs.map { it.id } == listOf("2") } == true }
        compose.runOnIdle { query = "quiet" }
        compose.waitUntil(10_000) { current?.let { !it.searching && it.results.songs.map { it.id } == listOf("2") } == true }
        compose.runOnIdle { query = "Loud" }
        compose.waitUntil(10_000) { current?.let { !it.searching && it.results.songs.isEmpty() } == true }
        compose.runOnIdle { genreOnly = false; selected = null; query = "rock" }
        compose.waitUntil(10_000) { current?.searching == false }
        // The ordinary tab still respects a disabled song filter.
        assertTrue(current!!.results.songs.isEmpty())
    }
}
