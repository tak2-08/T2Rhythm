// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.ai

import java.util.Locale
import java.util.PriorityQueue

/** One pass over the local catalog; retain only the request window, never a sorted catalog copy. */
internal object AiCandidates {
    private data class Ranked<T>(val value: T, val score: Int, val tie: Int)

    fun <T> select(candidates: Sequence<T>, prompt: String, text: (T) -> String,
        favorite: (T) -> Boolean, id: (T) -> String): List<T> {
        val words = prompt.lowercase(Locale.ROOT).split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.length >= 2 }.distinct()
        val order = compareBy<Ranked<T>> { it.score }.thenByDescending { it.tie }
        val best = PriorityQueue(AiProtocol.MAX_TRACKS, order)
        for (item in candidates) {
            val description = text(item).lowercase(Locale.ROOT)
            val ranked = Ranked(item, words.count { description.contains(it) } * 10 +
                if (favorite(item)) 3 else 0, id(item).hashCode())
            if (best.size < AiProtocol.MAX_TRACKS) best.add(ranked)
            else if (order.compare(ranked, best.peek()) > 0) { best.poll(); best.add(ranked) }
        }
        return best.sortedWith(order.reversed()).map { it.value }
    }
}
