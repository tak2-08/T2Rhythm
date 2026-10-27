// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class AiFailureKind { NETWORK, TIMEOUT, HTTP, INVALID_RESPONSE, TRUNCATED, REFUSED }
class AiFailure(val status: Int = 0, val retryAfterMs: Long = 0,
    val kind: AiFailureKind = if (status == 0) AiFailureKind.NETWORK else AiFailureKind.HTTP,
) : Exception("AI request failed ($status, $kind)") {
    fun userMessage(): String = when (kind) {
        AiFailureKind.NETWORK -> "제공사에 연결하지 못했습니다. 네트워크 연결을 확인해 주세요."
        AiFailureKind.TIMEOUT -> "모델의 응답 시간이 초과되었습니다. 더 빠른 모델을 선택하거나 다시 시도해 주세요."
        AiFailureKind.INVALID_RESPONSE -> "모델이 필요한 형식으로 응답하지 않았습니다. 다른 텍스트 모델을 선택하거나 요청을 간단히 해 주세요."
        AiFailureKind.TRUNCATED -> "모델의 응답이 길이 제한으로 중단되었습니다. 요청할 곡 수나 수정 항목을 줄여 주세요."
        AiFailureKind.REFUSED -> "제공사가 이 요청에 응답하지 않았습니다. 요청 내용을 바꿔 주세요."
        AiFailureKind.HTTP -> when (status) {
            401 -> "API 키를 인증하지 못했습니다. 연결 수정에서 키를 확인해 주세요."
            403 -> "이 키로 모델을 사용할 권한이 없습니다. 제공사의 모델 접근 권한을 확인해 주세요."
            404 -> "모델이나 API를 찾을 수 없습니다. 연결 수정에서 제공사 모델 목록을 조회해 주세요."
            400, 422 -> "제공사가 요청을 거부했습니다. 모델 이름과 텍스트 생성 지원 여부를 확인해 주세요."
            402 -> "API 사용 잔액이 부족합니다. 제공사의 결제 설정을 확인해 주세요."
            429 -> "요청 한도 또는 사용량 한도에 도달했습니다. 제공사의 잔여 한도를 확인하고 잠시 후 다시 시도해 주세요."
            in 500..599 -> "제공사 서버에 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
            else -> "제공사 요청에 실패했습니다. HTTP $status"
        }
    }
}
data class AiAnswer(val plan: AiPlan, val provider: String, val attempts: Int)

/** One user request at a time, one attempt per selected connection, bounded wall time. */
class AiRouter(private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        internal val session by lazy { AiRouter() }
    }
    private val mutex = Mutex()
    private val cooldowns = mutableMapOf<String, Long>()
    private val failures = mutableMapOf<String, AiFailure>()
    private var cursor = 0

    suspend fun resetConnection(id: String) = mutex.withLock {
        cooldowns.remove(id)
        failures.remove(id)
    }

    suspend fun request(
        connections: List<AiConnection>, rotate: Boolean, fallback: Boolean,
        operation: AiOperation, allowed: Set<String>,
        transport: suspend (AiConnection) -> String,
    ): AiAnswer = mutex.withLock {
        withTimeoutOrNull(180_000) {
            cooldowns.keys.retainAll(connections.map { it.id }.toSet())
            failures.keys.retainAll(connections.map { it.id }.toSet())
            val selected = connections.filter { it.selected }
            require(selected.isNotEmpty())
            val offset = if (rotate) cursor.mod(selected.size) else 0
            if (rotate) cursor = (offset + 1) % selected.size
            val order = selected.drop(offset) + selected.take(offset)
            var attempts = 0
            var lastFailure: AiFailure? = null
            for (connection in order) {
                if ((cooldowns[connection.id] ?: 0) > now()) {
                    lastFailure = failures[connection.id] ?: lastFailure
                    if (!fallback) throw lastFailure ?: AiFailure()
                    continue
                }
                attempts++
                try {
                    val plan = AiProtocol.parse(transport(connection), operation, allowed)
                    cooldowns.remove(connection.id)
                    failures.remove(connection.id)
                    return@withTimeoutOrNull AiAnswer(plan, connection.provider.label, attempts)
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: AiFailure) {
                    lastFailure = failure
                    failures[connection.id] = failure
                    cooldowns[connection.id] = now() + when (failure.status) {
                        401, 403 -> 300_000L
                        429 -> failure.retryAfterMs.coerceIn(15_000, 300_000)
                        else -> 15_000L
                    }
                } catch (_: IllegalArgumentException) {
                    lastFailure = AiFailure(kind = AiFailureKind.INVALID_RESPONSE)
                    failures[connection.id] = lastFailure
                    cooldowns[connection.id] = now() + 15_000
                } catch (_: org.json.JSONException) {
                    lastFailure = AiFailure(kind = AiFailureKind.INVALID_RESPONSE)
                    failures[connection.id] = lastFailure
                    cooldowns[connection.id] = now() + 15_000
                }
                if (!fallback) break
            }
            throw lastFailure ?: AiFailure()
        } ?: throw AiFailure(kind = AiFailureKind.TIMEOUT)
    }
}
