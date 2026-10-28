// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.ai

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiTransportTest {
    private val transport = AiTransport()
    private fun connection(provider: AiProvider = AiProvider.GROQ, model: String = "chosen-model") = AiConnection("test", provider, model, "test-secret")
    private fun body(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
    private fun rejected(provider: AiProvider, raw: String, kind: AiFailureKind? = null) {
        val error = runCatching { transport.decode(provider, raw) }.exceptionOrNull()
        assertTrue(error is AiFailure)
        if (kind != null) assertEquals(kind, (error as AiFailure).kind)
    }
    private fun chat(content: String = "answer", extra: String = "") =
        """{"choices":[{"finish_reason":"stop","message":{"content":${JSONObject.quote(content)}$extra}}]}"""
    private fun response(text: String) = """{"status":"completed","error":null,"incomplete_details":null,"output":[{"type":"reasoning","summary":[]},{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":${JSONObject.quote(text)}}]}]}"""

    @Test fun allProvidersUseNativeEndpointsAndHeaderAuthentication() {
        for (provider in AiProvider.entries) {
            val request = transport.buildRequest(connection(provider), "catalog")
            assertTrue(request.url.isHttps)
            assertEquals(provider.endpoint + if (provider == AiProvider.GOOGLE) "/chosen-model:generateContent" else "", request.url.toString())
            assertFalse(request.url.toString().contains("test-secret"))
            assertFalse(body(request).toString().contains("test-secret"))
            when (provider) {
                AiProvider.GOOGLE -> { assertEquals("test-secret", request.header("x-goog-api-key")); assertNull(request.header("Authorization")) }
                AiProvider.ANTHROPIC -> {
                    assertEquals("test-secret", request.header("x-api-key"))
                    assertEquals("2023-06-01", request.header("anthropic-version")); assertNull(request.header("Authorization"))
                }
                else -> assertEquals("Bearer test-secret", request.header("Authorization"))
            }
        }
    }
    @Test fun geminiUsesModelPathNativePartsAndJsonGeneration() {
        val request = transport.buildRequest(connection(AiProvider.GOOGLE, "models/gemini-3.5-flash-lite"), "catalog")
        assertTrue(request.url.encodedPath.endsWith("/models/gemini-3.5-flash-lite:generateContent"))
        val json = body(request)
        assertFalse(json.has("model")); assertFalse(json.has("messages"))
        assertEquals(AiProtocol.SYSTEM, json.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("catalog", json.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("application/json", json.getJSONObject("generationConfig").getString("responseMimeType"))
        assertTrue(runCatching { transport.buildRequest(connection(AiProvider.GOOGLE, "../other?key=x"), "x") }.isFailure)
    }
    @Test fun responsesProvidersDisableStorageAndUseOutputTokenBudget() {
        for (provider in listOf(AiProvider.OPENAI, AiProvider.XAI)) {
            val json = body(transport.buildRequest(connection(provider), "catalog"))
            assertEquals("chosen-model", json.getString("model")); assertFalse(json.getBoolean("store"))
            assertFalse(json.getBoolean("stream")); assertEquals(8192, json.getInt("max_output_tokens"))
            assertFalse(json.has("max_tokens")); assertFalse(json.has("messages"))
            assertEquals("catalog", json.getJSONArray("input").getJSONObject(1).getString("content"))
            if (provider == AiProvider.OPENAI) assertEquals("json_object", json.getJSONObject("text").getJSONObject("format").getString("type"))
        }
    }
    @Test fun messagesAndChatProvidersUseTheirOwnTokenAndSystemFields() {
        for (provider in listOf(AiProvider.ANTHROPIC, AiProvider.GROQ, AiProvider.CEREBRAS, AiProvider.HUGGING_FACE)) {
            val json = body(transport.buildRequest(connection(provider), "catalog"))
            val tokenKey = if (provider in listOf(AiProvider.ANTHROPIC, AiProvider.HUGGING_FACE)) "max_tokens" else "max_completion_tokens"
            assertEquals(8192, json.getInt(tokenKey))
            assertFalse(json.has(if (tokenKey == "max_tokens") "max_completion_tokens" else "max_tokens"))
            if (provider == AiProvider.ANTHROPIC) {
                assertEquals(AiProtocol.SYSTEM, json.getString("system"))
                assertEquals(1, json.getJSONArray("messages").length())
            } else assertEquals("system", json.getJSONArray("messages").getJSONObject(0).getString("role"))
            if (provider in listOf(AiProvider.GROQ, AiProvider.CEREBRAS)) assertEquals("json_object", json.getJSONObject("response_format").getString("type"))
        }
    }
    @Test fun normalNullAndEmptyToolFieldsAreAcceptedButActualCallsAreRejected() {
        for (provider in listOf(AiProvider.GROQ, AiProvider.CEREBRAS, AiProvider.HUGGING_FACE)) {
            assertEquals("answer", transport.decode(provider, chat(extra = ",\"tool_calls\":null,\"function_call\":null,\"refusal\":null")))
            assertEquals("answer", transport.decode(provider, chat(extra = ",\"tool_calls\":[]")))
            rejected(provider, chat(extra = ",\"tool_calls\":[{\"type\":\"function\"}]"))
            rejected(provider, chat(extra = ",\"function_call\":{\"name\":\"delete\"}"))
        }
    }
    @Test fun readsChatTextBlocksWithoutReasoningOrNullContent() {
        val raw = """{"choices":[{"finish_reason":"stop","message":{"reasoning":"ignored","content":[{"type":"text","text":"one"},{"type":"text","text":"two"}]}}]}"""
        assertEquals("onetwo", transport.decode(AiProvider.HUGGING_FACE, raw))
        rejected(AiProvider.GROQ, """{"choices":[{"finish_reason":"stop","message":{"content":null}}]}""")
        rejected(AiProvider.CEREBRAS, chat().replace("\"stop\"", "\"length\""), AiFailureKind.TRUNCATED)
    }
    @Test fun responsesIgnoresReasoningButRejectsIncompleteRefusalsAndActions() {
        assertEquals("answer", transport.decode(AiProvider.OPENAI, response("answer")))
        assertEquals("answer", transport.decode(AiProvider.XAI, response("answer")))
        rejected(AiProvider.OPENAI, response("partial").replace("\"status\":\"completed\"", "\"status\":\"incomplete\""), AiFailureKind.TRUNCATED)
        rejected(AiProvider.OPENAI, """{"status":"completed","output":[{"type":"function_call","name":"delete"}]}""")
        rejected(AiProvider.OPENAI, """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"refusal","refusal":"no"}]}]}""", AiFailureKind.REFUSED)
    }
    @Test fun geminiSeparatesThoughtsAndRejectsBlockedTruncatedAndToolResults() {
        val raw = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"thought":true,"text":"ignored"},{"text":"answer"}]}}]}"""
        assertEquals("answer", transport.decode(AiProvider.GOOGLE, raw))
        rejected(AiProvider.GOOGLE, raw.replace("STOP", "MAX_TOKENS"), AiFailureKind.TRUNCATED)
        rejected(AiProvider.GOOGLE, """{"promptFeedback":{"blockReason":"SAFETY"}}""", AiFailureKind.REFUSED)
        rejected(AiProvider.GOOGLE, raw.replace("\"text\":\"answer\"", "\"functionCall\":{\"name\":\"delete\"}"))
    }
    @Test fun claudeAcceptsThinkingBlocksAndRejectsPartialOrToolMessages() {
        val raw = """{"stop_reason":"end_turn","content":[{"type":"thinking","thinking":"ignored","signature":"opaque"},{"type":"redacted_thinking","data":"opaque"},{"type":"text","text":"answer"}]}"""
        assertEquals("answer", transport.decode(AiProvider.ANTHROPIC, raw))
        rejected(AiProvider.ANTHROPIC, raw.replace("end_turn", "max_tokens"), AiFailureKind.TRUNCATED)
        rejected(AiProvider.ANTHROPIC, raw.replace("end_turn", "refusal"), AiFailureKind.REFUSED)
        rejected(AiProvider.ANTHROPIC, raw.replace("\"type\":\"text\"", "\"type\":\"tool_use\""))
    }
    @Test fun modelsFilterUnsupportedGeminiMethodsAndPreserveHuggingFaceRoutingSuffixes() {
        assertEquals(listOf("gemini-test"), transport.decodeModels(AiProvider.GOOGLE, """{"models":[{"name":"models/embed","supportedGenerationMethods":["embedContent"]},{"name":"models/gemini-test","supportedGenerationMethods":["generateContent"]}]}"""))
        assertEquals(listOf("org/model:provider"), transport.decodeModels(AiProvider.HUGGING_FACE, """{"data":[{"id":"org/model:provider"},{"id":"org/model:provider"},{"id":""}]}"""))
        assertTrue(transport.decodeModels(AiProvider.OPENAI, """{"data":[{"id":null},{}]}""").isEmpty())
    }
    @Test fun retryAfterSupportsSecondsAndHttpDatesWithoutOverflow() {
        assertEquals(30_000L, AiTransport.retryAfterMillis("30", 0))
        assertEquals(300_000L, AiTransport.retryAfterMillis(Long.MAX_VALUE.toString(), 0))
        assertEquals(60_000L, AiTransport.retryAfterMillis("Thu, 1 Jan 1970 00:01:00 GMT", 0))
        assertEquals(0L, AiTransport.retryAfterMillis("invalid", 0))
    }
    @Test fun boundedReaderRejectsUnknownLengthOversizedBodies() {
        assertEquals("small", "small".byteInputStream().readNBytesBounded(5).decodeToString())
        assertTrue(runCatching { ByteArray(100_000).inputStream().readNBytesBounded(96 * 1024) }.isFailure)
    }
    private fun network(server: MockWebServer, client: OkHttpClient = OkHttpClient()): AiTransport =
        AiTransport(Call.Factory { request -> client.newCall(request.newBuilder().url(server.url(request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: ""))).build()) })

    @Test fun connectionTestUsesRealHttpAndValidatesTheSameMusicPlan() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val plan = """{"operation":"SONGS","title":"Test","explanation":"Test","ids":["test-track"],"edits":[]}"""
            server.enqueue(MockResponse.Builder().body(response(plan)).build())
            network(server).testConnection(connection(AiProvider.OPENAI))
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method); assertEquals("/v1/responses", request.target)
            assertTrue(request.body!!.utf8().contains("test-track"))
            assertFalse(request.body!!.utf8().contains("test-secret"))
            server.enqueue(MockResponse.Builder().body(response(plan.replace("test-track", "hallucinated"))).build())
            val failure = runCatching { network(server).testConnection(connection(AiProvider.OPENAI)) }.exceptionOrNull() as AiFailure
            assertEquals(AiFailureKind.INVALID_RESPONSE, failure.kind)
        }
    }
    @Test fun httpFailuresPreserveStatusAndRetryDelayWithoutEchoingProviderBodies() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "35").body("test-secret private metadata").build())
            val error = runCatching { network(server).complete(connection(), "catalog") }.exceptionOrNull() as AiFailure
            assertEquals(429, error.status); assertEquals(35_000L, error.retryAfterMs)
            assertFalse(error.toString().contains("test-secret")); assertFalse(error.userMessage().contains("private metadata"))
        }
    }
    @Test fun everyModelListUsesTheRightEndpointAndCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (provider in AiProvider.entries) {
                server.enqueue(MockResponse.Builder().body(if (provider == AiProvider.GOOGLE) "{\"models\":[]}" else "{\"data\":[]}").build())
                assertTrue(network(server).models(connection(provider)).isEmpty())
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("GET", request.method)
                assertTrue(request.url.encodedPath.endsWith("/models"))
                assertFalse(request.url.toString().contains("test-secret"))
                assertEquals("test-secret", when (provider) {
                    AiProvider.GOOGLE -> request.headers["x-goog-api-key"]
                    AiProvider.ANTHROPIC -> request.headers["x-api-key"]
                    else -> request.headers["Authorization"]?.removePrefix("Bearer ")
                })
            }
        }
    }
    @Test fun cancellationCancelsTheUnderlyingHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(chat()).headersDelay(2, TimeUnit.SECONDS).build())
            var captured: Call? = null
            val client = OkHttpClient()
            val cancellable = AiTransport(Call.Factory { request -> client.newCall(request.newBuilder().url(server.url("/")).build()).also { captured = it } })
            val job = launch { cancellable.complete(connection(), "catalog") }
            yield()
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            job.cancelAndJoin()
            assertTrue(captured!!.isCanceled())
        }
    }
}
