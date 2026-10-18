// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import androidx.annotation.Keep
import java.util.Locale

@Keep data class LyricTimingMark(val text: String, val start: Long? = null, val end: Long? = null)

/** All coordinates are milliseconds from the beginning of the recording, never tap deltas. */
object ManualLyricTiming {
    fun fromText(text: List<String>) = text.map { LyricTimingMark(it) }
    fun fromTimed(lines: List<TimedLine>) = lines.map { LyricTimingMark(it.text, it.ms, it.endMs) }
    fun lines(rows: List<LyricTimingMark>): List<TimedLine> = rows.map { TimedLine(requireNotNull(it.start), it.text, it.end) }
    fun valid(rows: List<LyricTimingMark>, duration: Long, complete: Boolean = false): Boolean =
        rows.size in 1..1000 && duration > 0 && rows.all { it.text.isNotBlank() } &&
            rows.withIndex().all { (i, row) ->
                (!complete || row.start != null && row.end != null) &&
                (row.start == null || row.start in 0 until duration) &&
                (row.end == null || row.start != null && row.end > row.start && row.end <= duration) &&
                (row.start == null || rows.take(i).lastOrNull { it.start != null }?.let { previous ->
                    row.start > previous.start!! && (previous.end == null || previous.end <= row.start)
                } != false)
            }
    fun mark(rows: List<LyricTimingMark>, index: Int, end: Boolean, time: Long, duration: Long): List<LyricTimingMark> {
        require(index in rows.indices)
        val old = rows[index]
        val replacement = if (end) old.copy(end = time) else old.copy(start = time, end = old.end?.takeIf { it > time })
        return rows.toMutableList().also { it[index] = replacement; require(valid(it, duration)) }
    }
    fun shift(rows: List<LyricTimingMark>, delta: Long, duration: Long): List<LyricTimingMark> = rows.map {
        it.copy(start = it.start?.plus(delta), end = it.end?.plus(delta))
    }.also { require(valid(it, duration)) }
    fun stamp(ms: Long?) = ms?.let { "%02d:%02d.%03d".format(Locale.ROOT, it / 60000, it / 1000 % 60, it % 1000) } ?: "—"
    fun parseTime(text: String): Long? {
        val match = Regex("^(\\d{1,3}):([0-5]\\d)(?:[.,](\\d{1,3}))?$").matchEntire(text.trim()) ?: return null
        return (match.groupValues[1].toLong() * 60 + match.groupValues[2].toLong()) * 1000 +
            (match.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0)
    }
}
