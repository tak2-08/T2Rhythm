// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import org.json.JSONObject

/** HTTPS only, no static file handler, no uploads, no URL credentials, no directory listing. */
class StreamingServer(
    port: Int, ssl: SSLContext, private val access: StreamingAccess, private val catalog: StreamingCatalog,
    private val changed: () -> Unit = {}, private val artwork: (String) -> ByteArray?,
) : NanoHTTPD(port) {
    private val runner = BoundedRunner()
    val bytesSent = AtomicLong()
    private var pairingWindow = 0L
    private var pairingCount = 0
    init {
        makeSecure(ssl.serverSocketFactory, arrayOf("TLSv1.2", "TLSv1.3").filter { it in ssl.supportedSSLParameters.protocols }.toTypedArray())
        setAsyncRunner(runner)
    }
    override fun serve(session: IHTTPSession): Response {
        return try { handle(session) } catch (_: Exception) { reply(Response.Status.SERVICE_UNAVAILABLE) }
            .apply {
                addHeader("Connection", "close"); addHeader("Cache-Control", "private, no-store")
                addHeader("X-Content-Type-Options", "nosniff"); addHeader("X-Robots-Tag", "noindex, nofollow, noarchive")
            }
    }
    private fun handle(s: IHTTPSession): Response {
        val path = s.uri
        if (s.headers.containsKey("transfer-encoding")) return reply(Response.Status.BAD_REQUEST)
        val bearer = s.headers["authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ").orEmpty()
        if (path == "/v1/pair" && s.method == Method.POST) {
            if (!allowPairAttempt()) return reply(Response.Status.TOO_MANY_REQUESTS)
            val length = s.headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..2048 } ?: return reply(Response.Status.BAD_REQUEST)
            val body = ByteArray(length); var at = 0
            while (at < length) { val n = s.inputStream.read(body, at, length - at); if (n < 0) return reply(Response.Status.BAD_REQUEST); at += n }
            val j = JSONObject(body.decodeToString())
            val request = access.pair(j.optString("invite"), j.optString("token"), j.optString("name")) ?: return reply(Response.Status.NOT_FOUND)
            changed(); return reply(Response.Status.ACCEPTED, JSONObject().put("status", "pending").put("code", request.code))
        }
        if (path == "/v1/pair/status" && s.method == Method.GET) {
            val status = access.status(bearer)
            return if (status == "denied") reply(Response.Status.NOT_FOUND) else reply(Response.Status.OK, JSONObject().put("status", status))
        }
        if (path == "/v1/pair/cancel" && s.method == Method.POST) { access.cancel(bearer); changed(); return reply(Response.Status.OK) }
        val peer = access.authorized(bearer) ?: return reply(Response.Status.NOT_FOUND)
        if (path == "/v1/disconnect" && s.method == Method.POST) { access.revoke(peer.id); changed(); return reply(Response.Status.OK) }
        if (s.method != Method.GET && s.method != Method.HEAD) return reply(Response.Status.METHOD_NOT_ALLOWED)
        if (path == "/v1/status") return reply(Response.Status.OK, JSONObject().put("server", catalog.serverId).put("revision", catalog.snapshot.revision))
        if (path == "/v1/library") {
            val offset = s.parameters["offset"]?.singleOrNull()?.toIntOrNull()?.takeIf { it in 0..100_000 } ?: 0
            val limit = s.parameters["limit"]?.singleOrNull()?.toIntOrNull()?.coerceIn(1, 100) ?: 100
            return reply(Response.Status.OK, catalog.page(s.parameters["q"]?.firstOrNull().orEmpty(), s.parameters["genre"]?.firstOrNull().orEmpty().take(100), offset, limit))
        }
        val parts = path.split('/')
        if (parts.size != 4 || parts[1] != "v1" || !StreamingProtocol.validId(parts[3]) || parts[3] !in catalog.snapshot.entries) return reply(Response.Status.NOT_FOUND)
        val id = parts[3]
        if (parts[2] == "art") {
            val bytes = artwork(id) ?: return reply(Response.Status.NOT_FOUND)
            return newFixedLengthResponse(Response.Status.OK, "image/webp", ByteArrayInputStream(bytes), bytes.size.toLong())
        }
        if (parts[2] != "audio") return reply(Response.Status.NOT_FOUND)
        val audio = catalog.open(id)
        val range = StreamingProtocol.ByteRange.parse(s.headers["range"], audio.size)
        if (range == null) { audio.close(); return reply(Response.Status.RANGE_NOT_SATISFIABLE).apply { addHeader("Content-Range", "bytes */${audio.size}") } }
        if (s.method == Method.HEAD) audio.close() else {
            try { audio.file.channel.position(audio.offset + range.start) } catch (e: Exception) { audio.close(); throw e }
        }
        val input = if (s.method == Method.HEAD) ByteArrayInputStream(byteArrayOf()) else object : FilterInputStream(audio.file) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!access.approved(peer.id) || id !in catalog.snapshot.entries) throw IOException("Access ended")
                val n = super.read(buffer, offset, minOf(length, 32 * 1024)); if (n > 0) bytesSent.addAndGet(n.toLong()); return n
            }
            override fun read(): Int { if (!access.approved(peer.id) || id !in catalog.snapshot.entries) throw IOException("Access ended"); return super.read() }
        }
        return newFixedLengthResponse(if (s.headers["range"] == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT, audio.mime, input, range.length).apply {
            addHeader("Accept-Ranges", "bytes")
            if (s.headers["range"] != null) addHeader("Content-Range", "bytes ${range.start}-${range.end}/${audio.size}")
        }
    }
    @Synchronized private fun allowPairAttempt(): Boolean {
        val now = System.nanoTime() / 1_000_000
        if (now - pairingWindow > 60_000) { pairingWindow = now; pairingCount = 0 }
        return ++pairingCount <= 30
    }
    private fun reply(status: Response.Status, json: JSONObject = JSONObject()) = newFixedLengthResponse(status, "application/json; charset=utf-8", json.toString())

    /** Bounded sockets leave room for playback and controls without an unbounded thread backlog. */
    private class BoundedRunner : AsyncRunner {
        private val clients = ConcurrentHashMap.newKeySet<ClientHandler>()
        private val pool = ThreadPoolExecutor(0, 12, 30, TimeUnit.SECONDS, SynchronousQueue(), { task -> Thread(task, "T2-private-stream").apply { isDaemon = true } })
        override fun exec(code: ClientHandler) {
            clients.add(code)
            try { pool.execute(code) } catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(code); code.close() }
        }
        override fun closed(clientHandler: ClientHandler) { clients.remove(clientHandler) }
        override fun closeAll() { clients.toList().forEach { it.close() }; clients.clear(); pool.shutdownNow() }
    }
}
