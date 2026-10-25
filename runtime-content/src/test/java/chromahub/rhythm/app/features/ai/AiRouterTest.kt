// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.ai

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AiRouterTest {
    private val valid = """{"operation":"SONGS","title":"음악","explanation":"추천","ids":["t1"],"edits":[]}"""
    private fun connection(id: String, selected: Boolean = true) = AiConnection(id, AiProvider.GROQ, "model", "key", selected)
    private suspend fun request(router: AiRouter, connections: List<AiConnection>, rotate: Boolean = false, fallback: Boolean = true, run: suspend (AiConnection) -> String) =
        router.request(connections, rotate, fallback, AiOperation.SONGS, setOf("t1"), run)

    @Test fun skipsUnselectedAndFallsBackInPriorityOrder() = runBlocking {
        val attempted = mutableListOf<String>()
        val result = request(AiRouter(), listOf(connection("off", false), connection("first"), connection("second"))) {
            attempted += it.id; if (it.id == "first") throw AiFailure(429, 30_000) else valid
        }
        assertEquals(listOf("first", "second"), attempted)
        assertEquals(2, result.attempts)
    }
    @Test fun rotationChangesStartingConnectionAndDisablingFallbackStopsAfterOne() = runBlocking {
        val router = AiRouter(); val list = listOf(connection("first"), connection("second")); val attempted = mutableListOf<String>()
        repeat(3) { request(router, list, rotate = true) { attempted += it.id; valid } }
        assertEquals(listOf("first", "second", "first"), attempted)
        attempted.clear()
        assertTrue(runCatching { request(AiRouter(), list, fallback = false) { attempted += it.id; throw AiFailure(503) } }.isFailure)
        assertEquals(listOf("first"), attempted)
    }
    @Test fun cooldownSkipsRateLimitedConnectionAndExpires() = runBlocking {
        var time = 1000L; val router = AiRouter { time }; val list = listOf(connection("first"), connection("second"))
        request(router, list) { if (it.id == "first") throw AiFailure(429, 30_000) else valid }
        val attempted = mutableListOf<String>()
        request(router, list) { attempted += it.id; valid }
        assertEquals(listOf("second"), attempted)
        time += 30_001; attempted.clear()
        request(router, list) { attempted += it.id; valid }
        assertEquals(listOf("first"), attempted)
    }
    @Test fun malformedOrEscalatedResponseFallsBackWithoutExecuting() = runBlocking {
        val attempted = mutableListOf<String>()
        val result = request(AiRouter(), listOf(connection("first"), connection("second"))) {
            attempted += it.id; if (it.id == "first") valid.replace("SONGS", "TRASH") else valid
        }
        assertEquals(listOf("first", "second"), attempted)
        assertEquals(AiOperation.SONGS, result.plan.operation)
    }
    @Test fun cancellationNeverBecomesFallback() = runBlocking {
        val attempted = mutableListOf<String>()
        val job = launch {
            request(AiRouter(), listOf(connection("first"), connection("second"))) {
                attempted += it.id; awaitCancellation()
            }
        }
        yield(); job.cancelAndJoin()
        assertEquals(listOf("first"), attempted)
    }
    @Test fun allUnselectedMakesNoNetworkRequest() = runBlocking {
        var attempted = false
        assertTrue(runCatching { request(AiRouter(), listOf(connection("off", false))) { attempted = true; valid } }.isFailure)
        assertFalse(attempted)
    }
}
