// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.metadata.EntityPageStore
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import kotlinx.coroutines.*
import kr.co.metadata.crawler.EntityKind

@Composable
fun EntityPageActions(kind: EntityKind, key: String, title: String, artist: String = "",
    description: String?, artwork: Uri?, modifier: Modifier = Modifier) {
    val context = LocalContext.current; val store = remember { EntityPageStore(context) }; val scope = rememberCoroutineScope()
    var editing by remember(kind, key) { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }; var selectedImage by remember { mutableStateOf<Uri?>(null) }
    var removeImage by remember { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun run(action: suspend () -> Unit) { if (!busy) { busy = true; message = null; scope.launch {
        try { action() } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { message = context.getString(R.string.page_operation_failed) }
        finally { busy = false }
    } } }
    LaunchedEffect(kind, key, description, artwork) {
        withContext(Dispatchers.IO) {
            val page = store.capture(kind, key, title, artist, description.orEmpty())
            if (artwork != null && !page.imageEdited && page.imageSource != artwork.toString() && store.image(page) != artwork)
                try { store.save(kind, key, title, artist, page.description, artwork, edited = page.edited, customImage = false) }
                catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { /* Keep readable offline page. */ }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) { selectedImage = uri; removeImage = false } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.t2rhythm.page")) { uri ->
        if (uri != null) run { withContext(Dispatchers.IO) {
            var page = store.read(kind, key) ?: store.capture(kind, key, title, artist, description.orEmpty())
            if (artwork != null && store.image(page) == null && !page.imageEdited)
                page = store.save(kind, key, title, artist, page.description, artwork, edited = page.edited, customImage = false)
            context.contentResolver.openOutputStream(uri, "wt")!!.use { store.export(page, it) }
        }; message = context.getString(R.string.page_exported) }
    }
    val importPage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run { withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)!!.use { store.importPage(kind, key, title, artist, it) }
        }; message = context.getString(R.string.page_imported) }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !busy, modifier = Modifier.weight(1f).testTag("page-edit"), onClick = {
                run { draft = withContext(Dispatchers.IO) { store.read(kind, key)?.description } ?: description.orEmpty()
                    selectedImage = null; removeImage = false; editing = true }
            }) { Icon(MaterialSymbolIcon("edit_note"), null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.page_edit)) }
            IconButton(enabled = !busy, onClick = { export.launch(title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(70) + ".t2page") }) {
                Icon(MaterialSymbolIcon("download"), stringResource(R.string.page_export))
            }
            IconButton(enabled = !busy, onClick = { importPage.launch(arrayOf("application/vnd.t2rhythm.page", "application/octet-stream", "application/zip")) }) {
                Icon(MaterialSymbolIcon("upload"), stringResource(R.string.page_import))
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (editing) AlertDialog(onDismissRequest = { if (!busy) editing = false }, title = { Text(stringResource(R.string.page_edit)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(draft, onValueChange = { draft = it.take(100_000) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 230.dp).testTag("page-description"), label = { Text(stringResource(R.string.page_description)) })
            TextButton(enabled = !busy, onClick = { pick.launch("image/*") }) { Text(stringResource(if (selectedImage == null) R.string.page_choose_image else R.string.page_image_selected)) }
            TextButton(enabled = !busy, onClick = { selectedImage = null; removeImage = true }) { Text(stringResource(R.string.page_remove_image)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = { run {
            store.save(kind, key, title, artist, draft, selectedImage, removeImage = removeImage); editing = false
        } }) { Text(stringResource(R.string.page_save)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { editing = false }) { Text(stringResource(android.R.string.cancel)) } })
}
