// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import android.content.Context
import android.net.Uri
import coil.imageLoader
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Purge derived images, then change request identity so already displayed painters reload. */
object ArtworkInvalidation {
    private val lock = Mutex()
    private val requests =
        MutableSharedFlow<Context>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private val processor =
        scope.launch {
            requests.debounce(300).collect { context ->
                try {
                    invalidate(context)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    android.util.Log.w(
                        "ArtworkInvalidation",
                        "Unable to refresh artwork caches",
                        error,
                    )
                }
            }
        }

    fun request(context: Context) {
        requests.tryEmit(context.applicationContext)
    }

    private val generation = MutableStateFlow(0L)
    val changes = generation.asStateFlow()
    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (!initialized)
            synchronized(this) {
                if (!initialized) {
                    generation.value =
                        context
                            .getSharedPreferences("artwork_revision", Context.MODE_PRIVATE)
                            .getLong("value", 0)
                    initialized = true
                }
            }
    }

    fun version(uri: Uri?, revision: Long = generation.value): Uri? {
        if (uri == null || revision == 0L) return uri
        // Fragments are part of Coil identity but aren't sent to HTTP servers or content providers.
        return uri.buildUpon().fragment("t2art=$revision").build()
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun invalidate(context: Context) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                context.imageLoader.memoryCache?.clear()
                context.imageLoader.diskCache?.clear()
                MediaUtils.clearRawArtworkCache()
                MediaUtils.clearArtworkDiskCache(context.cacheDir)
                File(context.cacheDir, "embedded-artwork-v2").deleteRecursively()
                ColorExtractor.clearExtractedColorCache()
                chromahub.rhythm.app.infrastructure.widget.glance.RhythmMusicWidget.clearArtCache()
                chromahub.rhythm.app.infrastructure.widget.glance.RhythmCookieWidget.clearArtCache()
                val next = maxOf(System.currentTimeMillis(), generation.value + 1)
                check(
                    context
                        .getSharedPreferences("artwork_revision", Context.MODE_PRIVATE)
                        .edit()
                        .putLong("value", next)
                        .commit()
                )
                generation.value = next
            }
        }
}

/** In-flight old requests cannot repopulate a newly invalidated cache identity. */
class ArtworkRevisionInterceptor : coil.intercept.Interceptor {
    override suspend fun intercept(
        chain: coil.intercept.Interceptor.Chain
    ): coil.request.ImageResult {
        val request = chain.request
        ArtworkInvalidation.initialize(request.context)
        val revision = ArtworkInvalidation.changes.value
        val identity =
            request.memoryCacheKey
                ?: chromahub.rhythm.app.util.coil.StreamingArtworkKeyer.keyFromUrlString(
                    request.data.toString()
                )
                ?: request.data.toString()
        return chain.proceed(
            request
                .newBuilder()
                .memoryCacheKey("$identity:artrev=$revision")
                .diskCacheKey("${request.diskCacheKey ?: identity}:artrev=$revision")
                .build()
        )
    }
}
