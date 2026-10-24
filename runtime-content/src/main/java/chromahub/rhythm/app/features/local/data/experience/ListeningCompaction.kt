// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import com.google.gson.Gson
import kotlin.math.round

/** Older records retain observations used by the model, rather than repeated sensor snapshots. */
internal object ListeningCompaction {
    private val gson = Gson()

    fun situation(s: ListeningSituation?): ListeningSituation? =
        s?.let {
            ListeningSituation(
                place = ListeningContext.region(it),
                routeType = it.routeType,
                routeName = ListeningContext.outputGroup(it).substringAfter(":"),
                weekday = it.weekday,
                hour = it.hour,
                systemVolume =
                    if (it.systemVolumeMax > 0)
                        ((it.systemVolume.toDouble() / it.systemVolumeMax *
                                (it.playerVolume ?: 1f) *
                                4)
                            .toInt()
                            .coerceIn(0, 3))
                    else 0,
                systemVolumeMax = if (it.systemVolumeMax > 0) 4 else 0,
                playerVolume = 1f,
                weather =
                    it.weather?.copy(
                        at = 0,
                        fetchedAt = 0,
                        region = "",
                        temperatureC = round(it.weather.temperatureC / 5) * 5,
                        apparentTemperatureC = null,
                        humidityPercent = null,
                        precipitationMm = null,
                        cloudPercent = null,
                        windKmh = null,
                        pressureHpa = null,
                        provider = "summary",
                    ),
                brightnessSetting = it.brightnessSetting?.let { value -> value / 64 * 64 },
                ambientSound =
                    it.ambientSound?.let { sound ->
                        AmbientSound(0, (sound.rmsDbfs / 10).toInt() * 10.0, 0.0, 0.0, 0, 0, false)
                    },
                motion =
                    it.motion
                        ?.takeIf { motion -> motion.confidence >= .4 && motion.kind != "unknown" }
                        ?.let { motion -> MotionSnapshot(motion.kind, 1.0, "summary", 0) },
                daylight = it.daylight?.let { day -> DaylightSnapshot(day.isDay, "summary") },
            )
        }

    fun summarize(record: ListeningRecord): ListeningRecord =
        record.copy(
            situation = situation(record.situation),
            outputTypes = emptyList(),
            codec = null,
            sampleRate = null,
            channels = null,
            bitrate = null,
            libraryModifiedAt = 0,
            volume = 1f,
            compacted = true,
            sourceSegments = record.sourceSegments.coerceAtLeast(1),
            completionReachedAt =
                record.completionReachedAt
                    ?: record.endedAt.takeIf { (record.playCompletionRatio ?: 0.0) >= .9 },
        )

    /** Merge only contiguous intervals with identical musical and contextual dimensions. */
    fun summarizePlay(records: List<ListeningRecord>): List<ListeningRecord> {
        val result = mutableListOf<ListeningRecord>()
        var current: ListeningRecord? = null
        fun identity(r: ListeningRecord) =
            listOf(
                r.playId,
                r.songId,
                r.songKey,
                r.title,
                r.artist,
                r.album,
                r.genre,
                r.timezone,
                r.libraryAddedAt,
                r.audioDurationMs,
                gson.toJson(r.situation),
            )
        for (original in records.filter { it.kind == "segment" }.sortedBy { it.startedAt }) {
            val next = summarize(original)
            val before = current
            if (
                before != null &&
                    before.kind == "segment" &&
                    next.kind == "segment" &&
                    before.endedAt == next.startedAt &&
                    identity(before) == identity(next) &&
                    before.replaces.orEmpty().size + next.replaces.orEmpty().size < 128
            ) {
                current =
                    before.copy(
                        endedAt = next.endedAt,
                        listenedMs = before.listenedMs + next.listenedMs,
                        endPositionMs = next.endPositionMs,
                        recordedAt = maxOf(before.recordedAt, next.recordedAt),
                        reason = next.reason,
                        playedAudioMs = next.playedAudioMs,
                        playCompletionRatio = next.playCompletionRatio,
                        completed = before.completed || next.completed,
                        completionReachedAt =
                            listOfNotNull(before.completionReachedAt, next.completionReachedAt)
                                .minOrNull(),
                        replaces =
                            (before.replaces.orEmpty() + next.id + next.replaces.orEmpty())
                                .distinct(),
                        sourceSegments = before.sourceSegments + next.sourceSegments,
                    )
            } else {
                before?.let(result::add)
                current = next
            }
        }
        current?.let(result::add)
        result += records.filter { it.kind != "segment" }.map(::summarize)
        return result.sortedBy { it.startedAt }
    }
}
