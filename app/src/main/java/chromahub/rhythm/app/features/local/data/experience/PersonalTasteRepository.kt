// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import android.content.Context
import chromahub.rhythm.app.shared.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PersonalTasteRepository {
    fun recommend(context: Context, songs: List<Song>, favoriteIds: List<String>, currentId: String?, count: Int): List<Song> {
        val ledger = ListeningLedger.get(context)
        val history = ledger.affinities().map { h ->
            if (h.songKey.startsWith("legacy:")) songs.firstOrNull { it.id == h.songKey.removePrefix("legacy:") }?.let { h.copy(songKey = it.uri.toString()) } ?: h else h
        }
        val profile = ExperienceProfile.read(context)
        val anchors = history.take(3).mapNotNull { ledger.feature(it.songKey) }
        val candidates = songs.map { song ->
            val acoustic = if (profile.audioWeight > 0 && anchors.isNotEmpty()) ledger.feature(song.uri.toString())?.let { f -> anchors.maxOf { SignalAnalysis.similarity(f, it) } } ?: 0.0 else 0.0
            RecommendationCandidate(song.uri.toString(), song.artist, song.genre, song.dateAdded, song.id in favoriteIds, song.id == currentId, acoustic)
        }
        val byKey = songs.associateBy { it.uri.toString() }
        // Limit adjacent artist repetition while retaining the original ranking within each group.
        val ranked = PersonalRecommendation.rank(candidates, history, profile).mapNotNull { byKey[it.key] }.toMutableList()
        val result = mutableListOf<Song>()
        while (result.size < count && ranked.isNotEmpty()) {
            val index = ranked.indexOfFirst { it.artist != result.lastOrNull()?.artist }.takeIf { it >= 0 } ?: 0
            result += ranked.removeAt(index)
        }
        return result
    }
}
