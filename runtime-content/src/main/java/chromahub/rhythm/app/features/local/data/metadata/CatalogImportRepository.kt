// SPDX-License-Identifier: GPL-3.0-or-later

package chromahub.rhythm.app.features.local.data.metadata

import androidx.core.content.edit
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.metadata.crawler.*
import java.io.File
import java.net.URI
import java.util.UUID

/** Shared HTTP client keeps provider requests throttled across searches and details. */
class CatalogImportRepository(private val context: Context) {
    private val http = CatalogEnrichmentService.get(context).http
    private val search = CatalogSearch(http)
    private val metadata = MusicMetadataRepository(listOf(
        BugsMobileProvider(http), BugsPcProvider(http), GenieProvider(http), MelonProvider(http),
    ))

    suspend fun search(provider: Provider?, kind: EntityKind, query: String) =
        if (provider == null) search.searchAll(kind, query, 10) else search.searchVariants(provider, kind, query)

    internal suspend fun youtubeFallback(song: chromahub.rhythm.app.shared.data.model.Song?, hits: List<SearchHit>,
        fetch: (suspend (String) -> List<chromahub.rhythm.app.features.local.data.discovery.AudioSourceCandidate>)? = null): List<YouTubeArtworkMatch> {
        if (song == null) return emptyList()
        val service = CatalogEnrichmentService.get(context)
        val options = service.preferences.options.value
        // Manual import is independent of automatic enrichment; global online/fallback choices still apply.
        if (!chromahub.rhythm.app.shared.data.model.AppSettings.getInstance(context).integrationsEnabled.value || !options.youtubeArtworkFallback) return emptyList()
        val possible = hits.filter { it.kind == EntityKind.TRACK &&
            ArtworkMatchPolicy.score(song.title, song.artist, it.title, it.artists?.takeIf(String::isNotBlank) ?: song.artist) * 100 + 1e-8 >= options.artworkMatchPercent }.take(4)
        for (hit in possible) {
            val doc = try { load(hit) } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { null }
            if (doc?.has(CatalogField.TRACK_IMAGE) == true && ArtworkMatchPolicy.accepts(song.title, song.artist,
                    doc[CatalogField.TRACK_TITLE].orEmpty(), doc[CatalogField.TRACK_ARTISTS].orEmpty(), options.artworkMatchPercent,
                    if (options.strictDuration) song.duration else 0, doc[CatalogField.TRACK_DURATION_MS]?.toLongOrNull(), options.durationTolerancePercent)) return emptyList()
        }
        return if (fetch == null) YouTubeArtwork.search(song.title, song.artist, song.duration, options)
            else YouTubeArtwork.search(song.title, song.artist, song.duration, options, fetch)
    }

    suspend fun load(hit: SearchHit): CatalogDocument? = withContext(Dispatchers.IO) {
        val ids = if (hit.provider in setOf(Provider.BUGS_MOBILE, Provider.BUGS_PC)) {
            // Only the two Bugs views share catalog IDs. Never reuse them for Genie or Melon.
            mapOf(Provider.BUGS_MOBILE to hit.providerId, Provider.BUGS_PC to hit.providerId)
        } else mapOf(hit.provider to hit.providerId)
        val lookup = MusicLookup(ids)
        when (hit.kind) {
            EntityKind.TRACK -> metadata.track(lookup)?.let(CatalogDocument::from)
            EntityKind.ALBUM -> metadata.album(lookup)?.let(CatalogDocument::from)
            EntityKind.ARTIST -> metadata.artist(lookup)?.let(CatalogDocument::from)
        }
    }

    suspend fun artwork(context: Context, url: String): Uri = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null)
        val result = context.imageLoader.execute(ImageRequest.Builder(context)
            .data(url.substringBefore("/melon/resize/")).size(1600).allowHardware(false).build()) as? SuccessResult
            ?: error("Artwork could not be downloaded")
        val file = File(context.cacheDir, "catalog-artwork-${UUID.randomUUID()}.webp")
        file.outputStream().use {
            check(result.drawable.toBitmap().compress(chromahub.rhythm.app.util.ArtworkStorage.format(), 100, it))
        }
        Uri.fromFile(file)
    }
}

/** Verified rules only; no page HTML, picked sample text, or lyrics are persisted here. */
class SiteProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences("music_catalog_profiles", Context.MODE_PRIVATE)

    fun list(): List<DynamicSiteProfile> = preferences.all.values.mapNotNull {
        (it as? String)?.let { json -> runCatching { SiteProfileCodec.decode(json) }.getOrNull() }
    }.sortedBy { it.displayName }

    fun save(profile: DynamicSiteProfile) {
        preferences.edit { putString(profile.id, SiteProfileCodec.encode(profile)) }
    }

    fun delete(profile: DynamicSiteProfile) { preferences.edit { remove(profile.id) } }
}
