// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import androidx.annotation.Keep
import chromahub.rhythm.app.features.local.data.experience.AudioVectors
import kotlin.math.*

@Keep
data class GenreExample(
    val key: String,
    val genre: String,
    val vector: List<Double>,
    val positive: Boolean = true,
)

@Keep data class GenreGuess(val genre: String, val confidence: Double, val neighbors: List<String>)

object LocalGenreClassifier {
    fun predict(key: String, vector: List<Double>, examples: List<GenreExample>): GenreGuess? {
        if (vector.isEmpty() || vector.any { !it.isFinite() }) return null
        val compatible =
            GenreTrainingPolicy.merge(examples, emptyList()).filter {
                it.key != key && it.vector.size == vector.size && it.vector.all(Double::isFinite)
            }
        if (
            compatible.count { it.positive } < 6 ||
                compatible.filter { it.positive }.map { it.genre }.distinct().size < 2
        )
            return null
        val nearest =
            compatible
                .filter { it.positive }
                .map { it to AudioVectors.cosine(vector, it.vector) }
                .sortedByDescending { it.second }
                .take(9)
        if (nearest.size < 3 || nearest.first().second < .75) return null
        val classes = compatible.filter { it.positive }.groupingBy { it.genre }.eachCount()
        val scores =
            nearest
                .groupBy { it.first.genre }
                .mapValues { (genre, rows) ->
                    val positive =
                        rows.sumOf { exp((it.second - 1) * 30) } /
                            sqrt((classes[genre] ?: 1).toDouble())
                    val negative =
                        compatible
                            .filter { !it.positive && it.genre == genre }
                            .sumOf { exp((AudioVectors.cosine(vector, it.vector) - 1) * 35) } * .35
                    (positive - negative).coerceAtLeast(0.0)
                }
                .entries
                .sortedByDescending { it.value }
        val best = scores.firstOrNull() ?: return null
        val total = scores.sumOf { it.value }
        if (total <= 0) return null
        val probability = best.value / total
        val margin = probability - (scores.getOrNull(1)?.value ?: 0.0) / total
        val support = nearest.count { it.first.genre == best.key }
        if (probability < .58 || margin < .18 || support < 3) return null
        return GenreGuess(
            best.key,
            (probability * (support.toDouble() / (support + 1))).coerceIn(0.0, 1.0),
            nearest.filter { it.first.genre == best.key }.map { it.first.key }.take(5),
        )
    }
}

@Keep data class TimedLine(val ms: Long, val text: String, val endMs: Long? = null)

@Keep
data class SpeechWord(val startMs: Long, val endMs: Long, val text: String, val confidence: Double)

@Keep
data class TimingTraining(
    val key: String,
    val pcm: String,
    val genre: String,
    val durationMs: Long,
    val lines: List<TimedLine>,
    val reviewed: Boolean = false,
    val audio: List<Double>? = null,
    val speechOffsetMs: Double? = null,
    val speechEndOffsetMs: Double? = null,
)

@Keep
data class TimingGuess(
    val lines: List<TimedLine>,
    val confidence: Double,
    val method: String,
    val evidence: Int,
    val anchors: List<Int> = emptyList(),
)

object LocalLyricAlignment {
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    fun normalized(text: String) =
        text.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

    fun parse(lrc: String): List<TimedLine> {
        val offset =
            Regex("\\[offset:([+-]?\\d{1,7})]", RegexOption.IGNORE_CASE)
                .find(lrc)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull() ?: 0
        val events = lrc.lineSequence()
            .take(3000)
            .flatMap { row ->
                val text = row.replace(stamp, "").trim()
                    stamp
                        .findAll(row)
                        .filter { it.groupValues[2].toInt() < 60 }
                        .map { m ->
                            val fraction =
                                m.groupValues[3].padEnd(3, '0').take(3).toIntOrNull() ?: 0
                            TimedLine(
                                (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) *
                                    1000 + fraction + offset,
                                text,
                            )
                        }
            }
            .sortedBy { it.ms }
            .toList()
        return events.mapIndexedNotNull { index, event ->
            if (event.text.isBlank()) null else event.copy(endMs = events.getOrNull(index + 1)
                ?.takeIf { it.text.isBlank() && it.ms > event.ms }?.ms)
        }
    }

    fun valid(lines: List<TimedLine>, duration: Long) =
        lines.size in 3..2000 &&
            lines.first().ms >= 0 &&
            lines.last().ms < duration &&
            lines.zipWithNext().all { (a, b) -> b.ms > a.ms } &&
            lines.withIndex().all { (i, line) -> line.endMs == null ||
                line.endMs > line.ms && line.endMs <= (lines.getOrNull(i + 1)?.ms ?: duration) }

    fun lrc(lines: List<TimedLine>) =
        lines.mapIndexed { i, line ->
            fun stamp(ms: Long) = "[%02d:%02d.%03d]".format(java.util.Locale.ROOT, ms / 60000, ms / 1000 % 60, ms % 1000)
            stamp(line.ms) + line.text + (line.endMs?.takeIf { it <= (lines.getOrNull(i + 1)?.ms ?: Long.MAX_VALUE) }
                ?.let { "\n" + stamp(it) }.orEmpty())
        }.joinToString("\n")

    fun text(plain: String?, synced: String?): List<String> =
        (plain?.takeIf { it.isNotBlank() }?.lines() ?: parse(synced.orEmpty()).map { it.text })
            .map { it.replace(stamp, "").trim() }
            .filter {
                normalized(it).isNotBlank() &&
                    !it.matches(
                        Regex("\\[(?:ar|ti|al|by|offset|re|ve|length):.*]", RegexOption.IGNORE_CASE)
                    )
            }
            .take(2000)

    fun light(
        lines: List<String>,
        duration: Long,
        pcm: String,
        genre: String,
        envelope: List<Double>,
        examples: List<TimingTraining>,
        audio: List<Double> = emptyList(),
    ): TimingGuess? {
        if (lines.size !in 3..1000 || duration !in 15_000..1_200_000) return null
        val textKey = lines.map(::normalized)
        examples
            .firstOrNull {
                pcm.isNotBlank() && it.pcm == pcm &&
                    valid(it.lines, duration) &&
                    it.lines.map { l -> normalized(l.text) } == textKey
            }
            ?.let {
                return TimingGuess(
                    it.lines.mapIndexed { i, l -> l.copy(text = lines[i]) },
                    .98,
                    "same_audio_reference",
                    it.lines.size,
                )
            }
        val training =
            examples
                .filter { valid(it.lines, it.durationMs) && it.durationMs > 0 }
                .let { all ->
                    all.filter { it.genre.equals(genre.trim(), ignoreCase = true) }.ifEmpty { all }
                }
        if (envelope.size < 30) return null
        val learned = training.size >= 3
        val energy = envelope.filter { it.isFinite() && it >= 0 }.sorted()
        if (energy.isEmpty() || energy.last() <= .000001) return null
        val floor = energy[energy.size / 5]
        val threshold = floor + (energy[energy.size * 4 / 5] - floor) * .18
        val active = envelope.indices.filter { envelope[it].isFinite() && envelope[it] >= threshold }
        if (active.size < 5) return null
        val intro =
            (if (learned) training.map { it.lines.first().ms.toDouble() / it.durationMs }
                else listOf(active.first().toDouble() / envelope.size))
                .sorted()
                .let { it[it.size / 2] }
                .coerceIn(.01, .22)
        val outro =
            (if (learned) training.map { it.lines.last().ms.toDouble() / it.durationMs }
                else listOf(active.last().toDouble() / envelope.size))
                .sorted()
                .let { it[it.size / 2] }
                .coerceIn(.6, .99)
        // Learn pacing from known line intervals instead of assuming a fixed characters/time ratio.
        val pace =
            training
                .flatMap { example ->
                    example.lines.zipWithNext().mapNotNull { (a, b) ->
                        val count = normalized(a.text).length.toDouble()
                        val elapsed = (b.ms - a.ms).toDouble()
                        if (count >= 2 && elapsed in 500.0..20000.0) ln(count) to ln(elapsed)
                        else null
                    }
                }
                .take(12000)
        val exponent =
            if (pace.size >= 12) {
                val x = pace.map { it.first }.average()
                val y = pace.map { it.second }.average()
                val variance = pace.sumOf { (it.first - x).pow(2) }
                if (variance > .01)
                    (pace.sumOf { (it.first - x) * (it.second - y) } / variance).coerceIn(.25, 1.2)
                else .65
            } else .65
        val weights =
            lines.map { normalized(it).length.toDouble().coerceAtLeast(2.0).pow(exponent) }
        // With references, outro is the LAST LINE START, not the end of the last line.
        val total = if (learned) weights.dropLast(1).sum() else weights.sum()
        var cumulative = 0.0
        var previous = -1L
        val guessed =
            lines.mapIndexed { i, line ->
                val predicted = duration * (intro + (outro - intro) * cumulative / total)
                cumulative += weights[i]
                val index = (predicted / 200).toInt().coerceIn(0, envelope.lastIndex)
                val target =
                    ((index - 6).coerceAtLeast(0)..(index + 6).coerceAtMost(envelope.lastIndex))
                        .minByOrNull { j ->
                            val energy = envelope[j].coerceAtLeast(0.0)
                            energy + abs(j - index) * .012
                        } ?: index
                val ms = maxOf(previous + 120, target * 200L).coerceAtMost(duration - 1)
                previous = ms
                TimedLine(ms, line)
            }
        if (!valid(guessed, duration)) return null
        val fitted = LearnedLyricTiming.fit(lines, duration, genre, guessed, training, audio)
        return TimingGuess(
            fitted,
            if (learned) (.25 + training.size.coerceAtMost(40) * .004).coerceAtMost(.45) else .18,
            if (learned) "learned_estimate" else "waveform_estimate",
            training.size,
        )
    }

    /** Monotone word anchors; never equate instrumental energy with recognized singing. */
    fun speech(
        lines: List<String>,
        words: List<SpeechWord>,
        duration: Long,
        checkpoint: () -> Unit = {},
    ): TimingGuess? {
        if (lines.size !in 3..1000 || words.isEmpty()) return null
        val reliable =
            words.filter {
                it.confidence >= .55 &&
                    it.startMs >= 0 &&
                    it.endMs <= duration &&
                    it.endMs >= it.startMs
            }
        val anchors = mutableMapOf<Int, Long>()
        val anchorEnds = mutableMapOf<Int, Long>()
        var cursor = 0
        lines.forEachIndexed { i, line ->
            checkpoint()
            val target = normalized(line).take(200)
            val hits =
                (cursor until minOf(cursor + 24, reliable.size))
                    .mapNotNull { start ->
                        var text = ""
                        var best = 0.0
                        var end = start
                        for (j in start until minOf(start + 12, reliable.size)) {
                            text += normalized(reliable[j].text)
                            val score = similarity(target, text)
                            if (score > best) {
                                best = score
                                end = j
                            }
                            if (text.length > target.length * 2 + 6) break
                        }
                        if (best >= .67) Triple(start, end, best) else null
                    }
                    .maxByOrNull { it.third }
            if (hits != null) {
                anchors[i] = reliable[hits.first].startMs
                anchorEnds[i] = reliable[hits.second].endMs
                cursor = hits.second + 1
            }
        }
        if (anchors.size < 3 || anchors.size.toDouble() / lines.size < .45) return null
        val result =
            lines.mapIndexed { i, line ->
                val before = anchors.keys.lastOrNull { it <= i }
                val after = anchors.keys.firstOrNull { it >= i }
                val ms =
                    anchors[i]
                        ?: when {
                            before != null && after != null ->
                                anchors[before]!! +
                                    (anchors[after]!! - anchors[before]!!) * (i - before) /
                                        (after - before)
                            before != null ->
                                anchors[before]!! +
                                    (duration - anchors[before]!!) * (i - before) /
                                        (lines.size - before)
                            after != null -> anchors[after]!! * i / (after + 1)
                            else -> 0
                        }
                TimedLine(ms, line, anchorEnds[i])
            }.let { rows -> rows.mapIndexed { i, line -> line.copy(endMs = line.endMs?.coerceAtMost(rows.getOrNull(i + 1)?.ms ?: duration)?.takeIf { it > line.ms }) } }
        return if (valid(result, duration))
            TimingGuess(
                result,
                (anchors.size.toDouble() / lines.size * .9).coerceAtMost(.9),
                "speech_anchors",
                anchors.size,
                anchors.keys.toList(),
            )
        else null
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty() || a.length > 500 || b.length > 1000) return 0.0
        var prev = IntArray(b.length + 1) { it }
        a.forEachIndexed { i, x ->
            val row = IntArray(b.length + 1)
            row[0] = i + 1
            b.forEachIndexed { j, y ->
                row[j + 1] = minOf(row[j] + 1, prev[j + 1] + 1, prev[j] + if (x == y) 0 else 1)
            }
            prev = row
        }
        return 1 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }
}
