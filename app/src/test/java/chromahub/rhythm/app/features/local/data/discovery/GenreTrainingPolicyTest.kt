// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import chromahub.rhythm.app.util.GenreUtils
import org.junit.Assert.*
import org.junit.Test

class GenreTrainingPolicyTest {
    @Test fun allCaseVariantsUseOneUppercaseTrainingClassAndChoice() {
        assertEquals(listOf("POP", "ROCK", "R&B"), GenreUtils.splitGenres("Pop,pop,POP,roCK,rock,R&b"))
        val merged = GenreTrainingPolicy.merge(listOf(GenreExample("one", "Pop", listOf(1.0, 0.0)),
            GenreExample("one", "POP", listOf(1.0, 0.0))), emptyList())
        assertEquals(1, merged.size)
        assertEquals("POP", merged.single().genre)
    }

    @Test fun libraryChoicesSplitDeduplicateAndRejectUnusableLabels() {
        assertEquals(listOf("JAZZ", "K-POP", "R&B", "ROCK"), GenreUtils.availableGenres(
            listOf(null, "-", "unknown", " Rock; Jazz; R&B", "rock, jazz, K-Pop", "Z".repeat(81))))
    }
    @Test fun multipleLabelsCannotPassTheMinimumRecordingCount() {
        val examples = (1..5).map { GenreExample("s$it", if (it == 1) "POP, ROCK" else "POP", listOf(1.0, 0.0)) }
        assertNull(LocalGenreClassifier.predict("unseen", listOf(1.0, 0.0), examples))
    }
    @Test fun oneHeavilyTaggedRecordingCannotCrowdOutOtherNeighbors() {
        val examples = listOf(GenreExample("many-tags", (1..8).joinToString(",") { "other$it" }, listOf(1.0, 0.0))) +
            (1..3).map { GenreExample("pop$it", "POP", listOf(1.0, 0.0)) } +
            (1..3).map { GenreExample("rock$it", "ROCK", listOf(0.0, 1.0)) }
        val guess = LocalGenreClassifier.predict("unseen", listOf(1.0, 0.0), examples)!!
        assertEquals("POP", guess.genre)
        assertEquals(setOf("pop1", "pop2", "pop3"), guess.neighbors.toSet())
    }
    @Test fun missingTagsNeverBecomeGenresButRealNamesRemain() {
        listOf(null, "", "  ", "-", "—", "--", "＿", "<unknown>", "Unknown", "알 수 없음", "n/a", "미지정").forEach {
            assertTrue("Placeholder: $it", GenreUtils.splitGenres(it).isEmpty())
        }
        assertEquals(listOf("K-POP", "R&B", "JAZZ"), GenreUtils.splitGenres("-; K-Pop; R&B; Jazz; jazz; <unknown>"))
    }
    @Test fun multiGenreSelectionUsesUnionAndDoesNotRequireEveryTag() {
        val picked = setOf("POP", "ROCK")
        assertTrue(GenreUtils.matchesAny("ROCK", picked))
        assertTrue(GenreUtils.matchesAny("Jazz; Pop", picked))
        assertFalse(GenreUtils.matchesAny("JAZZ", picked))
        assertFalse(GenreUtils.matchesAny("-", picked))
        assertTrue(GenreUtils.matchesAny(null, emptySet()))
    }
    @Test fun explicitCorrectionReplacesOldPositiveAndNegativeVotes() {
        val data = listOf(GenreExample("song", "POP", listOf(1.0, 0.0)),
            GenreExample("song", "JAZZ", listOf(1.0, 0.0), false), GenreExample("other", "ROCK", listOf(0.0, 1.0)))
        val reviewed = listOf(GenreExample("song", "JAZZ", listOf(1.0, 0.0)))
        val merged = GenreTrainingPolicy.merge(data, reviewed)
        assertEquals(listOf("JAZZ"), merged.filter { it.key == "song" }.map { it.genre })
        assertTrue(merged.single { it.key == "song" }.positive)
        assertEquals(merged, GenreTrainingPolicy.merge(merged, reviewed + reviewed))
    }
    @Test fun repeatedTeachingCannotCreateIndependentNeighborSupport() {
        val labels = List(100) { GenreExample("one", "POP", listOf(1.0, 0.0)) } +
            List(100) { GenreExample("two", "ROCK", listOf(0.0, 1.0)) }
        assertEquals(2, GenreTrainingPolicy.merge(labels, emptyList()).size)
        assertNull(LocalGenreClassifier.predict("new", listOf(1.0, 0.0), labels))
    }
    @Test fun realCorrectionsChangePredictionsWithoutPromotingPlaceholders() {
        val corpus = (1..3).map { GenreExample("a$it", "POP", listOf(1.0, 0.0)) } +
            (1..3).map { GenreExample("b$it", "ROCK", listOf(0.0, 1.0)) }
        assertEquals("POP", LocalGenreClassifier.predict("new", listOf(1.0, 0.0), corpus)?.genre)
        val reviewed = (1..3).map { GenreExample("a$it", "JAZZ", listOf(1.0, 0.0)) }
        assertEquals("JAZZ", LocalGenreClassifier.predict("new", listOf(1.0, 0.0), GenreTrainingPolicy.merge(corpus, reviewed))?.genre)
        val legacy = (1..20).map { GenreExample("bad$it", "-", listOf(1.0, 0.0)) } + corpus.drop(3)
        assertNull(LocalGenreClassifier.predict("new", listOf(1.0, 0.0), legacy))
    }
    @Test fun trainingRemainsBoundedAndRejectsInvalidVectors() {
        val corpus = (1..1000).map { GenreExample("$it", "POP", listOf(1.0, 0.0)) } +
            listOf(GenreExample("nan", "ROCK", listOf(Double.NaN, 1.0)), GenreExample("empty", "ROCK", emptyList()))
        val merged = GenreTrainingPolicy.merge(corpus, emptyList())
        assertEquals(64, merged.size)
        assertTrue(merged.all { it.genre == "POP" })
    }
}
