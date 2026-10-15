// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.discovery.DiscoveryKeywords
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import kotlinx.coroutines.*

@Composable
fun SearchDiscoveryPanel(
    songs: List<Song>,
    favorites: List<String>,
    viewModel: MusicViewModel,
    onKeyword: (String) -> Unit,
) {
    val context = LocalContext.current
    var keywords by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(true) }
    var charts by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf(false) }
    LaunchedEffect(songs, favorites) {
        loading = true
        try {
            delay(250)
            keywords =
                withContext(Dispatchers.IO) {
                    DiscoveryKeywords.suggest(context, songs, favorites, null)
                }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            keywords = emptyList()
        } finally {
            loading = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.discovery_keywords),
            style = MaterialTheme.typography.titleMedium,
        )
        if (keywords.isNotEmpty())
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(keywords, key = { it }) { word ->
                    SuggestionChip(
                        onClick = { onKeyword(word) },
                        label = { Text(word, maxLines = 1) },
                    )
                }
            }
        else
            Text(
                stringResource(
                    if (loading) R.string.discovery_loading_keywords
                    else R.string.discovery_empty_keywords
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { charts = true },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(MaterialSymbolIcon("trending_up"), null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.discovery_charts), maxLines = 1)
            }
            OutlinedButton(
                onClick = { review = true },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(MaterialSymbolIcon("auto_fix_high"), null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.discovery_review_short), maxLines = 1)
            }
        }
    }
    if (charts) ChartBrowser { charts = false }
    if (review) EnrichmentReview(viewModel) { review = false }
}
