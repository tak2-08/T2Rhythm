// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.discovery.ChartRepository
import chromahub.rhythm.app.features.local.data.discovery.ChartSnapshot
import chromahub.rhythm.app.features.local.data.discovery.PopularLibrary
import chromahub.rhythm.app.features.local.data.video.DownloadRecord
import chromahub.rhythm.app.features.local.data.video.DownloadRegistry
import chromahub.rhythm.app.features.local.data.video.DownloadedAudioLibrary
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.shared.presentation.components.common.CollapsibleHeaderScreen
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import chromahub.rhythm.app.ui.LocalMiniPlayerPadding
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kr.co.metadata.crawler.*

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun PopularListScreen(songs: List<Song> = emptyList(), onPlay: (Song) -> Unit = {}) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val repository = remember(context) { ChartRepository(context) }
    var snapshots by remember { mutableStateOf(emptyList<ChartSnapshot>()) }
    var failures by remember { mutableStateOf(emptyList<Provider>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<ChartTrack?>(null) }
    var info by remember { mutableStateOf(false) }
    var lookup by remember { mutableStateOf(PopularLibrary(emptyList())) }
    var indexedSongs by remember { mutableStateOf<List<Song>?>(null) }
    val matchingReady = indexedSongs === songs
    var downloads by remember { mutableStateOf(emptyList<DownloadRecord>()) }
    var completed by remember { mutableStateOf(PopularLibrary(emptyList())) }
    var albumDownload by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(songs) {
        lookup = withContext(Dispatchers.Default) { PopularLibrary(songs) }
        indexedSongs = songs
    }
    LaunchedEffect(context) {
        var workState: List<Pair<String, androidx.work.WorkInfo.State>>? = null
        var history = emptyList<DownloadRecord>()
        var completedState = emptyList<DownloadRecord>()
        combine(
                WorkManager.getInstance(context).getWorkInfosByTagFlow(DownloadRegistry.TAG),
                DownloadRegistry.revision,
            ) { work, _ ->
                work
            }
            .debounce(250)
            .collect { work ->
                val restored =
                    withContext(Dispatchers.IO) {
                        val jobs = work.associateBy { it.id.toString() }
                        val state = work.map { it.id.toString() to it.state }
                        if (state != workState) {
                            history = DownloadRegistry.recent(context, 1000)
                            workState = state
                        }
                        (work
                                .filter { !it.state.isFinished }
                                .mapNotNull { DownloadRegistry.get(context, it.id.toString()) } +
                                history)
                            .distinctBy { it.id }
                            .map { DownloadRegistry.merge(it, jobs[it.id]) }
                    }
                downloads = restored
                val ready =
                    restored.filter { it.kind == "AUDIO" && it.state == "SUCCEEDED" }.take(20)
                if (ready != completedState) {
                    completedState = ready
                    completed =
                        withContext(Dispatchers.IO) {
                            PopularLibrary(
                                ready.mapNotNull { record ->
                                    DownloadedAudioLibrary.existing(context, record)?.let { (uri, _)
                                        ->
                                        Song(
                                            uri.lastPathSegment?.takeIf {
                                                it.toLongOrNull() != null
                                            } ?: "download:" + record.id,
                                            record.title,
                                            record.artist,
                                            record.album,
                                            duration = 0L,
                                            uri = uri,
                                        )
                                    }
                                }
                            )
                        }
                }
            }
    }

    LaunchedEffect(refresh) {
        loading = true
        try {
            if (snapshots.isEmpty())
                snapshots =
                    withContext(Dispatchers.IO) {
                        PopularRanking.weights.keys.mapNotNull(repository::cached)
                    }
            val (data, failed) = repository.popular(force = refresh > 0)
            snapshots = data
            failures = failed
        } finally {
            loading = false
        }
    }
    val ranked = remember(snapshots) { PopularRanking.rank(snapshots.flatMap { it.tracks }) }
    val members =
        remember(ranked, filter) {
            ranked.filter {
                filter == null || entityKey(it.track, filter!!.startsWith("album:")) == filter
            }
        }
    val groups =
        remember(ranked, tab) {
            if (tab == 0) emptyList()
            else
                ranked
                    .filter { tab == 2 || !CatalogIdentity.missing(it.track.album) }
                    .groupBy { entityKey(it.track, tab == 1) }
                    .entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, List<PopularTrack>>> { entry ->
                                entry.value.sumOf { it.score }
                            }
                            .thenBy { it.key }
                    )
        }
    CollapsibleHeaderScreen(
        title = stringResource(R.string.discovery_charts),
        actions = {
            IconButton(onClick = { info = true }) {
                Icon(
                    MaterialSymbolIcon("info"),
                    stringResource(R.string.popular_basis),
                    Modifier.size(24.dp),
                )
            }
            IconButton(enabled = !loading, onClick = { refresh++ }) {
                Icon(
                    MaterialSymbolIcon("refresh"),
                    stringResource(R.string.discovery_refresh),
                    Modifier.size(24.dp),
                )
            }
        },
        headerContent = {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf(
                        R.string.discovery_songs,
                        R.string.discovery_albums,
                        R.string.discovery_artists,
                    )
                    .forEachIndexed { i, title ->
                        Tab(
                            selected = tab == i,
                            onClick = {
                                tab = i
                                filter = null
                                albumDownload = false
                            },
                            text = { Text(stringResource(title)) },
                        )
                    }
            }
        },
    ) { modifier ->
        LazyColumn(
            modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    bottom = LocalMiniPlayerPadding.current.calculateBottomPadding() + 24.dp
                ),
        ) {
            item {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(R.string.popular_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    snapshots
                        .minOfOrNull { it.fetchedAt }
                        ?.let { at ->
                            Text(
                                stringResource(
                                    R.string.discovery_updated,
                                    java.text.DateFormat.getDateTimeInstance(
                                            java.text.DateFormat.SHORT,
                                            java.text.DateFormat.SHORT,
                                            locale,
                                        )
                                        .format(java.util.Date(at)),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    if (failures.isNotEmpty())
                        Text(
                            stringResource(R.string.popular_partial),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    if (tab != 0)
                        Text(
                            stringResource(R.string.popular_entity_basis),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    if (albumDownload && filter != null)
                        Text(
                            stringResource(R.string.popular_album_download_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    if (filter != null)
                        InputChip(
                            selected = true,
                            onClick = {
                                filter = null
                                albumDownload = false
                            },
                            label = { Text(stringResource(R.string.discovery_clear_filter)) },
                            trailingIcon = {
                                Icon(MaterialSymbolIcon("close"), null, Modifier.size(18.dp))
                            },
                        )
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (ranked.isEmpty() && !loading)
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(MaterialSymbolIcon("trending_up"), null, Modifier.size(48.dp))
                        Text(stringResource(R.string.popular_empty))
                        FilledTonalButton(onClick = { refresh++ }) {
                            Text(stringResource(R.string.discovery_refresh))
                        }
                    }
                }
            if (tab == 0)
                itemsIndexed(members, key = { _, item -> item.key }) { _, row ->
                    val local = lookup.find(row.track) ?: completed.find(row.track)
                    val transfer =
                        downloads.firstOrNull {
                            PopularLibrary.active(it) && PopularLibrary.matches(it, row.track)
                        }
                    ListItem(
                        headlineContent = {
                            Text(row.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Column {
                                Text(
                                    row.track.artist,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    row.providers.joinToString(" · ") { providerName(it) },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        leadingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    row.rank.toString(),
                                    Modifier.width(28.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                    color =
                                        if (row.rank <= 3) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                ChartCover(row.track.artwork)
                            }
                        },
                        trailingContent = {
                            PopularAction(
                                local != null,
                                transfer != null,
                                transfer?.let {
                                    if (it.totalBytes > 0)
                                        (it.bytes.toFloat() / it.totalBytes).coerceIn(0f, 1f)
                                    else null
                                },
                                enabled = matchingReady,
                            ) {
                                if (local != null) onPlay(local)
                                else if (transfer == null) selected = row.track
                            }
                        },
                        modifier =
                            Modifier.clickable(enabled = matchingReady) {
                                if (local != null) onPlay(local) else selected = row.track
                            },
                    )
                }
            else
                itemsIndexed(groups, key = { _, entry -> entry.key }) { index, entry ->
                    val first = entry.value.first().track
                    ListItem(
                        headlineContent = {
                            Text(
                                if (tab == 1) first.album else first.artist,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                if (tab == 1) first.artist
                                else
                                    stringResource(R.string.popular_tracks_count, entry.value.size),
                                maxLines = 1,
                            )
                        },
                        leadingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    (index + 1).toString(),
                                    Modifier.width(28.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                if (tab == 1) ChartCover(first.artwork)
                                else Icon(MaterialSymbolIcon("person"), null, Modifier.size(48.dp))
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (tab == 1) {
                                    val active =
                                        downloads.any { record ->
                                            PopularLibrary.active(record) &&
                                                entry.value.any {
                                                    PopularLibrary.matches(record, it.track)
                                                }
                                        }
                                    val owned =
                                        entry.value.all {
                                            lookup.find(it.track) != null ||
                                                completed.find(it.track) != null
                                        }
                                    PopularAction(
                                        owned,
                                        active,
                                        null,
                                        album = true,
                                        enabled = matchingReady,
                                    ) {
                                        if (owned)
                                            (lookup.find(first) ?: completed.find(first))?.let(
                                                onPlay
                                            )
                                        else {
                                            filter = entry.key
                                            tab = 0
                                            albumDownload = true
                                        }
                                    }
                                }
                                Icon(
                                    MaterialSymbolIcon("chevron_right"),
                                    null,
                                    Modifier.size(24.dp),
                                )
                            }
                        },
                        modifier =
                            Modifier.clickable {
                                filter = entry.key
                                tab = 0
                                albumDownload = false
                            },
                    )
                }
        }
    }
    selected?.let { AudioSourcePicker(it, onPlay = onPlay) { selected = null } }
    if (info)
        AlertDialog(
            onDismissRequest = { info = false },
            title = { Text(stringResource(R.string.popular_basis)) },
            text = { Text(stringResource(R.string.popular_basis_detail)) },
            confirmButton = {
                TextButton(onClick = { info = false }) { Text(stringResource(android.R.string.ok)) }
            },
        )
}

private fun entityKey(track: ChartTrack, album: Boolean) =
    if (album)
        "album:" +
            CatalogIdentity.normalize(track.album) +
            "|" +
            CatalogIdentity.normalize(track.artist)
    else "artist:" + CatalogIdentity.normalize(track.artist)

private fun providerName(provider: Provider) =
    when (provider) {
        Provider.MELON -> "멜론"
        Provider.GENIE -> "지니"
        else -> "벅스"
    }

@Composable
internal fun PopularAction(
    local: Boolean,
    downloading: Boolean,
    progress: Float?,
    album: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val description =
        stringResource(
            if (local) R.string.popular_play_local
            else if (downloading) R.string.popular_downloading
            else if (album) R.string.popular_download_album else R.string.discovery_download_audio
        )
    IconButton(onClick = onClick, enabled = enabled && (!downloading || local)) {
        Box(contentAlignment = Alignment.Center) {
            if (downloading && !local) {
                if (progress == null)
                    CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.dp)
                else
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(30.dp),
                        strokeWidth = 2.dp,
                    )
            }
            Icon(
                MaterialSymbolIcon(
                    if (local) "play_arrow" else if (downloading) "arrow_downward" else "download"
                ),
                description,
                Modifier.size(if (downloading && !local) 18.dp else 24.dp),
            )
        }
    }
}
