/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.shared.presentation.components.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.util.HapticType
import chromahub.rhythm.app.util.HapticUtils
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun <T> DragDropLazyColumn(
    items: List<T>,
    modifier: Modifier = Modifier,
    lazyListState: LazyListState,
    onMove: (Int, Int) -> Unit,
    itemKey: (T) -> Any,
    isReorderableItem: (T) -> Boolean = { true },
    isStickyHeader: (T) -> Boolean = { false },
    contentPadding: PaddingValues = PaddingValues(0.dp),
    itemSpacing: Dp = 0.dp,
    animateItemPlacement: Boolean = false,
    dragTopInset: Dp = 0.dp,
    dragBottomInset: Dp = 0.dp,
    onDragStart: ((startIndex: Int) -> Unit)? = null,
    onDrop: ((fromIndex: Int, toIndex: Int) -> Unit)? = null,
    onDragCancel: (() -> Unit)? = null,
    itemContent: @Composable (item: T, isDragging: Boolean, index: Int) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val currentItems by rememberUpdatedState(items)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    val currentIsReorderable by rememberUpdatedState(isReorderableItem)

    var initialDragIndex by remember { mutableIntStateOf(-1) }
    var lastMovedToIndex by remember { mutableIntStateOf(-1) }

    val reorderableState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        onMove = { from, to ->
            val fromIndex = from.index
            val toIndex = to.index
            val list = currentItems
            if (fromIndex !in list.indices || toIndex !in list.indices) return@rememberReorderableLazyListState
            if (!currentIsReorderable(list[fromIndex]) || !currentIsReorderable(list[toIndex])) return@rememberReorderableLazyListState

            if (initialDragIndex == -1) {
                initialDragIndex = fromIndex
            }
            lastMovedToIndex = toIndex

            HapticUtils.performHapticFeedback(context, haptic, HapticType.LIGHT)
            currentOnMove(fromIndex, toIndex)
        }
    )

    val isAnyItemDragging = reorderableState.isAnyItemDragging
    var wasDragging by remember { mutableStateOf(false) }

    LaunchedEffect(isAnyItemDragging) {
        if (isAnyItemDragging && !wasDragging) {
            wasDragging = true
        } else if (!isAnyItemDragging && wasDragging) {
            wasDragging = false
            val start = initialDragIndex
            val end = lastMovedToIndex
            initialDragIndex = -1
            lastMovedToIndex = -1
            if (start != -1 && end != -1 && start != end) {
                HapticUtils.performHapticFeedback(context, haptic, HapticType.MEDIUM)
                currentOnDrop?.invoke(start, end)
            } else {
                currentOnDragCancel?.invoke()
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = lazyListState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(itemSpacing),
        userScrollEnabled = !isAnyItemDragging
    ) {
        items.forEachIndexed { index, item ->
            val key = itemKey(item)
            if (isStickyHeader(item)) {
                stickyHeader(key = key) {
                    itemContent(item, false, index)
                }
            } else {
                val canReorder = currentIsReorderable(item)
                item(key = key) {
                    ReorderableItem(
                        state = reorderableState,
                        key = key,
                        enabled = canReorder,
                        animateItemModifier = if (animateItemPlacement) {
                            Modifier.animateItem(
                                fadeInSpec = tween(durationMillis = 0),
                                placementSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                fadeOutSpec = tween(durationMillis = 0)
                            )
                        } else {
                            Modifier
                        }
                    ) { isDragging ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .longPressDraggableHandle(
                                    enabled = canReorder,
                                    onDragStarted = {
                                        initialDragIndex = index
                                        currentOnDragStart?.invoke(index)
                                        HapticUtils.performHapticFeedback(context, haptic, HapticType.HEAVY)
                                    },
                                    onDragStopped = {
                                        HapticUtils.performHapticFeedback(context, haptic, HapticType.LIGHT)
                                    }
                                )
                        ) {
                            itemContent(item, isDragging, index)
                        }
                    }
                }
            }
        }
    }
}
