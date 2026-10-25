// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.ai

import android.content.Context
import android.util.AtomicFile
import chromahub.rhythm.app.features.local.data.metadata.CatalogSongCodec
import chromahub.rhythm.app.shared.data.model.Song
import java.io.File
import java.util.UUID
import org.json.JSONObject

data class AiUndo(val id: String, val before: Song, val after: Song)

/** Small durable before/after journal; originals are written before the first mutation. */
class AiLibraryEdits(context: Context) {
    private val root = File(context.noBackupFilesDir, "music_ai/edits").apply { mkdirs() }
    fun prepare(before: Song, after: Song): AiUndo {
        require(before.id == after.id && before.uri == after.uri)
        require(root.listFiles().orEmpty().count { it.extension == "json" } < 100) { "되돌리기 기록이 가득 찼습니다." }
        val entry = AiUndo(UUID.randomUUID().toString(), before, after)
        val bytes = JSONObject().put("id", entry.id).put("before", CatalogSongCodec.encode(before))
            .put("after", CatalogSongCodec.encode(after)).toString().toByteArray()
        val file = AtomicFile(File(root, "${entry.id}.json")); val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (error: Exception) { file.failWrite(stream); throw error }
        return entry
    }
    fun entries(): List<AiUndo> = root.listFiles().orEmpty().filter { it.extension == "json" }.take(100).mapNotNull { f ->
        runCatching {
            val j = JSONObject(AtomicFile(f).openRead().use { it.readNBytesBounded(64 * 1024).toString(Charsets.UTF_8) })
            val id = j.getString("id"); require(UUID.fromString(id).toString() == id && f.name == "$id.json")
            AiUndo(id, CatalogSongCodec.decode(j.getString("before")), CatalogSongCodec.decode(j.getString("after")))
        }.getOrNull()
    }
    fun forget(entry: AiUndo) {
        require(UUID.fromString(entry.id).toString() == entry.id)
        AtomicFile(File(root, "${entry.id}.json")).delete()
    }
    companion object {
        fun sameTags(a: Song, b: Song) = a.id == b.id && a.uri == b.uri && a.title == b.title &&
            a.artist == b.artist && a.album == b.album &&
            chromahub.rhythm.app.util.GenreUtils.splitGenres(a.genre).toSet() ==
                chromahub.rhythm.app.util.GenreUtils.splitGenres(b.genre).toSet()
    }
}
