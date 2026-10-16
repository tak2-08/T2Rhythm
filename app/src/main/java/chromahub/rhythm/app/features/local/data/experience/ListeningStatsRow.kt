// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

/** A transient projection; rich context and audio traces never enter statistics screen memory. */
data class ListeningStatsRow(
    val songId: String,
    val playId: String,
    val start: Long,
    val end: Long,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String?,
)

object ListeningPolicy {
    const val CHECKPOINT_MS = 30_000L
    const val CONTEXT_MS = 90_000L
    const val PRESSURE_CONTEXT_MS = 180_000L

    /** Neutral automatic outcomes are omitted, not labelled .5 (which can still lower a score). */
    fun outcome(
        ratio: Double,
        firstCompletedAt: Long?,
        startedAt: Long,
        immediateRepeat: Boolean,
    ): Double? {
        require(ratio.isFinite() && ratio in 0.0..1.0)
        if (ratio >= .9 || immediateRepeat) return 1.0
        if (firstCompletedAt != null && firstCompletedAt <= startedAt) return null
        return ratio
    }
}
