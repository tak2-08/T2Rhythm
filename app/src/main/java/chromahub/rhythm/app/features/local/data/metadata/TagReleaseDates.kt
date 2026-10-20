// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import android.content.Context
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.util.ReleaseDates
import java.io.File
import kr.co.metadata.crawler.CatalogFileStore

object TagReleaseDates {
    /** Disposable, bounded tag cache. Reading a date never decodes audio. Call on IO. */
    fun read(context: Context, song: Song): String? {
        if (song.uri.scheme !in setOf("file", "content")) return null
        val root = File(context.cacheDir, "release-tag-dates").apply { mkdirs() }
        val file = File(root, CatalogFileStore.hash("${song.uri}:${song.dateModified}:${song.duration}") + ".txt")
        if (file.isFile && file.length() <= 32) return file.readText().takeIf(String::isNotBlank)
        val result = runCatching {
            context.contentResolver.openFileDescriptor(song.uri, "r")?.use { fd ->
                val tags = com.kyant.taglib.TagLib.getMetadata(fd.detachFd())?.propertyMap
                sequenceOf("DATE", "ORIGINALDATE", "RELEASEDATE", "YEAR").mapNotNull { tags?.get(it)?.firstOrNull() }
                    .mapNotNull { ReleaseDates.display(it) }.firstOrNull()
            }
        }.getOrNull()
        PersonalArchive.atomicWrite(file, result.orEmpty().toByteArray())
        val files = root.listFiles().orEmpty()
        if (files.size > 1024) files.sortedBy { it.lastModified() }.take(files.size - 1024).forEach(File::delete)
        return result
    }
}
