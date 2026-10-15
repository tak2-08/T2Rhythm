// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.discovery

import android.content.Context
import chromahub.rhythm.app.features.local.data.experience.PersonalTasteRepository
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.util.GenreUtils

/** Suggestions are real searchable library terms, ordered by the current personal context. */
object DiscoveryKeywords {
    fun suggest(context:Context,songs:List<Song>,favorites:List<String>,currentId:String?):List<String> {
        val selected=PersonalTasteRepository.recommend(context,songs,favorites,currentId,24)
        val scores=linkedMapOf<String,Pair<String,Double>>()
        fun add(text:String,value:Double) {
            val label=text.trim();if(label.length !in 2..70 || label.lowercase() in setOf("unknown","<unknown>","알 수 없음"))return
            val key=label.lowercase(java.util.Locale.ROOT);val old=scores[key]
            scores[key]=label to ((old?.second ?: 0.0)+value)
        }
        selected.forEachIndexed { index,song ->
            val weight=1.0/(1+index*.12);add(song.artist,weight);GenreUtils.splitGenres(song.genre).forEach { add(it,weight*.7) };add(song.title,weight*.4)
        }
        return scores.values.sortedByDescending { it.second }.take(12).map { it.first }
    }
}
