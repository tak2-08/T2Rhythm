// SPDX-License-Identifier: GPL-3.0-or-later
package kr.co.metadata.crawler
import org.junit.jupiter.api.Test
import kotlin.test.*
class CatalogQueriesTest {
    @Test fun koreanAndEnglishArtistAliasesKeepOriginalAndParenthesisFreeQueries() {
        assertEquals(listOf("홍길동(HONG GILDONG)", "홍길동", "HONG GILDONG"), CatalogQueries.variants("홍길동(HONG GILDONG)"))
        val queries = CatalogQueries.queries("앨범", "홍길동(HONG GILDONG)")
        assertTrue("앨범 홍길동(HONG GILDONG)" in queries)
        assertTrue("앨범 홍길동" in queries)
        assertTrue("앨범 HONG GILDONG" in queries)
    }
    @Test fun nestedAndFullWidthParenthesesDoNotLeaveBrokenPunctuation() {
        assertEquals("Artist", CatalogQueries.withoutParentheses("Artist（아티스트(별칭)）"))
        assertEquals("Title Artist", CatalogQueries.withoutParentheses("Title (live) Artist (별칭)"))
    }
    @Test fun variantsAreBoundedDeduplicatedAndNeverBlank() {
        assertEquals(listOf("Artist"), CatalogQueries.variants("  Artist  "))
        assertTrue(CatalogQueries.queries("Title(A)(B)(C)(D)", "Name(E)(F)(G)").size <= 9)
        assertTrue(CatalogQueries.variants("( )").all { it.isNotBlank() })
    }
    @Test fun broadeningSearchNeverWeakensRecordingIdentityChecks() {
        assertFalse(CatalogIdentity.titleMatches("Song (Live)", "Song"))
        assertTrue(CatalogIdentity.artistMatches("홍길동(Hong Gildong)", "Hong Gildong"))
        assertFalse(CatalogIdentity.durationMatches(180000, 200000))
    }
    @Test fun melonArtworkWinsWithoutChangingMetadataSourcePriority() {
        fun evidence(value: String, provider: Provider, confidence: Double) = Evidence(value, provider, "https://example.com", confidence)
        val result = MetadataMerger.albums(listOf(
            AlbumMetadata(title = evidence("Bugs title", Provider.BUGS_MOBILE, .99), imageUrl = evidence("https://bugs/image", Provider.BUGS_MOBILE, .99)),
            AlbumMetadata(title = evidence("Melon title", Provider.MELON, .8), imageUrl = evidence("https://melon/image", Provider.MELON, .8))))
        assertEquals("Bugs title", result.title?.value)
        assertEquals("https://melon/image", result.imageUrl?.value)
        assertEquals(Provider.MELON, result.imageUrl?.provider)
    }
}
