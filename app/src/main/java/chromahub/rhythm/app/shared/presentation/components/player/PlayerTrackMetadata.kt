// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.shared.presentation.components.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.discovery.GenreCorrections
import chromahub.rhythm.app.features.local.data.metadata.CatalogEnrichmentService
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import chromahub.rhythm.app.util.GenreUtils
import chromahub.rhythm.app.util.ReleaseDates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.metadata.crawler.*

@Composable
fun PlayerTrackMetadata(song: Song?, foreground: Color = MaterialTheme.colorScheme.onSurface,
    centered: Boolean = false, compact: Boolean = false, onClick: () -> Unit) {
    if (song == null) return
    val context = LocalContext.current
    val service = remember { CatalogEnrichmentService.get(context) }
    val revision by service.changes.collectAsState()
    val genreRevision by GenreCorrections.revision.collectAsState()
    var genres by remember(song.uri) { mutableStateOf(GenreUtils.splitGenres(song.genre)) }
    var date by remember(song.uri) { mutableStateOf(ReleaseDates.display(null, song.year)) }
    var tagDate by remember(song.uri, song.dateModified) { mutableStateOf<String?>(null) }
    LaunchedEffect(song.uri, song.dateModified) {
        tagDate = withContext(Dispatchers.IO) { chromahub.rhythm.app.features.local.data.metadata.TagReleaseDates.read(context, song) }
    }
    LaunchedEffect(song, revision, genreRevision, tagDate) {
        val value = withContext(Dispatchers.IO) {
            val genre = GenreCorrections(context).apply(listOf(song)).single().genre
            val manual = service.read(EntityKind.TRACK, "manual:${service.songKey(song)}")?.document
            val track = service.read(EntityKind.TRACK, service.songKey(song))?.document
            val album = service.read(EntityKind.ALBUM, service.albumKey(song.album, service.effectiveAlbumArtist(song)))?.document
            GenreUtils.splitGenres(genre) to ReleaseDates.display(manual?.get(CatalogField.TRACK_RELEASE_DATE)
                ?: track?.get(CatalogField.TRACK_RELEASE_DATE) ?: album?.get(CatalogField.ALBUM_RELEASE_DATE) ?: tagDate, song.year)
        }
        genres = value.first; date = value.second
    }
    if (genres.isEmpty() && date == null) return
    Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 36.dp else 44.dp).testTag("player-track-metadata")
        .clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        if (genres.isNotEmpty()) Surface(color = foreground.copy(alpha = .09f), contentColor = foreground,
            shape = RoundedCornerShape(12.dp), modifier = Modifier.widthIn(max = if (compact) 160.dp else 220.dp)) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(MaterialSymbolIcon("category"), stringResource(R.string.metadata_genre), Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(genres.take(2).joinToString(" · ") + if (genres.size > 2) " +${genres.size - 2}" else "",
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        date?.let { release ->
            Spacer(Modifier.width(8.dp))
            Icon(MaterialSymbolIcon("calendar_month"), stringResource(R.string.player_release_date), Modifier.size(14.dp), tint = foreground.copy(alpha = .8f))
            Spacer(Modifier.width(5.dp)); Text(release, style = MaterialTheme.typography.labelSmall, color = foreground.copy(alpha = .85f), maxLines = 1)
        }
    }
}
