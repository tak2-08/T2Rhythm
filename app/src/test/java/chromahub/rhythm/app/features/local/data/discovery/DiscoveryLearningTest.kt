// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import chromahub.rhythm.app.features.local.data.experience.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryLearningTest {
    private fun examples() =
        (1..3).map { GenreExample("pop$it", "pop", listOf(1.0, 0.0)) } +
            (1..3).map { GenreExample("rock$it", "rock", listOf(0.0, 1.0)) }

    @Test
    fun genrePredictionUsesKnownTagsAndAbstainsWithoutIndependentSupport() {
        assertEquals(
            "pop",
            LocalGenreClassifier.predict("new", listOf(1.0, 0.0), examples())!!.genre,
        )
        assertNull(LocalGenreClassifier.predict("pop1", listOf(1.0, 0.0), examples()))
        assertNull(LocalGenreClassifier.predict("new", listOf(Double.NaN, 0.0), examples()))
        assertNull(LocalGenreClassifier.predict("new", listOf(0.0, 0.0), examples()))
        assertNull(LocalGenreClassifier.predict("new", listOf(1.0), examples()))
    }

    @Test
    fun genreClassBalanceProtectsSmallButCloserClasses() {
        val anchors =
            (1..64).map { GenreExample("large$it", "large", listOf(.92, .392)) } +
                (1..3).map { GenreExample("small$it", "small", listOf(1.0, 0.0)) }
        assertEquals(
            "small",
            LocalGenreClassifier.predict("new", listOf(1.0, 0.0), anchors)!!.genre,
        )
    }

    @Test
    fun rejectingAProposalChangesSubsequentDecisionsWithoutPromotingPredictionsToLabels() {
        val anchors =
            (1..3).map { GenreExample("a$it", "a", listOf(1.0, 0.0)) } +
                (1..3).map { GenreExample("b$it", "b", listOf(.97, .243)) }
        assertEquals("a", LocalGenreClassifier.predict("new", listOf(1.0, 0.0), anchors)!!.genre)
        val corrected =
            anchors + (1..3).map { GenreExample("rejected$it", "a", listOf(1.0, 0.0), false) }
        assertNotEquals(
            "a",
            LocalGenreClassifier.predict("new", listOf(1.0, 0.0), corrected)?.genre,
        )
    }

    @Test
    fun lrcOffsetsAndRepeatedLinesPreserveExplicitTiming() {
        val lines =
            LocalLyricAlignment.parse(
                "[offset:+150]\n[00:01.50][00:04.125]같은 후렴\n[00:99.00]잘못된 시간\n[00:08]마지막"
            )
        assertEquals(listOf(1650L, 4275L, 8150L), lines.map { it.ms })
        assertEquals(listOf("가사"), LocalLyricAlignment.text("[ar:가수]\n[ti:노래]\n[00:01.00]가사", null))
        assertEquals(lines, LocalLyricAlignment.parse(LocalLyricAlignment.lrc(lines)))
        assertFalse(
            LocalLyricAlignment.valid(
                LocalLyricAlignment.parse("[offset:-2000]\n[00:01]하나\n[00:04]둘\n[00:08]셋"),
                10000,
            )
        )
    }

    @Test
    fun identicalAudioReferenceRequiresMatchingLyricsToo() {
        val source = listOf(TimedLine(1000, "하나"), TimedLine(3000, "둘"), TimedLine(5000, "셋"))
        val anchor = TimingTraining("reference", "same", "pop", 20000, source)
        val match =
            LocalLyricAlignment.light(
                source.map { it.text },
                20000,
                "same",
                "pop",
                emptyList(),
                listOf(anchor),
            )!!
        assertEquals(source, match.lines)
        assertEquals("same_audio_reference", match.method)
        assertNull(
            LocalLyricAlignment.light(
                listOf("다른", "가사", "내용"),
                20000,
                "same",
                "pop",
                emptyList(),
                listOf(anchor),
            )
        )
    }

    @Test
    fun acousticEstimateIsBoundedMonotoneAndExplicitlyLowConfidence() {
        val samples =
            (1..3).map {
                TimingTraining(
                    "$it",
                    "pcm$it",
                    "pop",
                    180000,
                    listOf(
                        TimedLine(12000, "처음 가사"),
                        TimedLine(50000, "다음"),
                        TimedLine(150000, "마지막 노래"),
                    ),
                )
            }
        val guess =
            LocalLyricAlignment.light(
                listOf("시작", "길게 부르는 다음 가사", "셋째 줄", "마지막"),
                200000,
                "target",
                "pop",
                List(1000) { .1 },
                samples,
            )!!
        assertTrue(LocalLyricAlignment.valid(guess.lines, 200000))
        assertTrue(guess.confidence <= .45)
        assertEquals("learned_estimate", guess.method)
        assertEquals(3, guess.evidence)
        assertNull(
            LocalLyricAlignment.light(
                listOf("하나", "둘", "셋"),
                200000,
                "other",
                "pop",
                List(1000) { .1 },
                samples.take(2),
            )
        )
    }

    @Test
    fun speechAlignmentKeepsRepeatedChorusesAtTheirOwnPositions() {
        val lines = listOf("오늘 밤", "다시 만나", "오늘 밤", "함께 걸어")
        val words =
            listOf(
                SpeechWord(1000, 1800, "오늘", .99),
                SpeechWord(1800, 2200, "밤", .99),
                SpeechWord(5000, 5800, "다시", .99),
                SpeechWord(5800, 6500, "만나", .99),
                SpeechWord(10000, 11000, "오늘 밤", .99),
                SpeechWord(15000, 16500, "함께 걸어", .99),
            )
        val aligned = LocalLyricAlignment.speech(lines, words, 20000)!!
        assertEquals(listOf(1000L, 5000L, 10000L, 15000L), aligned.lines.map { it.ms })
        assertEquals("speech_anchors", aligned.method)
        assertNull(LocalLyricAlignment.speech(lines, words.take(2), 20000))
        assertNull(LocalLyricAlignment.speech(lines, words.map { it.copy(confidence = .1) }, 20000))
    }

    @Test
    fun overlappingSpeechChunksDeduplicateOnlyTheSameOccurrence() {
        val previous = listOf(SpeechWord(1000, 2000, "사랑", .7), SpeechWord(5000, 6000, "사랑", .8))
        val merged =
            SpeechAlignment.merge(
                previous,
                listOf(SpeechWord(1100, 1900, "사랑", .9), SpeechWord(9000, 10000, "사랑", .95)),
            )
        assertEquals(listOf(1100L, 5000L, 9000L), merged.map { it.startMs })
        assertEquals(.9, merged.first().confidence, .0001)
    }

    @Test
    fun contextInteractionsRetainTheOldFeaturePrefixAndChangeRanking() {
        val candidates =
            listOf(
                RecommendationCandidate("a", "A", "pop", 0),
                RecommendationCandidate("b", "B", "rock", 0),
            )
        val plain = PersonalRecommendation.rank(candidates, emptyList(), ExperienceProfile())
        val joint =
            PersonalRecommendation.rank(
                candidates,
                emptyList(),
                ExperienceProfile(),
                context =
                    RecommendationContext(
                        jointGenres = mapOf("pop" to 1.0, "rock" to .1),
                        completionRates = mapOf("a" to .95, "b" to .2),
                    ),
            )
        assertEquals(24, joint.first().features.size)
        assertEquals("a", joint.first().key)
        assertEquals(1.0, joint.first().features[20], .0001)
        assertTrue(joint.first().score > plain.first { it.key == "a" }.score)
        assertTrue(joint.first().features[22] > joint.last().features[22])
    }
}
