// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.ai

import org.junit.Assert.*
import org.junit.Test

class AiCandidatesTest {
    @Test fun koreanAndLatinWordsRankMatchesBeforeFavoritesWithoutWeightingRepeatedPromptWords() {
        val selected = AiCandidates.select(sequenceOf("좋아요", "재즈 jazz", "재즈", "다른 음악"),
            "재즈, JAZZ 재즈 재즈", text = { it }, favorite = { it == "좋아요" }, id = { it })
        assertEquals("재즈 jazz", selected[0])
        assertEquals("재즈", selected[1])
        assertEquals("좋아요", selected[2])
    }

    @Test fun largeSinglePassCatalogRetainsBoundedBestWindowIncludingLateMatches() {
        var evaluated = 0
        val selected = AiCandidates.select((0 until 100_000).asSequence(), "산책",
            text = { evaluated++; if (it >= 99_880) "산책" else "기타" },
            favorite = { false }, id = { it.toString() })
        assertEquals(100_000, evaluated)
        assertEquals(AiProtocol.MAX_TRACKS, selected.size)
        assertEquals((99_880 until 100_000).toSet(), selected.toSet())
    }
}
