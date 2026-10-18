// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import java.time.*
import java.time.temporal.TemporalAdjusters
import androidx.annotation.Keep

enum class ReportPeriod { WEEK, MONTH, QUARTER;
    fun window(now: LocalDate, offset: Int = 0, zone: ZoneId = ZoneId.systemDefault()): ReportWindow {
        require(offset <= 0 && offset >= -520)
        val first = when (this) {
            WEEK -> now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset.toLong())
            MONTH -> now.withDayOfMonth(1).plusMonths(offset.toLong())
            QUARTER -> LocalDate.of(now.year, ((now.monthValue - 1) / 3) * 3 + 1, 1).plusMonths(offset * 3L)
        }
        val end = when (this) { WEEK -> first.plusWeeks(1); MONTH -> first.plusMonths(1); QUARTER -> first.plusMonths(3) }
        return ReportWindow(first, end, first.atStartOfDay(zone).toInstant().toEpochMilli(), end.atStartOfDay(zone).toInstant().toEpochMilli())
    }
}
data class ReportWindow(val first: LocalDate, val until: LocalDate, val startMs: Long, val endMs: Long)
@Keep data class ReportTrack(val key: String, val songId: String, val title: String, val artist: String, val album: String, val listenedMs: Long, val plays: Int)
data class ListeningReport(val window: ReportWindow, val listenedMs: Long, val plays: Int, val songs: Int, val tracks: List<ReportTrack>, val genres: List<Pair<String, Long>>)

object ListeningReports {
    /** Half-open windows and proportional clipping keep midnight/month-boundary sessions honest. */
    fun overlap(listened: Long, started: Long, ended: Long, start: Long, end: Long): Long {
        if (ended <= started || listened <= 0) return 0
        val overlap = (minOf(ended, end) - maxOf(started, start)).coerceAtLeast(0)
        return (listened.toDouble() * overlap / (ended - started)).toLong().coerceIn(0, listened)
    }
    fun read(ledger: ListeningLedger, window: ReportWindow): ListeningReport {
        val db = ledger.readableDatabase
        // Numeric columns are indexed projections of the durable history. No waveform/sensor JSON is loaded.
        val amount = "CAST(listened * 1.0 * MAX(0,MIN(ended,${window.endMs})-MAX(started,${window.startMs})) / MAX(1,ended-started) AS INTEGER)"
        val where = "kind='segment' AND listened>0 AND ended>${window.startMs} AND started<${window.endMs}"
        val totals = db.rawQuery("SELECT COALESCE(SUM($amount),0),COUNT(DISTINCT play_id),COUNT(DISTINCT song_key) FROM records WHERE $where", null).use { c -> c.moveToFirst(); Triple(c.getLong(0), c.getInt(1), c.getInt(2)) }
        val tracks = buildList {
            db.rawQuery("SELECT song_key,song_id,COALESCE(title,''),artist,COALESCE(album,''),SUM($amount) AS ms,COUNT(DISTINCT play_id) FROM records WHERE $where GROUP BY song_key ORDER BY ms DESC,song_key LIMIT 40", null).use { c ->
                while (c.moveToNext()) add(ReportTrack(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getInt(6)))
            }
        }
        val genres = buildList {
            db.rawQuery("SELECT genre,SUM($amount) AS ms FROM records WHERE $where AND genre IS NOT NULL AND genre<>'' GROUP BY genre ORDER BY ms DESC LIMIT 5", null).use { c -> while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) }
        }
        return ListeningReport(window, totals.first, totals.second, totals.third, tracks, genres)
    }
}
