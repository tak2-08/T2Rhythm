// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.video

import android.content.Context
import android.media.MediaMetadataRetriever
import chromahub.rhythm.app.shared.data.model.Song

object DownloadPlayback {
    /** Validate the published local file, including when the library scan has not run yet. */
    fun song(context: Context, record: DownloadRecord, library: List<Song>): Song? {
        if (record.kind != "AUDIO" || record.state != "SUCCEEDED") return null
        val uri = DownloadedAudioLibrary.existing(context, record)?.first ?: return null
        library.firstOrNull { it.uri == uri }?.let { return it }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            require(duration > 0)
            Song(uri.lastPathSegment ?: "download:${record.id}", record.title, record.artist, record.album,
                duration = duration, uri = uri, dateAdded = record.createdAt,
                path = uri.path.takeIf { uri.scheme == "file" })
        } finally { retriever.release() }
    }
}
