// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.discovery.*
import chromahub.rhythm.app.features.local.data.video.AudioDownload
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import kr.co.metadata.crawler.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartBrowser(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { ChartRepository(context) }
    var provider by remember { mutableStateOf(Provider.BUGS_PC) }
    var tab by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<ChartSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var target by remember { mutableStateOf<ChartTrack?>(null) }
    var filter by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    LaunchedEffect(provider, refresh) {
        busy = true
        failed = false
        filter = null
        snapshot = withContext(Dispatchers.IO) { repository.cached(provider) }
        try {
            snapshot = repository.refresh(provider, force = refresh > 0)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        } finally {
            busy = false
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 720.dp).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        context.getString(R.string.discovery_charts),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    IconButton(enabled = !busy, onClick = { refresh++ }) {
                        Icon(
                            MaterialSymbolIcon("refresh"),
                            context.getString(R.string.discovery_refresh),
                            Modifier.size(24.dp),
                        )
                    }
                }
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(Provider.BUGS_PC to "벅스", Provider.MELON to "멜론", Provider.GENIE to "지니")
                        .forEachIndexed { i, (source, label) ->
                            SegmentedButton(
                                selected = provider == source,
                                onClick = { provider = source },
                                shape = SegmentedButtonDefaults.itemShape(i, 3),
                            ) {
                                Text(label)
                            }
                        }
                }
            }
            item {
                PrimaryTabRow(selectedTabIndex = tab) {
                    listOf(
                            R.string.discovery_songs,
                            R.string.discovery_albums,
                            R.string.discovery_artists,
                        )
                        .forEachIndexed { i, label ->
                            Tab(
                                selected = tab == i,
                                onClick = {
                                    tab = i
                                    filter = null
                                },
                                text = { Text(context.getString(label)) },
                            )
                        }
                }
            }
            item {
                Text(
                    context.getString(R.string.discovery_chart_basis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            snapshot
                ?.takeIf { it.provider == provider }
                ?.let { data ->
                    item {
                        Text(
                            context.getString(
                                R.string.discovery_updated,
                                java.text.DateFormat.getDateTimeInstance(
                                        java.text.DateFormat.SHORT,
                                        java.text.DateFormat.SHORT,
                                        context.resources.configuration.locales[0],
                                    )
                                    .format(java.util.Date(data.fetchedAt)),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (failed)
                item {
                    Text(
                        context.getString(R.string.discovery_chart_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            filter?.let { name ->
                item {
                    TextButton(onClick = { filter = null }) {
                        Text(
                            name.first + " · " + context.getString(R.string.discovery_clear_filter)
                        )
                    }
                }
            }
            val all = snapshot?.takeIf { it.provider == provider }?.tracks.orEmpty()
            if (tab == 0) {
                val tracks =
                    all.filter {
                        filter == null ||
                            filter?.let { selected ->
                                CatalogCharts.entityKey(it, selected.third) == selected.second
                            } == true
                    }
                items(tracks, key = { it.id }) { track ->
                    ListItem(
                        headlineContent = {
                            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = { Text(track.artist, maxLines = 1) },
                        overlineContent = { Text("#${track.rank}") },
                        leadingContent = { ChartCover(track.artwork) },
                        trailingContent = {
                            IconButton(onClick = { target = track }) {
                                Icon(
                                    MaterialSymbolIcon("download"),
                                    context.getString(R.string.discovery_download_audio),
                                    Modifier.size(24.dp),
                                )
                            }
                        },
                        modifier = Modifier.clickable { target = track },
                    )
                }
            } else {
                items(
                    CatalogCharts.entities(all, tab == 1),
                    key = { it.providerId ?: it.title + it.artist },
                ) { entity ->
                    ListItem(
                        headlineContent = { Text(entity.title, maxLines = 1) },
                        supportingContent = {
                            Text(
                                context.getString(
                                    R.string.discovery_appearances,
                                    entity.appearances,
                                    entity.bestRank,
                                )
                            )
                        },
                        leadingContent = {
                            if (tab == 1) ChartCover(entity.artwork)
                            else Icon(MaterialSymbolIcon("person"), null, Modifier.size(48.dp))
                        },
                        modifier =
                            Modifier.clickable {
                                val album = tab == 1
                                val member =
                                    all.firstOrNull {
                                        if (album)
                                            (if (entity.providerId != null)
                                                it.albumId == entity.providerId
                                            else
                                                it.album == entity.title &&
                                                    it.artist == entity.artist)
                                        else
                                            (if (entity.providerId != null)
                                                it.artistId == entity.providerId
                                            else it.artist == entity.title)
                                    }
                                if (member != null) {
                                    filter =
                                        Triple(
                                            entity.title,
                                            CatalogCharts.entityKey(member, album),
                                            album,
                                        )
                                    tab = 0
                                }
                            },
                    )
                }
            }
            item {
                TextButton(
                    onClick = {
                        runCatching {
                                context.startActivity(
                                    Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(CatalogCharts.url(provider)),
                                        )
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                            .onFailure { failed = true }
                    }
                ) {
                    Text(context.getString(R.string.discovery_open_source))
                }
            }
        }
    }
    target?.let { track -> AudioSourcePicker(track) { target = null } }
}

@Composable
private fun AudioSourcePicker(track: ChartTrack, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var service by remember { mutableStateOf("YOUTUBE") }
    var query by remember { mutableStateOf("${track.artist} ${track.title}") }
    var url by remember { mutableStateOf("") }
    var found by remember { mutableStateOf(emptyList<AudioSourceCandidate>()) }
    var selected by remember { mutableStateOf<AudioSourceCandidate?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var mobile by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    fun search() {
        searchJob?.cancel()
        val source = service
        val term = query
        searchJob =
            scope.launch {
                busy = true
                failed = false
                selected = null
                try {
                    val result = AudioSources.search(source, term)
                    if (service == source) {
                        found = result
                        failed = result.isEmpty()
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    if (service == source) failed = true
                } finally {
                    if (service == source && currentCoroutineContext()[Job] === searchJob)
                        busy = false
                }
            }
    }
    fun download() {
        scope.launch {
            val source = selected ?: return@launch
            busy = true
            failed = false
            try {
                AudioDownload.enqueueSource(
                    context,
                    source.url,
                    track.title,
                    track.artist,
                    track.album,
                    mobile,
                )
                accepted = true
                onDismiss()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
    val permission =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) download() else failed = true
        }
    LaunchedEffect(service) {
        found = emptyList()
        selected = null
        searchJob?.cancel()
        searchJob = null
        busy = false
        failed = false
        if (service != "DIRECT") search()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(track.title, maxLines = 2) },
        text = {
            LazyColumn(
                Modifier.heightIn(max = 430.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        context.getString(R.string.discovery_audio_source_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                                "YOUTUBE" to "YouTube",
                                "SOUNDCLOUD" to "SoundCloud",
                                "DIRECT" to context.getString(R.string.discovery_direct),
                            )
                            .forEach { (value, label) ->
                                FilterChip(
                                    selected = service == value,
                                    onClick = { service = value },
                                    label = {
                                        Text(label, style = MaterialTheme.typography.labelSmall)
                                    },
                                )
                            }
                    }
                }
                if (service == "DIRECT")
                    item {
                        OutlinedTextField(
                            url,
                            {
                                url = it
                                selected =
                                    runCatching {
                                            AudioSources.service(it)
                                            AudioSourceCandidate(it, track.title, track.artist, 0)
                                        }
                                        .getOrNull()
                            },
                            label = { Text(context.getString(R.string.discovery_direct_url)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                else {
                    item {
                        OutlinedTextField(
                            query,
                            { query = it },
                            label = { Text(context.getString(R.string.discovery_source_search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        TextButton(enabled = !busy && query.isNotBlank(), onClick = { search() }) {
                            Text(context.getString(R.string.discovery_source_search))
                        }
                    }
                    items(found, key = { it.url }) { candidate ->
                        Surface(
                            onClick = { selected = candidate },
                            color =
                                if (selected?.url == candidate.url)
                                    MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(
                                    candidate.title,
                                    maxLines = 2,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    candidate.artist +
                                        " · " +
                                        candidate.durationSeconds / 60 +
                                        ":" +
                                        (candidate.durationSeconds % 60)
                                            .toString()
                                            .padStart(2, '0'),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (failed)
                    item {
                        Text(
                            context.getString(R.string.discovery_operation_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                selected?.let { candidate ->
                    item {
                        Text(
                            context.getString(R.string.discovery_selected_source, candidate.title),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = {
                                runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(candidate.url))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                    .onFailure { failed = true }
                            }
                        ) {
                            Text(context.getString(R.string.discovery_preview_source))
                        }
                    }
                }
                item {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(mobile, { mobile = it })
                        Text(
                            context.getString(R.string.discovery_mobile),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (accepted) item { Text(context.getString(R.string.discovery_queued)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null && !busy,
                onClick = {
                    if (
                        android.os.Build.VERSION.SDK_INT < 29 &&
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    )
                        permission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    else download()
                },
            ) {
                Text(context.getString(R.string.discovery_download_audio))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(context.getString(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun ChartCover(url: String?) {
    var failed by remember(url) { mutableStateOf(false) }
    Surface(
        Modifier.size(48.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        if (url == null || failed)
            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                Icon(MaterialSymbolIcon("album"), null, Modifier.size(24.dp))
            }
        else
            AsyncImage(
                url,
                null,
                Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
            )
    }
}
