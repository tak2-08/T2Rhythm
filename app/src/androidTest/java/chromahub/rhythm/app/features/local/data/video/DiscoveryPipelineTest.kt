// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.video

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import chromahub.rhythm.app.features.local.data.discovery.*
import chromahub.rhythm.app.features.local.data.experience.*
import chromahub.rhythm.app.shared.data.model.Song
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class DiscoveryPipelineTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated(root:File)=object:ContextWrapper(context) {
        override fun getFilesDir()=File(root,"files").apply {mkdirs()}
        override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
    }
    private fun task()=DownloadRecord(UUID.randomUUID().toString(),"AUDIO","test","https://test.invalid/audio","테스트 음악","테스트 가수",service="DIRECT")
    @Test fun transferResumesOnlyValidatedRangesAndReusesCompleteFiles()=runBlocking {
        val root=File(context.cacheDir,"transfer-${System.nanoTime()}").apply {mkdirs()};val ctx=isolated(root);val record=task();val data=ByteArray(12000){it.toByte()}
        val transfer=File(ctx.cacheDir,"media_transfers").apply {mkdirs()}
        File(transfer,"${record.id}.part").writeBytes(data.copyOf(4000))
        File(transfer,"${record.id}.json").writeText("""{"identity":"stable","etag":"\"v1\""}""")
        val calls=AtomicInteger()
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet();assertEquals("bytes=4000-",chain.request().header("Range"));assertEquals("\"v1\"",chain.request().header("If-Range"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("Partial")
                .header("ETag","\"v1\"").header("Content-Range","bytes 4000-11999/12000").body(data.copyOfRange(4000,12000).toResponseBody()).build()
        }.build()
        try {
            val file=MediaTransfer.fetch(ctx,record,"https://test.invalid/a","stable",20000,client) {_,_->}
            assertArrayEquals(data,file.readBytes())
            assertEquals(file,MediaTransfer.fetch(ctx,record,"https://test.invalid/new-signed-url","stable",20000,client) {_,_->})
            assertEquals(1,calls.get())
        } finally {root.deleteRecursively()}
    }
    @Test fun transferRejectsTruncationOversizeAndUnsolicitedPartialResponses()=runBlocking {
        val root=File(context.cacheDir,"bad-transfer-${System.nanoTime()}").apply {mkdirs()};val ctx=isolated(root)
        try {
            for(code in listOf(200,206)) {
                val record=task()
                val client=OkHttpClient.Builder().addInterceptor {chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test").body(ByteArray(1001).toResponseBody()).build() }.build()
                assertTrue(runCatching {MediaTransfer.fetch(ctx,record,"https://test.invalid/a","id",1000,client) {_,_->}}.isFailure)
                assertFalse(File(ctx.cacheDir,"media_transfers/${record.id}.json").readTextOrEmpty().contains("completedBytes\":1001"))
            }
        } finally {root.deleteRecursively()}
    }
    @Test fun downloadSlotsBoundParallelismAndReleaseAfterCancellation()=runBlocking {
        val active=AtomicInteger();val maximum=AtomicInteger()
        coroutineScope {
            (1..6).map { async(Dispatchers.IO) {DownloadSlots.run(context) {val now=active.incrementAndGet();maximum.updateAndGet {maxOf(it,now)};try {delay(80)} finally {active.decrementAndGet()}}} }.awaitAll()
        }
        assertTrue(maximum.get() in 1..2);assertEquals(0,active.get())
        val job=launch(Dispatchers.IO) {DownloadSlots.run(context) {delay(10000)}};delay(100);job.cancelAndJoin()
        withTimeout(3000) {DownloadSlots.run(context) {assertTrue(true)}}
    }
    @Test fun audioPublishingIsIdempotentKeepsKoreanNamesAndRejectsVideo()=runBlocking {
        val root=File(context.cacheDir,"publish-${System.nanoTime()}").apply {mkdirs()};val ctx=isolated(root)
        val record=task().copy(title="한글 음악 ".repeat(35));DownloadRegistry.save(ctx,record)
        val source=File(root,"sample.m4a")
        InstrumentationRegistry.getInstrumentation().context.assets.open("audio-test.m4a").use {input->source.outputStream().use {input.copyTo(it)}}
        var uri:Uri?=null
        try {
            val first=DownloadedAudioLibrary.publish(ctx,record,source,"m4a",2000);uri=first.first
            val second=DownloadedAudioLibrary.publish(ctx,record,source,"m4a",2000)
            assertEquals(first.first,second.first);assertTrue(first.second>0)
            assertEquals(first.first,DownloadedAudioLibrary.existing(ctx,DownloadRegistry.get(ctx,record.id)!!)!!.first)
            context.contentResolver.query(first.first,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.IS_PENDING),null,null,null)!!.use {cursor->
                assertTrue(cursor.moveToFirst());assertTrue(cursor.getString(0).toByteArray().size<255);assertTrue(cursor.getString(0).contains(record.id));assertEquals(0,cursor.getInt(1))
            }
            val video=File(root,"video.mp4");InstrumentationRegistry.getInstrumentation().context.assets.open("video-test.mp4").use {input->video.outputStream().use {input.copyTo(it)}}
            assertTrue(runCatching {DownloadedAudioLibrary.publish(ctx,task(),video,"mp4",2000)}.isFailure)
        } finally {uri?.let {context.contentResolver.delete(it,null,null)};root.deleteRecursively()}
    }
    @Test fun proposalHistoryRetainsOriginalsRejectionsAndUndoWithoutNagging() {
        val root=File(context.cacheDir,"proposal-${System.nanoTime()}").apply {mkdirs()};val ctx=isolated(root)
        try {
            val store=EnrichmentStore(ctx);val song=Song("test","노래","가수","앨범",duration=200000,uri=Uri.parse("content://tests/song"))
            val proposal=store.proposal(song,"GENRE","pop",.8,"local_neighbors",4)
            assertTrue(store.propose(proposal));assertFalse(store.propose(proposal))
            store.decide(proposal.id,"ACCEPTED");store.decide(proposal.id,"REVERTED");assertEquals("REVERTED",store.get(proposal.id)!!.state)
            val next=store.proposal(song,"GENRE","rock",.85,"local_neighbors",5);assertTrue(store.propose(next));store.decide(next.id,"REJECTED")
            assertFalse(store.propose(store.proposal(song,"GENRE","jazz",.9,"local_neighbors",6)))
            assertEquals(2,store.list().size)
        } finally {root.deleteRecursively()}
    }
    @Test fun speechPcmWindowsResampleAndDecodeWithoutMicrophone()=runBlocking {
        val file=File(context.cacheDir,"speech-${System.nanoTime()}.m4a")
        InstrumentationRegistry.getInstrumentation().context.assets.open("audio-test.m4a").use {input->file.outputStream().use {input.copyTo(it)}}
        try {
            val song=Song("pcm","PCM","Test","Test",duration=2000,uri=Uri.fromFile(file))
            var samples=0;var finite=true
            withContext(AnalysisBudget.dispatcher) {
                val budget=AnalysisBudget(context,maxMillis=30000)
                val resampler=SpeechResampler {buffer,count ->samples+=count;finite=finite && buffer.take(count).all {it in Short.MIN_VALUE..Short.MAX_VALUE}}
                SpeechChunkDecoder.decode(context,song,500,1500,budget,resampler);resampler.flush()
            }
            assertTrue("Expected about one second at 16kHz: $samples",samples in 15000..17500);assertTrue(finite)
        } finally {file.delete()}
    }
    @Test fun koreanVoskModelLoadsAndSilenceDoesNotInventLyrics()=runBlocking {
        val archive=File(context.getExternalFilesDir(null),"vosk-test-model.zip")
        // Explicit local QA fixture: ordinary CI/device runs need no model download.
        org.junit.Assume.assumeTrue(archive.isFile)
        withContext(AnalysisBudget.dispatcher) {
            if(!SpeechModelStore.installed(context))SpeechModelStore.install(context,archive);assertTrue(SpeechModelStore.installed(context))
            org.vosk.Model(SpeechModelStore.directory(context).path).use {model ->org.vosk.Recognizer(model,16000f).use {recognizer ->
                recognizer.setWords(true);recognizer.acceptWaveForm(ShortArray(16000),16000)
                val result=com.google.gson.JsonParser.parseString(recognizer.finalResult).asJsonObject
                assertTrue(result.get("text").asString.isBlank())
            }}
        }
    }
    @Test fun downloadOwnershipSurvivesCancellationOfTheScreenCoroutine()=runBlocking {
        val token=UUID.randomUUID().toString();val source="https://test.invalid/$token.m4a"
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.IO);val queued=CompletableDeferred<Unit>()
        val manager=androidx.work.WorkManager.getInstance(context)
        owner.launch {AudioDownload.enqueueSource(context,source,"Navigation $token");queued.complete(Unit);awaitCancellation()}
        withTimeout(15000) {queued.await()};owner.cancel()
        val name="audio_source:${kr.co.metadata.crawler.CatalogFileStore.hash(source)}"
        val work=manager.getWorkInfosForUniqueWork(name).get(15,java.util.concurrent.TimeUnit.SECONDS).last()
        try {
            assertNotEquals(androidx.work.WorkInfo.State.CANCELLED,work.state)
            assertNotNull(DownloadRegistry.get(context,work.id.toString()))
            DownloadRegistry.cancel(context,work.id.toString())
            assertEquals("CANCELLED",DownloadRegistry.get(context,work.id.toString())!!.state)
        } finally {manager.cancelUniqueWork(name).result.get(15,java.util.concurrent.TimeUnit.SECONDS);File(context.filesDir,"music_metadata/downloads/${work.id}.json").delete()}
    }
    private fun File.readTextOrEmpty()=if(isFile)readText() else ""
}
