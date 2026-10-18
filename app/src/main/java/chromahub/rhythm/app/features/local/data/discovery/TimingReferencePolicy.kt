// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

/** Structural checks are necessary but do not establish that singing was actually recognized. */
object TimingReferencePolicy {
    fun trusted(lines: List<TimedLine>, duration: Long, source: String?, reviewed: Boolean = false): Boolean {
        if (!LocalLyricAlignment.valid(lines, duration) || lines.size > 400) return false
        if (!reviewed && source.orEmpty().contains("T2Rhythm", ignoreCase = true)) return false
        val intervals = lines.zipWithNext().map { (a, b) -> b.ms - a.ms }
        val median = intervals.sorted()[intervals.size / 2]
        return (lines.last().ms - lines.first().ms).toDouble() / duration >= .35 &&
            median in 500..20_000 && intervals.count { it in 300..30_000 }.toDouble() / intervals.size >= .85 &&
            lines.count { LocalLyricAlignment.normalized(it.text).length >= 2 }.toDouble() / lines.size >= .8
    }

    /** One recording has one vote, with a per-genre quota and a fixed global memory bound. */
    fun balanced(examples: List<TimingTraining>, limit: Int = 32): List<TimingTraining> {
        val counts = mutableMapOf<String, Int>()
        return examples.filter { it.pcm.isNotBlank() && LocalLyricAlignment.valid(it.lines, it.durationMs) }
            .distinctBy { it.pcm }.filter { sample ->
                val genre = sample.genre.lowercase(java.util.Locale.ROOT)
                val n = counts[genre] ?: 0
                if (n >= 8) false else { counts[genre] = n + 1; true }
            }.take(limit)
    }
}
