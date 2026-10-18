// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.InputStream
import java.security.MessageDigest

/**
 * Semantic coverage of a summarized journal; raw sensor values are intentionally outside retention.
 */
internal class JournalCoverage
private constructor(private val entries: Map<String, Header>, private val cutoff: Long) {
    private data class Header(
        val id: String,
        val key: String,
        val play: String,
        val recorded: Long,
        val ended: Long,
        val compacted: Boolean,
        val replaces: List<String>,
    )

    fun covers(input: InputStream, budget: AnalysisBudget? = null): Boolean =
        try {
            var count = 0
            visit(input, budget) { old ->
                fun matches(id: String): Boolean =
                    entries[id]?.let { newer ->
                        newer.key == old.key &&
                            newer.play == old.play &&
                            newer.recorded >= old.recorded
                    } == true
                require(old.ended < cutoff && matches(old.id) && old.replaces.all(::matches))
                count++
            }
            count > 0
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (defer: AnalysisDeferred) {
            throw defer
        } catch (slice: AnalysisSliceEnded) {
            throw slice
        } catch (_: Exception) {
            false
        }

    companion object {
        fun read(
            input: InputStream,
            cutoff: Long,
            budget: AnalysisBudget? = null,
        ): JournalCoverage? =
            try {
                val entries = mutableMapOf<String, Header>()
                visit(input, budget) { current ->
                    require(current.compacted && current.ended < cutoff)
                    for (id in listOf(current.id) + current.replaces) {
                        require(entries.size < 200_000 || id in entries)
                        entries[id] = current
                    }
                }
                if (entries.isEmpty()) null else JournalCoverage(entries, cutoff)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (defer: AnalysisDeferred) {
                throw defer
            } catch (slice: AnalysisSliceEnded) {
                throw slice
            } catch (_: Exception) {
                null
            }

        @Suppress("DEPRECATION")
        private fun visit(input: InputStream, budget: AnalysisBudget?, action: (Header) -> Unit) {
            JsonReader(input.bufferedReader()).use { reader ->
                reader.isLenient = true
                while (reader.peek() != JsonToken.END_DOCUMENT) {
                    budget?.blockingCheckpoint()
                    var id = ""
                    var key = ""
                    var play = ""
                    var recorded = 0L
                    var ended = 0L
                    var compacted = false
                    val replaces = mutableListOf<String>()
                    reader.beginObject()
                    while (reader.hasNext()) when (reader.nextName()) {
                        "id" -> id = reader.nextString()
                        "songKey" -> key = reader.nextString()
                        "playId" -> play = reader.nextString()
                        "recordedAt" -> recorded = reader.nextLong()
                        "endedAt" -> ended = reader.nextLong()
                        "compacted" -> compacted = reader.nextBoolean()
                        "replaces" ->
                            if (reader.peek() == JsonToken.NULL) reader.nextNull()
                            else {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    require(replaces.size < 128)
                                    replaces += reader.nextString()
                                }
                                reader.endArray()
                            }
                        else -> reader.skipValue()
                    }
                    reader.endObject()
                    require(
                        id.isNotBlank() &&
                            key.isNotBlank() &&
                            play.isNotBlank() &&
                            ended >= 0 &&
                            recorded >= 0
                    )
                    action(Header(id, key, play, recorded, ended, compacted, replaces))
                }
            }
        }

        fun digest(input: InputStream, budget: AnalysisBudget? = null): String =
            input.use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    budget?.blockingCheckpoint()
                    val n = stream.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
    }
}
