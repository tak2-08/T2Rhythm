// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.infrastructure.service.player

import android.app.ActivityManager
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import chromahub.rhythm.app.shared.data.model.AppSettings

/** A sliding window, never a second copy of the entire queue. No speculative remote I/O. */
@OptIn(UnstableApi::class)
class PreloadController(private val context: Context, private val appSettings: AppSettings) {
    private var manager: DefaultPreloadManager? = null
    private val retained = linkedMapOf<MediaItem, Int>()
    private val control = object : TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
        override fun getTargetPreloadStatus(rankingData: Int) =
            DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
    }

    fun update(player: Player) {
        val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
        val limit = appSettings.preloadLimit.value.coerceIn(0, if (lowRam) 1 else 2)
        val next = linkedMapOf<MediaItem, Int>()
        val timeline = player.currentTimeline
        if (!timeline.isEmpty) {
            for (index in PreloadWindow.upcoming(player.mediaItemCount, player.currentMediaItemIndex, limit) {
                timeline.getNextWindowIndex(it, player.repeatMode, player.shuffleModeEnabled)
            }) {
                val item = player.getMediaItemAt(index)
                if (item.localConfiguration?.uri?.scheme in setOf("content", "file")) next[item] = index
            }
        }
        if (next.isEmpty()) { release(); return }
        if (next == retained) return
        try {
            val loader = manager ?: DefaultPreloadManager.Builder(context, control).build().also { manager = it }
            retained.forEach { (item, rank) -> if (next[item] != rank) loader.remove(item) }
            next.forEach { (item, rank) -> if (retained[item] != rank) loader.add(item, rank) }
            retained.clear(); retained.putAll(next)
            loader.setCurrentPlayingIndex(player.currentMediaItemIndex)
            loader.invalidate()
        } catch (_: Exception) { release() } // Optional warming must never take down playback.
    }

    fun release() {
        manager?.release()
        manager = null
        retained.clear()
    }
}
