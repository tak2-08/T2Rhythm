// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.metadata.CatalogImportRepository
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import kotlinx.coroutines.*
import kr.co.metadata.crawler.*

internal fun Provider?.displayName() = when (this) {
    Provider.BUGS_MOBILE, Provider.BUGS_PC -> "Bugs"
    Provider.MELON -> "Melon"
    Provider.GENIE -> "Genie"
    null -> ""
}

@Composable
fun CatalogArtworkThumbnail(url: String?, modifier: Modifier = Modifier, description: String? = null) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Icon(MaterialSymbolIcon("image"), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (url != null) AsyncImage(ImageRequest.Builder(LocalContext.current).data(url).size(400).build(), description,
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { failed = true }, onSuccess = { failed = false })
        if (failed) Text(stringResource(R.string.entity_image_load_failed), Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun CatalogImagePreview(url: String, onDismiss: () -> Unit) {
    var scale by remember(url) { mutableFloatStateOf(1f) }; var offset by remember(url) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 4f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.testTag("catalog-full-image-preview").fillMaxWidth(.96f).fillMaxHeight(.9f).systemBarsPadding(), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.entity_image_preview), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) { Icon(MaterialSymbolIcon("close"), stringResource(R.string.ui_close)) }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).transformable(transform)) {
                    AsyncImage(ImageRequest.Builder(LocalContext.current).data(url).size(1600).build(),
                        stringResource(R.string.entity_image_preview), Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                        }, contentScale = ContentScale.Fit)
                }
            }
        }
    }
}

/** A selected online image is staged in the parent editor until its explicit Save action. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogArtworkDialog(initialQuery: String, initialKind: EntityKind, onDismiss: () -> Unit, onSelect: (Uri) -> Unit,
    allowAlbums: Boolean = false, lookupSong: chromahub.rhythm.app.shared.data.model.Song? = null) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val keyboard = LocalSoftwareKeyboardController.current
    val repository = remember { CatalogImportRepository(context.applicationContext) }
    var query by remember { mutableStateOf(initialQuery) }; var kind by remember { mutableStateOf(initialKind) }
    var provider by remember { mutableStateOf<Provider?>(null) }; var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var chosen by remember { mutableStateOf<SearchHit?>(null) }; var image by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }; var generation by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableStateOf(false) }
    var youtube by remember { mutableStateOf(emptyList<chromahub.rhythm.app.features.local.data.metadata.YouTubeArtworkMatch>()) }
    var youtubeChoice by remember { mutableStateOf<chromahub.rhythm.app.features.local.data.metadata.YouTubeArtworkMatch?>(null) }
    fun run(action: suspend () -> Unit) {
        job?.cancel(); val current = ++generation; busy = true; message = null
        job = scope.launch {
            try { action() } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = context.getString(R.string.catalog_error) }
            finally { if (current == generation) busy = false }
        }
    }
    fun search() { if (query.isNotBlank()) {
        keyboard?.hide(); run { chosen = null; youtubeChoice = null; image = null
            hits = repository.search(provider, kind, query.trim())
            youtube = if (kind == EntityKind.TRACK) repository.youtubeFallback(lookupSong, hits) else emptyList()
            if (hits.isEmpty() && youtube.isEmpty()) message = context.getString(R.string.catalog_no_results)
        }
    } }
    LaunchedEffect(Unit) { search() }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    val maximumHeight = LocalConfiguration.current.screenHeightDp.dp * .9f
    Dialog(onDismissRequest = { ++generation; job?.cancel(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).heightIn(max = maximumHeight).imePadding(), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.entity_artwork_search), style = MaterialTheme.typography.titleLarge)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (chosen == null && youtubeChoice == null) LazyVerticalGrid(GridCells.Adaptive(130.dp), Modifier.weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "controls") {
                      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (allowAlbums) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(EntityKind.TRACK, EntityKind.ALBUM).forEach { option -> FilterChip(kind == option, enabled = !busy,
                                onClick = { kind = option; search() }, label = { Text(option.label(context)) }) }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(null, Provider.MELON, Provider.BUGS_MOBILE, Provider.GENIE).forEach { option ->
                                FilterChip(provider == option, enabled = !busy, onClick = { provider = option; search() },
                                    label = { Text(option?.displayName() ?: stringResource(R.string.catalog_all_sources)) })
                            }
                        }
                        OutlinedTextField(query, onValueChange = { query = it.take(300) }, enabled = !busy, singleLine = true,
                            label = { Text(stringResource(R.string.entity_artwork_query)) }, modifier = Modifier.fillMaxWidth().testTag("artwork-query"))
                        Button(onClick = { search() }, enabled = !busy && query.isNotBlank(), modifier = Modifier.testTag("artwork-search")) { Text(stringResource(R.string.catalog_search)) }
                        Text(stringResource(R.string.entity_artwork_hint), style = MaterialTheme.typography.bodySmall)
                      }
                    }
                    items(hits, key = { "${it.provider}:${it.kind}:${it.providerId}" }) { hit ->
                        Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = !busy) {
                            run { chosen = hit
                                val document = repository.load(hit)
                                image = document?.values?.firstOrNull { it.field.image && it.hasValue() }?.value ?: hit.thumbnailUrl
                                if (image == null) message = context.getString(R.string.entity_artwork_missing)
                            }
                        }.padding(4.dp)) {
                            CatalogArtworkThumbnail(hit.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(1f), hit.title)
                            Text(hit.title, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                            Text(hit.provider.displayName(), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    items(youtube, key = { "youtube:${it.id}" }) { match ->
                        Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = !busy) {
                            youtubeChoice = match; image = match.thumbnail
                        }.padding(4.dp)) {
                            CatalogArtworkThumbnail(match.thumbnail, Modifier.fillMaxWidth().aspectRatio(1f), match.title)
                            Text(match.title, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.youtube_artwork_match, (match.score * 100).toInt()), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(enabled = !busy, onClick = { chosen = null; youtubeChoice = null; image = null; message = null }) { Text(stringResource(R.string.catalog_results)) }
                    Text(chosen?.title ?: youtubeChoice!!.title, style = MaterialTheme.typography.titleMedium)
                    Text(chosen?.let { listOfNotNull(it.artists, it.provider.displayName()).joinToString(" · ") }
                        ?: stringResource(R.string.youtube_artwork_match, (youtubeChoice!!.score * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                    image?.let { url ->
                        CatalogArtworkThumbnail(url, Modifier.fillMaxWidth().aspectRatio(1f).heightIn(max = 300.dp)
                            .clickable { zoom = true }, stringResource(R.string.entity_image_preview))
                        TextButton(onClick = { zoom = true }) { Text(stringResource(R.string.entity_image_zoom)) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { ++generation; job?.cancel(); onDismiss() }) { Text(stringResource(R.string.catalog_close)) }
                    if (chosen != null || youtubeChoice != null) Button(enabled = image != null && !busy, modifier = Modifier.testTag("artwork-apply"), onClick = {
                        val url = image ?: return@Button
                        run { val uri = repository.artwork(context, url); onSelect(uri) }
                    }) { Text(stringResource(R.string.entity_artwork_use)) }
                }
            }
        }
    }
    if (zoom && image != null) CatalogImagePreview(image!!) { zoom = false }
}
