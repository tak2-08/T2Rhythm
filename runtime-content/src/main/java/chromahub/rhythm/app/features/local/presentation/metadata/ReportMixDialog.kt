// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.shared.data.model.Song
import kotlinx.coroutines.*

/** Two bounded local decoder streams crossfade selected excerpts, without duplicating music files. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun ReportMixDialog(songs: List<Song>, viewModel: MusicViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val players = remember { listOf(ExoPlayer.Builder(context).build(), ExoPlayer.Builder(context).build()) }
    val audio = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }
    val volume by AppSettings.getInstance(context).appVolume.collectAsState()
    val starts = remember(songs) { mutableStateListOf<Long>().apply { addAll(songs.map { (it.duration * .35).toLong().coerceAtMost((it.duration - 30_000).coerceAtLeast(0)) }) } }
    var length by remember { mutableFloatStateOf(30f) }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(0) }
    var playback by remember { mutableStateOf<Job?>(null) }
    val focus = remember { android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { change -> if (change < 0) { playback?.cancel(); players.forEach { it.pause() }; playing = false } }.build() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(players, lifecycle) {
        val listener = object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failed = true; playing = false; playback?.cancel(); players.forEach { it.pause() } } }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) { playback?.cancel(); playing = false; players.forEach { it.pause() } } }
        players.forEach { it.addListener(listener) }; lifecycle.addObserver(observer)
        onDispose { playback?.cancel(); audio.abandonAudioFocusRequest(focus); lifecycle.removeObserver(observer); players.forEach { it.removeListener(listener); it.release() } }
    }
    fun stop() { playback?.cancel(); players.forEach { it.pause() }; audio.abandonAudioFocusRequest(focus); playing = false }
    fun play() {
        val previous = playback
        stop(); failed = false
        playback = scope.launch {
            previous?.join()
            playing = true
            viewModel.pauseMusic()
            if (audio.requestAudioFocus(focus) != android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { playing = false; failed = true; return@launch }
            fun prepare(player: ExoPlayer, i: Int) {
                val song = songs[i]
                player.setMediaItem(MediaItem.Builder().setUri(song.uri).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(starts[i]).setEndPositionMs(minOf(song.duration, starts[i] + (length * 1000).toLong())).build()).build())
                player.prepare(); player.volume = 0f
            }
            suspend fun ready(player: ExoPlayer) = withTimeout(30_000) {
                while (player.playbackState != Player.STATE_READY) { ensureActive(); delay(100) }
            }
            try {
                prepare(players[0], 0); ready(players[0]); players[0].volume = volume; players[0].play()
                for (i in songs.indices) {
                    index = i
                    val current = players[i % 2]
                    val next = players[(i + 1) % 2]
                    if (i + 1 < songs.size) { prepare(next, i + 1); ready(next) }
                    val duration = minOf((length * 1000).toLong(), songs[i].duration - starts[i])
                    val fade = minOf(2000L, duration / 4)
                    withTimeout(duration + 30_000) {
                        while (current.currentPosition < duration - (if (i + 1 < songs.size) fade else 0) && current.playbackState != Player.STATE_ENDED) { ensureActive(); delay(80) }
                    }
                    if (i + 1 < songs.size) {
                        next.play()
                        // Equal-power blend avoids the volume dip of a linear crossfade.
                        for (step in 0..25) {
                            val angle = step / 25.0 * Math.PI / 2
                            current.volume = (kotlin.math.cos(angle) * volume).toFloat()
                            next.volume = (kotlin.math.sin(angle) * volume).toFloat()
                            delay(fade / 25)
                        }
                        current.pause()
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failed = true }
            finally { players.forEach { it.pause() }; audio.abandonAudioFocusRequest(focus); playing = false }
        }
    }
    AlertDialog(onDismissRequest = { stop(); onDismiss() }, title = { Text(stringResource(R.string.report_mix)) }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.report_mix_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.report_mix_seconds, length.toInt()), style = MaterialTheme.typography.labelLarge)
                Slider(length, onValueChange = { length = it; songs.forEachIndexed { i, song -> starts[i] = starts[i].coerceAtMost((song.duration - (it * 1000).toLong()).coerceAtLeast(0)) } }, enabled = !playing, valueRange = 15f..45f, steps = 5)
            }
            songs.forEachIndexed { i, song -> item(key = song.uri.toString()) {
                Text((if (playing && index == i) "▶ " else "") + song.title, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.report_mix_start, starts[i] / 1000), style = MaterialTheme.typography.labelSmall)
                Slider(starts[i].toFloat(), onValueChange = { starts[i] = it.toLong() }, enabled = !playing,
                    valueRange = 0f..(song.duration - minOf((length * 1000).toLong(), song.duration)).coerceAtLeast(1).toFloat())
            } }
            if (failed) item { Text(stringResource(R.string.discovery_operation_failed), color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { if (playing) stop() else play() }) { Text(stringResource(if (playing) R.string.report_mix_stop else R.string.download_listen)) } },
        dismissButton = { TextButton(onClick = { stop(); onDismiss() }) { Text(stringResource(android.R.string.cancel)) } })
}
