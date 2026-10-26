// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.ai

import java.io.IOException
import java.io.InterruptedIOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Explicit requests only. Keys never enter URLs, logs, exception messages, or redirects. */
class AiTransport internal constructor(private val callFactory: Call.Factory) {
    constructor() : this(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(65, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequests = 1; maxRequestsPerHost = 1 })
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build())

    suspend fun complete(connection: AiConnection, payload: String): String =
        decode(connection.provider, execute(buildRequest(connection, payload)))

    /** Same request, decoding and plan validation as music requests, using fictitious data. */
    suspend fun testConnection(connection: AiConnection) {
        val payload = AiProtocol.payload(AiOperation.SONGS, "Return the one supplied test track.", "Connection test",
            listOf(AiTrack("test-track", "Test track", "Test artist", "Test album", "Test", 60)))
        try { AiProtocol.parse(complete(connection, payload), AiOperation.SONGS, setOf("test-track")) }
        catch (error: IllegalArgumentException) { throw AiFailure(kind = AiFailureKind.INVALID_RESPONSE) }
        catch (error: org.json.JSONException) { throw AiFailure(kind = AiFailureKind.INVALID_RESPONSE) }
    }

    internal fun buildRequest(connection: AiConnection, payload: String): Request {
        val provider = connection.provider
        val model = connection.model.trim()
        var url = provider.endpoint.toHttpUrl()
        val body = when (provider) {
            AiProvider.GOOGLE -> {
                val modelId = model.removePrefix("models/")
                require(modelId.matches(Regex("[a-zA-Z0-9._-]+"))) { "Invalid Gemini model ID" }
                url = url.newBuilder().addPathSegment("$modelId:generateContent").build()
                JSONObject().put("systemInstruction", parts(AiProtocol.SYSTEM))
                    .put("contents", JSONArray().put(parts(payload).put("role", "user")))
                    .put("generationConfig", JSONObject().put("maxOutputTokens", 8192).put("responseMimeType", "application/json"))
            }
            AiProvider.OPENAI, AiProvider.XAI -> JSONObject().put("model", model).put("stream", false)
                .put("store", false).put("max_output_tokens", 8192)
                .put("input", JSONArray().put(message("system", AiProtocol.SYSTEM)).put(message("user", payload)))
            AiProvider.ANTHROPIC -> JSONObject().put("model", model).put("stream", false).put("max_tokens", 8192)
                .put("system", AiProtocol.SYSTEM).put("messages", JSONArray().put(message("user", payload)))
            else -> JSONObject().put("model", model).put("stream", false)
                .put(if (provider == AiProvider.HUGGING_FACE) "max_tokens" else "max_completion_tokens", 8192)
                .put("messages", JSONArray().put(message("system", AiProtocol.SYSTEM)).put(message("user", payload)))
        }
        return authenticate(Request.Builder().url(url), connection)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    }

    /** Model IDs are account-specific; manual entry remains available for additional pages/models. */
    suspend fun models(connection: AiConnection): List<String> {
        val url = when (connection.provider) {
            AiProvider.GOOGLE -> connection.provider.endpoint + "?pageSize=100"
            AiProvider.ANTHROPIC -> "https://api.anthropic.com/v1/models?limit=100"
            else -> connection.provider.endpoint.substringBeforeLast(if (connection.provider in setOf(AiProvider.OPENAI, AiProvider.XAI)) "/responses" else "/chat/completions") + "/models"
        }
        return decodeModels(connection.provider, execute(authenticate(Request.Builder().url(url), connection).get().build(), 1024 * 1024))
    }

    internal fun decodeModels(provider: AiProvider, raw: String): List<String> {
        val entries = JSONObject(raw).getJSONArray(if (provider == AiProvider.GOOGLE) "models" else "data")
        return (0 until entries.length()).mapNotNull { index ->
            val entry = entries.getJSONObject(index)
            if (provider == AiProvider.GOOGLE) {
                val methods = entry.optJSONArray("supportedGenerationMethods") ?: return@mapNotNull null
                if ((0 until methods.length()).none { methods.optString(it) == "generateContent" }) return@mapNotNull null
            }
            entry.optString(if (provider == AiProvider.GOOGLE) "name" else "id").removePrefix("models/")
                .takeIf { it.isNotBlank() && it.length <= 160 && it.none(Char::isISOControl) }
        }.distinct().sorted().take(200)
    }

    private fun authenticate(builder: Request.Builder, connection: AiConnection): Request.Builder = builder.apply {
        header("Accept", "application/json")
        when (connection.provider) {
            AiProvider.GOOGLE -> header("x-goog-api-key", connection.key.trim())
            AiProvider.ANTHROPIC -> { header("x-api-key", connection.key.trim()); header("anthropic-version", "2023-06-01") }
            else -> header("Authorization", "Bearer ${connection.key.trim()}")
        }
    }

    internal fun decode(provider: AiProvider, raw: String): String {
        try {
            val json = JSONObject(raw)
            val result = when (provider) {
                AiProvider.GOOGLE -> {
                    if (json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty().isNotEmpty())
                        throw AiFailure(kind = AiFailureKind.REFUSED)
                    val candidate = json.getJSONArray("candidates").getJSONObject(0)
                    finish(candidate.getString("finishReason"), setOf("STOP"))
                    joinParts(candidate.getJSONObject("content").getJSONArray("parts")) { part ->
                        require(part.isNull("functionCall") && part.isNull("executableCode") && part.isNull("codeExecutionResult"))
                        if (part.optBoolean("thought", false)) "" else part.getString("text")
                    }
                }
                AiProvider.OPENAI, AiProvider.XAI -> {
                    finish(json.getString("status"), setOf("completed"))
                    require(json.isNull("error") && json.isNull("incomplete_details"))
                    joinParts(json.getJSONArray("output")) { item ->
                        when (item.getString("type")) {
                            "reasoning" -> ""
                            "message" -> {
                                require(item.getString("role") == "assistant")
                                finish(item.optString("status", "completed"), setOf("completed"))
                                joinParts(item.getJSONArray("content")) { part ->
                                    if (part.optString("type") == "refusal") throw AiFailure(kind = AiFailureKind.REFUSED)
                                    require(part.getString("type") == "output_text"); part.getString("text")
                                }
                            }
                            else -> throw AiFailure(kind = AiFailureKind.INVALID_RESPONSE)
                        }
                    }
                }
                AiProvider.ANTHROPIC -> {
                    finish(json.getString("stop_reason"), setOf("end_turn", "stop_sequence"))
                    joinParts(json.getJSONArray("content")) { part ->
                        when (part.getString("type")) {
                            "text" -> part.getString("text")
                            "thinking", "redacted_thinking" -> ""
                            else -> throw AiFailure(kind = AiFailureKind.INVALID_RESPONSE)
                        }
                    }
                }
                else -> {
                    val choice = json.getJSONArray("choices").getJSONObject(0)
                    finish(choice.getString("finish_reason"), setOf("stop"))
                    val message = choice.getJSONObject("message")
                    require(message.isNull("function_call"))
                    require(message.isNull("tool_calls") || message.optJSONArray("tool_calls")?.length() == 0)
                    if (!message.isNull("refusal") && message.optString("refusal").isNotBlank()) throw AiFailure(kind = AiFailureKind.REFUSED)
                    when (val content = message.get("content")) {
                        is String -> content
                        is JSONArray -> joinParts(content) { part -> require(part.getString("type") == "text"); part.getString("text") }
                        else -> throw AiFailure(kind = AiFailureKind.INVALID_RESPONSE)
                    }
                }
            }
            require(result.isNotBlank() && result.toByteArray().size <= AiProtocol.MAX_RESPONSE_BYTES)
            return result
        } catch (error: IllegalArgumentException) { throw AiFailure(kind = AiFailureKind.INVALID_RESPONSE) }
        catch (error: org.json.JSONException) { throw AiFailure(kind = AiFailureKind.INVALID_RESPONSE) }
    }

    private fun finish(reason: String, allowed: Set<String>) {
        if (reason in allowed) return
        throw AiFailure(kind = when (reason) {
            "length", "max_tokens", "MAX_TOKENS", "incomplete", "model_context_window_exceeded" -> AiFailureKind.TRUNCATED
            "refusal", "content_filter", "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> AiFailureKind.REFUSED
            else -> AiFailureKind.INVALID_RESPONSE
        })
    }

    private suspend fun execute(request: Request, maxBytes: Int = AiProtocol.MAX_RESPONSE_BYTES): String = suspendCancellableCoroutine { continuation ->
        val call = callFactory.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(AiFailure(kind =
                    if (e is InterruptedIOException) AiFailureKind.TIMEOUT else AiFailureKind.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!it.isSuccessful) throw AiFailure(it.code, retryAfterMillis(it.header("Retry-After")))
                        require(it.body.contentLength() <= maxBytes || it.body.contentLength() == -1L)
                        it.body.byteStream().use { input -> input.readNBytesBounded(maxBytes).toString(Charsets.UTF_8) }
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        when (error) {
                            is AiFailure -> error
                            is InterruptedIOException -> AiFailure(kind = AiFailureKind.TIMEOUT)
                            is IOException -> AiFailure(kind = AiFailureKind.NETWORK)
                            else -> AiFailure(kind = AiFailureKind.INVALID_RESPONSE)
                        })
                }
            }
        })
    }

    companion object {
        private fun message(role: String, content: String) = JSONObject().put("role", role).put("content", content)
        private fun parts(text: String) = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))
        private fun joinParts(array: JSONArray, text: (JSONObject) -> String): String =
            (0 until array.length()).joinToString("") { text(array.getJSONObject(it)) }
        internal fun retryAfterMillis(value: String?, now: Long = System.currentTimeMillis()): Long {
            val seconds = value?.trim()?.toLongOrNull()
            if (seconds != null) return seconds.coerceIn(0, 300) * 1000
            return runCatching { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceIn(0, 300_000) }.getOrDefault(0)
        }
    }
}
