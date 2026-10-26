/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import chromahub.rhythm.app.shared.data.model.Playlist
import chromahub.rhythm.app.shared.data.model.Song
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.text.SimpleDateFormat
import java.util.*

// Marker exception used to signal a Rhythm backup file was selected in the single-playlist import flow
class RhythmBackupDetectedException(val backupJson: String) : Exception("Rhythm backup file detected")

/**
 * Utility class for importing and exporting playlists in various formats
 */
object PlaylistImportExportUtils {
    private const val TAG = "PlaylistImportExport"
    
    /**
     * Builds a map of file paths to songs by querying MediaStore.
     * This is essential for M3U/PLS import since content URIs don't contain file paths.
     */
    private fun buildFilePathToSongMap(context: Context, availableSongs: List<Song>): Map<String, Song> {
        val pathToSong = mutableMapOf<String, Song>()
        val songIdToSong = availableSongs.associateBy { it.id }
        
        try {
            val projection = arrayOf(
                android.provider.MediaStore.Audio.Media._ID,
                android.provider.MediaStore.Audio.Media.DATA
            )
            
            context.contentResolver.query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID)
                val dataIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)
                
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex).toString()
                    val filePath = cursor.getString(dataIndex) ?: continue
                    
                    songIdToSong[id]?.let { song ->
                        // Store both original path and lowercase version for case-insensitive matching
                        pathToSong[filePath] = song
                        pathToSong[filePath.lowercase(Locale.ROOT)] = song
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error building file path map", e)
        }
        
        Log.d(TAG, "Built file path map with ${pathToSong.size / 2} unique entries")
        return pathToSong
    }
    
    /**
     * Gets the directory path from the M3U file URI for resolving relative paths.
     */
    private fun getM3uDirectoryPath(context: Context, m3uUri: Uri): String? {
        return try {
            when (m3uUri.scheme) {
                "content" -> {
                    // For content URIs, try to get the path from DocumentFile
                    val documentFile = DocumentFile.fromSingleUri(context, m3uUri)
                    val fileName = documentFile?.name ?: return null
                    
                    // Try to get the actual file path via MediaStore
                    context.contentResolver.query(m3uUri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            // Try DATA column if available
                            val dataIndex = cursor.getColumnIndex("_data")
                            if (dataIndex >= 0) {
                                val path = cursor.getString(dataIndex)
                                if (!path.isNullOrEmpty()) {
                                    return@use java.io.File(path).parent
                                }
                            }
                        }
                        null
                    }
                }
                "file" -> {
                    m3uUri.path?.let { java.io.File(it).parent }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting M3U directory path", e)
            null
        }
    }
    
    enum class PlaylistExportFormat(val extension: String, val mimeType: String, val displayName: String) {
        JSON(".json", "application/json", "JSON Format"),
        M3U(".m3u", "audio/x-mpegurl", "M3U Playlist"),
        M3U8(".m3u8", "application/x-mpegURL", "M3U8 Playlist"),
        PLS(".pls", "audio/x-scpls", "PLS Playlist")
    }
    
    data class PlaylistExportData(
        @SerializedName("name") val name: String,
        @SerializedName("id") val id: String,
        @SerializedName("dateCreated") val dateCreated: Long,
        @SerializedName("dateModified") val dateModified: Long,
        @SerializedName("songs") val songs: List<PlaylistSongEntry>,
        @SerializedName("exportedAt") val exportedAt: Long = System.currentTimeMillis(),
        @SerializedName("exportedBy") val exportedBy: String = "Rhythm Music Player"
    )
    
    data class PlaylistSongEntry(
        @SerializedName("title") val title: String,
        @SerializedName("artist") val artist: String,
        @SerializedName("album") val album: String,
        @SerializedName("duration") val duration: Long,
        @SerializedName("filePath") val filePath: String?, // Original file path if available
        @SerializedName("uri") val uri: String, // Content URI
        @SerializedName("trackNumber") val trackNumber: Int = 0,
        @SerializedName("year") val year: Int = 0
    )
    
    /**
     * Exports a single playlist to the specified format
     */
    fun exportPlaylist(
        context: Context,
        playlist: Playlist,
        format: PlaylistExportFormat,
        outputDirectory: File? = null,
        userSelectedDirectoryUri: Uri? = null
    ): Result<File> {
        return try {
            val fileName = sanitizeFileName("${playlist.name}_${getCurrentTimestamp()}${format.extension}")
            
            // Use user-selected directory if provided, otherwise use default
            val outputFile = if (userSelectedDirectoryUri != null) {
                // Use Storage Access Framework for user-selected directory
                exportToUserSelectedDirectory(context, userSelectedDirectoryUri, playlist, format, fileName)
            } else {
                // Use traditional file system approach
                val exportDir = outputDirectory ?: getDefaultExportDirectory(context)
                if (!exportDir.exists()) {
                    exportDir.mkdirs()
                }
                val file = File(exportDir, fileName)
                
                when (format) {
                    PlaylistExportFormat.JSON -> exportToJson(playlist, file)
                    PlaylistExportFormat.M3U -> exportToM3u(playlist, file, false)
                    PlaylistExportFormat.M3U8 -> exportToM3u(playlist, file, true)
                    PlaylistExportFormat.PLS -> exportToPls(playlist, file)
                }
                file
            }
            
            Log.d(TAG, "Successfully exported playlist '${playlist.name}' to ${outputFile.absolutePath}")
            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting playlist '${playlist.name}'", e)
            Result.failure(e)
        }
    }
    
    /**
     * Exports multiple playlists to a single ZIP file
     */
    fun exportAllPlaylists(
        context: Context,
        playlists: List<Playlist>,
        format: PlaylistExportFormat,
        outputDirectory: File? = null,
        userSelectedDirectoryUri: Uri? = null
    ): Result<File> {
        return try {
            val fileName = "RhythmPlaylists_${getCurrentTimestamp()}.zip"
            
            // Use user-selected directory if provided, otherwise use default
            val zipFile = if (userSelectedDirectoryUri != null) {
                // Use Storage Access Framework for user-selected directory
                exportAllPlaylistsToUserSelectedDirectory(context, userSelectedDirectoryUri, playlists, format, fileName)
            } else {
                // Use traditional file system approach
                val exportDir = outputDirectory ?: getDefaultExportDirectory(context)
                if (!exportDir.exists()) {
                    exportDir.mkdirs()
                }
                val file = File(exportDir, fileName)
                createPlaylistsZip(playlists, format, file)
                file
            }
            
            Log.d(TAG, "Successfully exported ${playlists.size} playlists to ${zipFile.absolutePath}")
            Result.success(zipFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting all playlists", e)
            Result.failure(e)
        }
    }
    
    /**
     * Returns true when the given JSON string is a Rhythm app backup
     * (i.e. it contains the "backup_version" and "playlists_data" keys).
     */
    fun isRhythmBackupJson(json: String): Boolean {
        return try {
            val map = GsonUtils.gson.fromJson(json, Map::class.java) ?: return false
            map.containsKey("backup_version") && map.containsKey("playlists_data")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Imports ALL playlists from a Rhythm app backup JSON.
     *
     * A Rhythm backup stores playlists as a `List<Playlist>` JSON string under the
     * `playlists_data` key.  Each song's URI is matched against [availableSongs] so that
     * only locally-present tracks are kept.
     */
    fun importPlaylistsFromRhythmBackup(
        context: Context,
        backupJson: String,
        availableSongs: List<Song>
    ): Result<List<Playlist>> {
        return try {
            val backupMap = GsonUtils.gson.fromJson(backupJson, Map::class.java)
                ?: return Result.failure(IllegalArgumentException("Invalid Rhythm backup JSON"))

            val playlistsData = backupMap["playlists_data"] as? String
                ?: return Result.failure(IllegalArgumentException("No playlist data found in backup"))

            val type = object : TypeToken<List<Playlist>>() {}.type
            val rawPlaylists: List<Playlist> = GsonUtils.gson.fromJson(playlistsData, type)
                ?: return Result.failure(IllegalArgumentException("Failed to parse playlists from backup"))

            // Build a URI -> Song lookup for fast matching
            val songByUri = availableSongs.associateBy { it.uri.toString() }

            val restoredPlaylists = rawPlaylists.map { playlist ->
                val matchedSongs = playlist.songs.mapNotNull { song ->
                    // Primary: match by URI (fast, exact)
                    songByUri[song.uri.toString()]
                        // Fallback: match by title + artist (handles URI changes across reinstalls)
                        ?: availableSongs.find { available ->
                            available.title.equals(song.title, ignoreCase = true) &&
                            available.artist.equals(song.artist, ignoreCase = true)
                        }
                }
                playlist.copy(songs = matchedSongs)
            }

            Log.d(TAG, "Imported ${restoredPlaylists.size} playlists from Rhythm backup")
            Result.success(restoredPlaylists)
        } catch (e: Exception) {
            Log.e(TAG, "Error importing playlists from Rhythm backup", e)
            Result.failure(e)
        }
    }

    /**
     * Imports a playlist from a file.
     *
     * If the selected file is a full Rhythm app backup (detected via [isRhythmBackupJson]),
     * a [RhythmBackupDetectedException] is thrown so that the caller can redirect to
     * [importPlaylistsFromRhythmBackup] for batch import.
     */
    fun importPlaylist(
        context: Context,
        uri: Uri,
        availableSongs: List<Song>
    ): Result<Playlist> {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(IllegalArgumentException("Cannot open file"))
            
            val content = inputStream.bufferedReader().use { it.readText() }
            if (content.isBlank()) {
                return Result.failure(IllegalArgumentException("Selected file is empty"))
            }
            val fileName = getFileName(context, uri)
            
            // Detect Rhythm backup files early so the ViewModel can handle batch import
            val trimmedContent = content.trim()
            if ((fileName.endsWith(".json", true) || (trimmedContent.startsWith("{") && trimmedContent.endsWith("}"))) &&
                isRhythmBackupJson(trimmedContent)) {
                throw RhythmBackupDetectedException(trimmedContent)
            }

            val filePathMap = buildFilePathToSongMap(context, availableSongs)
            val m3uDirectory = getM3uDirectoryPath(context, uri)
            
            Log.d(TAG, "Importing playlist: $fileName, M3U directory: $m3uDirectory")
            
            val resolvedFormat = when {
                fileName.endsWith(".json", true) || (trimmedContent.startsWith("{") && trimmedContent.endsWith("}")) -> "json"
                fileName.endsWith(".m3u", true) || fileName.endsWith(".m3u8", true) || trimmedContent.startsWith("#EXTM3U", true) -> "m3u"
                fileName.endsWith(".pls", true) || trimmedContent.startsWith("[playlist]", true) -> "pls"
                else -> null
            }
            
            val playlist = when (resolvedFormat) {
                "json" -> importFromJson(content, availableSongs)
                "m3u" -> importFromM3u(content, fileName, availableSongs, filePathMap, m3uDirectory)
                "pls" -> importFromPls(content, fileName, availableSongs, filePathMap, m3uDirectory)
                else -> return Result.failure(IllegalArgumentException("Unsupported or unrecognized file format"))
            }
            
            Log.d(TAG, "Successfully imported playlist '${playlist.name}' with ${playlist.songs.size} songs")
            Result.success(playlist)
        } catch (e: RhythmBackupDetectedException) {
            // Re-throw so the ViewModel can redirect to batch import
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error importing playlist from $uri", e)
            Result.failure(e)
        }
    }
    
    private fun exportToJson(playlist: Playlist, outputFile: File) {
        val exportData = PlaylistExportData(
            name = playlist.name,
            id = playlist.id,
            dateCreated = playlist.dateCreated,
            dateModified = playlist.dateModified,
            songs = playlist.songs.map { song ->
                PlaylistSongEntry(
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    duration = song.duration,
                    filePath = song.uri.path,
                    uri = song.uri.toString(),
                    trackNumber = song.trackNumber,
                    year = song.year
                )
            }
        )
        
        val json = GsonUtils.gson.toJson(exportData)
        outputFile.writeText(json)
    }
    
    private fun exportToM3u(playlist: Playlist, outputFile: File, extended: Boolean) {
        outputFile.bufferedWriter().use { writer ->
            if (extended) {
                writer.write("#EXTM3U\n")
            }
            
            playlist.songs.forEach { song ->
                if (extended) {
                    writer.write("#EXTINF:${song.duration / 1000},${song.artist} - ${song.title}\n")
                }
                writer.write("${song.uri}\n")
            }
        }
    }
    
    private fun exportToPls(playlist: Playlist, outputFile: File) {
        outputFile.bufferedWriter().use { writer ->
            writer.write("[playlist]\n")
            
            playlist.songs.forEachIndexed { index, song ->
                val num = index + 1
                writer.write("File$num=${song.uri}\n")
                writer.write("Title$num=${song.artist} - ${song.title}\n")
                writer.write("Length$num=${song.duration / 1000}\n")
            }
            
            writer.write("NumberOfEntries=${playlist.songs.size}\n")
            writer.write("Version=2\n")
        }
    }
    
    private fun importFromJson(content: String, availableSongs: List<Song>): Playlist {
        val exportData = GsonUtils.gson.fromJson(content, PlaylistExportData::class.java)
            ?: throw IllegalArgumentException("Invalid playlist JSON format")
        
        val songsList = exportData.songs
        val playlistName = exportData.name.takeIf { !it.isNullOrBlank() } ?: "Imported Playlist"
        
        val songMap = availableSongs.associateBy { it.uri.toString() }
        val addedSongUris = mutableSetOf<String>()
        val addedSongKeys = mutableSetOf<String>()
        var skippedInvalid = false
        
        val matchedSongs = songsList.mapNotNull { entry ->
            // Gson may inject LinkedTreeMap at runtime despite compile-time type
            @Suppress("USELESS_IS_CHECK")
            if (entry !is PlaylistSongEntry) {
                if (!skippedInvalid) {
                    Log.w(TAG, "Skipping invalid song entry (wrong type: ${entry.javaClass.simpleName}) — likely a ProGuard/Gson deserialization mismatch")
                    skippedInvalid = true
                }
                return@mapNotNull null
            }
            val title = entry.title.trim()
            val artist = entry.artist.trim()
            val uriStr = entry.uri
            
            val songKey = "${title.lowercase(Locale.ROOT)}_${artist.lowercase(Locale.ROOT)}"
            
            if (addedSongUris.contains(uriStr) || addedSongKeys.contains(songKey)) {
                Log.d(TAG, "Skipping duplicate song: $title by $artist")
                return@mapNotNull null
            }
            
            val matchedSong = songMap[uriStr] ?: 
                availableSongs.find { 
                    it.title.equals(title, ignoreCase = true) && 
                    it.artist.equals(artist, ignoreCase = true) 
                }
            
            matchedSong?.let {
                addedSongUris.add(it.uri.toString())
                addedSongKeys.add("${it.title.trim().lowercase(Locale.ROOT)}_${it.artist.trim().lowercase(Locale.ROOT)}")
            }
            
            matchedSong
        }
        
        return Playlist(
            id = System.currentTimeMillis().toString(),
            name = playlistName,
            songs = matchedSongs,
            dateCreated = System.currentTimeMillis(),
            dateModified = System.currentTimeMillis()
        )
    }
    
    private fun importFromM3u(
        content: String, 
        fileName: String, 
        availableSongs: List<Song>,
        filePathMap: Map<String, Song>,
        m3uDirectory: String?
    ): Playlist {
        val matchedSongs = matchPlaylistEntries(PlaylistTextParser.m3u(content), availableSongs, filePathMap, m3uDirectory)

        Log.d(TAG, "M3U import completed: ${matchedSongs.size} songs imported from ${fileName}")
        return Playlist(
            id = System.currentTimeMillis().toString(),
            name = fileName.substringBeforeLast("."),
            songs = matchedSongs,
            dateCreated = System.currentTimeMillis(),
            dateModified = System.currentTimeMillis()
        )
    }
    
    private fun importFromPls(
        content: String, 
        fileName: String, 
        availableSongs: List<Song>,
        filePathMap: Map<String, Song>,
        m3uDirectory: String?
    ): Playlist {
        val matchedSongs = matchPlaylistEntries(PlaylistTextParser.pls(content), availableSongs, filePathMap, m3uDirectory)

        return Playlist(
            id = System.currentTimeMillis().toString(),
            name = fileName.substringBeforeLast("."),
            songs = matchedSongs,
            dateCreated = System.currentTimeMillis(),
            dateModified = System.currentTimeMillis()
        )
    }
    
    private fun matchPlaylistEntries(
        entries: List<PlaylistTextEntry>, availableSongs: List<Song>,
        filePathMap: Map<String, Song>, directory: String?,
    ): List<Song> {
        // Build once per import, rather than once for every unmatched playlist entry.
        val songsByUri = availableSongs.associateBy { it.uri.toString() }
        val fileNames = filePathMap.entries.filter { it.key.contains("/") }
            .groupBy { File(it.key).name.lowercase(Locale.ROOT) }
        val seen = HashSet<String>()
        return entries.mapNotNull { entry ->
            findSongByPathOrTitle(entry.path, entry.title, availableSongs, filePathMap, directory, songsByUri, fileNames)
                ?.takeIf { seen.add(it.uri.toString()) }
        }
    }

    private fun findSongByPathOrTitle(
        path: String, 
        title: String, 
        availableSongs: List<Song>,
        filePathMap: Map<String, Song>,
        m3uDirectory: String?,
        songsByUri: Map<String, Song>,
        fileNameToSongs: Map<String, List<Map.Entry<String, Song>>>,
    ): Song? {
        // Try exact URI match first (for content:// URIs exported by Rhythm)
        songsByUri[path]?.let {
            Log.d(TAG, "Found song by exact URI match: $path")
            return it 
        }
        
        // Try exact file path match from the pre-built map
        filePathMap[path]?.let {
            Log.d(TAG, "Found song by exact file path match: $path")
            return it
        }
        
        // Try case-insensitive file path match
        filePathMap[path.lowercase(Locale.ROOT)]?.let {
            Log.d(TAG, "Found song by case-insensitive file path match: $path")
            return it
        }
        
        // Handle relative paths - resolve relative to M3U file directory
        if (!path.startsWith("/") && m3uDirectory != null) {
            val absolutePath = java.io.File(m3uDirectory, path).canonicalPath
            filePathMap[absolutePath]?.let {
                Log.d(TAG, "Found song by resolved relative path: $path -> $absolutePath")
                return it
            }
            filePathMap[absolutePath.lowercase(Locale.ROOT)]?.let {
                Log.d(TAG, "Found song by resolved relative path (case-insensitive): $path -> $absolutePath")
                return it
            }
        }
        
        // Handle partial paths like "/Music/filename" - try common storage prefixes
        if (path.startsWith("/") && !path.startsWith("/storage")) {
            val commonPrefixes = listOf(
                "/storage/emulated/0",
                "/storage/sdcard0",
                // /sdcard is a legacy symlink to the primary external storage dir
                // (resolved below via Environment), so it is not listed explicitly
                android.os.Environment.getExternalStorageDirectory().absolutePath
            )
            
            for (prefix in commonPrefixes) {
                val absolutePath = prefix + path
                filePathMap[absolutePath]?.let {
                    Log.d(TAG, "Found song by prefix resolution: $path -> $absolutePath")
                    return it
                }
                filePathMap[absolutePath.lowercase(Locale.ROOT)]?.let {
                    Log.d(TAG, "Found song by prefix resolution (case-insensitive): $path -> $absolutePath")
                    return it
                }
            }
        }
        
        // Build a map of filenames to songs for fallback matching
        
        // Try file name match (basename with extension)
        val fileName = path.substringAfterLast("/").substringAfterLast("\\")
        if (fileName.isNotBlank()) {
            fileNameToSongs[fileName.lowercase(Locale.ROOT)]?.firstOrNull()?.let { entry ->
                Log.d(TAG, "Found song by filename match: $fileName")
                return entry.value
            }
        }
        
        // Try file name without extension
        val fileNameNoExt = fileName.substringBeforeLast(".")
        if (fileNameNoExt.isNotBlank()) {
            fileNameToSongs.entries.find { (key, _) ->
                key.substringBeforeLast(".") == fileNameNoExt.lowercase(Locale.ROOT)
            }?.value?.firstOrNull()?.let { entry ->
                Log.d(TAG, "Found song by filename (no extension) match: $fileNameNoExt")
                return entry.value
            }
        }
        
        // Try fuzzy filename match (handles URL encoding, underscores vs spaces, etc.)
        val normalizedFileName = fileNameNoExt.replace("_", " ").replace("%20", " ").replace("-", " ").lowercase(Locale.ROOT)
        if (normalizedFileName.isNotBlank()) {
            fileNameToSongs.entries.find { (key, _) ->
                val normalizedKey = key.substringBeforeLast(".")
                    .replace("_", " ").replace("%20", " ").replace("-", " ")
                normalizedKey == normalizedFileName
            }?.value?.firstOrNull()?.let { entry ->
                Log.d(TAG, "Found song by normalized filename match: $normalizedFileName")
                return entry.value
            }
        }
        
        // Try matching by path segments (handles different root paths but same folder structure)
        val pathSegments = path.split("/", "\\").filter { it.isNotBlank() }
        if (pathSegments.size >= 2) {
            val lastTwoSegments = pathSegments.takeLast(2).map { it.lowercase(Locale.ROOT) }
            filePathMap.entries.find { (filePath, _) ->
                val songSegments = filePath.split("/").filter { it.isNotBlank() }.map { it.lowercase(Locale.ROOT) }
                songSegments.takeLast(2) == lastTwoSegments
            }?.let { entry ->
                Log.d(TAG, "Found song by path segments match: ${pathSegments.takeLast(2)}")
                return entry.value
            }
        }
        
        // Try title match from EXTINF metadata
        if (title.isNotBlank()) {
            // Try exact title match first
            availableSongs.find { song ->
                song.title.equals(title, ignoreCase = true)
            }?.let { 
                Log.d(TAG, "Found song by exact title match: $title")
                return it 
            }
            
            // Try "Artist - Title" format match
            val cleanTitle = title.trim()
            availableSongs.find { song ->
                val artistTitle = "${song.artist} - ${song.title}"
                artistTitle.equals(cleanTitle, ignoreCase = true)
            }?.let { 
                Log.d(TAG, "Found song by artist-title match: $cleanTitle")
                return it 
            }
            
            // Try reversed "Title - Artist" format match (some M3Us use this)
            availableSongs.find { song ->
                val titleArtist = "${song.title} - ${song.artist}"
                titleArtist.equals(cleanTitle, ignoreCase = true)
            }?.let { 
                Log.d(TAG, "Found song by title-artist match: $cleanTitle")
                return it 
            }
            
            // Try partial title match
            availableSongs.find { song ->
                cleanTitle.contains(song.title, ignoreCase = true) || 
                song.title.contains(cleanTitle, ignoreCase = true)
            }?.let { 
                Log.d(TAG, "Found song by partial title match: $cleanTitle")
                return it 
            }
        }
        
        Log.d(TAG, "No match found for path: $path" + if (title.isNotBlank()) " (title: $title)" else "")
        return null
    }
    
    private fun createPlaylistsZip(playlists: List<Playlist>, format: PlaylistExportFormat, zipFile: File) {
        // Create a temp directory to hold individual playlist files
        val tempDir = java.nio.file.Files.createTempDirectory(zipFile.absoluteFile.parentFile.toPath(), "playlists-").toFile()

        try {
            // Write each playlist to a temp file
            val tempFiles = mutableListOf<File>()
            val names = allocatePlaylistExportNames(playlists.map { sanitizeFileName("${it.name}${format.extension}") })
            playlists.forEachIndexed { index, playlist ->
                val fileName = names[index]
                val tempFile = File(tempDir, fileName)

                when (format) {
                    PlaylistExportFormat.JSON -> exportToJson(playlist, tempFile)
                    PlaylistExportFormat.M3U -> exportToM3u(playlist, tempFile, false)
                    PlaylistExportFormat.M3U8 -> exportToM3u(playlist, tempFile, true)
                    PlaylistExportFormat.PLS -> exportToPls(playlist, tempFile)
                }

                tempFiles.add(tempFile)
            }

            // Create zip output stream and add each temp file as an entry
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                tempFiles.forEach { file ->
                    FileInputStream(file).use { fis ->
                        val entry = ZipEntry(file.name)
                        entry.time = file.lastModified()
                        zos.putNextEntry(entry)
                        fis.copyTo(zos)
                        zos.closeEntry()
                    }
                }
            }

            Log.d(TAG, "Created zip with ${playlists.size} playlists: ${zipFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating playlists zip", e)
            // If zip creation failed, ensure partial zip is removed
            try { zipFile.delete() } catch (_: Exception) {}
            throw e
        } finally {
            // Clean up temp files and directory
            try {
                tempDir.listFiles()?.forEach { it.delete() }
                tempDir.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean temp files: ${e.message}")
            }
        }
    }
    
    private fun getDefaultExportDirectory(context: Context): File {
        return File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Playlists")
    }
    
    private fun getCurrentTimestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    }
    
    private fun sanitizeFileName(fileName: String): String {
        val policy = chromahub.rhythm.app.features.local.data.metadata.FileNamePolicy
        val base = policy.base(fileName.substringBeforeLast('.'), null, "TITLE") ?: "Playlist"
        return policy.filename(base, fileName.substringAfterLast('.'))
    }
    
    private fun getFileName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return cursor.getString(nameIndex)
                }
            }
        }
        return uri.lastPathSegment ?: "unknown"
    }
    
    /**
     * Export playlist to user-selected directory using Storage Access Framework
     */
    private fun exportToUserSelectedDirectory(
        context: Context,
        directoryUri: Uri,
        playlist: Playlist,
        format: PlaylistExportFormat,
        fileName: String
    ): File {
        val directory = DocumentFile.fromTreeUri(context, directoryUri)
            ?: throw IllegalArgumentException("Cannot access selected directory")
        
        val documentFile = directory.createFile(format.mimeType, fileName)
            ?: throw IllegalArgumentException("Cannot create file in selected directory")
        
        context.contentResolver.openOutputStream(documentFile.uri)?.use { outputStream ->
            when (format) {
                PlaylistExportFormat.JSON -> {
                    val exportData = PlaylistExportData(
                        name = playlist.name,
                        id = playlist.id,
                        dateCreated = playlist.dateCreated,
                        dateModified = playlist.dateModified,
                        songs = playlist.songs.map { song ->
                            PlaylistSongEntry(
                                title = song.title,
                                artist = song.artist,
                                album = song.album,
                                duration = song.duration,
                                filePath = song.uri.path,
                                uri = song.uri.toString(),
                                trackNumber = song.trackNumber,
                                year = song.year
                            )
                        }
                    )
                    val json = GsonUtils.gson.toJson(exportData)
                    outputStream.write(json.toByteArray())
                }
                PlaylistExportFormat.M3U -> exportM3uToStream(playlist, outputStream, false)
                PlaylistExportFormat.M3U8 -> exportM3uToStream(playlist, outputStream, true)
                PlaylistExportFormat.PLS -> exportPlsToStream(playlist, outputStream)
            }
        } ?: throw IllegalArgumentException("Cannot open output stream for selected file")
        
        // Return a File object for compatibility (though it's not a real file path)
        return File(directory.uri.path ?: "user_selected", fileName)
    }
    
    /**
     * Export all playlists to user-selected directory using Storage Access Framework
     */
    private fun exportAllPlaylistsToUserSelectedDirectory(
        context: Context,
        directoryUri: Uri,
        playlists: List<Playlist>,
        format: PlaylistExportFormat,
        zipFileName: String
    ): File {
        val directory = DocumentFile.fromTreeUri(context, directoryUri)
            ?: throw IllegalArgumentException("Cannot access selected directory")
        
        val archive = File.createTempFile("playlists-", ".zip", context.cacheDir)
        var document: DocumentFile? = null
        try {
            createPlaylistsZip(playlists, format, archive)
            val created = directory.createFile("application/zip", zipFileName)
                ?: throw IOException("Cannot create playlist archive")
            document = created
            val output = context.contentResolver.openOutputStream(created.uri)
                ?: throw IOException("Cannot open playlist archive")
            output.use { stream -> archive.inputStream().use { it.copyTo(stream) } }
            return File(directory.uri.path ?: "user_selected", created.name ?: zipFileName)
        } catch (e: Exception) {
            runCatching { document?.delete() }
            throw e
        } finally {
            archive.delete()
        }
    }
    
    /**
     * Helper to export M3U format to output stream
     */
    private fun exportM3uToStream(playlist: Playlist, outputStream: java.io.OutputStream, extended: Boolean) {
        val writer = outputStream.bufferedWriter()
        if (extended) {
            writer.write("#EXTM3U\n")
        }
        
        playlist.songs.forEach { song ->
            if (extended) {
                writer.write("#EXTINF:${song.duration / 1000},${song.artist} - ${song.title}\n")
            }
            writer.write("${song.uri}\n")
        }
        writer.flush()
    }
    
    /**
     * Helper to export PLS format to output stream
     */
    private fun exportPlsToStream(playlist: Playlist, outputStream: java.io.OutputStream) {
        val writer = outputStream.bufferedWriter()
        writer.write("[playlist]\n")
        
        playlist.songs.forEachIndexed { index, song ->
            val num = index + 1
            writer.write("File$num=${song.uri}\n")
            writer.write("Title$num=${song.artist} - ${song.title}\n")
            writer.write("Length$num=${song.duration / 1000}\n")
        }
        
        writer.write("NumberOfEntries=${playlist.songs.size}\n")
        writer.write("Version=2\n")
        writer.flush()
    }
}
