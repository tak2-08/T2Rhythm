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
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.discovery.ChartRepository
import chromahub.rhythm.app.features.local.data.discovery.ChartSnapshot
import chromahub.rhythm.app.shared.presentation.components.common.CollapsibleHeaderScreen
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import chromahub.rhythm.app.ui.LocalMiniPlayerPadding
import kotlinx.coroutines.*
import kr.co.metadata.crawler.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PopularListScreen() {
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
                    if (filter != null)
                        InputChip(
                            selected = true,
                            onClick = { filter = null },
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
                            IconButton(onClick = { selected = row.track }) {
                                Icon(
                                    MaterialSymbolIcon("download"),
                                    stringResource(R.string.discovery_download_audio),
                                    Modifier.size(24.dp),
                                )
                            }
                        },
                        modifier = Modifier.clickable { selected = row.track },
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
                            Icon(MaterialSymbolIcon("chevron_right"), null, Modifier.size(24.dp))
                        },
                        modifier =
                            Modifier.clickable {
                                filter = entry.key
                                tab = 0
                            },
                    )
                }
        }
    }
    selected?.let { AudioSourcePicker(it) { selected = null } }
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
