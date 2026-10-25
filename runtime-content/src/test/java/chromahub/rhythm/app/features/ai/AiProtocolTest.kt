// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.ai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiProtocolTest {
    private fun response(operation: String = "PLAYLIST", ids: String = "[\"t1\"]", edits: String = "[]") =
        """{"operation":"$operation","title":"저녁 산책","explanation":"선택한 음악입니다.","ids":$ids,"edits":$edits}"""
    private fun rejected(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun acceptsOnlyCatalogIdsAndRequestedCapability() {
        assertEquals(listOf("t1"), AiProtocol.parse(response(), AiOperation.PLAYLIST, setOf("t1")).ids)
        rejected { AiProtocol.parse(response(ids = "[\"/sdcard/Music/a.mp3\"]"), AiOperation.PLAYLIST, setOf("t1")) }
        rejected { AiProtocol.parse(response(operation = "TRASH"), AiOperation.PLAYLIST, setOf("t1")) }
        rejected { AiProtocol.parse(response(ids = "[\"t1\",\"t1\"]"), AiOperation.PLAYLIST, setOf("t1")) }
        rejected { AiProtocol.parse(response(ids = "[]"), AiOperation.PLAYLIST, setOf("t1")) }
    }
    @Test fun rejectsCommandsExtraCapabilitiesAndUnrequestedEdits() {
        rejected { AiProtocol.parse(response().dropLast(1) + ",\"command\":\"rm -rf\"}", AiOperation.PLAYLIST, setOf("t1")) }
        rejected { AiProtocol.parse(response(edits = "[{\"id\":\"t1\",\"title\":\"changed\"}]"), AiOperation.PLAYLIST, setOf("t1")) }
        rejected { AiProtocol.parse(response("EDIT", edits = "[{\"id\":\"t1\",\"path\":\"a\"}]"), AiOperation.EDIT, setOf("t1")) }
        rejected { AiProtocol.parse(response("EDIT"), AiOperation.EDIT, setOf("t1")) }
    }
    @Test fun reviewsExplicitFieldsWithoutFillingUnrequestedOnes() {
        val plan = AiProtocol.parse(response("EDIT", edits = "[{\"id\":\"t1\",\"genre\":\"재즈\"}]"), AiOperation.EDIT, setOf("t1"))
        assertEquals("재즈", plan.edits.single().genre)
        assertNull(plan.edits.single().title)
        rejected { AiProtocol.parse(response("EDIT", edits = "[{\"id\":\"t2\",\"genre\":\"재즈\"}]"), AiOperation.EDIT, setOf("t1", "t2")) }
    }
    @Test fun boundsInputOutputAndResultCount() {
        rejected { AiProtocol.parse("x".repeat(AiProtocol.MAX_RESPONSE_BYTES + 1), AiOperation.PLAYLIST, setOf("t1")) }
        val ids = (1..41).joinToString(",", "[", "]") { "\"t$it\"" }
        rejected { AiProtocol.parse(response(ids = ids), AiOperation.PLAYLIST, (1..41).map { "t$it" }.toSet()) }
        rejected { AiProtocol.payload(AiOperation.SONGS, "a".repeat(2001), "", listOf(AiTrack("t1", "a", "b", "c", "d", 10))) }
    }
    @Test fun payloadHasOnlyExplicitMetadataAndOpaqueIds() {
        val payload = JSONObject(AiProtocol.payload(AiOperation.SONGS, "잔잔한 음악", "비 오는 저녁", listOf(AiTrack("t1", "음악", "가수", "앨범", "장르", 120))))
        assertEquals(setOf("operation", "request", "context", "catalog"), payload.keys().asSequence().toSet())
        assertEquals(setOf("id", "title", "artist", "album", "genre", "seconds"), payload.getJSONArray("catalog").getJSONObject(0).keys().asSequence().toSet())
        assertFalse(payload.toString().contains("uri"))
        assertFalse(AiConnection("one", AiProvider.OPENAI, "model", "private-key").toString().contains("private-key"))
    }
}
