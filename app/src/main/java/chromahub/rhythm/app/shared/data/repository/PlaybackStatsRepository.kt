/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.shared.data.repository

import android.content.Context
import androidx.annotation.Keep
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.experience.ListeningLedger
import chromahub.rhythm.app.shared.data.model.Song
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Time range options for statistics */
@Keep
enum class StatsTimeRange(val displayName: String, val daysBack: Int) {
    TODAY("Today", 1),
    WEEK("This Week", 7),
    MONTH("This Month", 30),
    ALL_TIME("All Time", Int.MAX_VALUE);

    fun resolveBounds(nowMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): Pair<Long?, Long> {
        val now = Instant.ofEpochMilli(nowMillis)
        val endBound = nowMillis

        val startBound: Long? =
            when (this) {
                TODAY -> {
                    now.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
                }
                WEEK -> {
                    now.atZone(zoneId)
                        .toLocalDate()
                        .minusDays(6)
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli()
                }
                MONTH -> {
                    now.atZone(zoneId)
                        .toLocalDate()
                        .minusDays(29)
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli()
                }
                ALL_TIME -> null
            }

        return startBound to endBound
    }
}

/**
 * Repository for tracking and analyzing playback statistics Provides comprehensive stats tracking
 * for songs and listening habits
 */
class PlaybackStatsRepository private constructor(private val context: Context) {
    private val gson = Gson()
    private val historyFile = File(context.applicationContext.filesDir, "playback_history.json")
    private val fileLock = Any()
    private val summaryLock = Mutex()
    private val summaryCache = PlaybackSummaryCache(context)
    private val eventsType = object : TypeToken<MutableList<PlaybackEvent>>() {}.type

    companion object {
        @Volatile private var instance: PlaybackStatsRepository? = null

        // Maximum reasonable event duration (4 hours)
        private val MAX_REASONABLE_EVENT_DURATION_MS = TimeUnit.HOURS.toMillis(4)

        // Session gap threshold (30 minutes)
        private val SESSION_GAP_THRESHOLD_MS = TimeUnit.MINUTES.toMillis(30)

        fun getInstance(context: Context): PlaybackStatsRepository {
            return instance
                ?: synchronized(this) {
                    instance
                        ?: PlaybackStatsRepository(context.applicationContext).also {
                            instance = it
                        }
                }
        }
    }

    // Observable state for UI updates
    private val _statsSummary = MutableStateFlow<PlaybackStatsSummary?>(null)
    val statsSummary: StateFlow<PlaybackStatsSummary?> = _statsSummary.asStateFlow()

    /** Playback event data class */
    @Keep
    data class PlaybackEvent(
        val songId: String,
        val timestamp: Long,
        val durationMs: Long,
        val startTimestamp: Long? = null,
        val endTimestamp: Long? = null,
        val songTitle: String? = null,
        val artistName: String? = null,
        val albumName: String? = null,
        val genre: String? = null,
        val playId: String? = null,
    ) {
        fun startMillis(): Long = startTimestamp ?: (timestamp - durationMs).coerceAtLeast(0L)

        fun endMillis(): Long = endTimestamp ?: timestamp
    }

    /** Song playback summary */
    @Keep
    data class SongPlaybackSummary(
        val songId: String,
        val title: String,
        val artist: String,
        val albumArtUri: String?,
        val totalDurationMs: Long,
        val playCount: Int,
    )

    /** Artist playback summary */
    @Keep
    data class ArtistPlaybackSummary(
        val artist: String,
        val totalDurationMs: Long,
        val playCount: Int,
        val uniqueSongs: Int,
    )

    /** Genre playback summary */
    @Keep
    data class GenrePlaybackSummary(
        val genre: String,
        val totalDurationMs: Long,
        val playCount: Int,
        val percentage: Float,
    )

    /** Album playback summary */
    @Keep
    data class AlbumPlaybackSummary(
        val album: String,
        val albumArtUri: String?,
        val totalDurationMs: Long,
        val playCount: Int,
        val uniqueSongs: Int,
    )

    /** Timeline entry for charts */
    @Keep
    data class TimelineEntry(val label: String, val totalDurationMs: Long, val playCount: Int)

    /** Listening session data */
    data class ListeningSession(
        val startMillis: Long,
        val endMillis: Long,
        val totalDuration: Long,
        val songCount: Int,
    )

    /** Daily listening bucket for hour-by-hour distribution */
    @Keep
    data class DailyListeningBucket(
        val startHour: Int,
        val endHour: Int,
        val totalDurationMs: Long,
    )

    /** Comprehensive playback stats summary */
    @Keep
    data class PlaybackStatsSummary(
        val range: StatsTimeRange,
        val startTimestamp: Long?,
        val endTimestamp: Long,
        val totalDurationMs: Long,
        val totalPlayCount: Int,
        val uniqueSongs: Int,
        val uniqueArtists: Int,
        val averageDailyDurationMs: Long,
        val topSongs: List<SongPlaybackSummary>,
        val topArtists: List<ArtistPlaybackSummary>,
        val topAlbums: List<AlbumPlaybackSummary>,
        val topGenres: List<GenrePlaybackSummary>,
        val timeline: List<TimelineEntry>,
        val activeDays: Int,
        val longestStreakDays: Int,
        val currentStreakDays: Int,
        val totalSessions: Int,
        val averageSessionDurationMs: Long,
        val longestSessionDurationMs: Long,
        val averageSessionsPerDay: Float,
        val peakDayOfWeek: String?,
        val peakDayDurationMs: Long,
        val peakHour: Int?,
        val dailyDistribution: List<DailyListeningBucket>,
    )

    /** Record a playback event */
    fun recordPlayback(song: Song, durationMs: Long, timestamp: Long = System.currentTimeMillis()) {
        if (song.id.isBlank() || durationMs <= 0) return

        val coercedTimestamp = timestamp.coerceAtLeast(0L)
        val coercedDuration = durationMs.coerceIn(0L, MAX_REASONABLE_EVENT_DURATION_MS)
        val start = (coercedTimestamp - coercedDuration).coerceAtLeast(0L)

        val event =
            PlaybackEvent(
                songId = song.id,
                timestamp = coercedTimestamp,
                durationMs = coercedDuration,
                startTimestamp = start,
                endTimestamp = coercedTimestamp,
                songTitle = song.title,
                artistName = song.artist,
                albumName = song.album,
                genre = song.genre,
            )

        synchronized(fileLock) {
            val events = readEventsLocked()

            events += event
            writeEventsLocked(events)
        }
    }

    /** Record playback with simple duration tracking (for simpler integration) */
    fun recordSimplePlayback(
        songId: String,
        title: String,
        artist: String,
        album: String?,
        genre: String?,
        durationMs: Long,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        if (songId.isBlank() || durationMs <= 0) return

        val event =
            PlaybackEvent(
                songId = songId,
                timestamp = timestamp,
                durationMs = durationMs.coerceIn(0L, MAX_REASONABLE_EVENT_DURATION_MS),
                startTimestamp = (timestamp - durationMs).coerceAtLeast(0L),
                endTimestamp = timestamp,
                songTitle = title,
                artistName = artist,
                albumName = album,
                genre = genre,
            )

        synchronized(fileLock) {
            val events = readEventsLocked()
            events += event
            writeEventsLocked(events)
        }
    }

    /** Load statistics summary for a given time range */
    private fun summaryKey(range: StatsTimeRange, now: Long): String {
        val zone = ZoneId.systemDefault()
        val settings = chromahub.rhythm.app.shared.data.model.AppSettings.getInstance(context)
        return listOf(
                range.name,
                range.resolveBounds(now, zone).first,
                Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),
                zone.id,
                Locale.getDefault().toLanguageTag(),
                settings.artistSeparatorEnabled.value,
                settings.artistSeparatorDelimiters.value,
            )
            .joinToString("|")
    }

    private fun librarySignature(songs: List<Song>): String {
        var result = 1125899906842597L
        songs.forEach { song ->
            result = result * 31 + song.id.hashCode()
            result = result * 31 + song.title.hashCode()
            result = result * 31 + song.artist.hashCode()
            result = result * 31 + song.album.hashCode()
            result = result * 31 + (song.genre?.hashCode() ?: 0)
            result = result * 31 + (song.artworkUri?.toString()?.hashCode() ?: 0)
        }
        return "${songs.size}:$result"
    }

    suspend fun cachedSummary(
        range: StatsTimeRange,
        nowMillis: Long = System.currentTimeMillis(),
    ): PlaybackStatsSummary? =
        withContext(Dispatchers.IO) {
            val ledger = ListeningLedger.get(context)
            summaryCache
                .read(summaryKey(range, nowMillis), ledger.state("history_generation").orEmpty())
                ?.summary
        }

    suspend fun todayListeningDuration(nowMillis: Long = System.currentTimeMillis()): Long =
        withContext(Dispatchers.IO) {
            ensureHistoryReady()
            ListeningLedger.get(context)
                .listenedDuration(StatsTimeRange.TODAY.resolveBounds(nowMillis).first!!, nowMillis)
        }

    suspend fun loadSummary(
        range: StatsTimeRange,
        songs: List<Song> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
    ): PlaybackStatsSummary =
        withContext(Dispatchers.IO) {
            summaryLock.withLock {
                ensureHistoryReady()
                val ledger = ListeningLedger.get(context)
                val version = ledger.statsVersion()
                val key = summaryKey(range, nowMillis)
                val library = librarySignature(songs)
                val saved = summaryCache.read(key, ledger.state("history_generation").orEmpty())
                if (
                    saved != null &&
                        saved.version == version &&
                        saved.library == library &&
                        (kotlin.math.abs(saved.summary.endTimestamp - nowMillis) <= 60_000 ||
                            kotlin.math.abs(System.currentTimeMillis() - nowMillis) <= 120_000 &&
                                ledger.lastSegmentEnded() <= saved.summary.endTimestamp)
                ) {
                    return@withLock saved.summary.copy(endTimestamp = nowMillis).also {
                        _statsSummary.value = it
                    }
                }
                val settings =
                    chromahub.rhythm.app.shared.data.model.AppSettings.getInstance(context)
                val separated = settings.artistSeparatorEnabled.value
                val delimiters = settings.artistSeparatorDelimiters.value
                val projection =
                    StreamingPlaybackStats(
                        range,
                        nowMillis,
                        ZoneId.systemDefault(),
                        Locale.getDefault(),
                        songs.associate {
                            it.id to
                                StatsTrackMeta(
                                    it.title,
                                    it.artist,
                                    it.album,
                                    it.genre,
                                    it.artworkUri?.toString(),
                                )
                        },
                        artistsFor = { artist ->
                            chromahub.rhythm.app.util.ArtistSeparator.splitArtistNames(
                                    artist,
                                    delimiters,
                                    separated,
                                )
                                .ifEmpty { listOf(artist) }
                        },
                        unknownTitle = context.getString(R.string.unknown_song),
                        unknownArtist = context.getString(R.string.unknown_artist_name),
                        unknownAlbum = context.getString(R.string.unknown_album_name),
                        unknownGenre = context.getString(R.string.common_unknown),
                    )
                val (start, end) = range.resolveBounds(nowMillis)
                val active = coroutineContext
                var visited = 0
                ledger.visitStatsRows(start, end) { row ->
                    if (++visited % 256 == 0) active.ensureActive()
                    projection.add(row)
                }
                val result = projection.finish(ledger.statsPlayCounts(start, end))
                active.ensureActive()
                if (ledger.state("history_generation").orEmpty() != version.substringBefore(':'))
                    throw kotlinx.coroutines.CancellationException("History was cleared")
                // A recorder/restore can change history while the cursor is open. Such a result may
                // be
                // displayed, but cannot become a fresh cache for the newer history generation.
                ledger.publishForHistory(version.substringBefore(':')) {
                    if (ledger.statsVersion() == version)
                        summaryCache.save(
                            CachedPlaybackSummary(
                                key = key,
                                version = version,
                                library = library,
                                builtAt = System.currentTimeMillis(),
                                summary = result,
                            )
                        )
                }
                _statsSummary.value = result
                result
            }
        }

    /** Get playback statistics for a specific song */
    suspend fun getSongPlaybackStats(
        songId: String,
        range: StatsTimeRange = StatsTimeRange.ALL_TIME,
        nowMillis: Long = System.currentTimeMillis(),
    ): SongPlaybackSummary? =
        withContext(Dispatchers.IO) {
            ensureHistoryReady()
            val ledger = ListeningLedger.get(context)
            val (start, end) = range.resolveBounds(nowMillis)
            val projection =
                StreamingPlaybackStats(
                    range,
                    nowMillis,
                    ZoneId.systemDefault(),
                    Locale.getDefault(),
                )
            val active = coroutineContext
            var visited = 0
            ledger.visitStatsRows(start, end, songId) { row ->
                if (++visited % 256 == 0) active.ensureActive()
                projection.add(row)
            }
            projection.finish(ledger.statsPlayCounts(start, end, songId)).topSongs.firstOrNull()
        }

    /**
     * Retrieves recent playback history as distinct song IDs ordered by last played timestamp
     * descending.
     */
    fun getRecentlyPlayedSongIds(limit: Int = 50): List<String> {
        ensureHistoryReady()
        return ListeningLedger.get(context).recentSongIds(limit)
    }

    /**
     * Retrieves "On Repeat" song IDs (songs with highest frequency and listening time in the last
     * [daysBack] days).
     */
    fun getOnRepeatSongIds(
        daysBack: Int = 14,
        limit: Int = 50,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<String> {
        ensureHistoryReady()
        return ListeningLedger.get(context).onRepeatSongIds(daysBack, limit, nowMillis)
    }

    /**
     * Retrieves "Forgotten Favorites" song IDs (songs with high all-time play counts that have NOT
     * been played in the last [daysNotPlayed] days).
     */
    fun getForgottenFavoritesSongIds(
        allTimePlayCounts: Map<String, Int>,
        daysNotPlayed: Int = 30,
        limit: Int = 50,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<String> {
        val cutoff = nowMillis - TimeUnit.DAYS.toMillis(daysNotPlayed.toLong())
        ensureHistoryReady()
        val recentlyPlayedIds = ListeningLedger.get(context).songsHeardSince(cutoff)

        return allTimePlayCounts.entries
            .asSequence()
            .filter { (songId, playCount) -> playCount >= 4 && !recentlyPlayedIds.contains(songId) }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(limit)
            .toList()
    }

    /** Clear all history */
    fun clearHistory() {
        synchronized(fileLock) {
            chromahub.rhythm.app.features.local.data.experience.ListeningLedger.get(context)
                .clearRecords()
            if (historyFile.exists()) {
                historyFile.delete()
            }
        }
        summaryCache.clear()
        _statsSummary.value = null
    }

    // Private helper methods

    private fun ensureHistoryReady() =
        synchronized(fileLock) {
            val ledger = ListeningLedger.get(context)
            ledger.ensureRecovered()
            if (ledger.state("legacy_history_imported") == null) {
                readEventsLocked().forEach { event ->
                    val id =
                        "legacy:" +
                            kr.co.metadata.crawler.CatalogFileStore.hash(
                                "${event.songId}:${event.startMillis()}:${event.endMillis()}:${event.durationMs}"
                            )
                    ledger.record(
                        chromahub.rhythm.app.features.local.data.experience.ListeningRecord(
                            id,
                            id,
                            "segment",
                            event.songId,
                            "legacy:${event.songId}",
                            event.songTitle.orEmpty(),
                            event.artistName.orEmpty(),
                            event.albumName.orEmpty(),
                            event.genre,
                            event.startMillis(),
                            event.endMillis(),
                            event.durationMs,
                            0,
                            0,
                            0,
                            1f,
                            "legacy_import",
                        )
                    )
                }
                ledger.state("legacy_history_imported", "1")
            }
        }

    private fun readEventsLocked(): MutableList<PlaybackEvent> {
        if (!historyFile.exists()) {
            return mutableListOf()
        }
        val raw =
            runCatching { historyFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: return mutableListOf()

        return runCatching {
                val element = gson.fromJson(raw, JsonElement::class.java)
                if (element.isJsonArray) {
                    gson.fromJson<MutableList<PlaybackEvent>>(element, eventsType)
                } else {
                    mutableListOf()
                }
            }
            .getOrElse { mutableListOf() }
    }

    private fun writeEventsLocked(events: List<PlaybackEvent>) {
        runCatching {
            chromahub.rhythm.app.features.local.data.metadata.PersonalArchive.atomicWrite(
                historyFile,
                gson.toJson(events).toByteArray(),
            )
        }
    }
}
