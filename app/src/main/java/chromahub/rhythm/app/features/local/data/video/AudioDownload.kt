// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.video

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.work.*
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.util.MediaUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import java.util.concurrent.TimeUnit
import java.io.File

object AudioDownload {
    private val enqueueLock=Mutex()
    const val MAX_BYTES=128L*1024*1024
    internal fun choose(streams:List<AudioStream>,service:String="YOUTUBE"):AudioStream = streams.filter { stream ->
        stream.isUrl && stream.deliveryMethod==org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP && stream.format?.suffix in setOf("m4a","mp4","webm","opus","ogg","mp3") && runCatching {
            val url=java.net.URI(stream.content);url.scheme=="https" && (if(service=="SOUNDCLOUD") (url.host?.endsWith(".sndcdn.com")==true || url.host?.endsWith(".soundcloud.com")==true) else url.host?.endsWith(".googlevideo.com")==true)
        }.getOrDefault(false)
    }.sortedWith(compareBy<AudioStream> { if(it.format?.suffix in setOf("m4a","mp4"))0 else 1 }
        .thenBy { kotlin.math.abs((it.averageBitrate.takeIf { rate->rate>0 } ?: 128)-128) }).firstOrNull() ?: error("No audio stream")
    suspend fun enqueue(context:Context,sourceId:String,title:String,artist:String="",album:String="",mobile:Boolean=false) =
        enqueueSource(context,"https://www.youtube.com/watch?v=$sourceId",title,artist,album,mobile)
    suspend fun enqueueSource(context:Context,url:String,title:String,artist:String="",album:String="",mobile:Boolean=false) = withContext(Dispatchers.IO+NonCancellable) { enqueueLock.withLock {
        val service=chromahub.rhythm.app.features.local.data.discovery.AudioSources.service(url)
        val sourceId=if(service=="YOUTUBE")MusicVideoLinks.youtubeId(url)!! else url.trim()
        val manager=WorkManager.getInstance(context);val name="audio_source:${kr.co.metadata.crawler.CatalogFileStore.hash(sourceId)}"
        if(manager.getWorkInfosForUniqueWork(name).get(15,TimeUnit.SECONDS).any { !it.state.isFinished })return@withLock
        val completed=DownloadRegistry.recent(context,1000).firstOrNull { it.kind=="AUDIO" && it.sourceId==sourceId && it.state=="SUCCEEDED" }
        if(completed!=null && DownloadedAudioLibrary.existing(context,completed)!=null)return@withLock
        val request=OneTimeWorkRequestBuilder<AudioDownloadWorker>().addTag(DownloadRegistry.TAG)
            .setInputData(workDataOf("id" to sourceId)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if(mobile)NetworkType.CONNECTED else NetworkType.UNMETERED).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()).build()
        DownloadRegistry.save(context,DownloadRecord(request.id.toString(),"AUDIO",name,sourceId,title,artist,album=album,service=service))
        manager.enqueueUniqueWork(name,ExistingWorkPolicy.KEEP,request).result.get(15,TimeUnit.SECONDS)
    } }
}
class AudioDownloadWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result = withContext(Dispatchers.IO) { DownloadSlots.run(applicationContext) {
        val taskId=id.toString();val record=DownloadRegistry.get(applicationContext,taskId) ?: return@run Result.failure()
        if(record.state=="CANCELLED")return@run Result.success()
        DownloadedAudioLibrary.existing(applicationContext,record)?.let { existing ->
            DownloadRegistry.update(applicationContext,taskId) { it.copy(state="SUCCEEDED",uri=existing.first.toString(),fileBytes=existing.second) }
            return@run Result.success()
        }
        try {
            setForeground(MediaTransfer.foreground(applicationContext,record))
            DownloadRegistry.update(applicationContext,taskId) { it.copy(state="RUNNING",error=null) }
            var duration=0L;var suffix=""
            val source=if(record.service=="DIRECT")record.sourceId else {
                YouTubeVideoDownload.initialize()
                val info=YouTubeVideoDownload.extract {
                    StreamInfo.getInfo(if(record.service=="SOUNDCLOUD")ServiceList.SoundCloud else ServiceList.YouTube,
                        if(record.service=="SOUNDCLOUD")record.sourceId else "https://www.youtube.com/watch?v=${record.sourceId}")
                }
                val stream=AudioDownload.choose(info.audioStreams,record.service)
                duration=info.duration*1000;suffix=stream.format?.suffix.orEmpty();stream.content
            }
            val temporary=MediaTransfer.fetch(applicationContext,record,source,"audio:${record.sourceId}:$suffix",AudioDownload.MAX_BYTES) { bytes,total ->
                DownloadRegistry.update(applicationContext,taskId) { it.copy(bytes=bytes,totalBytes=total) }
                setProgress(workDataOf("bytes" to bytes,"total" to total))
            }
            currentCoroutineContext().ensureActive();check(DownloadRegistry.get(applicationContext,taskId)?.state!="CANCELLED")
            val published=YouTubeVideoDownload.installation.withLock {
                DownloadedAudioLibrary.publish(applicationContext,record,temporary,suffix,duration)
            }
            DownloadRegistry.update(applicationContext,taskId) { it.copy(state="SUCCEEDED",bytes=temporary.length(),totalBytes=temporary.length(),uri=published.first.toString(),fileBytes=published.second) }
            MediaTransfer.discard(applicationContext,taskId)
            Result.success()
        } catch(cancel:CancellationException) {
            if(DownloadRegistry.get(applicationContext,taskId)?.state=="CANCELLED")MediaTransfer.discard(applicationContext,taskId)
            else DownloadRegistry.update(applicationContext,taskId) { if(it.state=="SUCCEEDED")it else it.copy(state="ENQUEUED") }
            throw cancel
        } catch(error:Exception) {
            if(DownloadRegistry.get(applicationContext,taskId)?.state=="CANCELLED") {MediaTransfer.discard(applicationContext,taskId);return@run Result.success()}
            val retry=error is java.io.IOException && runAttemptCount<3
            DownloadRegistry.update(applicationContext,taskId) { it.copy(state=if(retry)"ENQUEUED" else "FAILED",error=error.javaClass.simpleName) }
            if(retry)Result.retry() else { MediaTransfer.discard(applicationContext,taskId);Result.failure() }
        }
    } }
}
internal object DownloadedAudioLibrary {
    internal fun filename(title:String,artist:String,id:String,extension:String):String {
        val raw=(title+" - "+artist).replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"),"_").trim().ifBlank { "T2Rhythm" }
        val base=buildString { for(point in raw.codePoints().toArray()) {
            val next=String(Character.toChars(point))
            if((toString()+next).toByteArray(Charsets.UTF_8).size>140)break
            append(next)
        } }
        return "$base-$id.$extension"
    }
    fun existing(context:Context,record:DownloadRecord):Pair<Uri,Long>? = runCatching {
        val uri=Uri.parse(record.uri ?: return null)
        if(Build.VERSION.SDK_INT<29) {
            val root=File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),"T2Rhythm")
            val file=File(uri.path!!)
            require(uri.scheme=="file" && file.canonicalFile.parentFile==root.canonicalFile && file.name.contains("-${record.id}.") && file.length()>0)
            uri to file.length()
        } else {
            require(uri.scheme=="content" && uri.authority=="media")
            context.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.OWNER_PACKAGE_NAME,MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.SIZE),null,null,null)!!.use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0).contains("-${record.id}.") && cursor.getString(1)=="Music/T2Rhythm/" && cursor.getString(2)==context.packageName && cursor.getInt(3)==0 && cursor.getLong(4)>0)
                uri to cursor.getLong(4)
            }
        }
    }.getOrNull()
    suspend fun publish(context:Context,record:DownloadRecord,source:File,suffix:String,duration:Long):Pair<Uri,Long> = withContext(Dispatchers.IO) {
        // Validate actual content before publishing a permanent library entry.
        val extractor=android.media.MediaExtractor()
        try {
            extractor.setDataSource(source.path)
            val types=(0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME).orEmpty() }
            check(types.any { it.startsWith("audio/") } && types.none { it.startsWith("video/") }) { "Select an audio-only source" }
        }
        finally { extractor.release() }
        val header=ByteArray(16);source.inputStream().use { it.read(header) }
        val extension=when {
            String(header,4,4,Charsets.US_ASCII)=="ftyp" -> "m4a"
            String(header,0,4,Charsets.US_ASCII)=="fLaC" -> "flac"
            String(header,0,4,Charsets.US_ASCII)=="OggS" -> "ogg"
            String(header,0,4,Charsets.US_ASCII)=="RIFF" -> "wav"
            (header[0].toInt() and 255)==0x1a && (header[1].toInt() and 255)==0x45 -> "webm"
            suffix=="mp3" || String(header,0,3,Charsets.US_ASCII)=="ID3" -> "mp3"
            (header[0].toInt() and 255)==255 -> if((header[1].toInt() and 0xf6)==0xf0) "aac" else "mp3"
            else -> error("Unsupported audio container")
        }
        val mime=when(extension) { "m4a"->"audio/mp4";"ogg"->"audio/ogg";"mp3"->"audio/mpeg";"wav"->"audio/wav";"flac"->"audio/flac";"aac"->"audio/aac";else->"audio/webm" }
        val name=filename(record.title,record.artist,record.id,extension)
        if(Build.VERSION.SDK_INT<29) {
            check(androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.WRITE_EXTERNAL_STORAGE)==android.content.pm.PackageManager.PERMISSION_GRANTED)
            val directory=File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),"T2Rhythm").apply { mkdirs() }
            val destination=File(directory,name);val pending=File(directory,".$name.part")
            try {
                source.inputStream().use { input -> pending.outputStream().use { output ->
                    val buffer=ByteArray(64*1024)
                    while(true) { currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;output.write(buffer,0,n) }
                    output.fd.sync()
                } }
                withContext(NonCancellable) {
                    check(pending.renameTo(destination))
                    DownloadRegistry.update(context,record.id) { it.copy(state="SUCCEEDED",uri=Uri.fromFile(destination).toString(),fileBytes=destination.length()) }
                }
            } finally { pending.delete() }
            android.media.MediaScannerConnection.scanFile(context,arrayOf(destination.path),arrayOf(mime),null)
            return@withContext Uri.fromFile(destination) to destination.length()
        }
        val resolver=context.contentResolver
        val values=ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME,name);put(MediaStore.Audio.Media.MIME_TYPE,mime)
            put(MediaStore.Audio.Media.TITLE,record.title);put(MediaStore.Audio.Media.ARTIST,record.artist);put(MediaStore.Audio.Media.ALBUM,record.album)
            put(MediaStore.Audio.Media.RELATIVE_PATH,"Music/T2Rhythm/");put(MediaStore.Audio.Media.IS_MUSIC,1);put(MediaStore.Audio.Media.IS_PENDING,1)
        }
        // Recover only this app's own pending entry, identified by the full job UUID.
        val previous=resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID,MediaStore.MediaColumns.OWNER_PACKAGE_NAME,MediaStore.MediaColumns.IS_PENDING),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",arrayOf(name,"Music/T2Rhythm/"),null)?.use { cursor ->
            var owned:Uri?=null
            while(cursor.moveToNext())if(cursor.getString(1)==context.packageName) { owned=android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,cursor.getLong(0));break }
            owned
        }
        val uri=previous ?: resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values) ?: error("MediaStore insert failed")
        val recovered=record.copy(uri=uri.toString())
        existing(context,recovered)?.let { return@withContext it }
        DownloadRegistry.update(context,record.id) { it.copy(uri=uri.toString()) }
        var completed=false
        try {
            resolver.openOutputStream(uri,"w")!!.use { output -> source.inputStream().use { input ->
                val buffer=ByteArray(64*1024)
                while(true) { currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;output.write(buffer,0,n) }
            } }
            val song=Song(uri.lastPathSegment.orEmpty(),record.title,record.artist,record.album,duration=duration,uri=uri)
            // Existing TagLib metadata path handles app-owned MediaStore files without a new permission flow.
            runCatching { MediaUtils.updateSongMetadata(context,song,record.title,record.artist,record.album,"",newTrackNumber=0) }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                check(resolver.update(uri,ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING,0) },null,null)>0)
                completed=true
                val size=resolver.openAssetFileDescriptor(uri,"r")?.use { it.length } ?: source.length()
                DownloadRegistry.update(context,record.id) { it.copy(state="SUCCEEDED",uri=uri.toString(),fileBytes=size) }
                uri to size
            }
        } finally { if(!completed)resolver.delete(uri,null,null) }
    }
}
