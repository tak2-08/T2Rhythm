// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import android.content.Context
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.source
import okio.buffer

class PrivateArtworkFetcher(private val context: Context, private val uri: Uri) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val parts = uri.pathSegments
        if (parts.size != 3 || parts[1] != "art" || !StreamingProtocol.validId(parts[2])) throw IOException("Invalid private artwork")
        val connection = PrivateStreaming.get(context).store.connection(parts[0]) ?: throw IOException("Disconnected music server")
        val bytes = StreamingClient.http(connection).newCall(StreamingClient.request(connection, "/v1/art/${parts[2]}").build()).execute().use {
            if (!it.isSuccessful) throw IOException("Artwork unavailable")
            it.body!!.byteStream().readBytesLimited(512 * 1024)
        }
        SourceResult(ImageSource(ByteArrayInputStream(bytes).source().buffer(), context), "image/webp", DataSource.NETWORK)
    }
    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (PrivateStreamDataSource.isPrivate(data)) PrivateArtworkFetcher(context, data) else null
    }
}
