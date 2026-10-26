// SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

/** Restore occurrences, not a set of song IDs. Keep the current queue's metadata and extras. */
internal fun <T> restoreUpcomingQueueOrder(
    currentSongs: List<T>, currentIndex: Int, originalOrder: List<T>, idSelector: (T) -> String,
): List<T> {
    if (currentSongs.isEmpty()) return emptyList()
    val split = currentIndex.coerceIn(0, currentSongs.lastIndex) + 1
    val consumed = HashMap<String, Int>()
    currentSongs.take(split).forEach { item ->
        val id = idSelector(item)
        consumed[id] = (consumed[id] ?: 0) + 1
    }
    val pending = HashMap<String, ArrayDeque<Int>>()
    for (i in split until currentSongs.size) {
        pending.getOrPut(idSelector(currentSongs[i])) { ArrayDeque() }.addLast(i)
    }
    val used = BooleanArray(currentSongs.size)
    return buildList(currentSongs.size) {
        addAll(currentSongs.subList(0, split))
        originalOrder.forEach { original ->
            val id = idSelector(original)
            val count = consumed[id] ?: 0
            if (count > 0) consumed[id] = count - 1
            else pending[id]?.removeFirstOrNull()?.let { index ->
                add(currentSongs[index])
                used[index] = true
            }
        }
        // Entries added after shuffling retain their relative order, including extra duplicates.
        for (i in split until currentSongs.size) if (!used[i]) add(currentSongs[i])
    }
}

/**
 * Represents an entry in a playback queue paired with a stable, persistent entryId
 * that survives reordering, removals, and duplicate items.
 */
data class QueueEntry<T>(
    val entryId: Long,
    val item: T
)

/**
 * Tracks and preserves stable entry IDs for queue items across updates and reorders,
 * even when identical items / duplicate song IDs are present.
 */
class QueueEntryKeyTracker<T>(private val idSelector: (T) -> String) {
    private var nextEntryId = 1L
    private var currentEntries = listOf<QueueEntry<T>>()

    fun sync(items: List<T>): List<QueueEntry<T>> {
        val pool = HashMap<String, ArrayDeque<QueueEntry<T>>>()
        currentEntries.forEach { entry ->
            pool.getOrPut(idSelector(entry.item)) { ArrayDeque() }.addLast(entry)
        }
        val result = ArrayList<QueueEntry<T>>(items.size)
        for (item in items) {
            val key = idSelector(item)
            val matched = pool[key]?.removeFirstOrNull()
            if (matched != null) {
                result.add(matched.copy(item = item))
            } else {
                result.add(QueueEntry(entryId = nextEntryId++, item = item))
            }
        }
        currentEntries = result
        return result
    }

    fun updateEntries(entries: List<QueueEntry<T>>) {
        currentEntries = entries
    }

    fun currentEntries(): List<QueueEntry<T>> = currentEntries
}

