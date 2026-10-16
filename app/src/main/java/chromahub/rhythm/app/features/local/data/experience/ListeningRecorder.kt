// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.shared.data.model.Song
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest

/**
 * One recorder owned by the playback service; UI lifecycles never determine whether a play is
 * logged.
 */
class ListeningRecorder(context: Context, private val songOf: (MediaItem) -> Song?) :
    Player.Listener {
    private val context = context.applicationContext
    private var closed = false
    private var favoriteJob: Job? = null
    private var player: Player? = null
    private var song: Song? = null
    private var playId = UUID.randomUUID().toString()
    private var segmentId: String? = null
    private var startWall = 0L
    private var startElapsed = 0L
    private var startPosition = 0L
    private var speed = 1f
    private var situation: ListeningSituation? = null
    private var playListenedMs = 0L
    private var lastSegmentMs = 0L
    private var coverage = PlayCoverage()
    private var playStartedAt = 0L
    private var immediateRepeat = false
    @Volatile private var favoriteIds = emptySet<String>()
    private val pending = ListeningWriteBuffer()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val records = Channel<Unit>(Channel.CONFLATED)
    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        ioScope.launch {
            val ledger = ListeningLedger.get(context)
            var recovered = false
            var recoveryFailures = 0
            while (!recovered) try {
                ledger.ensureRecovered()
                recovered = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ledger.storageIssue("write_failed")
                delay(minOf(30_000L, 2_000L * ++recoveryFailures))
            }
            for (wake in records) while (true) {
                val record = pending.next() ?: break
                var saved = false
                var failures = 0
                while (!saved) try {
                    ledger.record(record)
                    val learning = RecommendationLearning.get(context)
                    if (record.reason == "play_start")
                        learning.playbackStarted(record.songKey, record.playId)
                    if (
                        record.kind == "play" &&
                            record.listenedMs >= 1000 &&
                            record.playCompletionRatio != null
                    )
                        learning.playOutcome(
                            record.songKey,
                            record.playCompletionRatio,
                            record.playId,
                            record.startedAt,
                            record.immediateRepeat,
                        )
                    pending.saved(record)
                    ledger.storageIssue(null)
                    saved = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ledger.storageIssue("write_failed")
                    delay(minOf(30_000L, 2_000L * ++failures))
                }
                val omitted = pending.takeOmitted()
                if (omitted > 0) {
                    val now = System.currentTimeMillis()
                    enqueue(
                        ListeningRecord(
                            UUID.randomUUID().toString(),
                            "gap:$now",
                            "gap",
                            "recording",
                            "recording",
                            "",
                            "",
                            "",
                            null,
                            now,
                            now,
                            0,
                            0,
                            0,
                            0,
                            1f,
                            "storage_backpressure",
                            omittedEvents = omitted,
                        )
                    )
                }
            }
            ioScope.cancel()
        }
        favoriteJob =
            ioScope.launch {
                AppSettings.getInstance(context).favoriteSongs.collectLatest { json ->
                    favoriteIds =
                        runCatching {
                                com.google.gson.JsonParser.parseString(json ?: "[]")
                                    .asJsonArray
                                    .map { it.asString }
                                    .toHashSet()
                            }
                            .getOrDefault(emptySet())
                }
            }
        timerScope.launch {
            var last = AppSettings.getInstance(context).appVolume.value
            AppSettings.getInstance(context).appVolume.collectLatest { next ->
                delay(
                    1000
                ) // Coalesce user slider drags; automatic player fades never enter this flow.
                if (kotlin.math.abs(next - last) >= .02f) {
                    last = next
                    ListeningContext.get(context).requestRefresh(true)
                    if (segmentId != null) {
                        finish("user_volume_change")
                        action("user_volume_change")
                        if (player?.isPlaying == true) start()
                    }
                }
            }
        }
        timerScope.launch {
            while (isActive) {
                delay(ListeningPolicy.CHECKPOINT_MS)
                if (player?.isPlaying != true || segmentId == null) continue
                val next = ListeningContext.get(context).snapshot()
                val before = situation
                val changed =
                    before != null &&
                        (ListeningContext.outputGroup(before) !=
                            ListeningContext.outputGroup(next) ||
                            before.hour != next.hour ||
                            before.weekday != next.weekday ||
                            ListeningContext.region(before) != ListeningContext.region(next) ||
                            before.motion?.kind != next.motion?.kind ||
                            before.daylight?.isDay != next.daylight?.isDay ||
                            before.weather?.at != next.weather?.at ||
                            before.systemVolume != next.systemVolume ||
                            before.brightnessSetting != next.brightnessSetting ||
                            before.ambientSound?.at != next.ambientSound?.at ||
                            (next.precise &&
                                (before.latitude != next.latitude ||
                                    before.longitude != next.longitude)))
                if (changed) {
                    finish("context_change")
                    if (player?.isPlaying == true) start()
                } else checkpoint("checkpoint")
            }
        }
    }

    private fun enqueue(record: ListeningRecord) {
        if (!pending.offer(record)) ListeningLedger.get(context).storageIssue("write_failed")
        records.trySend(Unit)
    }

    fun attach(next: Player) {
        finish("player_swap")
        player?.removeListener(this)
        player = next
        next.addListener(this)
        val nextSong = next.currentMediaItem?.let(songOf)
        if (song?.id != nextSong?.id) {
            song = nextSong
            playId = UUID.randomUUID().toString()
            playListenedMs = 0L
            coverage = PlayCoverage()
            playStartedAt = 0L
        }
        if (next.isPlaying) start()
    }

    private fun start() {
        val p = player ?: return
        if (segmentId != null) return
        if (song == null) song = p.currentMediaItem?.let(songOf)
        if (song == null) return
        segmentId = UUID.randomUUID().toString()
        startWall = System.currentTimeMillis()
        if (playStartedAt == 0L) {
            playStartedAt = startWall
            action("play_start")
        }
        startElapsed = SystemClock.elapsedRealtime()
        startPosition = p.currentPosition.coerceAtLeast(0)
        speed = p.playbackParameters.speed
        MobilityContext.get(context).playback(true)
        ListeningSound.get(context).playback(true)
        ListeningContext.get(context).playback(true)
        situation =
            ListeningContext.get(context)
                .snapshot()
                .copy(playerVolume = AppSettings.getInstance(context).appVolume.value)
        lastSegmentMs = 0L
    }

    private fun checkpoint(reason: String, completed: Boolean = false, endPosition: Long? = null) {
        val p = player ?: return
        val s = song ?: return
        val id = segmentId ?: return
        val elapsed = (SystemClock.elapsedRealtime() - startElapsed).coerceAtLeast(0)
        if (elapsed == 0L) return
        playListenedMs += (elapsed - lastSegmentMs).coerceAtLeast(0)
        lastSegmentMs = elapsed
        val end = (endPosition ?: p.currentPosition).coerceAtLeast(0)
        val playedAudio = (elapsed * speed).toLong()
        coverage.record(startPosition, end, playedAudio, s.duration)
        val outputs = situation?.connectedOutputs.orEmpty().map { it.type }.distinct()
        val key =
            if (s.uri.scheme in listOf("http", "https"))
                s.uri.buildUpon().clearQuery().fragment(null).build().toString()
            else s.uri.toString()
        enqueue(
            ListeningRecord(
                id,
                playId,
                "segment",
                s.id,
                key,
                s.title,
                s.artist,
                s.album,
                s.genre,
                startWall,
                startWall + elapsed,
                elapsed,
                s.duration,
                startPosition,
                (endPosition ?: p.currentPosition).coerceAtLeast(0),
                speed,
                reason,
                completed,
                s.dateAdded,
                outputTypes = outputs,
                repeatMode = p.repeatMode,
                shuffled = p.shuffleModeEnabled,
                volume = p.volume,
                favorite = s.id in favoriteIds,
                albumArtist = s.albumArtist,
                libraryModifiedAt = s.dateModified,
                codec = s.codec,
                sampleRate = s.sampleRate,
                channels = s.channels,
                bitrate = s.bitrate,
                year = s.year,
                trackNumber = s.trackNumber,
                discNumber = s.discNumber,
                situation = situation,
                playedAudioMs = playedAudio,
                playCompletionRatio = coverage.ratio(s.duration),
            )
        )
    }

    private fun finish(reason: String, completed: Boolean = false, endPosition: Long? = null) {
        checkpoint(reason, completed, endPosition)
        segmentId = null
    }

    private fun summarize(reason: String) {
        val s = song ?: return
        if (playStartedAt == 0L) return
        val now = System.currentTimeMillis()
        enqueue(
            ListeningRecord(
                "play:$playId",
                playId,
                "play",
                s.id,
                s.uri.toString(),
                s.title,
                s.artist,
                s.album,
                s.genre,
                playStartedAt,
                now,
                playListenedMs,
                s.duration,
                0,
                coverage.coveredMs(),
                1f,
                reason,
                completed = (coverage.ratio(s.duration) ?: 0.0) >= .9,
                situation = situation,
                playedAudioMs = coverage.coveredMs(),
                playCompletionRatio = coverage.ratio(s.duration),
                immediateRepeat = immediateRepeat,
            )
        )
    }

    private fun action(reason: String, position: Long = player?.currentPosition ?: 0) {
        val s = song ?: return
        val p = player ?: return
        val now = System.currentTimeMillis()
        enqueue(
            ListeningRecord(
                UUID.randomUUID().toString(),
                playId,
                "action",
                s.id,
                (if (s.uri.scheme in listOf("http", "https"))
                    s.uri.buildUpon().clearQuery().fragment(null).build().toString()
                else s.uri.toString()),
                s.title,
                s.artist,
                s.album,
                s.genre,
                now,
                now,
                0,
                s.duration,
                position.coerceAtLeast(0),
                p.currentPosition.coerceAtLeast(0),
                p.playbackParameters.speed,
                reason,
                libraryAddedAt = s.dateAdded,
                repeatMode = p.repeatMode,
                shuffled = p.shuffleModeEnabled,
                volume = p.volume,
            )
        )
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        MobilityContext.get(context).playback(isPlaying)
        ListeningSound.get(context).playback(isPlaying)
        ListeningContext.get(context).playback(isPlaying)
        if (isPlaying) {
            start()
            action("resume")
        } else {
            val reason =
                if (
                    player?.playbackState == Player.STATE_BUFFERING && player?.playWhenReady == true
                )
                    "buffer"
                else "pause"
            finish(reason)
            action(reason)
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val next = mediaItem?.let(songOf)
        if (
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && next?.id == song?.id
        ) {
            song = next
            return
        }
        val completed =
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        finish(
            if (completed) "completed" else "skip_or_queue_change",
            completed,
            if (completed) song?.duration else null,
        )
        summarize(if (completed) "ended" else "track_change")
        action(
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) "repeat"
            else if (completed) "next_auto" else "track_change"
        )
        immediateRepeat =
            next?.uri == song?.uri &&
                playListenedMs >= 1000 &&
                System.currentTimeMillis() - (startWall + lastSegmentMs) in 0..15_000L
        playListenedMs = 0L
        coverage = PlayCoverage()
        playStartedAt = 0L
        song = next
        playId = UUID.randomUUID().toString()
        if (player?.isPlaying == true) start()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (
            reason == Player.DISCONTINUITY_REASON_SEEK &&
                oldPosition.mediaItem?.mediaId == newPosition.mediaItem?.mediaId
        ) {
            finish("seek", endPosition = oldPosition.positionMs)
            action("seek", oldPosition.positionMs)
            if (player?.isPlaying == true) start()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY)
            player?.currentMediaItem?.let(songOf)?.takeIf { it.id == song?.id }?.let { song = it }
        if (playbackState == Player.STATE_ENDED) {
            finish("completed", true)
            action("ended")
            summarize("ended")
        }
    }

    override fun onPlaybackParametersChanged(
        playbackParameters: androidx.media3.common.PlaybackParameters
    ) {
        finish("speed_change")
        action("speed_change")
        if (player?.isPlaying == true) start()
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        action("repeat_mode_change")
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        action("shuffle_change")
    }

    // Player volume callbacks include crossfade, ducking and normalization at frame rate.
    // User app volume is recorded once after settling by the settings flow above.
    fun close() {
        if (closed) return
        closed = true
        favoriteJob?.cancel()
        ListeningContext.get(context).playback(false)
        ListeningSound.get(context).playback(false)
        MobilityContext.get(context).playback(false)
        finish("service_stop")
        summarize("service_stop")
        player?.removeListener(this)
        timerScope.cancel()
        records.close()
    }
}
