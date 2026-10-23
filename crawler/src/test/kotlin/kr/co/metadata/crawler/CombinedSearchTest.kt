// SPDX-License-Identifier: GPL-3.0-or-later
package kr.co.metadata.crawler
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
class CombinedSearchTest {
    @Test fun providersRunTogetherAndRepeatedAliasHitsAreDeduplicated() = runBlocking {
        val active = AtomicInteger(); val maximum = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val n = active.incrementAndGet(); maximum.accumulateAndGet(n, ::maxOf)
            try {
                Thread.sleep(50)
                val host = chain.request().url.host
                val link = when {
                    "bugs" in host -> "/track/123"
                    "melon" in host -> "/song/detail.htm?songId=123"
                    else -> "/detail/songInfo?xgnm=123"
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("<html><a href=\"$link\" title=\"Song\">Song</a></html>".toResponseBody("text/html".toMediaType())).build()
            } finally { active.decrementAndGet() }
        }.build()
        val search = CatalogSearch(PageClient(minInterval = Duration.ZERO, backoff = HostBackoff(), client = client))
        val results = search.searchAll(EntityKind.TRACK, "Song Artist(Alias)", 10)
        assertEquals(3, results.size)
        assertEquals(setOf(Provider.BUGS_MOBILE, Provider.MELON, Provider.GENIE), results.map { it.provider }.toSet())
        assertTrue(maximum.get() >= 2)
    }
}
