// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class AiFailure(val status: Int = 0, val retryAfterMs: Long = 0) : Exception("AI request failed ($status)")
data class AiAnswer(val plan: AiPlan, val provider: String, val attempts: Int)

/** One user request at a time, one attempt per selected connection, bounded wall time. */
class AiRouter(private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        internal val session by lazy { AiRouter() }
    }
    private val mutex = Mutex()
    private val cooldowns = mutableMapOf<String, Long>()
    private var cursor = 0

    suspend fun request(
        connections: List<AiConnection>, rotate: Boolean, fallback: Boolean,
        operation: AiOperation, allowed: Set<String>,
        transport: suspend (AiConnection) -> String,
    ): AiAnswer = mutex.withLock {
        withTimeout(65_000) {
            cooldowns.keys.retainAll(connections.map { it.id }.toSet())
            val selected = connections.filter { it.selected }
            require(selected.isNotEmpty())
            val offset = if (rotate) cursor.mod(selected.size) else 0
            if (rotate) cursor = (offset + 1) % selected.size
            val order = selected.drop(offset) + selected.take(offset)
            var attempts = 0
            for (connection in order) {
                if ((cooldowns[connection.id] ?: 0) > now()) continue
                attempts++
                try {
                    val plan = AiProtocol.parse(transport(connection), operation, allowed)
                    cooldowns.remove(connection.id)
                    return@withTimeout AiAnswer(plan, connection.provider.label, attempts)
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: AiFailure) {
                    cooldowns[connection.id] = now() + when (failure.status) {
                        401, 403 -> 300_000L
                        429 -> failure.retryAfterMs.coerceIn(15_000, 300_000)
                        else -> 15_000L
                    }
                } catch (_: IllegalArgumentException) {
                    cooldowns[connection.id] = now() + 15_000
                } catch (_: org.json.JSONException) {
                    cooldowns[connection.id] = now() + 15_000
                }
                if (!fallback) break
            }
            throw AiFailure()
        }
    }
}
