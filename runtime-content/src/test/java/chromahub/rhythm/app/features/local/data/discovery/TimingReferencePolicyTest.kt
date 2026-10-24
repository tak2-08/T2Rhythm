// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import org.junit.Assert.*
import org.junit.Test

class TimingReferencePolicyTest {
    private val lines = (0..9).map { TimedLine(10_000 + it * 8000L, "가사 문장 $it") }
    @Test fun structuralTrustRejectsGeneratedAndCollapsedTimestamps() {
        assertTrue(TimingReferencePolicy.trusted(lines, 100_000, "LRC file"))
        assertFalse(TimingReferencePolicy.trusted(lines, 100_000, "T2Rhythm generated"))
        assertTrue(TimingReferencePolicy.trusted(lines, 100_000, "T2Rhythm reviewed", reviewed = true))
        assertFalse(TimingReferencePolicy.trusted(lines.mapIndexed { i, l -> l.copy(ms = i * 100L) }, 100_000, "LRC file"))
        assertFalse(TimingReferencePolicy.trusted(lines.reversed(), 100_000, "LRC file"))
    }
    @Test fun repeatedRecordingAndDominantGenreCannotFillTheReservoir() {
        val examples = (0..200).map { TimingTraining("song$it", "pcm${it / 2}", "genre${it / 50}", 100_000, lines) }
        val selected = TimingReferencePolicy.balanced(examples)
        assertEquals(32, selected.size)
        assertEquals(32, selected.map { it.pcm }.distinct().size)
        assertTrue(selected.groupBy { it.genre }.values.all { it.size <= 8 })
    }
    @Test fun waveformDraftNeedsAudibleEnergyAndNeverPretendsToRecognizeWords() {
        val text = lines.map { it.text }
        assertNull(LocalLyricAlignment.light(text, 100_000, "target", "", List(500) { 0.0 }, emptyList()))
        val guess = LocalLyricAlignment.light(text, 100_000, "target", "", List(500) { if (it in 40..460) .3 else .001 }, emptyList())!!
        assertEquals("waveform_estimate", guess.method)
        assertTrue(guess.confidence < .25)
        assertEquals(text, guess.lines.map { it.text })
        assertTrue(LocalLyricAlignment.valid(guess.lines, 100_000))
    }
    @Test fun missingFingerprintCannotMatchAnUnrelatedEmptyFingerprint() {
        val example = TimingTraining("other", "", "", 100_000, lines)
        assertNull(LocalLyricAlignment.light(lines.map { it.text }, 100_000, "", "", emptyList(), listOf(example)))
    }
}
