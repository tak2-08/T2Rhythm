// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.shared.data.model.Song
import kr.co.metadata.crawler.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class CatalogEnrichmentService private constructor(private val context: Context) {
    val preferences = CatalogPreferences(context)
    val root = File(context.filesDir, "music_metadata")
    private val store = CatalogFileStore(File(root, "records"))
    private val temporary = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, StoredCatalog>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StoredCatalog>?): Boolean = size > 256
    })
    private val projections = ConcurrentHashMap<String, StoredCatalog>()
    private val entityLocks = ConcurrentHashMap<String, Mutex>()
    private val audioLocks = ConcurrentHashMap<String, Mutex>()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    @Volatile var activeSongId: String? = null
        private set
    private val recentlyActive = ConcurrentHashMap<String, Long>()
    fun setActiveSong(id: String?) {
        if (id != activeSongId) activeSongId?.let { recentlyActive[it] = System.currentTimeMillis() + 30_000 }
        activeSongId = id
        recentlyActive.entries.removeIf { it.value <= System.currentTimeMillis() }
    }
    fun audioBusy(song: Song) = song.id == activeSongId || (recentlyActive[song.id] ?: 0) > System.currentTimeMillis()
    private val cooldownPrefs = context.getSharedPreferences("catalog_cooldowns", Context.MODE_PRIVATE)
    val http = PageClient(minInterval = Duration.ofSeconds(3), backoff = HostBackoff(persistence = object : CooldownPersistence {
        override fun read(host: String): Long = cooldownPrefs.getLong(host, 0)
        override fun write(host: String, until: Long) { cooldownPrefs.edit { putLong(host, until) } }
    }))
    private val search = CatalogSearch(http)
    private val providers: Map<Provider, MusicProvider> = listOf(BugsMobileProvider(http), BugsPcProvider(http),
        GenieProvider(http), MelonProvider(http)).associateBy { it.provider }

    fun onlineArtworkEnabled() = enabled() && preferences.options.value.artwork && preferences.options.value.artworkSource !in setOf("LOCAL_ONLY", "LEGACY", "DISABLED") && preferences.options.value.providers.isNotEmpty()
    fun enabled() = preferences.options.value.enabled && AppSettings.getInstance(context).integrationsEnabled.value
    fun songKey(song: Song): String = SongFileIdentity.key(context, song)
    fun albumKey(title: String, artist: String) = "${CatalogIdentity.normalize(artist)}:${CatalogIdentity.normalize(title)}"
    fun artistKey(name: String) = CatalogIdentity.normalize(name)
    private fun cacheKey(kind: EntityKind, key: String) = "${kind.name}:$key"
    fun read(kind: EntityKind, key: String): StoredCatalog? = temporary[cacheKey(kind, key)]
        ?: store.read(kind, key)?.let { it.copy(document = canonical(it.document)) }?.also { temporary[cacheKey(kind, key)] = it }
    private fun projection(kind: EntityKind, key: String): StoredCatalog = projections.computeIfAbsent(cacheKey(kind, key)) {
        compact(read(kind, key) ?: StoredCatalog(CatalogDocument(kind, emptyList())))
    }
    private fun canonical(document: CatalogDocument) = document.copy(values = document.values.map { value ->
        if (value.field.name.endsWith("GENRES")) value.copy(value = chromahub.rhythm.app.util.GenreUtils.splitGenres(value.value).joinToString(", ")) else value
    })
    private fun compact(record: StoredCatalog) = record.copy(document = record.document.copy(values = record.document.values.filter {
        it.field !in setOf(CatalogField.TRACK_LYRICS, CatalogField.ARTIST_DESCRIPTION, CatalogField.ALBUM_DESCRIPTION)
    }))
    private val artworkChoices = SongArtworkOverrides(context)
    private val manualTags = ManualSongMetadata(context)
    fun artworkRemoved(song: Song) = artworkChoices.read(song)?.removed == true
    fun manualArtwork(song: Song): Uri? = artworkChoices.read(song)?.uri?.let(Uri::parse)?.takeIf(::usableArtwork)
        ?: image(read(EntityKind.TRACK, "manual:${songKey(song)}"))
    fun effectiveAlbumArtist(song: Song) = song.albumArtist?.takeUnless(CatalogIdentity::missing) ?: song.artist
    fun image(record: StoredCatalog?): Uri? = record?.imageFile?.let {
        val file = File(File(root, "images"), File(it).name)
        if (file.isFile) Uri.fromFile(file) else null
    }
    fun usableArtwork(uri: Uri?): Boolean {
        if (uri == null) return false
        return when (uri.scheme) {
            "file" -> uri.path?.let { path -> File(path).isFile && !(path.startsWith(context.cacheDir.path) &&
                File(path).name.contains("placeholder", ignoreCase = true)) } == true
            "http", "https" -> true
            "content" -> runCatching { context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true }.getOrDefault(false)
            else -> false
        }
    }
    private fun changed() { revision.update { it + 1 }; PersonalArchive.enqueue(context) }
    private fun save(kind: EntityKind, key: String, document: CatalogDocument, retryAfter: Long,
                     imageFile: String? = null, replace: Boolean = false, attemptedFields: Set<CatalogField> = emptySet(), reset: Boolean = false, matchingPolicy: String? = null, durable: Boolean = false): StoredCatalog {
        val result = if (durable || preferences.options.value.permanent) store.merge(kind, key, canonical(document), retryAfter, imageFile, replace, attemptedFields, reset, matchingPolicy)
            else {
                val existing = if (reset) null else read(kind, key)
                val fields = existing?.document?.values.orEmpty().associateBy { it.field }.toMutableMap()
                canonical(document).values.filter { replace || it.hasValue() }.forEach { if (replace || fields[it.field]?.hasValue() != true) fields[it.field] = it }
                StoredCatalog(CatalogDocument(kind, fields.values.toList()), retryAfter, imageFile = imageFile ?: existing?.imageFile,
                    attemptedFields = attemptedFields + existing?.attemptedFields.orEmpty(), matchingPolicy = matchingPolicy ?: existing?.matchingPolicy)
            }
        temporary[cacheKey(kind, key)] = result
        projections[cacheKey(kind, key)] = compact(result)
        if (kind != EntityKind.TRACK) {
            val name = result.document[if (kind == EntityKind.ARTIST) CatalogField.ARTIST_NAME else CatalogField.ALBUM_TITLE] ?: key
            val artist = if (kind == EntityKind.ARTIST) "" else result.document[CatalogField.ALBUM_ARTISTS].orEmpty()
            val description = result.document[if (kind == EntityKind.ARTIST) CatalogField.ARTIST_DESCRIPTION else CatalogField.ALBUM_DESCRIPTION].orEmpty()
            EntityPageStore(context).snapshot(kind, key, name, artist, description, emptyList(),
                details = result.document.values.associate { it.field.name to it.value })
        }
        changed()
        return result
    }

    /** Stable service order, exact identities and duration corroboration for audio writes. */
    suspend fun collect(kind: EntityKind, key: String, name: String, artist: String?, wanted: Set<CatalogField>,
                        durationMs: Long = 0, album: String? = null, selectedProviders: Set<Provider>? = null,
                        replace: Boolean = false): StoredCatalog? = withContext(Dispatchers.IO) {
        val selection = selectedProviders ?: preferences.options.value.providers
        val options = preferences.options.value
        val artworkOnly = kind == EntityKind.TRACK && wanted == setOf(CatalogField.TRACK_IMAGE)
        val policy = "query-aliases-v3:${options.artworkMatchPercent}:${options.youtubeArtworkFallback}:" + selection.sortedByDescending { it.priority }.joinToString { it.name } + if (kind == EntityKind.TRACK) ":${durationMs}:${options.strictDuration}:${options.durationTolerancePercent}" else ""
        val recordKey = if (selectedProviders == null) key else "source:${selection.sortedByDescending { it.priority }.joinToString { it.name }}:$key"
        entityLocks.computeIfAbsent(cacheKey(kind, recordKey)) { Mutex() }.withLock {
            var record = read(kind, recordKey)
            val lengthVerified = kind != EntityKind.TRACK || durationMs <= 0 || !preferences.options.value.strictDuration || CatalogIdentity.durationMatches(durationMs, record?.document?.get(CatalogField.TRACK_DURATION_MS)?.toLongOrNull(), preferences.options.value.durationTolerancePercent)
            if ((!replace && lengthVerified && record?.matchingPolicy == policy && wanted.all { record?.document?.has(it) == true }) || !enabled() || CatalogIdentity.missing(name)) return@withLock record
            if (!replace && record?.matchingPolicy == policy && (record?.retryAfter ?: 0) > System.currentTimeMillis() && wanted.all {
                    record?.document?.has(it) == true || it in record?.attemptedFields.orEmpty()
                }) return@withLock record.takeIf { lengthVerified }
            val values = record?.document?.values.orEmpty().associateBy { it.field }.toMutableMap()
            if (!lengthVerified) values.clear() else if (replace) wanted.forEach(values::remove)
            if (artworkOnly && record?.matchingPolicy != policy) values.remove(CatalogField.TRACK_IMAGE)
            val documents = coroutineScope {
                selection.intersect(preferences.options.value.providers).map { provider -> async {
                try {
                val hits = search.searchVariants(provider, kind, name, artist?.takeUnless(CatalogIdentity::missing), 12)
                val candidates = if (kind == EntityKind.TRACK) hits.filter {
                    (if (artworkOnly) ArtworkMatchPolicy.score(name, artist.orEmpty(), it.title, it.artists?.takeIf(String::isNotBlank) ?: artist.orEmpty()) * 100 + 1e-8 >= options.artworkMatchPercent
                    else CatalogIdentity.titleMatches(name, it.title) && (it.artists == null || artist != null && CatalogIdentity.artistMatches(artist, it.artists.orEmpty())))
                }.distinctBy { it.providerId }.take(3) else listOfNotNull(CatalogIdentity.select(hits, name, artist))
                val source = providers.getValue(provider)
                val documents = candidates.mapNotNull { hit -> when (kind) {
                    EntityKind.TRACK -> source.track(hit.providerId)?.let(CatalogDocument::from)
                    EntityKind.ALBUM -> source.album(hit.providerId)?.let(CatalogDocument::from)
                    EntityKind.ARTIST -> source.artist(hit.providerId)?.let(CatalogDocument::from)
                } }.filter { doc -> when (kind) {
                    EntityKind.TRACK -> (if (artworkOnly) ArtworkMatchPolicy.score(name, artist.orEmpty(), doc[CatalogField.TRACK_TITLE].orEmpty(), doc[CatalogField.TRACK_ARTISTS].orEmpty()) * 100 + 1e-8 >= options.artworkMatchPercent
                        else CatalogIdentity.titleMatches(name, doc[CatalogField.TRACK_TITLE].orEmpty()) &&
                        !artist.isNullOrBlank() && CatalogIdentity.artistMatches(artist, doc[CatalogField.TRACK_ARTISTS].orEmpty())) &&
                        (!preferences.options.value.strictDuration || CatalogIdentity.durationMatches(durationMs, doc[CatalogField.TRACK_DURATION_MS]?.toLongOrNull(), preferences.options.value.durationTolerancePercent))
                    EntityKind.ALBUM -> CatalogIdentity.titleMatches(name, doc[CatalogField.ALBUM_TITLE].orEmpty()) &&
                        !artist.isNullOrBlank() && CatalogIdentity.artistMatches(artist, doc[CatalogField.ALBUM_ARTISTS].orEmpty())
                    EntityKind.ARTIST -> CatalogIdentity.artistMatches(name, doc[CatalogField.ARTIST_NAME].orEmpty())
                } }
                val preferred = if (documents.size > 1 && !CatalogIdentity.missing(album)) documents.filter {
                    CatalogIdentity.titleMatches(album.orEmpty(), it[CatalogField.TRACK_ALBUM_TITLE].orEmpty())
                } else documents
                provider to preferred.singleOrNull()
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
                } }.awaitAll().filterNotNull()
            }
            val incoming = documents.flatMap { (provider, doc) -> doc?.values.orEmpty().map { provider to it } }
                .filter { !artworkOnly || it.second.field in setOf(CatalogField.TRACK_IMAGE, CatalogField.TRACK_DURATION_MS) }
                .groupBy { it.second.field }.mapNotNull { (field, rows) ->
                    rows.filter { it.second.hasValue() }.maxByOrNull { (provider, _) ->
                        if (field.image && provider == Provider.MELON) 1000 else provider.priority
                    }?.second
                }
            incoming.forEach { value ->
                if (values[value.field]?.hasValue() != true || value.field.image && value.field in wanted) values[value.field] = value
            }
            if (kind == EntityKind.TRACK && CatalogField.TRACK_IMAGE in wanted &&
                values[CatalogField.TRACK_IMAGE]?.hasValue() != true && options.artwork && options.providers.isNotEmpty() &&
                options.artworkSource !in setOf("LOCAL_ONLY", "DISABLED", "LEGACY")) {
                YouTubeArtwork.search(name, artist.orEmpty(), durationMs, options).firstOrNull()?.document(includeDuration = true)?.values?.forEach { values[it.field] = it }
            }
            val artChanged = values.values.any { it.field.image && it.value != record?.document?.get(it.field) }
            val next = System.currentTimeMillis() + 7 * 24 * 3600_000L
            record = save(kind, recordKey, CatalogDocument(kind, values.values.toList()), next, replace = replace || !lengthVerified, attemptedFields = wanted, reset = !lengthVerified || artChanged, matchingPolicy = policy)
            record.takeIf { kind != EntityKind.TRACK || durationMs <= 0 || !preferences.options.value.strictDuration || CatalogIdentity.durationMatches(durationMs, it?.document?.get(CatalogField.TRACK_DURATION_MS)?.toLongOrNull(), preferences.options.value.durationTolerancePercent) }
        }
    }

    suspend fun persistArtwork(kind: EntityKind, key: String, source: Uri?): Uri? = withContext(Dispatchers.IO) {
        if (source == null || !preferences.options.value.permanent) return@withContext source
        val directory = File(root, "images").apply { mkdirs() }
        val owned = source.scheme == "file" && source.path?.endsWith(".webp") == true && source.path?.startsWith(directory.path + "/") == true
        val target = if (owned) File(requireNotNull(source.path)) else {
            val result = context.imageLoader.execute(ImageRequest.Builder(context).data(if (source.host?.endsWith("melon.co.kr") == true) source.toString().substringBefore("/melon/resize/") else source).memoryCachePolicy(coil.request.CachePolicy.DISABLED).diskCachePolicy(coil.request.CachePolicy.DISABLED).size(1600).allowHardware(false).build()) as? SuccessResult
                ?: return@withContext source
            val bytes = java.io.ByteArrayOutputStream().use { stream ->
                check(result.drawable.toBitmap().compress(chromahub.rhythm.app.util.ArtworkStorage.format(), 86, stream)); stream.toByteArray()
            }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            File(directory, "$hash.webp").also { target ->
                entityLocks.computeIfAbsent("image:$hash") { Mutex() }.withLock {
                    if (!target.isFile) PersonalArchive.atomicWrite(target, bytes)
                }
            }
        }
        val record = read(kind, key)
        if (record?.imageFile != target.name) save(kind, key, record?.document ?: CatalogDocument(kind, emptyList()), record?.retryAfter ?: 0, target.name)
        Uri.fromFile(target)
    }

    suspend fun fetchedImage(kind: EntityKind, key: String, name: String, artist: String? = null, durationMs: Long = 0, album: String? = null): Uri? {
        val field = when (kind) { EntityKind.TRACK -> CatalogField.TRACK_IMAGE; EntityKind.ALBUM -> CatalogField.ALBUM_IMAGE; EntityKind.ARTIST -> CatalogField.ARTIST_IMAGE }
        val selection = when (preferences.options.value.artworkSource) {
            "BUGS" -> setOf(Provider.BUGS_MOBILE)
            "MELON" -> setOf(Provider.MELON)
            "GENIE" -> setOf(Provider.GENIE)
            "LOCAL_ONLY", "DISABLED", "LEGACY" -> return image(read(kind, key))
            else -> null
        }
        val record = collect(kind, key, name, artist, setOf(field), durationMs, album, selection)
        image(record)?.let { return it }
        val url = record?.document?.get(field) ?: return null
        return persistArtwork(kind, key, Uri.parse(url))
    }
    fun apply(song: Song, fillMissingManual: Boolean = false): Song {
        val key = songKey(song)
        val includeMetadata = preferences.options.value.metadata
        val storedTrack = projection(EntityKind.TRACK, key)
        val verifiedTrack = !preferences.options.value.strictDuration || CatalogIdentity.durationMatches(song.duration, storedTrack.document[CatalogField.TRACK_DURATION_MS]?.toLongOrNull(), preferences.options.value.durationTolerancePercent)
        val automatic = if (includeMetadata && verifiedTrack) storedTrack.document else CatalogDocument(EntityKind.TRACK, emptyList())
        val manual = projection(EntityKind.TRACK, "manual:$key").document
        val album = projection(EntityKind.ALBUM, albumKey(song.album, effectiveAlbumArtist(song)))
        fun text(field: CatalogField, current: String?): String? = manual[field]?.takeIf {
            !fillMissingManual || !CatalogIdentity.missing(it)
        } ?: (if (preferences.options.value.replaces(field)) automatic.get(field) else null) ?: current?.takeUnless(CatalogIdentity::missing) ?: automatic.get(field) ?: when (field) {
                CatalogField.TRACK_GENRES -> if (includeMetadata) album.document[CatalogField.ALBUM_GENRES] else null
                CatalogField.TRACK_RELEASE_DATE -> if (includeMetadata) album.document[CatalogField.ALBUM_RELEASE_DATE] else null
                else -> null
            } ?: current
        val autoArt = (if (verifiedTrack) image(storedTrack) else null) ?: image(album)
        val removed = artworkRemoved(song)
        val enriched = song.copy(title = text(CatalogField.TRACK_TITLE, song.title).orEmpty(),
            artist = text(CatalogField.TRACK_ARTISTS, song.artist).orEmpty(),
            album = text(CatalogField.TRACK_ALBUM_TITLE, song.album).orEmpty(), genre = chromahub.rhythm.app.util.GenreUtils.splitGenres(text(CatalogField.TRACK_GENRES, song.genre)).joinToString(", ").ifEmpty { null },
            year = manual[CatalogField.TRACK_RELEASE_DATE]?.toIntOrNull()?.takeIf { !fillMissingManual || it > 0 }
                ?: (if (preferences.options.value.replaces(CatalogField.TRACK_RELEASE_DATE)) automatic[CatalogField.TRACK_RELEASE_DATE].yearOrNull() else null)
                ?: song.year.takeIf { it > 0 } ?: (automatic[CatalogField.TRACK_RELEASE_DATE] ?: if (includeMetadata) album.document[CatalogField.ALBUM_RELEASE_DATE] else null).yearOrNull() ?: 0,
            artworkUri = if (removed) null else manualArtwork(song) ?: {
                val original = song.artworkUri
                if (!preferences.options.value.replaces(CatalogField.TRACK_IMAGE) && original?.scheme == "file" && original.path?.let { File(it).isFile && !(it.startsWith(context.cacheDir.path) && File(it).name.contains("placeholder", true)) } == true) original
                else autoArt ?: original
            }())
        return manualTags.read(key)?.let { manualTags.apply(enriched, it) } ?: enriched
    }
    fun recordManualSong(song: Song) {
        // User actions are durable even when automatic catalog caching is temporary.
        manualTags.save(songKey(song), song)
        val source = song.uri.toString()
        val values = listOf(CatalogField.TRACK_TITLE to song.title, CatalogField.TRACK_ARTISTS to song.artist,
            CatalogField.TRACK_ALBUM_TITLE to song.album, CatalogField.TRACK_GENRES to song.genre.orEmpty(),
            CatalogField.TRACK_RELEASE_DATE to song.year.toString()).map { CatalogValue(it.first, it.second, source) }
        save(EntityKind.TRACK, "manual:${songKey(song)}", CatalogDocument(EntityKind.TRACK, values), Long.MAX_VALUE, replace = true, durable = true)
    }
    suspend fun artistDescription(name: String): String? {
        val wanted = setOf(CatalogField.ARTIST_DESCRIPTION, CatalogField.ARTIST_DEBUT_DATE, CatalogField.ARTIST_GENRES)
        val record = collect(EntityKind.ARTIST, artistKey(name), name, null, wanted)
        return record?.document?.values?.filter { it.field in wanted }?.joinToString("\n\n") { it.value }?.takeIf(String::isNotBlank)
    }
    fun preferredArtwork(current: Uri?, owned: Uri?): Uri? {
        if (current?.scheme == "file" && current.path?.startsWith(File(context.cacheDir, "page-images").path + "/") == true) return current
        if (current?.scheme == "file" && current.path?.let { File(it).isFile && !it.contains("/cache/") } == true) return current
        if (current?.scheme == "content" && !current.toString().startsWith("content://media/")) return current
        return owned ?: current
    }
    fun acceptMissingManualFields(song: Song) {
        val key = "manual:${songKey(song)}"
        if (read(EntityKind.TRACK, key) == null) return
        val source = song.uri.toString()
        val values = listOf(CatalogField.TRACK_TITLE to song.title, CatalogField.TRACK_ARTISTS to song.artist,
            CatalogField.TRACK_ALBUM_TITLE to song.album, CatalogField.TRACK_GENRES to song.genre.orEmpty(),
            CatalogField.TRACK_RELEASE_DATE to song.year.toString()).filter { !CatalogIdentity.missing(it.second) && it.second != "0" }
            .map { CatalogValue(it.first, it.second, source) }
        save(EntityKind.TRACK, key, CatalogDocument(EntityKind.TRACK, values), Long.MAX_VALUE)
    }
    fun descriptionLookupAllowed(kind: EntityKind, key: String): Boolean {
        val id = "description:${kind.name}:${CatalogFileStore.hash(key)}"
        if (cooldownPrefs.getLong(id, 0) > System.currentTimeMillis()) return false
        cooldownPrefs.edit { putLong(id, System.currentTimeMillis() + 24 * 3600_000L) }
        return true
    }
    fun cacheDescription(kind: EntityKind, key: String, text: String) {
        if (text.isBlank()) return
        val field = if (kind == EntityKind.ARTIST) CatalogField.ARTIST_DESCRIPTION else CatalogField.ALBUM_DESCRIPTION
        save(kind, key, CatalogDocument(kind, listOf(CatalogValue(field, text, "local:description"))), read(kind, key)?.retryAfter ?: 0)
    }
    fun notifyChanged() = changed()
    fun reloadStored() { temporary.clear(); projections.clear(); SongArtworkOverrides.invalidate(); ManualSongMetadata.invalidate(); changed() }
    suspend fun setPermanent(enabled: Boolean) = withContext(Dispatchers.IO) {
        preferences.update(preferences.options.value.copy(permanent = enabled))
        if (enabled) {
            val snapshot = synchronized(temporary) { temporary.toMap() }
            snapshot.forEach { (key, record) ->
                val kind = EntityKind.valueOf(key.substringBefore(':'))
                val stored = store.merge(kind, key.substringAfter(':'), record.document, record.retryAfter,
                    record.imageFile, attemptedFields = record.attemptedFields)
                temporary[key] = stored; projections[key] = compact(stored)
            }
            changed()
        }
    }
    suspend fun withAudioWrite(song: Song, write: suspend () -> Boolean): Boolean =
        audioLocks.computeIfAbsent(songKey(song)) { Mutex() }.withLock {
            if (audioBusy(song)) false else write()
        }
    companion object {
        @Volatile private var instance: CatalogEnrichmentService? = null
        fun get(context: Context): CatalogEnrichmentService = instance ?: synchronized(this) {
            instance ?: CatalogEnrichmentService(context.applicationContext).also { instance = it }
        }
    }
}
