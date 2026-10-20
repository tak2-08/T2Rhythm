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
    }
    private val gson = Gson()
    private val root get() = File(context.filesDir, "music_metadata/pages").apply {
        mkdirs(); File(this, ".nomedia").takeUnless(File::exists)?.writeText("")
    }
    private val cache get() = File(context.cacheDir, "page-images").apply {
        mkdirs(); File(this, ".nomedia").takeUnless(File::exists)?.writeText("")
    }
    fun file(kind: EntityKind, key: String) = File(root, CatalogFileStore.hash("$kind:$key") + ".t2page")
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
    fun read(kind: EntityKind, key: String): EntityPage? = synchronized(lock) { runCatching {
        val f = file(kind, key)
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
                    PersonalArchive.atomicWrite(target, bytes)
                }
            }
            page.image?.let { File(cache, "$it.webp").setLastModified(System.currentTimeMillis()) }
            trimCache()
            page
        }
    }.getOrNull() }
    private fun trimCache() {
        var bytes = 0L
        cache.listFiles().orEmpty().filter { it.extension == "webp" }.sortedByDescending { it.lastModified() }
            .forEachIndexed { index, file ->
                bytes += file.length()
                if (index >= 128 || bytes > 80_000_000) file.delete()
            }
    }
    fun image(page: EntityPage?): Uri? = page?.image?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        ?.let { File(cache, "$it.webp") }?.takeIf(File::isFile)?.let(Uri::fromFile)
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
        PersonalArchive.atomicWrite(file(EntityKind.valueOf(page.kind), page.key), bytes)
        revision.update { it + 1 }
        runCatching { PersonalArchive.enqueue(context) }
    }
    fun capture(kind: EntityKind, key: String, title: String, artist: String, description: String): EntityPage = synchronized(lock) {
        val old = read(kind, key)
        if (old?.edited == true || old != null && old.description == description) return@synchronized old!!
        (old ?: EntityPage(kind = kind.name, key = key, title = title.take(400), artist = artist.take(400)))
            .copy(description = description.take(100_000), updatedAt = System.currentTimeMillis()).also { write(it) }
    }
    fun snapshot(kind: EntityKind, key: String, title: String, artist: String, description: String,
                 tracks: List<PageTrack>, albums: List<PageAlbum> = emptyList(), details: Map<String, String> = emptyMap()): EntityPage = synchronized(lock) {
        val old = capture(kind, key, title, artist, description)
        val next = old.copy(tracks = (tracks + old.tracks.orEmpty()).distinctBy { it.source ?: "${it.artist}:${it.album}:${it.title}:${it.duration}" }, albums = (albums + old.albums.orEmpty()).distinctBy { "${it.artist}:${it.title}:${it.year}" },
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
            val art = if (removeImage) null else bytes ?: old?.let(::existingArt)
            val imageId = art?.let(::hash)
            val page = (old ?: EntityPage(kind = kind.name, key = key, title = title.take(400), artist = artist.take(400)))
                .copy(description = description, edited = edited, image = imageId, updatedAt = System.currentTimeMillis(),
                    imageEdited = removeImage || bytes != null && customImage || old?.imageEdited == true,
                    imageSource = artwork?.toString() ?: old?.imageSource)
            imageId?.let { PersonalArchive.atomicWrite(File(cache, "$it.webp"), requireNotNull(art)) }
            write(page, art)
            trimCache()
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
