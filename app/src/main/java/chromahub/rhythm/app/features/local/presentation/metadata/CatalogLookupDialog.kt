// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.metadata.CatalogImportRepository
import chromahub.rhythm.app.features.local.data.metadata.CatalogSelection
import kotlinx.coroutines.*
import kr.co.metadata.crawler.*
import java.io.File

/** Import only checked fields into a draft; the parent editor retains its Save action. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogLookupDialog(initialQuery: String, lyricsOnly: Boolean = false, onDismiss: () -> Unit,
    onApply: (CatalogDocument, Uri?) -> Unit, initialDocument: CatalogDocument? = null) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val keyboard = LocalSoftwareKeyboardController.current
    val repository = remember { CatalogImportRepository(context.applicationContext) }
    var query by remember { mutableStateOf(initialQuery) }; var kind by remember { mutableStateOf(EntityKind.TRACK) }
    var provider by remember { mutableStateOf<Provider?>(null) }; var hits by remember { mutableStateOf(emptyList<SearchHit>()) }
    var document by remember { mutableStateOf(initialDocument) }
    var selected by remember { mutableStateOf(CatalogSelection.defaults(initialDocument, lyricsOnly)) }
    var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }; var generation by remember { mutableIntStateOf(0) }
    var previewImage by remember { mutableStateOf<String?>(null) }
    fun preview(result: CatalogDocument?) {
        document = result?.copy(values = result.values.map { value ->
            if (value.field.name.endsWith("GENRES")) value.copy(value = chromahub.rhythm.app.util.GenreUtils.splitGenres(value.value).joinToString(", ")) else value
        })
        selected = CatalogSelection.defaults(document, lyricsOnly)
        if (result == null || result.values.isEmpty()) message = context.getString(R.string.catalog_no_metadata)
        else if (lyricsOnly && result[CatalogField.TRACK_LYRICS].isNullOrBlank()) message = context.getString(R.string.catalog_no_lyrics)
    }
    fun run(action: suspend () -> Unit) {
        job?.cancel(); val current = ++generation; busy = true; message = null
        job = scope.launch {
            try { action() } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = context.getString(R.string.catalog_error) }
            finally { if (generation == current) busy = false }
        }
    }
    val studio = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val name = result.data?.getStringExtra(SelectorStudioActivity.EXTRA_RESULT_FILE)
            if (name != null && name.matches(Regex("catalog-result-[a-f0-9-]+\\.json"))) run {
                preview(withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, name)
                    try { require(file.length() <= 1_000_000); CatalogDocumentCodec.decode(file.readText()) } finally { file.delete() }
                })
            }
        }
    }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    val maximumHeight = LocalConfiguration.current.screenHeightDp.dp * .92f
    Dialog(onDismissRequest = { ++generation; job?.cancel(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).heightIn(max = maximumHeight).imePadding(), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (lyricsOnly) R.string.catalog_find_lyrics else R.string.catalog_find_metadata), style = MaterialTheme.typography.titleLarge)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                  item(key = "controls") { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (document == null) {
                    if (!lyricsOnly) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(EntityKind.TRACK, EntityKind.ALBUM, EntityKind.ARTIST).forEach { option ->
                            FilterChip(kind == option, enabled = !busy, onClick = { kind = option; hits = emptyList(); message = null }, label = { Text(option.label(context)) })
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(null, Provider.MELON, Provider.BUGS_MOBILE, Provider.GENIE).forEach { option ->
                            FilterChip(provider == option, enabled = !busy, onClick = { provider = option; hits = emptyList(); message = null },
                                label = { Text(option?.displayName() ?: stringResource(R.string.catalog_all_sources)) })
                        }
                    }
                    OutlinedTextField(query, enabled = !busy, onValueChange = { query = it.take(300) },
                        label = { Text(stringResource(R.string.catalog_query)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && query.isNotBlank(), onClick = { keyboard?.hide(); run {
                            hits = repository.search(provider, kind, query)
                            if (hits.isEmpty()) message = context.getString(R.string.catalog_no_results)
                        } }) { Text(stringResource(R.string.catalog_search)) }
                        OutlinedButton(enabled = !busy, onClick = { studio.launch(Intent(context, SelectorStudioActivity::class.java)
                            .putExtra(SelectorStudioActivity.EXTRA_KIND, kind.name)) }) { Text(stringResource(R.string.catalog_custom_site)) }
                    }
                } else {
                    TextButton(enabled = !busy, onClick = { document = null; message = null }) { Text(stringResource(R.string.catalog_results)) }
                    Text(stringResource(R.string.catalog_preview_hint), style = MaterialTheme.typography.bodySmall)
                    if (!lyricsOnly && document!!.has(CatalogField.TRACK_LYRICS)) Text(stringResource(R.string.catalog_lyrics_optional), style = MaterialTheme.typography.bodySmall)
                }
                  } }
                    val current = document
                    if (current == null) items(hits, key = { "${it.provider}:${it.kind}:${it.providerId}" }) { hit ->
                        ListItem(headlineContent = { Text(hit.title) }, supportingContent = {
                            Text(listOfNotNull(hit.artists, hit.provider.displayName()).joinToString(" · "))
                        }, leadingContent = { CatalogArtworkThumbnail(hit.thumbnailUrl, Modifier.size(56.dp), hit.title) },
                            modifier = Modifier.clickable(enabled = !busy) { run { preview(repository.load(hit)) } })
                    } else items(current.values.filter { it.hasValue() }, key = { it.field }) { value ->
                        CatalogFieldRow(value, value.field in selected, CatalogSelection.selectable(value.field, lyricsOnly), !busy,
                            onChecked = { checked -> selected = if (checked) selected + value.field else selected - value.field },
                            onPreview = { previewImage = value.value })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { ++generation; job?.cancel(); onDismiss() }) { Text(stringResource(R.string.catalog_close)) }
                    Button(enabled = !busy && selected.isNotEmpty(), modifier = Modifier.testTag("catalog-apply"), onClick = {
                        val filtered = document?.let { it.copy(values = it.values.filter { value -> value.field in selected }) } ?: return@Button
                        run { val image = filtered.values.firstOrNull { it.field.image }
                            val artwork = image?.let { repository.artwork(context, it.value) }; onApply(filtered, artwork) }
                    }) { Text(stringResource(R.string.catalog_apply)) }
                }
            }
        }
    }
    previewImage?.let { CatalogImagePreview(it) { previewImage = null } }
}

@Composable
private fun CatalogFieldRow(value: CatalogValue, checked: Boolean, selectable: Boolean, enabled: Boolean,
    onChecked: (Boolean) -> Unit, onPreview: () -> Unit) {
    val fieldLabel = value.field.label(LocalContext.current)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (selectable) Checkbox(checked, onCheckedChange = onChecked, enabled = enabled,
            modifier = Modifier.testTag("catalog-field-${value.field.name}").semantics { contentDescription = fieldLabel })
        Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value.field.label(LocalContext.current), style = MaterialTheme.typography.titleSmall)
            if (value.field.image) {
                CatalogArtworkThumbnail(value.value, Modifier.size(112.dp).clickable(onClick = onPreview), stringResource(R.string.entity_image_preview))
                TextButton(onClick = onPreview, modifier = Modifier.testTag("catalog-image-preview")) { Text(stringResource(R.string.entity_image_zoom)) }
            } else Text(value.value.take(if (value.field == CatalogField.TRACK_LYRICS) 1200 else 500),
                style = MaterialTheme.typography.bodyMedium, maxLines = if (value.field == CatalogField.TRACK_LYRICS) 12 else 6, overflow = TextOverflow.Ellipsis)
            val source = runCatching { java.net.URI(value.sourceUrl).host }.getOrNull().orEmpty()
            val label = when { "melon.com" in source -> "Melon"; "bugs.co.kr" in source -> "Bugs"; "genie.co.kr" in source -> "Genie"; else -> source }
            if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
