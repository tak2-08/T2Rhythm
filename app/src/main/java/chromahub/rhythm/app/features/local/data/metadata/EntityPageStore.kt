// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.annotation.Keep
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kr.co.metadata.crawler.CatalogFileStore
import kr.co.metadata.crawler.EntityKind
import java.io.*
import java.util.zip.*

@Keep data class PageTrack(val title: String, val artist: String, val album: String, val duration: Long,
    val year: Int = 0, val genre: String? = null, val source: String? = null)
@Keep data class PageAlbum(val title: String, val artist: String, val year: Int = 0)
@Keep data class EntityPage(val version: Int = 1, val kind: String, val key: String, val title: String,
    val artist: String = "", val description: String = "", val edited: Boolean = false,
    val image: String? = null, val updatedAt: Long = System.currentTimeMillis(),
    val imageEdited: Boolean = false, val imageSource: String? = null,
    val tracks: List<PageTrack>? = null, val albums: List<PageAlbum>? = null,
    val details: Map<String, String>? = null)

/** Permanent pages are single T2Rhythm bundles; only private disposable rendering caches use image extensions. */
class EntityPageStore(private val context: Context) {
    companion object {
        val revision = MutableStateFlow(0L)
        private val lock = Any()
        private const val META_LIMIT = 8_000_000
        private const val IMAGE_LIMIT = 12_000_000
        private data class Cached(val page: EntityPage?, val bytes: Int)
        private val documents = LinkedHashMap<String, Cached>(64, .75f, true)
        private var documentBytes = 0
        private var lastImageTrim = 0L
        private val observers = LinkedHashMap<String, java.lang.ref.WeakReference<MutableStateFlow<Long>>>()
        data class ImageChange(val root: String, val kind: EntityKind, val key: String)
        val imageChanges = kotlinx.coroutines.flow.MutableSharedFlow<ImageChange>(extraBufferCapacity = 32,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
        fun invalidate() = synchronized(lock) {
            documents.clear(); documentBytes = 0
            revision.update { it + 1 }
            observers.values.forEach { reference -> reference.get()?.update { it + 1 } }
        }
    }
    private val gson = Gson()
    private val root get() = File(context.filesDir, "music_metadata/pages").apply {
        mkdirs(); File(this, ".nomedia").takeUnless(File::exists)?.writeText("")
    }
    private val cache = File(context.cacheDir, "page-images")
    private fun ensureImageCache() = cache.apply {
        mkdirs(); File(this, ".nomedia").takeUnless(File::exists)?.writeText("")
    }
    fun file(kind: EntityKind, key: String) = File(root, CatalogFileStore.hash("$kind:$key") + ".t2page")
    private fun cacheKey(kind: EntityKind, key: String) = "${context.filesDir.path}:$kind:$key"
    fun changes(kind: EntityKind, key: String): kotlinx.coroutines.flow.StateFlow<Long> = synchronized(lock) {
        val id = cacheKey(kind, key)
        val flow = observers[id]?.get() ?: MutableStateFlow(0L).also { observers[id] = java.lang.ref.WeakReference(it) }
        if (observers.size > 64) observers.entries.removeAll { it.value.get() == null }
        flow
    }
    private fun remember(kind: EntityKind, key: String, page: EntityPage?) {
        val id = cacheKey(kind, key)
        documentBytes -= documents.remove(id)?.bytes ?: 0
        val bytes = page?.let { gson.toJson(it).toByteArray(Charsets.UTF_8).size * 2 } ?: 32
        if (bytes > 8_000_000) return
        documents[id] = Cached(page, bytes); documentBytes += bytes
        while (documents.size > 64 || documentBytes > 8_000_000) {
            val oldest = documents.entries.iterator(); val entry = oldest.next()
            documentBytes -= entry.value.bytes; oldest.remove()
        }
    }
    private fun imageCached(page: EntityPage?) = page?.image == null || File(cache, "${page.image}.webp").isFile
    private fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun bounded(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192); var size = 0
        while (true) { val n = input.read(buffer); if (n < 0) break; size += n; require(size <= limit); out.write(buffer, 0, n) }
        return out.toByteArray()
    }
    private fun validate(page: EntityPage, kind: EntityKind, key: String? = null) {
        require(kind != EntityKind.TRACK && page.version == 1 && page.kind == kind.name && (key == null || page.key == key))
        require(page.title.length <= 400 && page.artist.length <= 400 && page.description.length <= 100_000)
        require(page.image == null || page.image.matches(Regex("[a-f0-9]{64}")))
    }
    fun read(kind: EntityKind, key: String): EntityPage? = synchronized(lock) {
        documents[cacheKey(kind, key)]?.let { if (imageCached(it.page)) return@synchronized it.page }
        val page = runCatching {
        val f = file(kind, key)
        var extracted = false
        AtomicFile(f).openRead().close() // Also restores a committed backup after an interrupted write.
        require(f.length() in 1..22_000_000)
        ZipFile(f).use { zip ->
            require(zip.size() in 2..3)
            val page = zip.getInputStream(requireNotNull(zip.getEntry("page.json"))).use {
                gson.fromJson(bounded(it, META_LIMIT).toString(Charsets.UTF_8), EntityPage::class.java)
            }
            validate(page, kind, key)
            page.image?.let { id ->
                val target = File(cache, "$id.webp")
                if (!target.isFile) {
                    val bytes = zip.getInputStream(requireNotNull(zip.getEntry("artwork.webp"))).use { bounded(it, IMAGE_LIMIT) }
                    require(hash(bytes) == id)
                    ensureImageCache()
                    PersonalArchive.atomicWrite(target, bytes)
                    extracted = true
                }
            }
            page.image?.let { File(cache, "$it.webp").setLastModified(System.currentTimeMillis()) }
            trimCache(extracted)
            page
        }
        }.getOrNull()
        remember(kind, key, page)
        page
    }
    private fun trimCache(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastImageTrim in 0..10_000 && lastImageTrim != 0L) return
        lastImageTrim = now
        var bytes = 0L
        cache.listFiles().orEmpty().filter { it.extension == "webp" }.sortedByDescending { it.lastModified() }
            .forEachIndexed { index, file ->
                bytes += file.length()
                if (index >= 128 || bytes > 80_000_000) file.delete()
            }
    }
    fun image(page: EntityPage?): Uri? = page?.image?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        ?.let { File(cache, "$it.webp") }?.let { file ->
            file.lastModified().takeIf { it > 0L }?.let { generation ->
                Uri.fromFile(file).buildUpon().appendQueryParameter("v", generation.toString()).build()
            }
        }
    private fun existingArt(page: EntityPage): ByteArray? {
        if (page.image == null) return null
        val cached = File(cache, "${page.image}.webp")
        if (cached.isFile && cached.length() <= IMAGE_LIMIT) return cached.readBytes()
        return ZipFile(file(EntityKind.valueOf(page.kind), page.key)).use { zip ->
            zip.getInputStream(requireNotNull(zip.getEntry("artwork.webp"))).use { bounded(it, IMAGE_LIMIT) }
        }
    }
    private fun pack(page: EntityPage, artwork: ByteArray?, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray, stored: Boolean = false) {
                val item = ZipEntry(name)
                if (stored) { item.method = ZipEntry.STORED; item.size = bytes.size.toLong(); item.compressedSize = item.size; item.crc = CRC32().apply { update(bytes) }.value }
                zip.putNextEntry(item); zip.write(bytes); zip.closeEntry()
            }
            val metadata = gson.toJson(page).toByteArray(); require(metadata.size <= META_LIMIT)
            entry("page.json", metadata)
            artwork?.let { require(it.size <= IMAGE_LIMIT && hash(it) == page.image); entry("artwork.webp", it, true) }
            entry(".nomedia", byteArrayOf())
        }
    }
    private fun write(page: EntityPage, artwork: ByteArray? = existingArt(page)) = synchronized(lock) {
        validate(page, EntityKind.valueOf(page.kind), page.key)
        require((page.image == null) == (artwork == null))
        val bytes = ByteArrayOutputStream().use { out -> pack(page, artwork, out); out.toByteArray() }
        val kind = EntityKind.valueOf(page.kind)
        val previous = documents[cacheKey(kind, page.key)]?.page
        PersonalArchive.atomicWrite(file(kind, page.key), bytes)
        remember(EntityKind.valueOf(page.kind), page.key, page)
        if (previous?.image != page.image || (previous?.imageEdited ?: false) != page.imageEdited)
            imageChanges.tryEmit(ImageChange(context.filesDir.path, kind, page.key))
        observers[cacheKey(EntityKind.valueOf(page.kind), page.key)]?.get()?.update { it + 1 }
        revision.update { it + 1 }
        runCatching { PersonalArchive.enqueue(context) }
    }
    fun capture(kind: EntityKind, key: String, title: String, artist: String, description: String): EntityPage = synchronized(lock) {
        val old = read(kind, key)
        if (old?.edited == true || !old?.description.isNullOrBlank() || old != null && old.description == description) return@synchronized old!!
        (old ?: EntityPage(kind = kind.name, key = key, title = title.take(400), artist = artist.take(400)))
            .copy(description = description.take(100_000), updatedAt = System.currentTimeMillis()).also { write(it) }
    }
    fun resetImage(kind: EntityKind, key: String) = synchronized(lock) {
        read(kind, key)?.copy(image = null, imageEdited = false, imageSource = null, updatedAt = System.currentTimeMillis())?.also { write(it, null) }
    }
    /** Refresh existing physical pages after a tag edit without changing their prose or artwork. */
    fun relinkTracks(kind: EntityKind, key: String, removedSources: Set<String>, tracks: List<PageTrack>) = synchronized(lock) {
        val old = read(kind, key) ?: return@synchronized
        val nextTracks = (tracks + old.tracks.orEmpty().filterNot { it.source in removedSources })
            .distinctBy { it.source ?: "${it.artist}:${it.album}:${it.title}:${it.duration}" }
        val next = old.copy(tracks = nextTracks, albums = if (kind == EntityKind.ARTIST)
            nextTracks.map { PageAlbum(it.album, it.artist, it.year) }.distinctBy { "${it.artist}:${it.title}" } else old.albums)
        if (next != old) write(next.copy(updatedAt = System.currentTimeMillis()))
    }
    fun snapshot(kind: EntityKind, key: String, title: String, artist: String, description: String,
                 tracks: List<PageTrack>, albums: List<PageAlbum> = emptyList(), details: Map<String, String> = emptyMap()): EntityPage = synchronized(lock) {
        val old = capture(kind, key, title, artist, description)
        // An already-running screen/materializer may finish after an edit moved this file.
        val manual = ManualSongMetadata(context)
        val currentTracks = tracks.filter { track ->
            val tags = track.source?.let(manual::read)
            tags == null || (tags.title == track.title && tags.artist == track.artist && tags.album == track.album)
        }
        val next = old.copy(tracks = (currentTracks + old.tracks.orEmpty()).distinctBy { it.source ?: "${it.artist}:${it.album}:${it.title}:${it.duration}" }, albums = (albums + old.albums.orEmpty()).distinctBy { "${it.artist}:${it.title}:${it.year}" },
            details = details.ifEmpty { old.details.orEmpty() })
        if (next != old) write(next)
        next
    }
    suspend fun save(kind: EntityKind, key: String, title: String, artist: String, description: String, artwork: Uri? = null,
                     edited: Boolean = true, customImage: Boolean = true, removeImage: Boolean = false): EntityPage = withContext(Dispatchers.IO) {
        require(description.length <= 100_000)
        val bytes = artwork?.let { uri ->
            val result = context.imageLoader.execute(ImageRequest.Builder(context).data(uri).size(1600).allowHardware(false).build()) as? SuccessResult
                ?: error("Image unavailable")
            ByteArrayOutputStream().use { out ->
                check(result.drawable.toBitmap().compress(chromahub.rhythm.app.util.ArtworkStorage.format(), 100, out)); out.toByteArray()
            }.also { require(it.size <= IMAGE_LIMIT) }
        }
        synchronized(lock) {
            val old = read(kind, key)
            val keepImage = !customImage && old != null && (old.imageEdited || old.image != null)
            val art = if (keepImage) old?.let(::existingArt) else if (removeImage) null else bytes ?: old?.let(::existingArt)
            val imageId = art?.let(::hash)
            val page = (old ?: EntityPage(kind = kind.name, key = key, title = title.take(400), artist = artist.take(400)))
                .copy(description = if (!edited && old != null && (old.edited || old.description.isNotBlank())) old.description else description,
                    edited = edited || old?.edited == true, image = imageId, updatedAt = System.currentTimeMillis(),
                    imageEdited = if (keepImage) old?.imageEdited == true else removeImage || bytes != null && customImage || old?.imageEdited == true,
                    imageSource = if (keepImage) old?.imageSource else artwork?.toString() ?: old?.imageSource)
            imageId?.let { if (!File(cache, "$it.webp").isFile) { ensureImageCache(); PersonalArchive.atomicWrite(File(cache, "$it.webp"), requireNotNull(art)) } }
            write(page, art)
            trimCache(force = bytes != null)
            page
        }
    }
    fun export(page: EntityPage, output: OutputStream) = synchronized(lock) {
        val source = file(EntityKind.valueOf(page.kind), page.key)
        source.inputStream().use { input -> output.use { input.copyTo(it) } }
    }
    suspend fun importPage(kind: EntityKind, key: String, title: String, artist: String, input: InputStream): EntityPage = withContext(Dispatchers.IO) {
        var page: EntityPage? = null; var art: ByteArray? = null; val names = mutableSetOf<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(names.size < 3 && names.add(entry.name) && !entry.isDirectory)
                val limit = when (entry.name) { "page.json" -> META_LIMIT; "artwork.webp" -> IMAGE_LIMIT; ".nomedia" -> 0; else -> error("Unsupported page file") }
                val bytes = bounded(zip, limit)
                when (entry.name) { "page.json" -> page = gson.fromJson(bytes.toString(Charsets.UTF_8), EntityPage::class.java); "artwork.webp" -> art = bytes }
                zip.closeEntry()
            }
        }
        val imported = requireNotNull(page); validate(imported, kind)
        require((imported.image == null) == (art == null))
        art?.let { require(hash(it) == imported.image) }
        // Decode and normalize imported artwork before committing the selected target page.
        var staged: File? = null
        try {
            val image = art?.let { bytes -> File.createTempFile("page-import-", ".webp", context.cacheDir)
                .also { staged = it; it.writeBytes(bytes) }.let(Uri::fromFile) }
            val saved = save(kind, key, title, artist, imported.description, image, removeImage = imported.image == null)
            synchronized(lock) {
                saved.copy(tracks = imported.tracks, albums = imported.albums, details = imported.details).also { write(it) }
            }
        } finally { staged?.delete() }
    }
}
