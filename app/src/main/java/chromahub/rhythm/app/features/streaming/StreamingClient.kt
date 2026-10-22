// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import java.io.IOException
import java.net.Proxy
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class StreamPage(val revision: String, val total: Int, val matched: Int, val offset: Int, val tracks: List<StreamTrack>, val genres: List<String>)

object StreamingClient {
    private val clients = object : LinkedHashMap<String, OkHttpClient>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OkHttpClient>?) = size > 8
    }
    fun http(connection: StreamConnection): OkHttpClient = synchronized(clients) {
        val key = "${connection.endpoint}|${connection.pin}"
        clients.getOrPut(key) {
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw java.security.cert.CertificateException("Client authentication is not supported") }
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (chain.isEmpty() || !StreamingProtocol.equal(StreamingProtocol.hash(chain[0].encoded), connection.pin))
                        throw java.security.cert.CertificateException("Server identity changed")
                    chain[0].checkValidity()
                }
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust)
                .hostnameVerifier { host, session -> host == java.net.URI(connection.endpoint).host.removePrefix("[").removeSuffix("]") &&
                    runCatching { StreamingProtocol.equal(StreamingProtocol.hash(session.peerCertificates[0].encoded), connection.pin) }.getOrDefault(false) }
                .followRedirects(false).followSslRedirects(false).proxy(Proxy.NO_PROXY)
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(8, TimeUnit.SECONDS)
                .build()
        }
    }
    fun request(connection: StreamConnection, path: String): Request.Builder {
        require(path.startsWith("/v1/") && !path.startsWith("//"))
        return Request.Builder().url(connection.endpoint + path).header("Authorization", "Bearer ${connection.token}")
    }
    private suspend fun json(connection: StreamConnection, request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = http(connection).newCall(request)
        // Applies to bounded control requests only; long music playback has no overall time limit.
        call.timeout().timeout(20, TimeUnit.SECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (it.code !in setOf(200, 202)) throw IOException(if (it.code == 404) "연결 승인이 만료되었거나 취소되었습니다. 서버에서 다시 초대해 주세요." else "음악 서버가 응답하지 않습니다 (${it.code}).")
                        val body = it.body ?: throw IOException("Empty response")
                        JSONObject(body.byteStream().readBytesLimited(2 * 1024 * 1024).decodeToString())
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    suspend fun pair(invite: StreamingProtocol.Invite, name: String, token: String): StreamConnection {
        val connection = StreamConnection(invite.server, invite.name, invite.endpoint, invite.pin, token)
        val body = JSONObject().put("invite", invite.secret).put("token", token).put("name", StreamingProtocol.name(name)).toString()
        val result = json(connection, request(connection, "/v1/pair").post(body.toRequestBody("application/json".toMediaType())).build())
        require(result.getString("code") == StreamingProtocol.code(token))
        return connection
    }
    suspend fun status(connection: StreamConnection) = json(connection, request(connection, "/v1/pair/status").build()).getString("status")
    suspend fun revision(connection: StreamConnection) = json(connection, request(connection, "/v1/status").build()).also { require(it.getString("server") == connection.server) }.getString("revision")
    suspend fun cancelPairing(connection: StreamConnection) { json(connection, request(connection, "/v1/pair/cancel").post(byteArrayOf().toRequestBody()).build()) }
    suspend fun page(connection: StreamConnection, query: String = "", genre: String = "", offset: Int = 0): StreamPage {
        val encodedQuery = java.net.URLEncoder.encode(query.take(100), "UTF-8")
        val encodedGenre = java.net.URLEncoder.encode(genre.take(100), "UTF-8")
        val j = json(connection, request(connection, "/v1/library?offset=$offset&limit=100&q=$encodedQuery&genre=$encodedGenre").build())
        require(j.getInt("version") == 1 && j.getString("server") == connection.server)
        val tracks = j.getJSONArray("tracks"); require(tracks.length() <= 100)
        val genres = j.optJSONArray("genres")
        return StreamPage(j.getString("revision"), j.getInt("total").coerceAtLeast(0), j.getInt("matched").coerceAtLeast(0), j.getInt("offset"),
            (0 until tracks.length()).map { StreamTrack.parse(tracks.getJSONObject(it)) },
            genres?.let { a -> (0 until minOf(a.length(), 16)).map { a.getString(it).take(100) } } ?: emptyList())
    }
    suspend fun disconnect(connection: StreamConnection) {
        json(connection, request(connection, "/v1/disconnect").post(byteArrayOf().toRequestBody()).build())
    }
    fun friendlyError(error: Throwable): String = when {
        generateSequence(error) { it.cause }.any { it is javax.net.ssl.SSLException } -> "서버의 인증서를 확인할 수 없습니다. 주소와 초대를 확인하고, 서버를 초기화했다면 다시 연결해 주세요."
        error is java.net.SocketTimeoutException || error is java.net.ConnectException || error is java.net.UnknownHostException || error is java.net.NoRouteToHostException -> "음악 서버에 닿지 않습니다. 서버 실행 상태와 같은 Wi-Fi 또는 개인 VPN 연결을 확인해 주세요."
        error is IOException && error.message?.contains("연결 승인") == true -> error.message!!
        else -> "연결을 완료하지 못했습니다. 서버와 네트워크를 확인한 뒤 다시 시도해 주세요."
    }
}
