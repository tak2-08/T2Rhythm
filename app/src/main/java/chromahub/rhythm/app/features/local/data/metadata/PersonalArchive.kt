// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.AtomicFile
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import chromahub.rhythm.app.features.local.data.experience.AnalysisBudget
import chromahub.rhythm.app.features.local.data.experience.AnalysisDeferred
import chromahub.rhythm.app.features.local.data.experience.AnalysisSliceEnded
import chromahub.rhythm.app.util.ArtworkEnvelope
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kr.co.metadata.crawler.CatalogFileStore

/** Plain files in the existing Documents/RhythmBackups folder, or a user-granted folder. */
class PersonalArchive(private val context: Context) {
    private val prefs = context.getSharedPreferences("personal_archive", Context.MODE_PRIVATE)
    val tree: String?
        get() = prefs.getString("tree", null)

    val status: String?
        get() = prefs.getString("status", null)

    val lastSync: Long
        get() = prefs.getLong("lastSync", 0)

    fun selectTree(uri: Uri) {
        prefs.edit(commit = true) {
            putString("tree", uri.toString())
            remove("privacy_migration_target")
        }
        ArtworkPrivacyWorker.enqueue(context)
        enqueue(context)
    }

    fun captureSong(song: chromahub.rhythm.app.shared.data.model.Song) {
        val catalog = CatalogEnrichmentService.get(context)
        val directory = File(catalog.root, "originals").apply { mkdirs() }
        val key = CatalogFileStore.hash(catalog.songKey(song))
        val record = File(directory, "$key.json")
        if (record.exists()) return
        val retriever = android.media.MediaMetadataRetriever()
        var original = song
        try {
            retriever.setDataSource(context, song.uri)
            fun tag(key: Int) = retriever.extractMetadata(key)?.takeIf(String::isNotBlank)
            original =
                song.copy(
                    title =
                        tag(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE) ?: song.title,
                    artist =
                        tag(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            ?: song.artist,
                    album =
                        tag(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: song.album,
                    genre =
                        tag(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE) ?: song.genre,
                    year =
                        tag(android.media.MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull()
                            ?: song.year,
                    albumArtist =
                        tag(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                            ?: song.albumArtist,
                )
            retriever.embeddedPicture?.let { bytes ->
                require(bytes.size <= 20 * 1024 * 1024)
                // Original bytes remain available for exact rollback; display copy is WebP.
                atomicWrite(File(directory, "$key.original-artwork"), bytes)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap
                    ->
                    try {
                        chromahub.rhythm.app.util.ArtworkStorage.write(
                            bitmap,
                            File(directory, "$key.webp"),
                            lossless = true,
                        )
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            retriever.release()
        }
        val image = File(directory, "$key.webp")
        if (!image.exists())
            song.artworkUri?.let { uri ->
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val data = input.readBytesBounded(20 * 1024 * 1024)
                        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)?.let {
                            bitmap ->
                            try {
                                chromahub.rhythm.app.util.ArtworkStorage.write(bitmap, image)
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        atomicWrite(record, CatalogSongCodec.encode(original).toByteArray())
        enqueue(context)
    }

    suspend fun sync(force: Boolean = false): Int = archiveMutex.withLock { syncUnlocked(force) }

    internal suspend fun syncBackground(budget: AnalysisBudget): Int =
        archiveMutex.withLock { syncUnlocked(false, budget) }

    private suspend fun syncUnlocked(force: Boolean, budget: AnalysisBudget? = null): Int =
        withContext(if (budget == null) Dispatchers.IO else AnalysisBudget.dispatcher) {
            val sessionStarted = android.os.SystemClock.elapsedRealtime()
            val syncIndex = ArchiveSyncIndex(context)
            val files =
                sequence<Pair<String, File>> {
                    val catalog = File(context.filesDir, "music_metadata")
                    if (catalog.isDirectory)
                        catalog
                            .walkTopDown()
                            .filter {
                                it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".bak")
                            }
                            .forEach {
                                yield(
                                    "T2Rhythm/music_metadata/${it.relativeTo(catalog).invariantSeparatorsPath}" to
                                        it
                                )
                            }
                    val history = File(context.filesDir, "listening_history")
                    if (history.isDirectory)
                        history
                            .walkTopDown()
                            .filter { it.isFile && it.extension == "jsonl" }
                            .forEach { yield("T2Rhythm/listening_history/${it.name}" to it) }
                    File(context.filesDir, "experience.json")
                        .takeIf { it.isFile }
                        ?.let { yield("T2Rhythm/experience.json" to it) }
                }
            var copied = 0
            try {
                budget?.blockingCheckpoint()
                syncIndex.migrate(prefs)
                val selectedTree =
                    tree?.let(Uri::parse)?.let { DocumentFile.fromTreeUri(context, it) }
                if (selectedTree != null)
                    check(
                        selectedTree.exists() && selectedTree.isDirectory && selectedTree.canWrite()
                    )
                val directory =
                    if (selectedTree == null)
                        File(
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOCUMENTS
                            ),
                            "RhythmBackups",
                        )
                    else null
                if (directory != null)
                    check(directory.isDirectory || directory.mkdirs()) {
                        "Select a writable backup folder"
                    }
                protectGallery(selectedTree, directory)
                files.forEach { (relative, source) ->
                    coroutineContext.ensureActive()
                    budget?.blockingCheckpoint()
                    if (
                        budget != null &&
                            copied > 0 &&
                            android.os.SystemClock.elapsedRealtime() - sessionStarted > 90_000
                    )
                        throw AnalysisSliceEnded()
                    val signature = "${source.length()}:${source.lastModified()}"
                    val key = "synced:private-art-v1:${tree ?: directory?.path}:$relative"
                    if (!force && syncIndex.signature(key) == signature) {
                        if (selectedTree != null) cleanupSummarizedBackups(selectedTree, relative, source, budget)
                        return@forEach
                    }
                    val snapshot =
                        if (relative.startsWith("T2Rhythm/listening_history/"))
                            File.createTempFile("journal-snapshot-", ".jsonl", context.cacheDir)
                                .also {
                                    chromahub.rhythm.app.features.local.data.experience
                                        .ListeningLedger
                                        .get(context)
                                        .copyJournal(source, it)
                                }
                        else null
                    try {
                        val wrapped =
                            if (ArtworkEnvelope.isImage(source))
                                File.createTempFile(
                                        "private-art-",
                                        ArtworkEnvelope.EXTENSION,
                                        context.cacheDir,
                                    )
                                    .also { ArtworkEnvelope.wrap(source, it) }
                            else null
                        val exported = wrapped ?: snapshot ?: source
                        val privateRelative =
                            relative + if (wrapped != null) ArtworkEnvelope.EXTENSION else ""
                        try {
                            if (selectedTree != null)
                                publish(selectedTree, privateRelative, exported, budget)
                            else {
                                val destination = File(directory!!, privateRelative)
                                destination.parentFile!!.mkdirs()
                                atomicCopy(exported, destination, budget)
                            }
                        } finally {
                            wrapped?.delete()
                        }
                    } finally {
                        snapshot?.delete()
                    }
                    syncIndex.remember(key, signature)
                    copied++
                    if (selectedTree != null) cleanupSummarizedBackups(selectedTree, relative, source, budget)
                    delay(40)
                }
                if (migrateGalleryImages(selectedTree, directory))
                    prefs.edit(commit = true) {
                        putString("privacy_migration_target", tree ?: "default")
                    }
                prefs.edit(commit = true) {
                    putLong("lastSync", System.currentTimeMillis())
                    remove("status")
                }
                copied
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (deferred: AnalysisDeferred) {
                throw deferred
            } catch (slice: AnalysisSliceEnded) {
                throw slice
            } catch (error: Exception) {
                prefs.edit(commit = true) { putString("status", "folder_required") }
                throw error
            } finally {
                syncIndex.close()
            }
        }

    suspend fun restore(): Int = archiveMutex.withLock { restoreUnlocked() }

    private suspend fun restoreUnlocked(): Int =
        withContext(Dispatchers.IO) {
            val selected =
                tree?.let(Uri::parse)?.let { DocumentFile.fromTreeUri(context, it) }
                    ?: error("Choose a backup folder")
            val base = selected.findFile("T2Rhythm") ?: selected
            var count = 0
            val metadata = base.findFile("music_metadata")
            val local = File(context.filesDir, "music_metadata")
            listOf(
                    "records",
                    "images",
                    "lyrics",
                    "originals",
                    "audio_features",
                    "recommendations",
                    "videos",
                    "entities",
                    "context",
                    "personalization",
                    "preferences",
                    "speech",
                    "charts",
                    "downloads",
                )
                .forEach { category ->
                    fun descend(
                        folder: DocumentFile,
                        prefix: String = "",
                    ): List<Pair<String, DocumentFile>> =
                        folder.listFiles().flatMap { child ->
                            val name = child.name ?: return@flatMap emptyList()
                            require('/' !in name && '\\' !in name && name != ".." && name != ".")
                            val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                            require(relative.count { it == '/' } < 8)
                            if (child.isDirectory) descend(child, relative)
                            else listOf(relative to child)
                        }
                    metadata
                        ?.findFile(category)
                        ?.let { descend(it) }
                        ?.filter { (_, it) ->
                            it.isFile &&
                                it.name?.contains(".previous-") != true &&
                                it.name?.contains(".pending-") != true
                        }
                        ?.forEach { (relative, remote) ->
                            val name = remote.name ?: return@forEach
                            require('/' !in name && '\\' !in name && name != "..")
                            val envelope = relative.endsWith(ArtworkEnvelope.EXTENSION)
                            val restoredRelative =
                                if (envelope) relative.removeSuffix(ArtworkEnvelope.EXTENSION)
                                else relative
                            val destination = File(File(local, category), restoredRelative)
                            if (envelope) {
                                if (destination.exists()) return@forEach
                                destination.parentFile!!.mkdirs()
                                val pending =
                                    File.createTempFile(
                                        "restore-art-",
                                        ".tmp",
                                        destination.parentFile,
                                    )
                                try {
                                    context.contentResolver.openInputStream(remote.uri)?.use {
                                        ArtworkEnvelope.unwrap(it, pending)
                                    } ?: return@forEach
                                    check(pending.renameTo(destination))
                                    count++
                                } finally {
                                    pending.delete()
                                }
                                return@forEach
                            }
                            val journal =
                                category in setOf("recommendations", "preferences") &&
                                    remote.name?.endsWith(".jsonl") == true
                            if (destination.exists() && !journal)
                                return@forEach // Restore fills gaps; never destroys newer personal
                                               // data.
                            if (
                                journal ||
                                    category == "videos" &&
                                        remote.name?.substringAfterLast('.') in setOf("mp4", "webm")
                            ) {
                                destination.parentFile!!.mkdirs()
                                val pending =
                                    File.createTempFile("restore-", ".tmp", destination.parentFile)
                                try {
                                    context.contentResolver.openInputStream(remote.uri)?.use { input
                                        ->
                                        pending.outputStream().use { output ->
                                            val buffer = ByteArray(8192)
                                            var size = 0L
                                            while (true) {
                                                val n = input.read(buffer)
                                                if (n < 0) break
                                                size += n
                                                require(size <= 1024L * 1024 * 1024)
                                                output.write(buffer, 0, n)
                                            }
                                        }
                                    } ?: return@forEach
                                    check(pending.length() == remote.length())
                                    val final =
                                        if (journal && destination.exists())
                                            File(
                                                destination.parentFile,
                                                destination.nameWithoutExtension +
                                                    "-import-" +
                                                    chromahub.rhythm.app.util.UpdateIntegrity
                                                        .fileDigest(pending)
                                                        .take(12) +
                                                    ".jsonl",
                                            )
                                        else destination
                                    if (!final.exists()) {
                                        check(pending.renameTo(final))
                                        count++
                                    }
                                    if (category == "preferences")
                                        chromahub.rhythm.app.features.local.data.experience
                                            .TasteMemory
                                            .get(context)
                                            .importJournal(final)
                                } finally {
                                    pending.delete()
                                }
                                return@forEach
                            }
                            val bytes =
                                context.contentResolver.openInputStream(remote.uri)?.use {
                                    it.readBytesBounded(
                                        if (category == "images" || category == "originals")
                                            20 * 1024 * 1024
                                        else if (category == "speech") 3_000_000
                                        else 2 * 1024 * 1024
                                    )
                                } ?: return@forEach
                            if (category == "records") {
                                val root =
                                    com.google.gson.JsonParser.parseString(
                                            bytes.toString(Charsets.UTF_8)
                                        )
                                        .asJsonObject
                                require(root.get("version").asInt == 1)
                                kr.co.metadata.crawler.CatalogDocumentCodec.decode(
                                    root.get("document").toString()
                                )
                            }
                            atomicWrite(destination, bytes)
                            count++
                        }
                }
            val ledger =
                chromahub.rhythm.app.features.local.data.experience.ListeningLedger.get(context)
            base
                .findFile("listening_history")
                ?.listFiles()
                ?.filter { it.name?.matches(Regex("\\d{4}-\\d{2}\\.jsonl")) == true }
                ?.forEach { remote ->
                    val scratch =
                        File.createTempFile("history-restore-", ".jsonl", context.cacheDir)
                    try {
                        context.contentResolver.openInputStream(remote.uri)?.use { input ->
                            scratch.outputStream().use { input.copyTo(it) }
                        } ?: return@forEach
                        count += ledger.importJournal(scratch)
                        val directory =
                            File(context.filesDir, "listening_history").apply { mkdirs() }
                        val destination = File(directory, remote.name!!)
                        // Keep the imported raw source even when this month already has local
                        // records.
                        val preserved =
                            if (destination.exists())
                                File(
                                    directory,
                                    remote.name!!.removeSuffix(".jsonl") +
                                        "-import-${scratch.inputStream().use { input -> val digest = java.security.MessageDigest.getInstance("SHA-256")
 val buffer = ByteArray(8192)
 while (true) { val n = input.read(buffer)
 if (n < 0) break
 digest.update(buffer, 0, n) }
 digest.digest().take(6).joinToString("") { "%02x".format(it.toInt() and 255) } }}.jsonl",
                                )
                            else destination
                        if (!preserved.exists()) atomicCopy(scratch, preserved)
                    } finally {
                        scratch.delete()
                    }
                }
            val rules = base.findFile("experience.json")
            if (!File(context.filesDir, "experience.json").exists() && rules != null) {
                context.contentResolver.openInputStream(rules.uri)?.use { input ->
                    val json = input.readBytesBounded(64_000).toString(Charsets.UTF_8)
                    chromahub.rhythm.app.features.local.data.experience.ExperienceProfile.apply(
                        context,
                        json,
                    )
                    count++
                }
            }
            ledger.invalidate()
            CatalogEnrichmentService.get(context).reloadStored()
            enqueue(context)
            count
        }

    private fun protectGallery(selected: DocumentFile?, directory: File?) {
        if (selected != null) {
            val own =
                selected.findFile("T2Rhythm") ?: selected.createDirectory("T2Rhythm") ?: return
            val metadata =
                own.findFile("music_metadata") ?: own.createDirectory("music_metadata") ?: return
            if (metadata.findFile(".nomedia") == null)
                metadata.createFile("application/octet-stream", ".nomedia")
        } else
            directory?.let {
                File(it, "T2Rhythm/music_metadata/.nomedia").let { marker ->
                    marker.parentFile!!.mkdirs()
                    if (!marker.exists()) marker.createNewFile()
                }
            }
    }

    /**
     * Only our backup subtree is touched. A legacy image is removed after byte-verified
     * publication.
     */
    private suspend fun migrateGalleryImages(selected: DocumentFile?, directory: File?): Boolean {
        val started = System.currentTimeMillis()
        if (selected == null) {
            val own = File(directory ?: return true, "T2Rhythm/music_metadata")
            own.walkTopDown()
                .filter { it.isFile && !it.name.endsWith(ArtworkEnvelope.EXTENSION) }
                .forEach { old ->
                    coroutineContext.ensureActive()
                    if (System.currentTimeMillis() - started > 90_000) {
                        ArtworkPrivacyWorker.enqueue(context, true)
                        return false
                    }
                    if (!ArtworkEnvelope.isImage(old)) return@forEach
                    val target = File(old.parentFile, old.name + ArtworkEnvelope.EXTENSION)
                    val pending = File.createTempFile("art-migration-", ".tmp", old.parentFile)
                    try {
                        ArtworkEnvelope.wrap(old, pending)
                        atomicCopy(pending, target)
                        check(target.inputStream().use { ArtworkEnvelope.matches(it, pending) })
                        check(old.delete())
                        android.media.MediaScannerConnection.scanFile(
                            context,
                            arrayOf(old.path),
                            null,
                            null,
                        )
                    } finally {
                        pending.delete()
                    }
                    delay(80)
                }
        } else {
            val own = selected.findFile("T2Rhythm")?.findFile("music_metadata") ?: return true
            suspend fun visit(folder: DocumentFile, prefix: String, depth: Int): Boolean {
                require(depth <= 8)
                for (old in folder.listFiles()) {
                    coroutineContext.ensureActive()
                    if (System.currentTimeMillis() - started > 90_000) {
                        ArtworkPrivacyWorker.enqueue(context, true)
                        return false
                    }
                    val name = old.name ?: continue
                    require('/' !in name && '\\' !in name && name != ".." && name != ".")
                    if (old.isDirectory) {
                        if (!visit(old, "$prefix/$name", depth + 1)) return false
                        continue
                    }
                    if (
                        name.endsWith(ArtworkEnvelope.EXTENSION) ||
                            name.endsWith(".tmp") ||
                            old.length() !in 12..ArtworkEnvelope.MAX_BYTES
                    )
                        continue
                    val header =
                        context.contentResolver.openInputStream(old.uri)?.use { stream ->
                            ByteArray(12).also { stream.read(it) }
                        } ?: continue
                    if (!ArtworkEnvelope.isImage(header)) continue
                    val source = File.createTempFile("legacy-art-", ".tmp", context.cacheDir)
                    val wrapped =
                        File.createTempFile(
                            "private-art-",
                            ArtworkEnvelope.EXTENSION,
                            context.cacheDir,
                        )
                    try {
                        context.contentResolver.openInputStream(old.uri)?.use { input ->
                            source.outputStream().use { out ->
                                val buffer = ByteArray(32 * 1024)
                                var size = 0L
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    size += n
                                    require(size <= ArtworkEnvelope.MAX_BYTES)
                                    out.write(buffer, 0, n)
                                }
                            }
                        } ?: continue
                        check(source.length() == old.length())
                        ArtworkEnvelope.wrap(source, wrapped)
                        publish(selected, "$prefix/$name${ArtworkEnvelope.EXTENSION}", wrapped)
                        val replacement =
                            folder.findFile(name + ArtworkEnvelope.EXTENSION)
                                ?: error("Missing migrated artwork")
                        check(
                            context.contentResolver.openInputStream(replacement.uri)?.use {
                                ArtworkEnvelope.matches(it, wrapped)
                            } == true
                        )
                        // A verified replacement contains exactly the original bytes; failed
                        // deletion is retried.
                        check(old.delete())
                        runCatching {
                            context.contentResolver.notifyChange(
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                null,
                            )
                        }
                    } finally {
                        source.delete()
                        wrapped.delete()
                    }
                    delay(80)
                }
                return true
            }
            if (!visit(own, "T2Rhythm/music_metadata", 0)) return false
        }
        return true
    }

    /** Delete only redundant old history backups, after verifying the published replacement. */
    private fun cleanupSummarizedBackups(root: DocumentFile, relative: String, source: File, budget: AnalysisBudget?) {
        if (!relative.startsWith("T2Rhythm/listening_history/") || source.extension!="jsonl") return
        val path=relative.split('/')
        var directory: DocumentFile = root
        for (name in path.dropLast(1)) directory=directory.findFile(name)?.takeIf { it.isDirectory } ?: return
        val name=path.last()
        val previous=directory.listFiles().filter { it.name?.startsWith("$name.previous-")==true }.take(8)
        if(previous.isEmpty()) return
        val current=directory.findFile(name) ?: return
        val snapshot=File.createTempFile("retention-proof-",".jsonl",context.cacheDir)
        try {
            chromahub.rhythm.app.features.local.data.experience.ListeningLedger.get(context).copyJournal(source,snapshot)
            val cutoff=System.currentTimeMillis()-chromahub.rhythm.app.features.local.data.experience.HistoryRetention.DETAIL_DAYS*86_400_000L
            val coverage=snapshot.inputStream().use { chromahub.rhythm.app.features.local.data.experience.JournalCoverage.read(it,cutoff,budget) } ?: return
            val expected=snapshot.inputStream().use { chromahub.rhythm.app.features.local.data.experience.JournalCoverage.digest(it,budget) }
            val published=context.contentResolver.openInputStream(current.uri)?.use {
                chromahub.rhythm.app.features.local.data.experience.JournalCoverage.digest(it,budget)
            } ?: return
            if(expected!=published) return
            for(old in previous) {
                budget?.blockingCheckpoint()
                val covered=context.contentResolver.openInputStream(old.uri)?.use { coverage.covers(it,budget) }==true
                if(covered) check(old.delete())
            }
        } finally { snapshot.delete() }
    }

    /** Keep the previous complete file if a document provider fails during publication. */
    private fun publish(
        root: DocumentFile,
        relative: String,
        source: File,
        budget: AnalysisBudget? = null,
    ) {
        val path = relative.split('/')
        val directory =
            path.dropLast(1).fold(root) { folder, name ->
                folder.findFile(name)?.takeIf { it.isDirectory }
                    ?: folder.createDirectory(name)
                    ?: error("Cannot create backup folder")
            }
        val name = path.last()
        val temporary =
            directory.createFile(mime(name), "$name.pending-${System.nanoTime()}")
                ?: error("Cannot create backup file")
        var old: DocumentFile? = null
        try {
            context.contentResolver.openOutputStream(temporary.uri, "wt")?.use { out ->
                source.inputStream().use { copyWithBudget(it, out, budget) }
            } ?: error("Cannot write backup file")
            check(temporary.length() == source.length()) { "Incomplete backup" }
            old = directory.findFile(name)
            if (old != null) check(old!!.renameTo("$name.previous-${System.currentTimeMillis()}"))
            check(temporary.renameTo(name)) { "Cannot publish backup file" }
            // Journal snapshots are cumulative. Delete a redundant prefix only after byte-for-byte
            // verification.
            if (
                relative.startsWith("T2Rhythm/listening_history/") &&
                    old != null &&
                    old!!.length() <= source.length()
            )
                runCatching {
                    val prefixMatches =
                        context.contentResolver.openInputStream(old!!.uri)?.use { previous ->
                            source.inputStream().use { current ->
                                val a = ByteArray(8192)
                                val b = ByteArray(8192)
                                var same = true
                                while (same) {
                                    val n = previous.read(a)
                                    if (n < 0) break
                                    var read = 0
                                    while (read < n) {
                                        val size = current.read(b, read, n - read)
                                        if (size < 0) {
                                            same = false
                                            break
                                        }
                                        read += size
                                    }
                                    if (read != n || (0 until n).any { a[it] != b[it] })
                                        same = false
                                }
                                same
                            }
                        } == true
                    if (prefixMatches) old!!.delete()
                }
        } catch (error: Exception) {
            if (directory.findFile(name) == null) old?.renameTo(name)
            temporary.delete()
            throw error
        }
    }

    companion object {
        private val archiveMutex = kotlinx.coroutines.sync.Mutex()

        private fun mime(name: String) =
            when (name.substringAfterLast('.')) {
                "webp" -> "image/webp"
                "webm" -> "video/webm"
                "mp4" -> "video/mp4"
                "jpg" -> "image/jpeg"
                "png" -> "image/png"
                "json" -> "application/json"
                "jsonl",
                "txt",
                "lrc" -> "text/plain"
                else -> "application/octet-stream"
            }

        fun atomicWrite(destination: File, bytes: ByteArray) {
            destination.parentFile!!.mkdirs()
            val file = AtomicFile(destination)
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }

        private fun atomicCopy(source: File, destination: File, budget: AnalysisBudget? = null) {
            val file = AtomicFile(destination)
            val stream = file.startWrite()
            try {
                source.inputStream().use { copyWithBudget(it, stream, budget) }
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }

        private fun copyWithBudget(
            input: java.io.InputStream,
            output: java.io.OutputStream,
            budget: AnalysisBudget?,
        ) {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                budget?.blockingCheckpoint()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }

        private var lastEnqueue = Long.MIN_VALUE

        @Synchronized
        fun enqueue(context: Context, continuation: Boolean = false) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (!continuation && lastEnqueue != Long.MIN_VALUE && now - lastEnqueue < 15 * 60_000L)
                return
            lastEnqueue = now
            val manager = WorkManager.getInstance(context)
            val constraints =
                Constraints.Builder()


                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            manager.enqueueUniquePeriodicWork(
                "personal_archive_periodic_v2",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<PersonalArchiveWorker>(6, TimeUnit.HOURS)
                    .setInputData(workDataOf("archive_constraints_v2" to true))
                    .setConstraints(constraints)
                    .build(),
            )
            manager.enqueueUniqueWork(
                "personal_archive_sync_v2",
                if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PersonalArchiveWorker>()
                    .setInputData(workDataOf("archive_constraints_v2" to true))
                    .setInitialDelay(if (continuation) 1 else 15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()


                            .setRequiresBatteryNotLow(true)
                            .setRequiresStorageNotLow(true)
                            .build()
                    )
                    .build(),
            )
        }
    }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

class PersonalArchiveWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            // Requests surviving an APK update may still carry the old unconstrained specification.
            if (!inputData.getBoolean("archive_constraints_v2", false)) {
                PersonalArchive.enqueue(applicationContext, true)
                return Result.success()
            }
            val job = coroutineContext[kotlinx.coroutines.Job]
            PersonalArchive(applicationContext)
                .syncBackground(AnalysisBudget(applicationContext, { job?.isActive != false }))
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: AnalysisSliceEnded) {
            PersonalArchive.enqueue(applicationContext, true)
            Result.success()
        } catch (_: AnalysisDeferred) {
            Result.retry()
        } catch (_: Exception) {
            Result.failure()
        }
    }
}
