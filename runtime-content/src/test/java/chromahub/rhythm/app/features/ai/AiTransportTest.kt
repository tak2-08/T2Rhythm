// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.ai

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiTransportTest {
    private val transport = AiTransport()
    @Test fun allSevenProvidersUsePinnedHttpsEndpointsAndCorrectAuthentication() {
        for (provider in AiProvider.entries) {
            val request = transport.buildRequest(AiConnection("test", provider, "chosen-model", "test-secret"), "{\"request\":\"music\"}")
            assertTrue(request.url.isHttps)
            assertEquals(provider.endpoint, request.url.toString())
            assertFalse(request.url.toString().contains("test-secret"))
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            val json = JSONObject(buffer.readUtf8())
            assertEquals("chosen-model", json.getString("model"))
            assertFalse(json.toString().contains("test-secret"))
            if (provider.anthropic) {
                assertEquals("test-secret", request.header("x-api-key"))
                assertEquals("2023-06-01", request.header("anthropic-version"))
                assertNull(request.header("Authorization"))
                assertEquals(AiProtocol.SYSTEM, json.getString("system"))
            } else {
                assertEquals("Bearer test-secret", request.header("Authorization"))
                assertEquals("system", json.getJSONArray("messages").getJSONObject(0).getString("role"))
            }
            assertEquals(3000, json.getInt(if (provider == AiProvider.OPENAI) "max_completion_tokens" else "max_tokens"))
        }
    }
    @Test fun decodesBothWireFormatsAndRejectsTruncatedOrToolResponses() {
        assertEquals("answer", transport.decode(AiProvider.GROQ, """{"choices":[{"finish_reason":"stop","message":{"content":"answer"}}]}"""))
        assertEquals("answer", transport.decode(AiProvider.ANTHROPIC, """{"stop_reason":"end_turn","content":[{"type":"text","text":"answer"}]}"""))
        assertTrue(runCatching { transport.decode(AiProvider.OPENAI, """{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}""") }.isFailure)
        assertTrue(runCatching { transport.decode(AiProvider.OPENAI, """{"choices":[{"message":{"content":"x","tool_calls":[]}}]}""") }.isFailure)
        assertTrue(runCatching { transport.decode(AiProvider.ANTHROPIC, """{"stop_reason":"tool_use","content":[]}""") }.isFailure)
        assertTrue(runCatching { transport.decode(AiProvider.ANTHROPIC, """{"content":[{"type":"tool_use","name":"delete"}]}""") }.isFailure)
    }
    @Test fun boundedReaderRejectsUnknownLengthOversizedBodies() {
        assertEquals("small", "small".byteInputStream().readNBytesBounded(5).decodeToString())
        assertTrue(runCatching { ByteArray(100_000).inputStream().readNBytesBounded(96 * 1024) }.isFailure)
    }
}
