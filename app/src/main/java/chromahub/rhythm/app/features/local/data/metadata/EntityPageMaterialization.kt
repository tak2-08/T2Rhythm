// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import android.content.Context
import android.net.Uri
import androidx.work.*
import chromahub.rhythm.app.features.local.di.LocalMusicModule
import chromahub.rhythm.app.shared.data.model.*
import chromahub.rhythm.app.util.GenreUtils
import kotlinx.coroutines.*
import kr.co.metadata.crawler.*
import java.io.File
import java.util.concurrent.TimeUnit

class EntityPageMaterializer(private val context: Context) {
    private val pages = EntityPageStore(context)
    private val catalog = CatalogEnrichmentService.get(context)
    private fun tracks(songs: List<Song>) = songs.map { PageTrack(it.title, it.artist, it.album, it.duration,
        it.year, GenreUtils.splitGenres(it.genre).joinToString(", ").ifEmpty { null }, catalog.songKey(it)) }
    private suspend fun art(page: EntityPage, uri: Uri?) {
        if (uri == null || page.imageEdited || page.imageSource == uri.toString() || pages.image(page) == uri) return
        try { withTimeout(12_000) { pages.save(EntityKind.valueOf(page.kind), page.key, page.title, page.artist,
            page.description, uri, edited = page.edited, customImage = false) } }
        catch (_: TimeoutCancellationException) { /* Preserve text and retry the image on a later pass. */ }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* A missing image never prevents storing the rest of the page. */ }
    }
    suspend fun album(album: Album, description: String?, songs: List<Song> = album.songs): EntityPage {
        val key = catalog.albumKey(album.title, album.artist)
        val record = catalog.read(EntityKind.ALBUM, key)
        val old = pages.read(EntityKind.ALBUM, key)
        val page = pages.snapshot(EntityKind.ALBUM, key, album.title, album.artist,
            description ?: old?.description.orEmpty(), tracks(songs), details = record?.document?.values.orEmpty().associate { it.field.name to it.value })
        art(page, album.artworkUri ?: catalog.image(record) ?: songs.firstNotNullOfOrNull { it.artworkUri })
        return pages.read(EntityKind.ALBUM, key) ?: page
    }
    suspend fun artist(artist: Artist, description: String?, songs: List<Song> = artist.songs,
                       albums: List<Album> = artist.albums): EntityPage {
        val key = catalog.artistKey(artist.name)
        val record = catalog.read(EntityKind.ARTIST, key)
        val old = pages.read(EntityKind.ARTIST, key)
        val page = pages.snapshot(EntityKind.ARTIST, key, artist.name, "", description ?: old?.description.orEmpty(),
            tracks(songs), albums.map { PageAlbum(it.title, it.artist, it.year) }, record?.document?.values.orEmpty().associate { it.field.name to it.value })
        art(page, artist.artworkUri ?: catalog.image(record))
        return pages.read(EntityKind.ARTIST, key) ?: page
    }
    suspend fun legacy(folder: File) {
        val file = File(folder, "metadata.json"); require(file.length() in 1..2_000_000)
        val json = com.google.gson.JsonParser.parseString(file.readText()).asJsonObject
        val kind = EntityKind.valueOf(json.get("kind").asString); require(kind != EntityKind.TRACK)
        val key = json.get("key").asString
        val doc = CatalogDocumentCodec.decode(json.get("document").toString())
        val title = doc[if (kind == EntityKind.ARTIST) CatalogField.ARTIST_NAME else CatalogField.ALBUM_TITLE] ?: key
        val artist = if (kind == EntityKind.ARTIST) "" else doc[CatalogField.ALBUM_ARTISTS].orEmpty()
        val description = doc[if (kind == EntityKind.ARTIST) CatalogField.ARTIST_DESCRIPTION else CatalogField.ALBUM_DESCRIPTION].orEmpty()
        val page = pages.snapshot(kind, key, title, artist, description, emptyList(),
            details = doc.values.associate { it.field.name to it.value })
        art(page, File(folder, "artwork.webp").takeIf(File::isFile)?.let(Uri::fromFile))
    }
}

/** Existing library pages are persisted automatically in small resumable batches, including unopened pages. */
class EntityPageMaterializationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private data class Target(val key: String, val album: Album? = null, val artist: Artist? = null, val legacy: File? = null)
    override suspend fun doWork(): Result = withContext(Dispatchers.IO.limitedParallelism(1)) {
        val context = applicationContext
        try {
            val repository = LocalMusicModule.provideMusicRepository(context)
            val catalog = CatalogEnrichmentService.get(context)
            val albums = repository.loadAlbums(); val artists = repository.loadArtists()
            val targets = (albums.map { Target("ALBUM:${catalog.albumKey(it.title, it.artist)}", album = it) } +
                artists.map { Target("ARTIST:${catalog.artistKey(it.name)}", artist = it) } +
                File(context.filesDir, "music_metadata/entities").walkTopDown().filter { it.isFile && it.name == "metadata.json" }
                    .map { Target("legacy:${it.parent}", legacy = it.parentFile) }.toList()).sortedBy { it.key }
            val state = context.getSharedPreferences("entity_page_progress", Context.MODE_PRIVATE)
            val signature = CatalogFileStore.hash(targets.joinToString("|") { it.key })
            val begin = if (state.getString("signature", null) == signature) state.getInt("offset", 0).coerceIn(0, targets.size) else 0
            val materializer = EntityPageMaterializer(context)
            val started = android.os.SystemClock.elapsedRealtime()
            var next = begin
            for (target in targets.drop(begin).take(24)) {
                currentCoroutineContext().ensureActive()
                if (isStopped) return@withContext Result.retry()
                try {
                    target.album?.let { album -> materializer.album(album,
                        repository.loadAlbumDescription(album.title, album.artist, fetch = false)) }
                    target.artist?.let { artist -> materializer.artist(artist,
                        repository.loadArtistDescription(artist.name, fetch = false),
                        repository.getSongsForArtist(artist.id), repository.getAlbumsForArtist(artist.id)) }
                    target.legacy?.let { materializer.legacy(it) }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Other independent pages must still be materialized. */ }
                next++
                check(state.edit().putString("signature", signature).putInt("offset", next).commit())
                setProgress(workDataOf("done" to next, "total" to targets.size))
                if (android.os.SystemClock.elapsedRealtime() - started > 45_000) break
                delay(20)
            }
            if (next < targets.size) enqueue(context, true)
            else state.edit().putInt("offset", 0).putLong("last_pass", System.currentTimeMillis()).apply()
            Result.success()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { Result.retry() }
    }
    companion object {
        fun enqueue(context: Context, continuation: Boolean = false) {
            val manager = WorkManager.getInstance(context)
            val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()
            manager.enqueueUniquePeriodicWork("entity_pages_periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<EntityPageMaterializationWorker>(12, TimeUnit.HOURS).setConstraints(constraints).build())
            manager.enqueueUniqueWork("entity_pages_materialization", if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<EntityPageMaterializationWorker>().setConstraints(constraints).build())
        }
    }
}
