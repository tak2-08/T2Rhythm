// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ManualLyricTimingTest {
    @Test fun tapsKeepMillisecondPositionsAndDistinctStartEndMeaning() {
        var rows = ManualLyricTiming.fromText(listOf("첫 소절", "다음 소절", "마지막 소절"))
        rows = ManualLyricTiming.mark(rows, 0, false, 1234, 20_000)
        rows = ManualLyricTiming.mark(rows, 0, true, 3456, 20_000)
        rows = ManualLyricTiming.mark(rows, 1, false, 6789, 20_000)
        rows = ManualLyricTiming.mark(rows, 1, true, 8999, 20_000)
        rows = ManualLyricTiming.mark(rows, 2, false, 12_345, 20_000)
        rows = ManualLyricTiming.mark(rows, 2, true, 19_876, 20_000)
        assertTrue(ManualLyricTiming.valid(rows, 20_000, complete = true))
        val lrc = LocalLyricAlignment.lrc(ManualLyricTiming.lines(rows))
        assertTrue(lrc.startsWith("[00:01.234]첫 소절\n[00:03.456]"))
        assertEquals(ManualLyricTiming.lines(rows), LocalLyricAlignment.parse(lrc))
    }
    @Test fun fineEditsCannotCrossAdjacentLinesOrSongBounds() {
        val rows = listOf(LyricTimingMark("첫", 1000, 3000), LyricTimingMark("둘", 4000, 5000), LyricTimingMark("셋", 6000, 9000))
        assertEquals(1010L, ManualLyricTiming.mark(rows, 0, false, 1010, 10_000).first().start)
        for (edit in listOf<() -> Unit>(
            { ManualLyricTiming.mark(rows, 0, true, 4500, 10_000) },
            { ManualLyricTiming.mark(rows, 1, false, 2999, 10_000) },
            { ManualLyricTiming.shift(rows, -1001, 10_000) },
            { ManualLyricTiming.mark(rows, 2, true, 10_001, 10_000) },
        )) try { edit(); fail("Invalid capture must be rejected") } catch (_: IllegalArgumentException) { }
        val shifted = ManualLyricTiming.shift(rows, 50, 10_000)
        assertEquals(rows.map { it.end!! - it.start!! }, shifted.map { it.end!! - it.start!! })
    }
    @Test fun exactEntryDoesNotRoundToAnEditingStep() {
        assertEquals(83_456L, ManualLyricTiming.parseTime("01:23.456"))
        assertEquals(83_400L, ManualLyricTiming.parseTime("01:23.4"))
        assertNull(ManualLyricTiming.parseTime("01:99.000"))
        assertNull(ManualLyricTiming.parseTime("-1:23.000"))
        assertNull(ManualLyricTiming.parseTime("01:23.4567"))
    }
    @Test fun adjacentEndsArePreservedAsWellAsSilentGaps() {
        val rows = listOf(TimedLine(1000, "하나", 3000), TimedLine(3000, "둘", 5000), TimedLine(7000, "셋", 9000))
        assertEquals(rows, LocalLyricAlignment.parse(LocalLyricAlignment.lrc(rows)))
    }
    @Test fun meaninglessChangesDoNotRegenerateRejectedProposals() {
        val original = "[00:01.000]하나\n[00:04.000]둘\n[00:08.000]셋"
        assertFalse(TimingRetryPolicy.changed(original, original.replace("01.000", "01.010")))
        assertTrue(TimingRetryPolicy.changed(original, original.replace("01.000", "01.300")))
        assertTrue(TimingRetryPolicy.changed(original, "[00:01.000]하나\n[00:03.000]\n[00:04.000]둘\n[00:07.000]\n[00:08.000]셋"))
    }
    @Test fun oneExtremeRecordingAndItsCopiesCannotDominateATemplate() {
        val baseline = listOf(TimedLine(1000, "첫 문장"), TimedLine(5000, "둘 문장"), TimedLine(9000, "셋 문장"))
        val normal = (0..3).map { TimingTraining("r$it", "pcm$it", "pop", 20_000,
            listOf(TimedLine(2000, "첫 문장", 3500), TimedLine(6000, "둘 문장", 7500), TimedLine(15_000, "셋 문장", 17_000)), true) }
        val outlier = TimingTraining("outlier", "same-outlier", "pop", 20_000,
            listOf(TimedLine(15_000, "첫 문장"), TimedLine(16_000, "둘 문장"), TimedLine(19_000, "셋 문장")), true)
        val once = LearnedLyricTiming.fit(baseline.map { it.text }, 20_000, "pop", baseline, normal + outlier)
        val copied = LearnedLyricTiming.fit(baseline.map { it.text }, 20_000, "pop", baseline, normal + List(100) { outlier.copy(key = "copy$it") })
        assertEquals(once, copied)
        assertTrue(once.first().ms < 5000)
        assertTrue(once.all { it.endMs != null })
    }
    @Test fun reviewedLineIntervalsImproveAnUnseenRecordingTemplate() {
        val truth = listOf(TimedLine(3000, "같은 길이 문장", 7000), TimedLine(11_000, "같은 길이 문장", 15_000), TimedLine(31_000, "같은 길이 문장", 35_000), TimedLine(38_000, "같은 길이 문장", 40_000))
        val baseline = listOf(3000L, 13_000L, 23_000L, 33_000L).mapIndexed { i, ms -> TimedLine(ms, truth[i].text) }
        val references = (0..5).map { TimingTraining("other$it", "different-pcm-$it", "pop", 45_000, truth, true) }
        val fitted = LearnedLyricTiming.fit(truth.map { it.text }, 45_000, "pop", baseline, references)
        fun error(lines: List<TimedLine>) = lines.zip(truth).sumOf { abs(it.first.ms - it.second.ms) }
        assertTrue(error(fitted) < error(baseline))
        assertTrue(LocalLyricAlignment.valid(fitted, 45_000))
        assertEquals(baseline, LearnedLyricTiming.fit(truth.map { it.text }, 45_000, "pop", baseline, references.take(2)))
    }
    @Test fun promotionRejectsMeanImprovementsThatHideLargeRegressions() {
        assertTrue(TimingValidation.accepts(listOf(800.0 to 1000.0, 900.0 to 1000.0, 1000.0 to 1000.0)))
        assertFalse(TimingValidation.accepts(listOf(0.0 to 1000.0, 0.0 to 1000.0, 2000.0 to 1000.0)))
        assertFalse(TimingValidation.accepts(listOf(1200.0 to 1000.0, 1200.0 to 1000.0, 1200.0 to 1000.0)))
        assertFalse(TimingValidation.accepts(listOf(100.0 to 1000.0, 100.0 to 1000.0)))
        assertFalse(TimingValidation.accepts(listOf(Double.NaN to 1000.0, 0.0 to 1000.0, 0.0 to 1000.0)))
    }
    @Test fun recognitionLatencyRequiresMultipleReviewedRecordings() {
        val raw = TimingGuess(listOf(TimedLine(1000, "첫", 3000), TimedLine(5000, "둘", 7000), TimedLine(9000, "셋", 12_000)), .9, "speech_anchors", 3, listOf(0, 1, 2))
        val truth = raw.lines.map { it.copy(ms = it.ms + 600, endMs = it.endMs!! + 600) }
        val offset = TimingSpeechCalibration.measure(truth, raw)
        assertEquals(600.0, offset.first!!, .001)
        val examples = (0..5).map { TimingTraining("r$it", "pcm$it", "pop", 20_000, truth, true, speechOffsetMs = offset.first, speechEndOffsetMs = offset.second) }
        assertEquals(raw, TimingSpeechCalibration.apply(raw, examples.take(2), 20_000))
        val corrected = TimingSpeechCalibration.apply(raw, examples, 20_000)
        assertTrue(abs(corrected.lines.first().ms - truth.first().ms) < abs(raw.lines.first().ms - truth.first().ms))
        assertEquals(raw, TimingSpeechCalibration.apply(raw, examples.map { it.copy(reviewed = false) }, 20_000))
    }
    @Test fun learnedLastStartIsNotMistakenForTheEndOfTheWholeVocalPassage() {
        val text = listOf("첫 번째 문장", "두 번째 문장", "세 번째 문장")
        val truth = text.mapIndexed { i, line -> TimedLine(2000L + i * 6000, line, 5000L + i * 6000) }
        val examples = (0..4).map { TimingTraining("source$it", "pcm$it", "pop", 20_000, truth, true) }
        val guess = LocalLyricAlignment.light(text, 20_000, "unseen", "pop", List(100) { .2 }, examples)!!
        assertTrue(abs(guess.lines.last().ms - 14_000) < 300)
        assertTrue(LocalLyricAlignment.valid(guess.lines, 20_000))
    }
}
