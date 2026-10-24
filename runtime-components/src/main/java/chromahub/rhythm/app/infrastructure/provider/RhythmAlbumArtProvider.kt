// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.infrastructure.provider

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import chromahub.rhythm.runtime.api.RuntimeComponentRegistry

class RhythmAlbumArtProvider : ContentProvider() {
    private lateinit var information: ProviderInfo
    private val implementation: ContentProvider by lazy {
        val host = RuntimeComponentRegistry.host()
        host.feature("artwork").createArtworkProvider(this).also {
            it.attachInfo(host.wrap(requireNotNull(context), "artwork"), information)
        }
    }
    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        information = info
    }
    override fun onCreate(): Boolean = true
    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor? = implementation.openFile(uri, mode, signal)
    override fun openAssetFile(uri: Uri, mode: String, signal: CancellationSignal?): AssetFileDescriptor? = implementation.openAssetFile(uri, mode, signal)
    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?, signal: CancellationSignal?): AssetFileDescriptor? =
        implementation.openTypedAssetFile(uri, mimeTypeFilter, opts, signal)
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        implementation.query(uri, projection, selection, selectionArgs, sortOrder)
    override fun getType(uri: Uri): String? = implementation.getType(uri)
    override fun insert(uri: Uri, values: ContentValues?): Uri? = implementation.insert(uri, values)
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = implementation.delete(uri, selection, selectionArgs)
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = implementation.update(uri, values, selection, selectionArgs)
    companion object {
        private const val TAG = "RhythmAlbumArtProvider"

        /** Authority for the ContentProvider that serves artwork to external processes. */
        val PROVIDER_AUTHORITY: String get() = RuntimeComponentRegistry.packageName() + ".albumart"

        /**
         * Builds a virtual `content://` URI pointing to [RhythmAlbumArtProvider] for a song.
         */
        fun buildSongUri(
            id: String,
            path: String? = null,
            albumId: String? = null,
            lossless: Boolean = false
        ): Uri {
            return Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(PROVIDER_AUTHORITY)
                .appendPath("song")
                .appendPath(id)
                .apply {
                    if (!path.isNullOrBlank()) appendQueryParameter("songFile", path)
                    if (!albumId.isNullOrBlank()) appendQueryParameter("albumId", albumId)
                    appendQueryParameter("lossless", lossless.toString())
                }
                .build()
        }

        /**
         * Builds a virtual `content://` URI pointing to [RhythmAlbumArtProvider] for an album.
         */
        fun buildAlbumUri(
            albumId: String,
            songFile: String? = null,
            lossless: Boolean = false
        ): Uri {
            return Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(PROVIDER_AUTHORITY)
                .appendPath("album")
                .appendPath(albumId)
                .apply {
                    if (!songFile.isNullOrBlank()) appendQueryParameter("songFile", songFile)
                    appendQueryParameter("lossless", lossless.toString())
                }
                .build()
        }
    }
}
