// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import kotlin.math.abs

object TimingRetryPolicy {
    /** A tiny numerical perturbation is not a useful new suggestion. */
    fun changed(before: String, after: String): Boolean {
        val a = LocalLyricAlignment.parse(before); val b = LocalLyricAlignment.parse(after)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.map { it.text } != b.map { it.text }) return true
        val delta = a.zip(b).flatMapIndexed { index, (x, y) ->
            val oldEnd = x.endMs ?: a.getOrNull(index + 1)?.ms
            val newEnd = y.endMs ?: b.getOrNull(index + 1)?.ms
            listOfNotNull(abs(x.ms - y.ms).toDouble(),
                if (oldEnd != null && newEnd != null) abs(oldEnd - newEnd).toDouble()
                else if (x.endMs == null && y.endMs != null) 200.0 else null)
        }
        return delta.average() >= 50 || delta.maxOrNull()?.let { it >= 200 } == true
    }
}
