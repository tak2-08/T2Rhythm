// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.shared.data.repository

import chromahub.rhythm.app.features.local.data.experience.ListeningStatsRow
import chromahub.rhythm.app.shared.data.repository.PlaybackStatsRepository.*
import chromahub.rhythm.app.util.GenreUtils
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

internal data class StatsTrackMeta(
    val title: String,
    val artist: String,
    val album: String,
    val genre: String?,
    val artwork: String?,
)

/** Sorted streaming union. Memory depends on songs and calendar buckets, never event count. */
internal class StreamingPlaybackStats(
    private val range: StatsTimeRange,
    private val now: Long,
    private val zone: ZoneId,
    private val locale: Locale,
    private val catalog: Map<String, StatsTrackMeta> = emptyMap(),
    private val artistsFor: (String) -> List<String> = { listOf(it) },
    private val unknownTitle: String = "Unknown",
    private val unknownArtist: String = "Unknown Artist",
    private val unknownAlbum: String = "Unknown Album",
    private val unknownGenre: String = "Unknown",
) {
    private val lower = range.resolveBounds(now, zone).first

    private data class SongTotal(
        val row: ListeningStatsRow,
        var duration: Long = 0,
        var end: Long = Long.MIN_VALUE,
        var spans: Int = 0,
    )

    private data class Bucket(var duration: Long = 0, var spans: Int = 0)

    private val songs = linkedMapOf<String, SongTotal>()
    private val days = sortedMapOf<LocalDate, Bucket>()
    private val hours = LongArray(24)
    private val hourSpans = IntArray(24)
    private var pendingStart = -1L
    private var pendingEnd = -1L
    private var lastStart = Long.MIN_VALUE
    private var first = now
    private var last = now
    private var duration = 0L
    private var sessions = 0
    private var sessionEnd = -1L
    private var sessionDuration = 0L
    private var longestSession = 0L

    fun add(row: ListeningStatsRow) {
        require(row.start >= lastStart) { "Statistics rows must be chronological" }
        lastStart = row.start
        val start = maxOf(row.start, lower ?: 0)
        val end = minOf(row.end, now)
        if (end <= start) return
        val song = songs.getOrPut(row.songId) { SongTotal(row) }
        if (start > song.end) song.spans++
        song.duration += (end - maxOf(start, song.end)).coerceAtLeast(0)
        song.end = maxOf(song.end, end)
        if (pendingStart < 0) {
            pendingStart = start
            pendingEnd = end
            first = start
        } else if (start <= pendingEnd) pendingEnd = maxOf(pendingEnd, end)
        else {
            flushSpan()
            pendingStart = start
            pendingEnd = end
        }
    }

    private fun flushSpan() {
        if (pendingStart < 0) return
        val size = pendingEnd - pendingStart
        duration += size
        last = pendingEnd
        if (sessionEnd < 0 || pendingStart - sessionEnd > 30 * 60_000L) {
            longestSession = maxOf(longestSession, sessionDuration)
            sessions++
            sessionDuration = 0
        }
        sessionDuration += size
        sessionEnd = pendingEnd
        var cursor = pendingStart
        while (cursor < pendingEnd) {
            val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val end =
                minOf(pendingEnd, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            days
                .getOrPut(date) { Bucket() }
                .also {
                    it.duration += end - cursor
                    it.spans++
                }
            cursor = end
        }
        cursor = pendingStart
        while (cursor < pendingEnd) {
            val local = Instant.ofEpochMilli(cursor).atZone(zone)
            val next =
                local
                    .withMinute(0)
                    .withSecond(0)
                    .withNano(0)
                    .plusHours(1)
                    .toInstant()
                    .toEpochMilli()
            val end = minOf(pendingEnd, next)
            hours[local.hour] += end - cursor
            hourSpans[local.hour]++
            cursor = end
        }
    }

    fun finish(playCounts: Map<String, Int>): PlaybackStatsSummary {
        flushSpan()
        pendingStart = -1
        longestSession = maxOf(longestSession, sessionDuration)
        val artistDurations = mutableMapOf<String, Long>()
        val artistPlays = mutableMapOf<String, Int>()
        val artistSongs = mutableMapOf<String, Int>()
        val albumDurations = mutableMapOf<String, Long>()
        val albumPlays = mutableMapOf<String, Int>()
        val albumSongs = mutableMapOf<String, Int>()
        val albumArt = mutableMapOf<String, String?>()
        val genreDurations = mutableMapOf<String, Long>()
        val genrePlays = mutableMapOf<String, Int>()
        var totalPlays = 0
        val trackTotals =
            songs.map { (id, total) ->
                val meta =
                    catalog[id]
                        ?: StatsTrackMeta(
                            total.row.title,
                            total.row.artist,
                            total.row.album,
                            total.row.genre,
                            null,
                        )
                val plays = playCounts[id] ?: total.spans
                totalPlays += plays
                artistsFor(meta.artist)
                    .filter(String::isNotBlank)
                    .distinct()
                    .ifEmpty { listOf(unknownArtist) }
                    .forEach { artist ->
                        artistDurations[artist] = (artistDurations[artist] ?: 0) + total.duration
                        artistPlays[artist] = (artistPlays[artist] ?: 0) + plays
                        artistSongs[artist] = (artistSongs[artist] ?: 0) + 1
                    }
                val album = meta.album.takeIf(String::isNotBlank) ?: unknownAlbum
                albumDurations[album] = (albumDurations[album] ?: 0) + total.duration
                albumPlays[album] = (albumPlays[album] ?: 0) + plays
                albumSongs[album] = (albumSongs[album] ?: 0) + 1
                if (albumArt[album] == null) albumArt[album] = meta.artwork
                GenreUtils.splitGenres(meta.genre)
                    .ifEmpty { listOf(unknownGenre) }
                    .distinct()
                    .forEach { genre ->
                        genreDurations[genre] = (genreDurations[genre] ?: 0) + total.duration
                        genrePlays[genre] = (genrePlays[genre] ?: 0) + plays
                    }
                SongPlaybackSummary(
                    id,
                    meta.title.takeIf(String::isNotBlank) ?: unknownTitle,
                    meta.artist.takeIf(String::isNotBlank) ?: unknownArtist,
                    meta.artwork,
                    total.duration,
                    plays,
                )
            }
        val daySpan =
            if (songs.isEmpty()) 1L
            else
                maxOf(
                    1L,
                    java.time.temporal.ChronoUnit.DAYS.between(
                        Instant.ofEpochMilli(lower ?: first).atZone(zone).toLocalDate(),
                        Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),
                    ) + 1,
                )
        var longest = 0
        var streak = 0
        var previous: LocalDate? = null
        days.keys.forEach { day ->
            streak = if (previous == null || day == previous!!.plusDays(1)) streak + 1 else 1
            longest = maxOf(longest, streak)
            previous = day
        }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val weekDays =
            days.entries
                .groupBy { it.key.dayOfWeek }
                .mapValues { (_, entries) -> entries.sumOf { it.value.duration } }
        val peakDay = weekDays.maxByOrNull { it.value }
        val genreTotal = genrePlays.values.sum().coerceAtLeast(1)
        val timeline =
            when (range) {
                StatsTimeRange.TODAY ->
                    (0..23).map {
                        TimelineEntry(
                            String.format(Locale.ROOT, "%02d:00", it),
                            hours[it],
                            hourSpans[it],
                        )
                    }
                StatsTimeRange.WEEK ->
                    (6 downTo 0).map { offset ->
                        val date = today.minusDays(offset.toLong())
                        val bucket = days[date]
                        TimelineEntry(
                            date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                            bucket?.duration ?: 0,
                            bucket?.spans ?: 0,
                        )
                    }
                StatsTimeRange.MONTH ->
                    days.entries
                        .groupBy { it.key.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
                        .toSortedMap()
                        .map { (date, entries) ->
                            TimelineEntry(
                                date.format(DateTimeFormatter.ofPattern("M/d", locale)),
                                entries.sumOf { it.value.duration },
                                entries.sumOf { it.value.spans },
                            )
                        }
                StatsTimeRange.ALL_TIME ->
                    days.entries
                        .groupBy { YearMonth.from(it.key) }
                        .toSortedMap()
                        .map { (month, entries) ->
                            TimelineEntry(
                                month.toString(),
                                entries.sumOf { it.value.duration },
                                entries.sumOf { it.value.spans },
                            )
                        }
            }
        return PlaybackStatsSummary(
            range,
            lower,
            now,
            duration,
            totalPlays,
            songs.size,
            artistDurations.size,
            duration / daySpan,
            trackTotals
                .sortedWith(
                    compareByDescending<SongPlaybackSummary> { it.totalDurationMs }
                        .thenBy { it.songId }
                )
                .take(5),
            artistDurations.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }
                )
                .take(5)
                .map {
                    ArtistPlaybackSummary(
                        it.key,
                        it.value,
                        artistPlays[it.key] ?: 0,
                        artistSongs[it.key] ?: 0,
                    )
                },
            albumDurations.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }
                )
                .take(5)
                .map {
                    AlbumPlaybackSummary(
                        it.key,
                        albumArt[it.key],
                        it.value,
                        albumPlays[it.key] ?: 0,
                        albumSongs[it.key] ?: 0,
                    )
                },
            genreDurations.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }
                )
                .take(5)
                .map {
                    GenrePlaybackSummary(
                        it.key,
                        it.value,
                        genrePlays[it.key] ?: 0,
                        (genrePlays[it.key] ?: 0).toFloat() / genreTotal,
                    )
                },
            timeline,
            days.size,
            longest,
            if (previous == today || previous == today.minusDays(1)) streak else 0,
            sessions,
            if (sessions > 0) duration / sessions else 0,
            longestSession,
            sessions.toFloat() / daySpan,
            peakDay?.key?.getDisplayName(TextStyle.FULL, locale),
            peakDay?.value ?: 0,
            hours.indices.maxByOrNull { hours[it] }?.takeIf { hours[it] > 0 },
            (0..23).map { DailyListeningBucket(it, (it + 1) % 24, hours[it]) },
        )
    }
}
