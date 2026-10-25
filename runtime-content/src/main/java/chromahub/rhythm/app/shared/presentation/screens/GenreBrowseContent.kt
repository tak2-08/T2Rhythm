// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.shared.presentation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.util.GenreBrowseIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal fun rememberGenreBrowse(songs: List<Song>): GenreBrowseIndex<Song>? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val revision by chromahub.rhythm.app.features.local.data.discovery.GenreCorrections.revision.collectAsState()
    val completed by produceState<Pair<List<Song>, GenreBrowseIndex<Song>>?>(null, songs, revision) {
        value = withContext(Dispatchers.Default) {
            songs to chromahub.rhythm.app.util.LibraryGenreIndex.get(context, songs)
        }
    }
    return completed?.takeIf { it.first == songs }?.second
}

@Composable
internal fun GenreBrowseContent(
    index: GenreBrowseIndex<Song>?, results: List<String>, selectedGenre: String?,
    contentPadding: PaddingValues, modifier: Modifier = Modifier,
    onGenreClick: (String) -> Unit, onOpenSongs: () -> Unit,
) {
    val children = remember(index, selectedGenre) { selectedGenre?.let { index?.children(it) }.orEmpty() }
    val related = remember(index, selectedGenre) { selectedGenre?.let { index?.related(it) }.orEmpty() }
    // Reset the scroll position for each drill-down, while Back retains the search text.
    key(selectedGenre) {
        LazyColumn(modifier, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(selectedGenre ?: stringResource(R.string.search_browse_by_genre), style = MaterialTheme.typography.headlineMedium)
            }
            if (index == null) {
                item { CircularProgressIndicator() }
            } else if (selectedGenre != null) {
                item {
                    FilledTonalButton(onClick = onOpenSongs, modifier = Modifier.fillMaxWidth().testTag("genre-open-songs")) {
                        Text(stringResource(R.string.genre_browse_songs) + " · " +
                            pluralStringResource(R.plurals.ui_songs_count, index.count(selectedGenre), index.count(selectedGenre)))
                    }
                }
                if (children.isNotEmpty()) {
                    item { Text(stringResource(R.string.genre_browse_children), style = MaterialTheme.typography.titleMedium) }
                    items(children, key = { "child:$it" }) { GenreResult(it, index.count(it)) { onGenreClick(it) } }
                }
                if (related.isNotEmpty()) {
                    item { Text(stringResource(R.string.genre_browse_related), style = MaterialTheme.typography.titleMedium) }
                }
                items(related, key = { "related:$it" }) { GenreResult(it, index.count(it)) { onGenreClick(it) } }
                if (related.isEmpty() && children.isEmpty()) {
                    item { Text(stringResource(R.string.genre_browse_empty), style = MaterialTheme.typography.bodyMedium) }
                }
            } else {
                item { Text(stringResource(R.string.genre_browse_hint), style = MaterialTheme.typography.bodyMedium) }
                items(results, key = { it }) { GenreResult(it, index.count(it)) { onGenreClick(it) } }
                if (results.isEmpty()) {
                    item { Text(stringResource(R.string.genre_browse_empty), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
private fun GenreResult(genre: String, count: Int, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("genre-result:$genre"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(genre, style = MaterialTheme.typography.titleMedium)
            Text(pluralStringResource(R.plurals.ui_songs_count, count, count), style = MaterialTheme.typography.bodySmall)
        }
    }
}
