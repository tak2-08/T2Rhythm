// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackWorkloadTest {
    @Test fun bulkWorkWaitsForPlaybackAndResumesAfterPause() = runBlocking {
        PlaybackWorkload.active = true
        try {
            val probe = async { PlaybackWorkload.awaitIdle(); "read" }
            yield(); assertFalse(probe.isCompleted)
            PlaybackWorkload.active = false
            assertEquals("read", withTimeout(2000) { probe.await() })
        } finally { PlaybackWorkload.active = false }
    }
    @Test fun cancelingAWaitingBulkProbeNeverStartsItsFileRead() = runBlocking {
        PlaybackWorkload.active = true
        var read = false
        try {
            val probe = launch { PlaybackWorkload.awaitIdle(); read = true }
            yield(); probe.cancelAndJoin()
            PlaybackWorkload.active = false
            assertFalse(read)
        } finally { PlaybackWorkload.active = false }
    }
    @Test fun idleDoesNotDelayAnAllowedProbe() = runBlocking {
        PlaybackWorkload.active = false
        withTimeout(100) { PlaybackWorkload.awaitIdle() }
    }
}
