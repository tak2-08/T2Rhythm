// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import androidx.compose.runtime.saveable.SaverScope
import chromahub.rhythm.app.features.local.data.discovery.AudioSourceCandidate
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DownloadSearchStateTest {
    @Test fun lateCancellationCannotEndOrFailANewerSearch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = DownloadSearchState()
        val oldFinished = CompletableDeferred<Unit>()
        val newFinished = CompletableDeferred<Unit>()
        try {
            state.work(scope, "old error") {
                withContext(NonCancellable) { oldFinished.await() }
                error("late provider failure")
            }
            state.work(scope, "new error") { newFinished.await() }
            oldFinished.complete(Unit); yield()
            assertTrue("The new request must remain busy", state.busy)
            assertFalse(state.failed); assertNull(state.message)
            newFinished.complete(Unit); yield()
            assertFalse(state.busy); assertFalse(state.failed)
        } finally { oldFinished.complete(Unit); newFinished.complete(Unit); scope.cancel() }
    }
    @Test fun cancelImmediatelyAllowsAnotherRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = DownloadSearchState()
        try {
            state.work(scope, "error") { awaitCancellation() }
            assertTrue(state.busy)
            state.cancel("canceled")
            assertFalse(state.busy); assertEquals("canceled", state.message)
            state.work(scope, "error") { state.message = "complete" }
            assertFalse(state.busy); assertEquals("complete", state.message)
        } finally { scope.cancel() }
    }
    @Test fun configurationStateKeepsInputsWithoutPuttingResultsIntoTheSavedBundle() {
        val state = DownloadSearchState("노래", "가수", "SOUNDCLOUD", true)
        state.songs = (0 until 200).map { AudioSourceCandidate("https://example.org/$it", "노래 $it", "가수", 180) }
        val saved = with(DownloadSearchState.Saver) { object : SaverScope { override fun canBeSaved(value: Any) = true }.save(state) }!!
        val restored = DownloadSearchState.Saver.restore(saved)!!
        assertEquals("노래", restored.query); assertEquals("가수", restored.artist)
        assertEquals("SOUNDCLOUD", restored.source); assertTrue(restored.mobile)
        assertTrue(restored.songs.isEmpty()); assertFalse(restored.busy)
        assertTrue("Large search results must not inflate Activity saved state", saved.toString().length < 200)
    }
}
