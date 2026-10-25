// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.ai

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Created only for explicit requests. Never uses the app's logging/intercepting network stack. */
class AiTransport {
    private val client by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(22, TimeUnit.SECONDS)
            .dispatcher(Dispatcher().apply { maxRequests = 1; maxRequestsPerHost = 1 })
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build()
    }

    suspend fun complete(connection: AiConnection, payload: String): String {
        return decode(connection.provider, execute(buildRequest(connection, payload)))
    }

    internal fun buildRequest(connection: AiConnection, payload: String): Request {
        val body = JSONObject().put("model", connection.model).put("stream", false)
        body.put(if (connection.provider == AiProvider.OPENAI) "max_completion_tokens" else "max_tokens", 3000)
        val messages = JSONArray()
        if (connection.provider.anthropic) body.put("system", AiProtocol.SYSTEM)
        else messages.put(JSONObject().put("role", "system").put("content", AiProtocol.SYSTEM))
        messages.put(JSONObject().put("role", "user").put("content", payload))
        body.put("messages", messages)
        val builder = Request.Builder().url(connection.provider.endpoint)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (connection.provider.anthropic) builder.header("x-api-key", connection.key).header("anthropic-version", "2023-06-01")
        else builder.header("Authorization", "Bearer ${connection.key}")
        return builder.build()
    }

    internal fun decode(provider: AiProvider, raw: String): String {
        val json = JSONObject(raw)
        return if (provider.anthropic) {
            require(json.optString("stop_reason") != "tool_use" && json.optString("stop_reason") != "max_tokens")
            val content = json.getJSONArray("content")
            (0 until content.length()).joinToString("") { i ->
                val part = content.getJSONObject(i); require(part.getString("type") == "text"); part.getString("text")
            }
        } else {
            val choice = json.getJSONArray("choices").getJSONObject(0)
            require(choice.optString("finish_reason") !in setOf("length", "tool_calls", "content_filter"))
            val message = choice.getJSONObject("message")
            require(!message.has("tool_calls") && !message.has("function_call"))
            message.getString("content")
        }
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(AiFailure())
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!it.isSuccessful) throw AiFailure(it.code, it.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 300)?.times(1000) ?: 0)
                        require(it.body.contentLength() <= AiProtocol.MAX_RESPONSE_BYTES || it.body.contentLength() == -1L)
                        it.body.byteStream().use { input -> input.readNBytesBounded(AiProtocol.MAX_RESPONSE_BYTES).toString(Charsets.UTF_8) }
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (error is AiFailure) error else AiFailure())
                }
            }
        })
    }
}
