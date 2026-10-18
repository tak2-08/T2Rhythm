// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.content.Context
import androidx.annotation.Keep
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import com.google.gson.Gson
import java.io.File

@Keep data class TimingRetry(val proposalId: String, val songKey: String, val revision: String?,
    val status: String = "learning", val day: Long = System.currentTimeMillis() / 86400_000, val attempts: Int = 0)

/** Rejections are negative feedback, never fresh truth. At most one attempt per model and three/day. */
class TimingRetryQueue(private val context: Context) {
    private val file = File(context.filesDir, "music_metadata/personalization/learning/timing-retries.json")
    fun read(): List<TimingRetry> = synchronized(lock) { runCatching {
        require(file.length() in 1..64_000); Gson().fromJson(file.readText(), Array<TimingRetry>::class.java).toList()
    }.getOrDefault(emptyList()) }
    private fun write(rows: List<TimingRetry>) {
        PersonalArchive.atomicWrite(file, Gson().toJson(rows.take(24)).toByteArray())
        EnrichmentStore.revision.value++
    }
    fun reject(proposal: EnrichmentProposal) = synchronized(lock) {
        write(listOf(TimingRetry(proposal.id, proposal.songKey, proposal.learningRevision)) + read().filter { it.songKey != proposal.songKey })
        TimingLearningWorker.enqueue(context, userInitiated = true)
    }
    fun complete(id: String, offered: Boolean, status: String? = null) = synchronized(lock) {
        write(read().map { if (it.proposalId == id) it.copy(status = status ?: if (offered) "ready" else "needs_examples") else it })
    }
    fun remove(songKey: String) = synchronized(lock) { write(read().filter { it.songKey != songKey }) }
    fun pause(songKey: String) = synchronized(lock) { write(read().map { if (it.songKey == songKey) it.copy(status = "paused") else it }) }
    fun resume(songKey: String) = synchronized(lock) { write(read().map { if (it.songKey == songKey && it.status == "paused") it.copy(status = "needs_examples") else it }) }
    fun learningFailed() = synchronized(lock) { write(read().map { if (it.status == "learning") it.copy(status = "learning_failed") else it }) }
    fun schedule(model: EnrichmentModel) = synchronized(lock) {
        val revision = model.timingRevision ?: return@synchronized
        val day = System.currentTimeMillis() / 86400_000
        var count = 0
        write(read().map { row ->
            val old = EnrichmentStore(context).get(row.proposalId)
            if (old?.state != "REJECTED" || row.status in setOf("ready", "paused")) row
            else if (row.revision == revision) row.copy(status = "needs_examples")
            else if (count >= 4 || row.day == day && row.attempts >= 3) row
            else {
                count++
                LyricSuggestionWorker.enqueueRetry(context, old)
                row.copy(revision = revision, status = "queued", day = day, attempts = if (row.day == day) row.attempts + 1 else 1)
            }
        })
    }
    companion object { private val lock = Any() }
}
