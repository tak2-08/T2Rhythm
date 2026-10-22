// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.IOException

/** Routes private streams before the shared audio cache. Revocation is checked on every read. */
@androidx.annotation.OptIn(UnstableApi::class)
class PrivateStreamDataSource(private val store: StreamingStore, private val fallback: DataSource.Factory) : DataSource {
    private var delegate: DataSource? = null
    private var original: Uri? = null
    private var credential: StreamConnection? = null
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(listener: TransferListener) { listeners += listener; delegate?.addTransferListener(listener) }
    override fun open(dataSpec: DataSpec): Long {
        original = dataSpec.uri
        if (isPrivate(dataSpec.uri)) {
            val parts = dataSpec.uri.pathSegments
            if (parts.size != 3 || parts[1] != "audio" || !StreamingProtocol.validId(parts[2])) throw IOException("Invalid private stream")
            val connection = store.connection(parts[0]) ?: throw IOException("This music server was disconnected")
            credential = connection
            val source = OkHttpDataSource.Factory(StreamingClient.http(connection)).setUserAgent("T2Rhythm-Streaming/1").createDataSource()
            delegate = source; listeners.forEach(source::addTransferListener)
            return source.open(dataSpec.withUri(Uri.parse(connection.endpoint + "/v1/audio/" + parts[2]))
                .withRequestHeaders(mapOf("Authorization" to "Bearer ${connection.token}")))
        }
        val source = fallback.createDataSource(); delegate = source; listeners.forEach(source::addTransferListener)
        return source.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        credential?.let { if (store.connection(it.server)?.token != it.token) throw IOException("This music server was disconnected") }
        return delegate?.read(buffer, offset, length) ?: throw IOException("Data source is closed")
    }
    override fun getUri(): Uri? = original
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()
    override fun close() { try { delegate?.close() } finally { delegate = null; credential = null; original = null } }
    class Factory(context: Context, private val fallback: DataSource.Factory) : DataSource.Factory {
        private val store = PrivateStreaming.get(context).store
        override fun createDataSource(): DataSource = PrivateStreamDataSource(store, fallback)
    }
    companion object { fun isPrivate(uri: Uri) = uri.scheme == "https" && uri.host == StreamingProtocol.MEDIA_HOST }
}
