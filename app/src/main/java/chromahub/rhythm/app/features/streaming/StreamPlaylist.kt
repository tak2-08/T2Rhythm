// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import org.json.JSONObject

data class SharePlaylist(val key: String, val name: String, val uris: List<String>)
data class StreamPlaylist(val id: String, val name: String, val count: Int, val truncated: Boolean = false) {
    fun json() = JSONObject().put("id", id).put("name", name).put("count", count).put("truncated", truncated)
    companion object {
        fun parse(json: JSONObject): StreamPlaylist {
            val id = json.getString("id"); require(StreamingProtocol.validId(id))
            return StreamPlaylist(id, json.getString("name").take(160), json.getInt("count").also { require(it in 0..5000) }, json.optBoolean("truncated"))
        }
    }
}
