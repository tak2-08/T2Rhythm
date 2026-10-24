// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import androidx.annotation.Keep
import chromahub.rhythm.app.util.GenreUtils
import java.time.*
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class ReportPeriod { WEEK, MONTH, QUARTER;
    fun window(now: LocalDate, offset: Int = 0, zone: ZoneId = ZoneId.systemDefault()): ReportWindow {
        require(offset in -520..0)
        val first = when (this) {
            WEEK -> now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset.toLong())
            MONTH -> now.withDayOfMonth(1).plusMonths(offset.toLong())
            QUARTER -> LocalDate.of(now.year, ((now.monthValue - 1) / 3) * 3 + 1, 1).plusMonths(offset * 3L)
        }
        val end = when (this) { WEEK -> first.plusWeeks(1); MONTH -> first.plusMonths(1); QUARTER -> first.plusMonths(3) }
        return ReportWindow(first, end, first.atStartOfDay(zone).toInstant().toEpochMilli(), end.atStartOfDay(zone).toInstant().toEpochMilli(), zone, this)
    }
}
data class ReportWindow(val first: LocalDate, val until: LocalDate, val startMs: Long, val endMs: Long,
    val zone: ZoneId = ZoneId.systemDefault(), val period: ReportPeriod? = null)
@Keep data class ReportTrack(val key: String, val songId: String, val title: String, val artist: String, val album: String,
    val listenedMs: Long, val plays: Int, val genre: String? = null)
data class ReportArtist(val name: String, val listenedMs: Long, val tracks: Int)
data class ReportDay(val date: LocalDate, val listenedMs: Long)
data class ReportComparison(val currentMs: Long, val previousMs: Long, val currentEndMs: Long, val previousStartMs: Long, val previousEndMs: Long)
data class ListeningReport(val window: ReportWindow, val listenedMs: Long, val plays: Int, val songs: Int,
    val tracks: List<ReportTrack>, val genres: List<Pair<String, Long>>, val artists: List<ReportArtist> = emptyList(),
    val days: List<ReportDay> = emptyList(), val comparison: ReportComparison? = null)

object ListeningReports {
    private data class CacheKey(val window: ReportWindow, val revision: Long, val minute: Long)
    private val cache = java.util.WeakHashMap<ListeningLedger, LinkedHashMap<CacheKey, ListeningReport>>()
    private val cacheLock = Any()

    /** Half-open windows and proportional clipping keep midnight/month-boundary sessions honest. */
    fun overlap(listened: Long, started: Long, ended: Long, start: Long, end: Long): Long {
        if (ended <= started || listened <= 0 || end <= start) return 0
        val overlap = (minOf(ended, end) - maxOf(started, start)).coerceAtLeast(0)
        return (listened.toDouble() * overlap / (ended - started)).toLong().coerceIn(0, listened)
    }

    /** Compare equal elapsed local time, capped to the shorter month (also across DST). */
    fun comparisonRanges(window: ReportWindow, nowMs: Long): Pair<LongRange, LongRange>? {
        val previousFirst = when (window.period) {
            ReportPeriod.WEEK -> window.first.minusWeeks(1)
            ReportPeriod.MONTH -> window.first.minusMonths(1)
            ReportPeriod.QUARTER -> window.first.minusMonths(3)
            null -> return null
        }
        if (nowMs <= window.startMs) return null
        val previousStart = previousFirst.atStartOfDay(window.zone).toInstant().toEpochMilli()
        if (nowMs >= window.endMs) return (window.startMs until window.endMs) to (previousStart until window.startMs)
        val now = Instant.ofEpochMilli(nowMs).atZone(window.zone)
        val elapsedDays = ChronoUnit.DAYS.between(window.first, now.toLocalDate())
        val previousDays = ChronoUnit.DAYS.between(previousFirst, window.first)
        val previousEnd = if (elapsedDays >= previousDays) window.startMs
            else previousFirst.plusDays(elapsedDays).atTime(now.toLocalTime()).atZone(window.zone).toInstant().toEpochMilli()
        val currentEnd = if (elapsedDays >= previousDays)
            window.first.plusDays(previousDays).atStartOfDay(window.zone).toInstant().toEpochMilli() else nowMs
        return (window.startMs until currentEnd) to (previousStart until previousEnd)
    }

    /** Light indexed projections only: no audio, waveform or raw history JSON is loaded. */
    fun read(ledger: ListeningLedger, window: ReportWindow, nowMs: Long = System.currentTimeMillis()): ListeningReport {
        val revision = ledger.changes.value
        val key = CacheKey(window, revision, minOf(nowMs, window.endMs) / 60_000)
        synchronized(cacheLock) { cache[ledger]?.get(key) }?.let { return it }
        val report = ledger.readSnapshot { db ->
            fun amount(start: Long, end: Long) = "CAST(listened * 1.0 * MAX(0,MIN(ended,$end)-MAX(started,$start)) / MAX(1,ended-started) AS INTEGER)"
            fun where(start: Long, end: Long) = "kind='segment' AND listened>0 AND ended>started AND ended>$start AND started<$end"
            fun total(start: Long, end: Long): Long = db.rawQuery("SELECT COALESCE(SUM(${amount(start, end)}),0) FROM records WHERE ${where(start, end)}", null).use { it.moveToFirst(); it.getLong(0) }
            val amount = amount(window.startMs, window.endMs)
            val where = where(window.startMs, window.endMs)
            val totals = db.rawQuery("SELECT COALESCE(SUM($amount),0),COUNT(DISTINCT play_id),COUNT(DISTINCT song_key) FROM records WHERE $where", null).use { c -> c.moveToFirst(); Triple(c.getLong(0), c.getInt(1), c.getInt(2)) }
            val tracks = buildList {
                // MAX chooses the most recent observed text when older segments used old tags.
                db.rawQuery("SELECT song_key,song_id,COALESCE(title,''),artist,COALESCE(album,''),SUM($amount) AS ms,COUNT(DISTINCT play_id),genre,MAX(ended) FROM records WHERE $where GROUP BY song_key ORDER BY ms DESC,song_key", null).use { c ->
                    while (c.moveToNext()) if (c.getLong(5) > 0) add(ReportTrack(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getInt(6), c.getString(7)))
                }
            }
            val dayTotals = linkedMapOf<LocalDate, Long>()
            var date = window.first
            while (date < window.until) { dayTotals[date] = 0; date = date.plusDays(1) }
            db.rawQuery("SELECT started,ended,listened FROM records WHERE $where", null).use { c ->
                while (c.moveToNext()) {
                    val started = c.getLong(0); val ended = c.getLong(1); val listened = c.getLong(2)
                    var day = Instant.ofEpochMilli(maxOf(started, window.startMs)).atZone(window.zone).toLocalDate()
                    var allocated = 0L
                    val clippedTotal = overlap(listened, started, ended, window.startMs, window.endMs)
                    while (day < window.until) {
                        val start = day.atStartOfDay(window.zone).toInstant().toEpochMilli()
                        if (start >= ended) break
                        val end = day.plusDays(1).atStartOfDay(window.zone).toInstant().toEpochMilli()
                        // Assign rounding remainder to the final day so chart totals match exactly.
                        val ms = if (end >= minOf(ended, window.endMs)) clippedTotal - allocated
                            else overlap(listened, started, ended, maxOf(start, window.startMs), minOf(end, window.endMs))
                        dayTotals[day] = (dayTotals[day] ?: 0) + ms.coerceAtLeast(0)
                        allocated += ms
                        day = day.plusDays(1)
                    }
                }
            }
            val comparison = comparisonRanges(window, nowMs)?.let { (current, previous) ->
                ReportComparison(total(current.first, current.last + 1), total(previous.first, previous.last + 1),
                    current.last + 1, previous.first, previous.last + 1)
            }
            summarize(ListeningReport(window, totals.first, totals.second, totals.third, tracks, emptyList(),
                days = dayTotals.map { ReportDay(it.key, it.value) }, comparison = comparison))
        }
        if (ledger.changes.value == revision && report.tracks.size <= 5000) synchronized(cacheLock) {
            cache.getOrPut(ledger) { object : LinkedHashMap<CacheKey, ListeningReport>(4, .75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, ListeningReport>?) = size > 4
            } }.apply { keys.removeAll { it.revision != revision }; put(key, report) }
        }
        return report
    }

    /** Reusable after projecting current library tags; no listening history is rewritten. */
    fun summarize(report: ListeningReport): ListeningReport {
        val artists = report.tracks.filter { it.artist.isNotBlank() && it.artist != "<unknown>" }
            .groupBy { it.artist.trim().uppercase(Locale.ROOT) }.values.map { rows ->
                ReportArtist(rows.first().artist, rows.sumOf { it.listenedMs }, rows.size)
            }.sortedWith(compareByDescending<ReportArtist> { it.listenedMs }.thenBy { it.name }).take(5)
        val genres = mutableMapOf<String, Long>()
        report.tracks.forEach { track ->
            val labels = GenreUtils.splitGenres(track.genre).toList()
            labels.forEachIndexed { index, label ->
                genres[label] = (genres[label] ?: 0) + track.listenedMs / labels.size + if (index.toLong() < track.listenedMs % labels.size) 1 else 0
            }
        }
        return report.copy(artists = artists, genres = genres.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).take(5).map { it.key to it.value })
    }
}
