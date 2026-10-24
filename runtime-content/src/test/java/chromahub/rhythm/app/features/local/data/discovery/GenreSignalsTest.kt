// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import chromahub.rhythm.app.features.local.data.experience.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class GenreSignalsTest {
    private fun signals(group: Int = 0, pcm: String = "") = GenreSignals(
        waveform = if (group == 0) listOf(1.0, 0.0) else listOf(0.0, 1.0),
        fingerprint = List(96) { if (it == group) 1.0 else 0.0 }, lyrics = listOf(group + 1), title = listOf(group + 100), pcm = pcm)
    private fun corpus() = (0..1).flatMap { g -> (1..8).map { n ->
        GenreExample("$g-$n", if (g == 0) "POP" else "ROCK", listOf(1.0), signals = signals(g, (g * 100 + n).toString(16).padStart(64, '0')))
    } }
    @Test fun changingOneInputHasExactlyTheRequestedRelativeWeight() {
        val a = signals()
        fun score(b: GenreSignals) = GenreSignalFeatures.similarity(a, b)!!.score
        assertEquals(1.0, score(a), 1e-9)
        assertEquals(.50, score(a.copy(waveform = listOf(0.0, 1.0))), 1e-9)
        assertEquals(.70, score(a.copy(fingerprint = signals(1).fingerprint)), 1e-9)
        assertEquals(.85, score(a.copy(lyrics = listOf(900))), 1e-9)
        assertEquals(.95, score(a.copy(title = listOf(900))), 1e-9)
    }
    @Test fun matchingWordsCannotOutvoteConflictingAudio() {
        val a = signals()
        assertTrue(GenreSignalFeatures.similarity(a, signals(1).copy(lyrics = a.lyrics, title = a.title))!!.score <
            GenreSignalFeatures.similarity(a, a.copy(lyrics = listOf(900), title = listOf(900)))!!.score)
        assertEquals("POP", LocalGenreClassifier.predict("unseen", a.copy(lyrics = signals(1).lyrics, title = signals(1).title), corpus())!!.genre)
    }
    @Test fun textWithoutWaveformNeverCreatesAPrediction() {
        val textOnly = signals().copy(waveform = emptyList(), fingerprint = emptyList())
        assertNull(GenreSignalFeatures.similarity(textOnly, signals()))
        assertNull(LocalGenreClassifier.predict("unseen", textOnly, corpus()))
    }
    @Test fun missingLyricsReduceCoverageWithoutInventingAnInput() {
        val match = GenreSignalFeatures.similarity(signals(), signals().copy(lyrics = emptyList()))!!
        assertEquals(.85, match.coverage, 1e-9); assertEquals(1.0, match.score, 1e-9)
        val complete = LocalGenreClassifier.predict("unseen", signals(), corpus())!!
        val missing = LocalGenreClassifier.predict("unseen", signals().copy(lyrics = emptyList()), corpus())!!
        assertTrue(missing.confidence < complete.confidence)
    }
    @Test fun identicalPcmCannotInflateRecordingCountOrOverrideAReview() {
        val duplicates = (1..100).map { GenreExample("copy-$it", "POP", listOf(1.0), signals = signals(pcm = "a".repeat(64))) }
        val other = corpus().filter { it.genre == "ROCK" }
        assertNull(LocalGenreClassifier.predict("new", signals(), duplicates + other))
        val fixed = GenreExample("review", "JAZZ", listOf(1.0), signals = signals(pcm = "a".repeat(64)))
        val merged = GenreTrainingPolicy.merge(duplicates + other, listOf(fixed))
        assertEquals(listOf("JAZZ"), merged.filter { it.signals?.pcm == "a".repeat(64) }.map { it.genre })
    }
    @Test fun manualCorrectionsChangeTheMultimodalPrediction() {
        val data = corpus()
        val reviews = data.filter { it.genre == "POP" }.map { it.copy(genre = "JAZZ") }
        assertEquals("POP", LocalGenreClassifier.predict("new", signals(), data)!!.genre)
        assertEquals("JAZZ", LocalGenreClassifier.predict("new", signals(), GenreTrainingPolicy.merge(data, reviews))!!.genre)
    }
    @Test fun hashesAreCaseStableBoundedAndDoNotRetainLrcTimestamps() {
        assertEquals(GenreSignalFeatures.text("Pop MUSIC", 32, 300), GenreSignalFeatures.text("pop music", 32, 300))
        assertEquals(GenreSignalFeatures.text("노래 가사", 128, 24000), GenreSignalFeatures.text("[00:01.234]노래 가사", 128, 24000))
        assertTrue(GenreSignalFeatures.text((0..20000).joinToString(" "), 128, 24000).size <= 128)
        assertFalse(GenreSignalFeatures.valid(signals().copy(waveform = listOf(Double.NaN))))
    }
    @Test fun validationExcludesTheWholePcmGroup() {
        val data = corpus()
        val exam = data.take(1)
        val recording = exam.single()
        val copies = (1..20).map { recording.copy(key = "copy-$it") }
        assertEquals(0.0, GenreValidation.evaluate(copies, exam).accuracy, 1e-9)
        assertTrue(GenreValidation.evaluate(data, exam).accuracy > .9)
    }
    @Test fun acousticFingerprintsIgnoreGainAndRetainSpectralDifferences() {
        fun audio(frequency: Double, gain: Double): AudioFeatures {
            val analysis = SignalAnalysis(16000, 2000)
            repeat(32000) { analysis.add(sin(2 * PI * frequency * it / 16000) * gain) }
            return analysis.finish("song", "a".repeat(64), 1)
        }
        val a = audio(440.0, .8); val quiet = audio(440.0, .2); val higher = audio(880.0, .8)
        assertEquals(4, a.version); assertEquals(96, a.acousticFingerprint!!.size)
        assertTrue(AudioVectors.cosine(a.acousticFingerprint!!, quiet.acousticFingerprint!!) > .999)
        assertTrue(AudioVectors.cosine(a.acousticFingerprint!!, higher.acousticFingerprint!!) < .9)
    }
}
