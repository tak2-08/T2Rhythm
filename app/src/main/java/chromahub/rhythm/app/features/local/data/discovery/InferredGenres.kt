// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.content.Context
import androidx.annotation.Keep
import chromahub.rhythm.app.features.local.data.metadata.PersonalArchive
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

@Keep data class InferredGenre(val genre: String, val confidence: Double, val proposalId: String)

object InferredGenres {
    private val gson = Gson()
    private var signature = ""
    private var cached = emptyMap<String, InferredGenre>()

    private fun file(context: Context) =
        File(context.filesDir, "music_metadata/personalization/learning/genre-hints.json")

    @Synchronized
    fun read(context: Context): Map<String, InferredGenre> {
        val f = file(context)
        val next = "${f.path}:${f.lastModified()}:${f.length()}"
        if (next != signature) {
            cached =
                runCatching {
                        require(f.length() in 1..2_000_000)
                        gson.fromJson<Map<String, InferredGenre>>(
                            f.readText(),
                            object : TypeToken<Map<String, InferredGenre>>() {}.type,
                        )
                    }
                    .getOrNull()
                    .orEmpty()
            signature = next
        }
        return cached
    }

    fun update(context: Context, proposal: EnrichmentProposal) =
        updateMany(context, listOf(proposal))

    @Synchronized
    fun updateMany(context: Context, proposals: List<EnrichmentProposal>) {
        if (proposals.isEmpty()) return
        val values = read(context).toMutableMap()
        proposals.forEach { proposal ->
            if (proposal.kind == "GENRE") {
                values.remove(proposal.songKey)
                if (proposal.state !in setOf("REJECTED", "REVERTED"))
                    values[proposal.songKey] =
                        InferredGenre(
                            proposal.value,
                            if (proposal.state == "ACCEPTED") 1.0 else proposal.confidence,
                            proposal.id,
                        )
            }
        }
        // The proposal history is permanent; this bounded projection is disposable.
        while (values.size > 5000) values.remove(values.keys.first())
        PersonalArchive.atomicWrite(file(context), gson.toJson(values).toByteArray())
        signature = ""
    }
}
