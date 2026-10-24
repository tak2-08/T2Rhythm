// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import androidx.media3.common.Player

/** A preview must seek and play, rather than merely append the song to the queue. */
object LyricPreviewPlayback {
    fun start(player: Player, mediaId: String, positionMs: Long, durationMs: Long, addMissing: () -> Unit): Boolean {
        fun find(): Int? = if (player.currentMediaItem?.mediaId == mediaId) player.currentMediaItemIndex
            else (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
        var index = find()
        if (index == null) { addMissing(); index = find() }
        val target = index ?: return false
        player.seekTo(target, positionMs.coerceIn(0L, (durationMs - 1).coerceAtLeast(0)))
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
        return true
    }
}
