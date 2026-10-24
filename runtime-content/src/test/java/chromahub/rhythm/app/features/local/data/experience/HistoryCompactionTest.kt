// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class HistoryCompactionTest {
    private val gson = Gson()
    private val context =
        ListeningSituation(
            latitude = 37.12345,
            longitude = 127.12345,
            precise = true,
            routeType = "bluetooth",
            routeName = "Buds",
            brightnessSetting = 155,
            systemVolume = 7,
            systemVolumeMax = 15,
            ambientSound = AmbientSound(123, -25.1, -10.0, .01, 16000, 0, false),
            motion = MotionSnapshot("walking", .8, "sensor", 123),
            daylight = DaylightSnapshot(true, "solar"),
        )

    private fun row(id: String, start: Long, end: Long) =
        ListeningRecord(
            id,
            "play",
            "segment",
            "song",
            "key",
            "제목",
            "가수",
            "앨범",
            "Pop",
            start,
            end,
            end - start,
            120000,
            start,
            end,
            1f,
            "pause",
            recordedAt = end,
            timezone = "UTC",
            situation = context,
            playCompletionRatio = .96,
        )

    private fun source(vararg rows: ListeningRecord) =
        rows.joinToString("\n", postfix = "\n") { gson.toJson(it) }.byteInputStream()

    @Test
    fun MergingPreservesCoverageCountsAndContextWhileRemovingFineReadings() {
        val rows = listOf(row("a", 1000, 31000), row("b", 31000, 61000), row("c", 91000, 121000))
        val compact = ListeningCompaction.summarizePlay(rows)
        assertEquals(2, compact.size)
        assertEquals(90000L, compact.sumOf { it.listenedMs })
        assertEquals(
            listOf(1000L to 61000L, 91000L to 121000L),
            compact.map { it.startedAt to it.endedAt },
        )
        assertEquals(3, compact.sumOf { it.sourceSegments })
        assertEquals(listOf("b"), compact[0].replaces)
        assertNull(compact[0].situation?.latitude)
        assertNull(compact[0].situation?.longitude)
        assertEquals(
            ListeningContext.region(context),
            ListeningContext.region(compact[0].situation),
        )
        assertEquals(
            ListeningContext.outputGroup(context),
            ListeningContext.outputGroup(requireNotNull(compact[0].situation)),
        )
        assertEquals(
            ListeningInsightAnalysis.analyze(rows),
            ListeningInsightAnalysis.analyze(compact),
        )
    }

    @Test
    fun ContextChangesAndGapsNeverBecomeContinuousListening() {
        val a = row("a", 1000, 31000)
        val b = row("b", 31000, 61000).copy(situation = context.copy(routeType = "wired"))
        assertEquals(2, ListeningCompaction.summarizePlay(listOf(a, b)).size)
        assertEquals(2, ListeningCompaction.summarizePlay(listOf(a, row("c", 32000, 62000))).size)
    }

    @Test
    fun RecommendationSummaryKeepsAllTrainingInputsAndReversibleFeedback() {
        val event =
            RecommendationEvent(
                "id",
                1,
                "explicit",
                "key",
                features = listOf(.2, .4),
                reward = 1.0,
                prediction = .6,
                situation = context,
                candidateKeys = listOf("a", "b"),
                title = "title",
                completionRatio = .96,
                startedAt = 0,
            )
        val compact = HistoryRetention.compact(event)
        assertEquals(event.features, compact.features)
        assertEquals(event.reward, compact.reward)
        assertEquals(event.prediction, compact.prediction)
        assertEquals(event.startedAt, compact.startedAt)
        assertEquals(event.completionRatio, compact.completionRatio)
        assertNull(compact.situation)
        assertTrue(compact.candidateKeys.isEmpty())
        assertTrue(compact.compacted)
        assertNull(HistoryRetention.compact(event.copy(reward = null)).reward)
    }

    @Test
    fun BackupCoverageRequiresEveryOldRecordAndRejectsNewerOrUnrelatedData() {
        val a = row("a", 1000, 31000)
        val b = row("b", 31000, 61000)
        val summary = ListeningCompaction.summarizePlay(listOf(a, b)).single()
        val coverage = requireNotNull(JournalCoverage.read(source(summary), 200000))
        assertTrue(coverage.covers(source(a, b)))
        assertFalse(coverage.covers(source(a.copy(recordedAt = 999999))))
        assertFalse(coverage.covers(source(b.copy(songKey = "different"))))
        assertFalse(coverage.covers(source(row("missing", 1000, 31000))))
        assertFalse(coverage.covers("not json".byteInputStream()))
        assertNull(JournalCoverage.read(source(a, b), 200000))
        assertNull(JournalCoverage.read(source(summary), 100))
    }
}
