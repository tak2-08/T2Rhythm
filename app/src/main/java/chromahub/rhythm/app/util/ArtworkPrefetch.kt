// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** At most one speculative decode, four nearby targets; visible requests remain independent. */
object ArtworkPrefetch {
    private val speculative = Mutex()
    private val songKey = Regex("^song_(.*?)_(?:content|file|https?):")

    fun logicalKey(key: String): String =
        if (key.startsWith("datesong_")) key.removePrefix("datesong_")
        else songKey.find(key)?.groupValues?.get(1) ?: key

    suspend fun load(context: Context, uris: List<Uri?>, pixels: Int = 384) {
        val runtime = Runtime.getRuntime()
        val pressure = runtime.totalMemory() - runtime.freeMemory() > runtime.maxMemory() * .8
        for (uri in uris.filterNotNull().distinct().take(if (pressure) 1 else 4)) {
            speculative.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        context.imageLoader.execute(
                            ImageRequest.Builder(context)
                                .data(uri)
                                .size(if (pressure) 256 else pixels.coerceIn(128, 768))
                                .allowHardware(true)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .build()
                        )
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        /* A speculative image must never crash a screen. */
                    }
                }
            }
        }
    }
}

@Composable
fun <T> NearbyArtworkPrefetch(
    items: List<T>,
    list: LazyListState? = null,
    grid: LazyGridState? = null,
    itemId: ((T) -> String)? = null,
    image: (T) -> Uri?,
) {
    val context = LocalContext.current
    val latestImage by rememberUpdatedState(image)
    val latestId by rememberUpdatedState(itemId)
    var indexById by remember(items) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(items) {
        val id = latestId
        indexById =
            if (id == null) emptyMap()
            else
                withContext(Dispatchers.Default) {
                    val active = coroutineContext
                    buildMap(items.size) {
                        items.forEachIndexed { index, item ->
                            if (index % 512 == 0) active.ensureActive()
                            put(id(item), index)
                        }
                    }
                }
    }
    LaunchedEffect(items, list, grid) {
        snapshotFlow {
                val visible =
                    list?.layoutInfo?.visibleItemsInfo?.map { it.index to it.key }
                        ?: grid?.layoutInfo?.visibleItemsInfo?.map { it.index to it.key }.orEmpty()
                val indices =
                    visible.mapNotNull { (index, key) ->
                        if (itemId == null) index
                        else indexById[ArtworkPrefetch.logicalKey(key.toString())]
                    }
                if (indices.isEmpty()) null else indices.minOrNull()!! to indices.maxOrNull()!!
            }
            .collectLatest { range ->
                if (range == null) return@collectLatest
                val (first, last) = range
                delay(300) // A fast fling never fills a queue of off-screen metadata extractors.
                val nearby =
                    listOf(first - 1, first - 2, last + 1, last + 2).mapNotNull {
                        items.getOrNull(it)?.let(latestImage)
                    }
                ArtworkPrefetch.load(context, nearby)
            }
    }
}
