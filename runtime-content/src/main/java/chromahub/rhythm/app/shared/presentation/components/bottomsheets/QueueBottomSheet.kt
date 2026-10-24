/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.shared.presentation.components.bottomsheets
import chromahub.rhythm.app.shared.presentation.components.bottomsheets.SheetAdaptiveType

import chromahub.rhythm.app.shared.presentation.components.icons.RhythmIcons
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import chromahub.rhythm.app.shared.presentation.components.icons.Icon

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.derivedStateOf
import chromahub.rhythm.app.R
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.shared.presentation.components.common.M3PlaceholderType
import chromahub.rhythm.app.shared.presentation.components.common.DragDropLazyColumn
import chromahub.rhythm.app.shared.presentation.components.common.ExpressiveCookieEmptyState
import chromahub.rhythm.app.shared.presentation.components.common.ExpressiveFilledTonalIconButton
import chromahub.rhythm.app.shared.presentation.components.common.ExpressiveShapeTarget
import chromahub.rhythm.app.shared.presentation.components.common.rememberExpressiveShapeFor
import chromahub.rhythm.app.shared.presentation.components.common.rememberExpressiveShape
import chromahub.rhythm.app.shared.presentation.components.common.ExpressiveScrollBar
import chromahub.rhythm.app.shared.presentation.components.common.RhythmButtonSize
import chromahub.rhythm.app.shared.presentation.components.common.RhythmButtonType
import chromahub.rhythm.app.shared.presentation.components.common.RhythmButtonWeighted
import chromahub.rhythm.app.shared.presentation.components.common.RhythmGroupedButton
import chromahub.rhythm.app.util.HapticType
import chromahub.rhythm.app.util.HapticUtils
import chromahub.rhythm.app.util.ImageUtils
import chromahub.rhythm.app.util.QueueEntry
import chromahub.rhythm.app.util.QueueEntryKeyTracker
import androidx.compose.ui.res.stringResource


private data class QueueItemCorners(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomStart: Dp,
    val bottomEnd: Dp
) {
    fun toShape(): RoundedCornerShape =
        RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart)
}

private fun groupedQueueItemCorners(index: Int, totalCount: Int): QueueItemCorners {
    if (totalCount <= 1) return QueueItemCorners(24.dp, 24.dp, 24.dp, 24.dp)

    return when (index) {
        0 -> QueueItemCorners(24.dp, 24.dp, 6.dp, 6.dp)
        totalCount - 1 -> QueueItemCorners(6.dp, 6.dp, 24.dp, 24.dp)
        else -> QueueItemCorners(6.dp, 6.dp, 6.dp, 6.dp)
    }
}


private enum class QueueSectionLabel { PLAYED, UP_NEXT }

private data class QueueSongRow(
    val queueIndex: Int,
    val upcomingIndex: Int,
    val displayNumber: Int,
    val entryId: Long,
    val song: Song,
    val isPlayed: Boolean,
    val corners: QueueItemCorners
)

private sealed interface QueueListRow {
    data class Section(val label: QueueSectionLabel) : QueueListRow
    data class Song(val row: QueueSongRow) : QueueListRow
}

private fun queueListRowKey(row: QueueListRow): String = when (row) {
    is QueueListRow.Section -> "section_${row.label.name}"
    is QueueListRow.Song -> "entry_${row.row.entryId}"
}

private fun buildQueueListRows(
    entries: List<QueueEntry<Song>>,
    currentSongIndex: Int,
    showPlayedSongs: Boolean
): List<QueueListRow> = buildList {
    if (entries.isEmpty()) return@buildList

    val safeCurrentIndex = currentSongIndex.coerceIn(0, entries.lastIndex)
    val playedEntries = if (safeCurrentIndex > 0) {
        entries.subList(0, safeCurrentIndex)
    } else {
        emptyList()
    }

    val upcomingEntries = if (safeCurrentIndex + 1 < entries.size) {
        entries.subList(safeCurrentIndex + 1, entries.size)
    } else {
        emptyList()
    }

    if (showPlayedSongs && playedEntries.isNotEmpty()) {
        add(QueueListRow.Section(QueueSectionLabel.PLAYED))
        playedEntries.forEachIndexed { index, entry ->
            add(
                QueueListRow.Song(
                    QueueSongRow(
                        queueIndex = index,
                        upcomingIndex = -1,
                        displayNumber = 0,
                        entryId = entry.entryId,
                        song = entry.item,
                        isPlayed = true,
                        corners = groupedQueueItemCorners(index, playedEntries.size)
                    )
                )
            )
        }
    }

    if (upcomingEntries.isNotEmpty()) {
        if (showPlayedSongs && playedEntries.isNotEmpty()) {
            add(QueueListRow.Section(QueueSectionLabel.UP_NEXT))
        }
        val upcomingBaseIndex = safeCurrentIndex + 1
        upcomingEntries.forEachIndexed { index, entry ->
            add(
                QueueListRow.Song(
                    QueueSongRow(
                        queueIndex = upcomingBaseIndex + index,
                        upcomingIndex = index,
                        displayNumber = index + 1,
                        entryId = entry.entryId,
                        song = entry.item,
                        isPlayed = false,
                        corners = groupedQueueItemCorners(index, upcomingEntries.size)
                    )
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheet(
    currentSong: Song?,
    queue: List<Song>,
    currentQueueIndex: Int = 0,
    isShuffleEnabled: Boolean = false,
    repeatMode: Int = Player.REPEAT_MODE_OFF,
    onSongClick: (Song) -> Unit,
    onSongClickAtIndex: (Int) -> Unit = { _ -> },
    onDismiss: () -> Unit,
    onRemoveSongAtIndex: (Int) -> Unit = {},
    onMoveQueueItem: (Int, Int) -> Unit = { _, _ -> },
    onAddSongsClick: () -> Unit = {},
    onClearQueue: () -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    onToggleRepeat: () -> Unit = {},
    sheetState: SheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val appSettings = remember(context) { AppSettings.getInstance(context) }
    val hidePlayedQueueSongs by appSettings.hidePlayedQueueSongs.collectAsState()
    val gestureQueueSwipeToRemove by appSettings.gestureQueueSwipeToRemove.collectAsState()
    val showAlreadyPlayedSongsInQueue = !hidePlayedQueueSongs

    var queueEpoch by remember { mutableIntStateOf(0) }
    var clearRequested by remember { mutableStateOf(false) }
    val clearFadeAlpha by animateFloatAsState(
        targetValue = if (clearRequested) 0f else 1f,
        animationSpec = tween(durationMillis = 240),
        label = "queueClearFade"
    )

    val removingQueueKeys = remember { mutableStateMapOf<String, Boolean>() }
    val queueRowScope = rememberCoroutineScope()

    fun dismissQueueRow(rowKey: String, onRemoved: () -> Unit) {
        if (removingQueueKeys[rowKey] == true) return
        removingQueueKeys[rowKey] = true
        val epochAtDismiss = queueEpoch
        queueRowScope.launch {
            delay(300)
            removingQueueKeys.remove(rowKey)
            if (queueEpoch != epochAtDismiss) return@launch
            onRemoved()
        }
    }

    val keyTracker = remember { QueueEntryKeyTracker<Song> { it.id } }
    var localQueueEntries by remember { mutableStateOf(keyTracker.sync(queue)) }
    var reorderHandleInUse by remember { mutableStateOf(false) }
    var pendingReorderExpectedIds by remember { mutableStateOf<List<String>?>(null) }
    var pendingReorderGraceUpdates by remember { mutableIntStateOf(0) }
    var lastMovedFrom by remember { mutableStateOf<Int?>(null) }
    var lastMovedTo by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(queue) {
        if (reorderHandleInUse) return@LaunchedEffect
        val expectedIds = pendingReorderExpectedIds
        if (expectedIds != null) {
            val currentIds = queue.map { it.id }
            if (currentIds == expectedIds) {
                pendingReorderExpectedIds = null
                pendingReorderGraceUpdates = 0
                localQueueEntries = keyTracker.sync(queue)
            } else if (pendingReorderGraceUpdates > 0) {
                pendingReorderGraceUpdates -= 1
            } else {
                pendingReorderExpectedIds = null
                pendingReorderGraceUpdates = 0
                localQueueEntries = keyTracker.sync(queue)
            }
        } else {
            localQueueEntries = keyTracker.sync(queue)
        }
    }

    fun removeQueueSong(entryId: Long) {
        val position = localQueueEntries.indexOfFirst { it.entryId == entryId }
        if (position < 0 || position >= localQueueEntries.size) return
        val updated = localQueueEntries.toMutableList()
        updated.removeAt(position)
        keyTracker.updateEntries(updated)
        localQueueEntries = updated
        onRemoveSongAtIndex(position)
    }

    LaunchedEffect(clearRequested) {
        if (clearRequested) {
            for (entry in localQueueEntries) {
                removingQueueKeys["entry_${entry.entryId}"] = true
            }
            if (localQueueEntries.isNotEmpty()) {
                delay(280)
            }
            removingQueueKeys.clear()
            onClearQueue()
            clearRequested = false
        }
    }

    val lazyListState = rememberLazyListState()

    RhythmAdaptiveModalSheet(
        adaptiveType = SheetAdaptiveType.WIDE_DIALOG,
        lazyListState = lazyListState,
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { 
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.primary
            )
        },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onBackground,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // Header with title and actions
            QueueHeader(
                queueSize = localQueueEntries.size,
                isShuffleEnabled = isShuffleEnabled,
                repeatMode = repeatMode,
                onAddSongsClick = onAddSongsClick,
                onClearQueue = if (localQueueEntries.isNotEmpty()) {
                    {
                        queueEpoch++
                        clearRequested = true
                    }
                } else null,
                onToggleShuffle = onToggleShuffle,
                onToggleRepeat = onToggleRepeat
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Queue settings info and warnings
            if (localQueueEntries.isNotEmpty() && hidePlayedQueueSongs) {
                Column(
                    modifier = Modifier.graphicsLayer { alpha = clearFadeAlpha }
                ) {
                    QueueSettingsInfo(
                        isShuffleEnabled = isShuffleEnabled,
                        repeatMode = repeatMode,
                        hidePlayedSongs = hidePlayedQueueSongs,
                        queueSize = localQueueEntries.size
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
            
            if (localQueueEntries.isEmpty()) {
                EmptyQueueContent()
            } else {
                // Now Playing section - show current song separately
                currentSong?.let { song ->
                    NowPlayingCard(
                        song = song,
                        onClick = { onSongClick(song) },
                        modifier = Modifier.graphicsLayer { alpha = clearFadeAlpha }
                    )
                }
                
                val currentSongIndexInQueue = remember(currentSong, localQueueEntries, currentQueueIndex) {
                    if (currentSong != null && currentQueueIndex in localQueueEntries.indices && localQueueEntries[currentQueueIndex].item.id == currentSong.id) {
                        currentQueueIndex
                    } else if (currentSong != null) {
                        localQueueEntries.indexOfFirst { it.item.id == currentSong.id }.takeIf { it >= 0 } ?: currentQueueIndex
                    } else {
                        currentQueueIndex
                    }
                }.coerceIn(0, localQueueEntries.lastIndex.coerceAtLeast(0))

                val queueListRows = remember(localQueueEntries, currentSongIndexInQueue, showAlreadyPlayedSongsInQueue) {
                    buildQueueListRows(
                        entries = localQueueEntries,
                        currentSongIndex = currentSongIndexInQueue,
                        showPlayedSongs = showAlreadyPlayedSongsInQueue
                    )
                }

                val hasPlayedSection = queueListRows.any {
                    it is QueueListRow.Section && it.label == QueueSectionLabel.PLAYED
                }

                val hasUpcomingSongs = queueListRows.any {
                    it is QueueListRow.Song && !it.row.isPlayed
                }

                if (hasUpcomingSongs || hasPlayedSection) {
                    if (!hasPlayedSection) {
                        Text(
                            text = context.getString(R.string.bottomsheet_up_next),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .graphicsLayer { alpha = clearFadeAlpha }
                                .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 8.dp)
                        )
                    }
                    
                    var hasScrolledOnOpening by remember { mutableStateOf(false) }

                    LaunchedEffect(queueListRows, currentSongIndexInQueue) {
                        if (!hasScrolledOnOpening && queueListRows.isNotEmpty()) {
                            while (lazyListState.layoutInfo.visibleItemsInfo.isEmpty()) {
                                delay(10)
                            }
                            val firstUpcomingIndex = queueListRows.indexOfFirst {
                                it is QueueListRow.Song && it.row.queueIndex > currentSongIndexInQueue
                            }
                            if (firstUpcomingIndex > 0) {
                                lazyListState.scrollToItem(firstUpcomingIndex - 1)
                            }
                            hasScrolledOnOpening = true
                        }
                    }

                    val reorderableState = rememberReorderableLazyListState(
                        lazyListState = lazyListState,
                        onMove = { from, to ->
                            val fromRow = (queueListRows.firstOrNull { queueListRowKey(it) == from.key } as? QueueListRow.Song)?.row
                                ?: return@rememberReorderableLazyListState
                            val toRow = (queueListRows.firstOrNull { queueListRowKey(it) == to.key } as? QueueListRow.Song)?.row
                                ?: return@rememberReorderableLazyListState

                            if (fromRow.isPlayed || toRow.isPlayed) return@rememberReorderableLazyListState
                            if (fromRow.queueIndex == toRow.queueIndex) return@rememberReorderableLazyListState
                            if (fromRow.queueIndex !in localQueueEntries.indices || toRow.queueIndex !in localQueueEntries.indices) return@rememberReorderableLazyListState

                            if (lastMovedFrom == null) {
                                lastMovedFrom = fromRow.queueIndex
                            }
                            lastMovedTo = toRow.queueIndex

                            val updated = localQueueEntries.toMutableList()
                            val moved = updated.removeAt(fromRow.queueIndex)
                            updated.add(toRow.queueIndex, moved)
                            keyTracker.updateEntries(updated)
                            localQueueEntries = updated
                        }
                    )

                    val isReordering by remember {
                        derivedStateOf { reorderableState.isAnyItemDragging }
                    }
                    val updatedIsReordering by rememberUpdatedState(isReordering)
                    val updatedReorderHandleInUse by rememberUpdatedState(reorderHandleInUse)

                    var wasDragging by remember { mutableStateOf(false) }

                    LaunchedEffect(isReordering) {
                        if (wasDragging && !isReordering) {
                            wasDragging = false
                            val fromIndex = lastMovedFrom
                            val toIndex = lastMovedTo
                            lastMovedFrom = null
                            lastMovedTo = null

                            if (fromIndex != null && toIndex != null && fromIndex != toIndex) {
                                val expectedIds = localQueueEntries.map { it.item.id }
                                pendingReorderExpectedIds = expectedIds
                                pendingReorderGraceUpdates = 6
                                onMoveQueueItem(fromIndex, toIndex)
                            } else {
                                pendingReorderExpectedIds = null
                                pendingReorderGraceUpdates = 0
                            }
                        } else if (isReordering) {
                            wasDragging = true
                        }
                    }

                    val listDragConnection = remember(updatedIsReordering, updatedReorderHandleInUse) {
                        object : NestedScrollConnection {
                            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                if (updatedIsReordering || updatedReorderHandleInUse) {
                                    return available
                                }
                                return Offset.Zero
                            }

                            override suspend fun onPreFling(available: Velocity): Velocity {
                                if (updatedIsReordering || updatedReorderHandleInUse) {
                                    return available
                                }
                                return Velocity.Zero
                            }
                        }
                    }

                    AdaptiveSheetScrollContainer(
                        lazyListState = lazyListState,
                        enableBlend = !hasPlayedSection,
                        blendColor = MaterialTheme.colorScheme.surfaceContainer,
                        scrollBarPaddingEnd = 4.dp,
                        scrollBarPaddingTop = 8.dp,
                        scrollBarPaddingBottom = 8.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .graphicsLayer { alpha = clearFadeAlpha }
                    ) { endPadding ->
                        val rowEndPadding = 16.dp + (endPadding * (20f / 12f))
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .nestedScroll(listDragConnection),
                            userScrollEnabled = !(isReordering || reorderHandleInUse),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(
                                items = queueListRows,
                                key = { _, row -> queueListRowKey(row) }
                            ) { index, row ->
                                val rowKey = queueListRowKey(row)
                                if (row is QueueListRow.Section) {
                                    QueueListRowContent(
                                        row = row,
                                        isDragging = false,
                                        reorderSupported = false,
                                        endPadding = rowEndPadding,
                                        isRemoving = { false },
                                        enableSwipeToRemove = false,
                                        onSongClickAtIndex = {},
                                        onRequestRemove = { _, _ -> },
                                        modifier = Modifier
                                    )
                                } else {
                                    val songRow = (row as QueueListRow.Song).row
                                    val canReorder = !isShuffleEnabled && !songRow.isPlayed

                                    ReorderableItem(
                                        state = reorderableState,
                                        key = rowKey,
                                        enabled = canReorder,
                                        animateItemModifier = Modifier.animateItem(
                                            fadeInSpec = tween(durationMillis = 0),
                                            placementSpec = spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            ),
                                            fadeOutSpec = tween(durationMillis = 0)
                                        )
                                    ) { isDragging ->
                                        val scale by animateFloatAsState(
                                            targetValue = if (isDragging) 1.02f else 1f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            ),
                                            label = "queueItemScale"
                                        )

                                        val handleModifier = if (canReorder) {
                                            Modifier.draggableHandle(
                                                onDragStarted = {
                                                    reorderHandleInUse = true
                                                    HapticUtils.performHapticFeedback(context, haptics, HapticType.MEDIUM)
                                                },
                                                onDragStopped = {
                                                    reorderHandleInUse = false
                                                    HapticUtils.performHapticFeedback(context, haptics, HapticType.LIGHT)
                                                }
                                            )
                                        } else Modifier

                                        QueueListRowContent(
                                            row = row,
                                            isDragging = isDragging,
                                            reorderSupported = !isShuffleEnabled,
                                            endPadding = rowEndPadding,
                                            isRemoving = { key -> removingQueueKeys[key] == true },
                                            enableSwipeToRemove = gestureQueueSwipeToRemove,
                                            onSongClickAtIndex = onSongClickAtIndex,
                                            dragHandleModifier = handleModifier,
                                            onRequestRemove = { targetRow, itemKey ->
                                                dismissQueueRow(itemKey) { removeQueueSong(targetRow.entryId) }
                                            },
                                            modifier = Modifier.graphicsLayer {
                                                scaleX = scale
                                                scaleY = scale
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (currentSong != null) {
                    // Show empty up next content when only current song is in queue
                    // Add more spacing before empty state
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    EmptyUpNextContent()
                }
            }
        }
    }
}

@Composable
private fun QueueHeader(
    queueSize: Int,
    isShuffleEnabled: Boolean,
    repeatMode: Int,
    onAddSongsClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClearQueue: (() -> Unit)? = null,
    onToggleShuffle: () -> Unit = {},
    onToggleRepeat: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = context.getString(R.string.bottomsheet_queue),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            if (queueSize > 0) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = CircleShape
                        )
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        text = if (queueSize == 1) "1 song" else "$queueSize songs",
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Shuffle + repeat connected toggle group (delete stays separate)
            RhythmGroupedButton(
                modifier = Modifier.width(100.dp),
                isFillMaxWidth = false,
                size = RhythmButtonSize.Medium
            ) {
                RhythmButtonWeighted(
                    onClick = {
                        HapticUtils.performHapticFeedback(context, haptic, HapticType.LIGHT)
                        onToggleShuffle()
                    },
                    weight = 1f,
                    size = RhythmButtonSize.Medium,
                    type = RhythmButtonType.Tonal,
                    icon = RhythmIcons.Shuffle,
                    iconSize = 20.dp,
                    contentDescription = if (isShuffleEnabled) "Disable shuffle" else "Enable shuffle",
                    isFirst = true,
                    isLast = false,
                    expandSlotWhenSelected = false,
                    containerColor = if (isShuffleEnabled)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (isShuffleEnabled)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                val repeatEnabled = repeatMode != Player.REPEAT_MODE_OFF
                val repeatIcon = when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> RhythmIcons.RepeatOne
                    else -> RhythmIcons.Repeat
                }

                RhythmButtonWeighted(
                    onClick = {
                        HapticUtils.performHapticFeedback(context, haptic, HapticType.LIGHT)
                        onToggleRepeat()
                    },
                    weight = 1f,
                    size = RhythmButtonSize.Medium,
                    type = RhythmButtonType.Tonal,
                    icon = repeatIcon,
                    iconSize = 20.dp,
                    contentDescription = if (repeatEnabled) "Disable repeat" else "Enable repeat",
                    isFirst = false,
                    isLast = true,
                    expandSlotWhenSelected = false,
                    containerColor = if (repeatEnabled)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (repeatEnabled)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Clear queue button (only show if queue is not empty)
            onClearQueue?.let { clearAction ->
                FilledTonalIconButton(
                    onClick = {
                        HapticUtils.performHapticFeedback(context, haptic, HapticType.HEAVY)
                        clearAction()
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = RhythmIcons.Delete,
                        contentDescription = stringResource(R.string.content_desc_clear_queue),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Subtle pulsing animation for the Now Playing indicator
    val infiniteTransition = rememberInfiniteTransition(label = "nowPlayingPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    
    val cardShape = RoundedCornerShape(
        topStart = 26.dp,
        topEnd = 20.dp,
        bottomStart = 20.dp,
        bottomEnd = 30.dp
    )

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        ),
        shape = cardShape,
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp,
            pressedElevation = 4.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Album art
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 4.dp
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .apply(ImageUtils.buildImageRequest(
                            song.artworkUri,
                            song.title,
                            LocalContext.current.cacheDir,
                            M3PlaceholderType.TRACK
                        ))
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // Song info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = context.getString(R.string.now_playing),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Text(
                    text = "${song.artist} • ${song.album}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // Playing indicator with pulse animation
            Icon(
                imageVector = RhythmIcons.MusicNote,
                contentDescription = stringResource(R.string.content_desc_now_playing),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = pulseAlpha),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun QueueItem(
    song: Song,
    displayNumber: Int,
    corners: QueueItemCorners,
    isPlayed: Boolean,
    isDragging: Boolean,
    onSongClick: () -> Unit,
    onRemove: () -> Unit,
    showDragHandle: Boolean = true,
    dragHandleModifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // Primary drag fill like the Library Songs active row (flips a frame after drag so it animates from rest).
    var dragActive by remember { mutableStateOf(false) }
    LaunchedEffect(isDragging) {
        dragActive = isDragging
    }

    val cardColor by animateColorAsState(
        targetValue = when {
            dragActive -> MaterialTheme.colorScheme.primary
            isPlayed -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.42f)
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(durationMillis = 300),
        label = "queueItemCardColor"
    )

    val titleColor by animateColorAsState(
        targetValue = when {
            dragActive -> MaterialTheme.colorScheme.onPrimary
            isPlayed -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = 300),
        label = "queueItemTitleColor"
    )

    val subtitleColor by animateColorAsState(
        targetValue = when {
            dragActive -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
            isPlayed -> MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 300),
        label = "queueItemSubtitleColor"
    )

    val liftedCorner = 24.dp
    // Ramp the shadow only after the drag morph settles so it isn't clipped mid-morph.
    var liftShadowReady by remember { mutableStateOf(false) }
    LaunchedEffect(dragActive) {
        if (!dragActive) liftShadowReady = false
    }
    val animatedTopStart by animateDpAsState(
        targetValue = if (dragActive) liftedCorner else corners.topStart,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "queueItemTopStartCorner",
        finishedListener = { if (dragActive) liftShadowReady = true }
    )
    val animatedTopEnd by animateDpAsState(
        targetValue = if (dragActive) liftedCorner else corners.topEnd,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "queueItemTopEndCorner"
    )
    val animatedBottomStart by animateDpAsState(
        targetValue = if (dragActive) liftedCorner else corners.bottomStart,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "queueItemBottomStartCorner"
    )
    val animatedBottomEnd by animateDpAsState(
        targetValue = if (dragActive) liftedCorner else corners.bottomEnd,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "queueItemBottomEndCorner"
    )
    val animatedShape = RoundedCornerShape(
        topStart = animatedTopStart,
        topEnd = animatedTopEnd,
        bottomEnd = animatedBottomEnd,
        bottomStart = animatedBottomStart
    )

    val animatedShadow by animateDpAsState(
        targetValue = if (liftShadowReady) 12.dp else 0.dp,
        animationSpec = tween(durationMillis = 400),
        label = "queueItemShadowElevation"
    )

    val songArtShape = rememberExpressiveShapeFor(
        ExpressiveShapeTarget.SONG_ART,
        fallbackShape = RoundedCornerShape(8.dp)
    )

    Surface(
        onClick = onSongClick,
        modifier = Modifier.fillMaxWidth(),
        shape = animatedShape,
        color = cardColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = if (dragActive) 0.dp else 1.dp,
        shadowElevation = animatedShadow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = when {
                    isPlayed -> MaterialTheme.colorScheme.tertiaryContainer
                    dragActive -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.primaryContainer
                },
                modifier = Modifier.size(32.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (isPlayed) {
                        Icon(
                            imageVector = RhythmIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Text(
                            text = "$displayNumber",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (dragActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Album art
            Surface(
                modifier = Modifier.size(48.dp),
                shape = songArtShape,
                tonalElevation = 2.dp,
                border = if (dragActive) {
                    BorderStroke(2.dp, MaterialTheme.colorScheme.onPrimary)
                } else {
                    null
                }
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .apply(ImageUtils.buildImageRequest(
                            song.artworkUri,
                            song.title,
                            context.cacheDir,
                            M3PlaceholderType.TRACK
                        ))
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Song info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            if (showDragHandle) {
                Icon(
                    imageVector = RhythmIcons.DragHandle,
                    contentDescription = stringResource(R.string.content_desc_drag_reorder),
                    tint = when {
                        dragActive -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                        isPlayed -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                    modifier = Modifier
                        .size(20.dp)
                        .then(dragHandleModifier)
                )
                        
                Spacer(modifier = Modifier.width(8.dp))
            }
            
            // Remove button with hover effect
            var isPressed by remember { mutableStateOf(false) }
            val buttonScale by animateFloatAsState(
                targetValue = if (isPressed) 0.9f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "buttonScale"
            )
            
            ExpressiveFilledTonalIconButton(
                onClick = {
                    isPressed = true
                    HapticUtils.performHapticFeedback(context, haptic, HapticType.MEDIUM)
                    onRemove()
                },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier
                    .size(32.dp)
                    .graphicsLayer {
                        scaleX = buttonScale
                        scaleY = buttonScale
                    }
                ) {
                Icon(
                    imageVector = MaterialSymbolIcon("clear"),
                    contentDescription = stringResource(R.string.content_desc_remove_from_queue),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DismissibleQueueItem(
    song: Song,
    queuePosition: Int,
    displayNumber: Int,
    isPlayed: Boolean,
    isDragging: Boolean,
    showDragHandle: Boolean,
    itemShape: RoundedCornerShape,
    corners: QueueItemCorners,
    endPadding: Dp,
    isRemoving: Boolean,
    onSongClickAtIndex: (Int) -> Unit,
    onRequestRemove: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
    enableSwipeToRemove: Boolean = true
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val rowDismissAlpha by animateFloatAsState(
        targetValue = if (isRemoving) 0f else 1f,
        animationSpec = tween(280),
        label = "queueRowDismissAlpha"
    )

    Box(
        modifier = modifier
            .padding(
                start = 16.dp,
                end = endPadding
            )
            .graphicsLayer {
                alpha = rowDismissAlpha
            }
    ) {
        val dismissState = rememberSwipeToDismissBoxState()

        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = enableSwipeToRemove && !isDragging,
            enableDismissFromEndToStart = enableSwipeToRemove && !isDragging,
            onDismiss = {
                HapticUtils.performHapticFeedback(context, haptic, HapticType.HEAVY)
                onRequestRemove()
            },
            backgroundContent = {
                if (enableSwipeToRemove && dismissState.dismissDirection != SwipeToDismissBoxValue.Settled) {
                    val alignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                        Alignment.CenterStart
                    } else {
                        Alignment.CenterEnd
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(itemShape)
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 20.dp),
                        contentAlignment = alignment
                    ) {
                        Icon(
                            imageVector = RhythmIcons.Delete,
                            contentDescription = stringResource(R.string.content_desc_remove_from_queue),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        ) {
            AnimateIn {
                QueueItem(
                    song = song,
                    displayNumber = displayNumber,
                    corners = corners,
                    isPlayed = isPlayed,
                    isDragging = isDragging,
                    onSongClick = {
                        onSongClickAtIndex(queuePosition)
                    },
                    onRemove = onRequestRemove,
                    showDragHandle = showDragHandle,
                    dragHandleModifier = dragHandleModifier
                )
            }
        }
    }
}

@Composable
private fun QueueListRowContent(
    row: QueueListRow,
    isDragging: Boolean,
    reorderSupported: Boolean,
    endPadding: Dp,
    isRemoving: (String) -> Boolean,
    onSongClickAtIndex: (Int) -> Unit,
    onRequestRemove: (QueueSongRow, String) -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
    enableSwipeToRemove: Boolean = true
) {
    when (row) {
        is QueueListRow.Section -> {
            Box(modifier = modifier) {
                QueueSectionHeader(row.label)
            }
        }

        is QueueListRow.Song -> {
            val songRow = row.row
            val itemKey = queueListRowKey(row)
            DismissibleQueueItem(
                song = songRow.song,
                queuePosition = songRow.queueIndex,
                displayNumber = songRow.displayNumber,
                isPlayed = songRow.isPlayed,
                isDragging = isDragging,
                showDragHandle = reorderSupported && !songRow.isPlayed,
                dragHandleModifier = dragHandleModifier,
                itemShape = songRow.corners.toShape(),
                corners = songRow.corners,
                endPadding = endPadding,
                isRemoving = isRemoving(itemKey),
                onSongClickAtIndex = onSongClickAtIndex,
                onRequestRemove = { onRequestRemove(songRow, itemKey) },
                enableSwipeToRemove = enableSwipeToRemove,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun QueueSectionHeader(label: QueueSectionLabel) {
    val context = LocalContext.current
    val text = when (label) {
        QueueSectionLabel.PLAYED -> context.getString(R.string.queue_section_played)
        QueueSectionLabel.UP_NEXT -> context.getString(R.string.bottomsheet_up_next)
    }
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Text(
                text = text.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 8.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceContainer,
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

@Composable
private fun QueueSettingsInfo(
    isShuffleEnabled: Boolean,
    repeatMode: Int,
    hidePlayedSongs: Boolean,
    queueSize: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Hide played songs info
        if (hidePlayedSongs && queueSize > 0) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = RhythmIcons.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.queuebottomsheet_played_songs_are_hidden),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun AnimateIn(
    modifier: Modifier = Modifier,
    delay: Int = 50,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delay.toLong())
        visible = true
    }

    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 350, delayMillis = 0),
        label = "alpha"
    )

    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0.92f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "scale"
    )

    val translationY by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 0f else 20f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "translationY"
    )

    Box(
        modifier = modifier.graphicsLayer(
            alpha = alpha,
            scaleX = scale,
            scaleY = scale,
            translationY = translationY
        )
    ) {
        content()
    }
}

@Composable
private fun EmptyQueueContent(
    modifier: Modifier = Modifier
) {
    QueueEmptyState(
        title = stringResource(R.string.queue_empty),
        subtitle = stringResource(R.string.queue_add_songs),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier
    )
}

@Composable
private fun EmptyUpNextContent(
    modifier: Modifier = Modifier
) {
    QueueEmptyState(
        title = stringResource(R.string.queue_no_more),
        subtitle = stringResource(R.string.queue_add_more),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
        height = 260.dp
    )
}

@Composable
private fun QueueEmptyState(
    title: String,
    subtitle: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 300.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        contentAlignment = Alignment.Center
    ) {
        ExpressiveCookieEmptyState(
            title = title,
            subtitle = subtitle,
            mainIcon = RhythmIcons.Queue,
            accentIcon = RhythmIcons.MusicNote,
            cornerIcon = RhythmIcons.MusicNote,
            containerColor = containerColor,
            contentColor = contentColor
        )
    }
}
