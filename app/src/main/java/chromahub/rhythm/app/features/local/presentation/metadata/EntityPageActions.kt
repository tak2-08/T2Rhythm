// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.metadata.*
import chromahub.rhythm.app.shared.data.model.Album
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import kotlinx.coroutines.*
import kr.co.metadata.crawler.EntityKind

/** Persistence is independent of visible controls and performs no work during composition. */
@Composable
fun rememberEntityPage(kind: EntityKind, key: String, title: String, artist: String = "", description: String?,
    artwork: Uri?, songs: List<Song>, albums: List<Album> = emptyList()): EntityPage? {
    val context = LocalContext.current
    val store = remember(context) { EntityPageStore(context) }
    val revision by remember(store, kind, key) { store.changes(kind, key) }.collectAsState()
    var page by remember(kind, key) { mutableStateOf<EntityPage?>(null) }
    LaunchedEffect(kind, key, revision) { page = withContext(Dispatchers.IO) { store.read(kind, key) } }
    LaunchedEffect(kind, key, title, artist, description, artwork, songs, albums) {
        withContext(Dispatchers.IO) {
            try {
                val catalog = CatalogEnrichmentService.get(context)
                val current = store.snapshot(kind, key, title, artist, description.orEmpty(), songs.map {
                    PageTrack(it.title, it.artist, it.album, it.duration, it.year, it.genre, catalog.songKey(it))
                }, albums.map { PageAlbum(it.title, it.artist, it.year) })
                if (artwork != null && current.image == null && !current.imageEdited)
                    withTimeout(12_000) { store.save(kind, key, title, artist, current.description, artwork, edited = false, customImage = false) }
            } catch (_: TimeoutCancellationException) { /* The worker retries unavailable images. */ }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Background materialization never interrupts the page. */ }
        }
    }
    return page
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityEditMenu(kind: EntityKind, title: String, onDismiss: () -> Unit, onDescription: () -> Unit,
    onArtwork: () -> Unit, onTags: (() -> Unit)? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp).navigationBarsPadding().testTag("entity-edit-menu")) {
            Text(title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
            ListItem(headlineContent = { Text(stringResource(R.string.entity_edit_description)) },
                leadingContent = { Icon(MaterialSymbolIcon("edit_note"), null) },
                modifier = Modifier.clickable(onClick = onDescription).testTag("entity-edit-description"))
            ListItem(headlineContent = { Text(stringResource(R.string.entity_edit_artwork, kind.label(LocalContext.current))) },
                leadingContent = { Icon(MaterialSymbolIcon("image"), null) },
                modifier = Modifier.clickable(onClick = onArtwork).testTag("entity-edit-artwork"))
            if (onTags != null) ListItem(headlineContent = { Text(stringResource(R.string.entity_edit_tags)) },
                leadingContent = { Icon(MaterialSymbolIcon("library_music"), null) }, modifier = Modifier.clickable(onClick = onTags))
        }
    }
}

@Composable
fun EntityDescriptionEditor(kind: EntityKind, key: String, title: String, artist: String = "", description: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current; val store = remember(context) { EntityPageStore(context) }; val scope = rememberCoroutineScope()
    var draft by remember(kind, key) { mutableStateOf(description.orEmpty()) }
    var ready by remember(kind, key) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }; var failed by remember { mutableStateOf(false) }
    LaunchedEffect(kind, key) { draft = withContext(Dispatchers.IO) { store.read(kind, key)?.description } ?: description.orEmpty(); ready = true }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(R.string.entity_edit_description)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(draft, onValueChange = { draft = it.take(100_000) }, enabled = ready && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp).testTag("page-description"),
                label = { Text(stringResource(R.string.page_description)) })
            if (busy || !ready) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) Text(stringResource(R.string.page_operation_failed), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(enabled = ready && !busy, onClick = {
            busy = true; failed = false
            scope.launch {
                try { store.save(kind, key, title, artist, draft); onDismiss() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.page_save)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}
