// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import org.junit.Assert.*
import org.junit.Test

class ListeningPolicyTest {
    private fun row(
        id: String,
        kind: String = "segment",
        reason: String = "checkpoint",
        heard: Long = 1000,
    ) =
        ListeningRecord(
            id,
            id,
            kind,
            "song",
            "song",
            "Song",
            "Artist",
            "Album",
            null,
            0,
            heard,
            heard,
            100000,
            0,
            heard,
            1f,
            reason,
        )

    @Test
    fun earlierCompletePlayProtectsLaterBriefPlaysButNotEarlierOutcomes() {
        assertNull(ListeningPolicy.outcome(.01, 1000, 2000, false))
        assertEquals(.01, ListeningPolicy.outcome(.01, 1000, 500, false)!!, .0)
        assertEquals(.1, ListeningPolicy.outcome(.1, null, 2000, false)!!, .0)
        assertEquals(1.0, ListeningPolicy.outcome(.92, null, 2000, false)!!, .0)
        assertEquals(1.0, ListeningPolicy.outcome(.01, null, 2000, true)!!, .0)
    }

    @Test
    fun positiveOnlyLearningCannotApplyDecayAsAnImplicitPenalty() {
        val features = List(24) { if (it == 0) 1.0 else 0.0 }
        val before = List(24) { 2.5 }
        val after = AdaptiveRecommendation.learn(before, features, 1.0, 100, positiveOnly = true)
        assertTrue(after[0] >= before[0])
        assertEquals(before.drop(1), after.drop(1))
    }

    @Test
    fun pendingCheckpointCanAdvanceWhileItsEarlierVersionIsBeingWritten() {
        val buffer = ListeningWriteBuffer(2)
        val old = row("a")
        buffer.offer(old)
        assertSame(old, buffer.next())
        val latest = row("a", reason = "pause", heard = 2000)
        buffer.offer(latest)
        buffer.saved(old)
        assertSame(latest, buffer.next())
        buffer.saved(latest)
        assertEquals(0, buffer.size())
    }

    @Test
    fun storageTroubleHasABoundedBufferAndPreservesPlaySummariesBeforeActions() {
        val buffer = ListeningWriteBuffer(2)
        buffer.offer(row("summary", "play", "ended"))
        buffer.offer(row("slider", "action", "volume"))
        assertTrue(buffer.offer(row("next", "play", "ended")))
        assertEquals(2, buffer.size())
        assertEquals("summary", buffer.next()!!.id)
        assertFalse(buffer.offer(row("third", "play", "ended")))
        assertEquals(2, buffer.takeOmitted())
        assertEquals(0, buffer.takeOmitted())
    }
}
