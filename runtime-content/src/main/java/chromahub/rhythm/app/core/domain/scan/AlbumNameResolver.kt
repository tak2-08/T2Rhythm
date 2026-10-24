// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.core.domain.scan

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.edit
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.shared.data.model.Song
import java.io.File
import kr.co.metadata.crawler.CatalogFileStore
import kr.co.metadata.crawler.CatalogIdentity
import org.json.JSONObject

object AlbumNamePolicy {
    fun resolve(reported: String?, folder: String?, embedded: String?, allowFolder: Boolean): String =
        embedded?.trim()?.takeUnless(CatalogIdentity::missing)
            ?: reported?.trim()?.takeUnless { CatalogIdentity.missing(it) || it == folder }
            ?: folder?.takeIf { allowFolder && it.isNotBlank() } ?: "Unknown Album"
}

/** MediaStore invents an album from the folder when no ALBUM tag exists. Verify only those cases. */
class AlbumNameResolver(private val context: Context) {
    private val prefs = context.getSharedPreferences("embedded_album_tags_v1", Context.MODE_PRIVATE)
    fun resolve(reported: String?, path: String?, uri: Uri, modified: Long, verify: Boolean = true): String {
        val folder = path?.let { File(it).parentFile?.name }
        val allow = AppSettings.getInstance(context).useFolderNameAsAlbum.value
        if (allow || folder == null || reported != folder)
            return AlbumNamePolicy.resolve(reported, folder, null, allow)
        val file = path?.let(::File)
        val stamp = "$modified:${file?.lastModified()}:${file?.length()}"
        val key = CatalogFileStore.hash(path ?: uri.toString())
        val cached = runCatching { JSONObject(prefs.getString(key, "")!!) }.getOrNull()?.takeIf { it.optString("stamp") == stamp }
        val embedded = if (cached != null) cached.optString("album").takeIf(String::isNotBlank)
            else if (!verify) null else {
                val result = runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                        com.kyant.taglib.TagLib.getMetadata(fd.detachFd())?.let { true to it.propertyMap["ALBUM"]?.firstOrNull() }
                    }
                }.getOrNull() ?: runCatching {
                    val retriever = MediaMetadataRetriever()
                    try { retriever.setDataSource(context, uri); true to retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) }
                    finally { retriever.release() }
                }.getOrNull()
                if (prefs.all.size >= 4096) prefs.edit { prefs.all.keys.take(128).forEach(::remove) }
                if (result != null) prefs.edit { putString(key, JSONObject().put("stamp", stamp).put("album", result.second.orEmpty()).toString()) }
                result?.second
            }
        return AlbumNamePolicy.resolve(reported, folder, embedded, false)
    }
    fun apply(song: Song, verify: Boolean = true) = song.copy(album = resolve(song.album, song.path, song.uri, song.dateModified, verify))
}
