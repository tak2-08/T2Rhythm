// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class QueueOrderTest {
    private data class Track(val id: String, val title: String = id)
    @Test fun duplicatesAreConsumedOnceAndCurrentMetadataSurvives() {
        val original = listOf(Track("a"), Track("b"), Track("a"), Track("c"))
        val current = listOf(Track("a", "playing"), Track("c", "new C"), Track("a", "new A"), Track("b", "new B"))
        assertEquals(listOf(current[0], current[3], current[2], current[1]),
            restoreUpcomingQueueOrder(current, 0, original) { it.id })
    }
    @Test fun removedOccurrencesStayRemovedAndExtraOccurrencesStayInQueue() {
        val original = listOf("a", "b", "a", "c")
        assertEquals(listOf("b", "a", "c"), restoreUpcomingQueueOrder(listOf("b", "c", "a"), 0, original) { it })
        assertEquals(listOf("b", "a", "c", "x", "y", "a"),
            restoreUpcomingQueueOrder(listOf("b", "x", "a", "y", "c", "a"), 0, listOf("a", "b", "c")) { it })
    }
    @Test fun arbitraryQueuesPreserveEveryOccurrenceAndPlayedPrefix() {
        val random = Random(42)
        repeat(300) {
            val original = List(50) { random.nextInt(8) }
            val current = original.shuffled(random).take(random.nextInt(1, 50)) + List(5) { random.nextInt(12) }
            val index = random.nextInt(current.size)
            val result = restoreUpcomingQueueOrder(current, index, original) { it.toString() }
            assertEquals(current.groupingBy { it }.eachCount(), result.groupingBy { it }.eachCount())
            assertEquals(current.take(index + 1), result.take(index + 1))
        }
    }
    @Test fun largeQueueUsesLinearKeyLookups() {
        val original = (0 until 50_000).toList()
        var lookups = 0
        val current = original.reversed()
        val result = restoreUpcomingQueueOrder(current, 0, original) { lookups++; it.toString() }
        assertEquals(listOf(49_999) + (0 until 49_999), result)
        assertTrue(lookups <= original.size + current.size)
    }
    @Test fun stableEntryKeysSurviveLargeReverseAndMetadataChanges() {
        var lookups = 0
        val tracker = QueueEntryKeyTracker<Track> { lookups++; it.id }
        val first = tracker.sync(List(50_000) { Track(it.toString()) })
        lookups = 0
        val second = tracker.sync(first.reversed().map { it.item.copy(title = "updated") })
        assertEquals(first.reversed().map { it.entryId }, second.map { it.entryId })
        assertTrue(second.all { it.item.title == "updated" })
        assertTrue(lookups <= 100_000)
    }
    @Test fun duplicateEntryKeysRemainFifoAfterRemovalAndInsertion() {
        val tracker = QueueEntryKeyTracker<String> { it }
        val first = tracker.sync(listOf("a", "b", "a"))
        val second = tracker.sync(listOf("a", "a", "c"))
        assertEquals(listOf(first[0].entryId, first[2].entryId), second.take(2).map { it.entryId })
        assertFalse(second.last().entryId in first.map { it.entryId })
    }
}
