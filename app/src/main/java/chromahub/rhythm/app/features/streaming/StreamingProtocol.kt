// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Versioned, deliberately small protocol. Credentials never belong in a URL or a media item. */
object StreamingProtocol {
    const val PORT = 8898
    const val MEDIA_HOST = "private.t2rhythm.invalid"
    const val MAX_PEERS = 8
    private val tokenPattern = Regex("[A-Za-z0-9_-]{43}")
    private val hexPattern = Regex("[0-9a-f]{64}")
    fun hash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes); val hex = "0123456789abcdef"
        return CharArray(digest.size * 2) { index -> val value = digest[index / 2].toInt() and 255; hex[if (index % 2 == 0) value ushr 4 else value and 15] }.concatToString()
    }
    fun hash(text: String): String = hash(text.toByteArray(Charsets.UTF_8))
    fun token(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun validToken(value: String) = tokenPattern.matches(value)
    fun equal(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))
    fun name(value: String) = value.filterNot { it.isISOControl() }.trim().take(48).ifBlank { "T2Rhythm" }
    fun endpoint(value: String): String {
        val uri = URI(if ("://" in value.trim()) value.trim() else "https://${value.trim()}")
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.path.isNullOrEmpty() || uri.path == "/")
        require(uri.port == -1 || uri.port in 1..65535)
        val host = uri.host.removePrefix("[").removeSuffix("]")
        require('%' !in host && host.length <= 253)
        return "https://${if (':' in host) "[$host]" else host.lowercase(java.util.Locale.ROOT)}:${if (uri.port == -1) PORT else uri.port}"
    }
    fun code(secret: String): String = (hash(secret).take(7).toLong(16) % 1_000_000).toString().padStart(6, '0')
    fun validId(id: String) = hexPattern.matches(id)

    data class Invite(val server: String, val name: String, val endpoint: String, val pin: String, val secret: String, val expires: Long) {
        fun encode(): String = "T2S1." + Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject()
            .put("server", server).put("name", name).put("endpoint", endpoint).put("pin", pin).put("invite", secret).put("expires", expires)
            .toString().toByteArray(Charsets.UTF_8))
        // Sharing sheets may prepend a human-readable sentence. Only one bounded code is accepted.
        companion object {
            fun decode(text: String, now: Long = System.currentTimeMillis()): Invite {
                require(text.length <= 8192)
                val code = Regex("T2S1\\.([A-Za-z0-9_-]{1,4096})").findAll(text).toList().single()
                val json = JSONObject(String(Base64.getUrlDecoder().decode(code.groupValues[1]), Charsets.UTF_8))
                val server = json.getString("server"); require(UUID.fromString(server).toString() == server)
                val pin = json.getString("pin"); require(hexPattern.matches(pin))
                val secret = json.getString("invite"); require(validToken(secret))
                val expires = json.getLong("expires"); require(expires > now && expires - now <= 11 * 60_000)
                return Invite(server, name(json.getString("name")), endpoint(json.getString("endpoint")), pin, secret, expires)
            }
        }
    }

    data class ByteRange(val start: Long, val length: Long) {
        val end get() = start + length - 1
        companion object {
            /** Exactly one RFC 9110 byte range. An invalid/unsatisfiable range returns null. */
            fun parse(header: String?, size: Long): ByteRange? {
                if (size < 0) return null
                if (header == null) return ByteRange(0, size)
                if (size == 0L || header.length > 100) return null
                val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header.trim()) ?: return null
                val (a, b) = match.destructured
                if (a.isEmpty()) {
                    val suffix = b.toLongOrNull()?.takeIf { it > 0 } ?: return null
                    val length = minOf(suffix, size); return ByteRange(size - length, length)
                }
                val start = a.toLongOrNull()?.takeIf { it >= 0 && it < size } ?: return null
                val end = if (b.isEmpty()) size - 1 else b.toLongOrNull()?.coerceAtMost(size - 1) ?: return null
                if (end < start) return null
                return ByteRange(start, end - start + 1)
            }
        }
    }
}

data class StreamPeer(val id: String, val name: String, val tokenHash: String, val added: Long)
data class PairRequest(val peer: StreamPeer, val code: String, val expires: Long)

/** No Android or networking dependencies: authorization and bounded pairing state have one owner. */
class StreamingAccess(initial: List<StreamPeer> = emptyList(), private val persist: (List<StreamPeer>) -> Unit = {}) {
    private val peers = initial.take(StreamingProtocol.MAX_PEERS).associateByTo(linkedMapOf()) { it.id }
    private val pending = linkedMapOf<String, PairRequest>()
    private var inviteHash: String? = null
    private var inviteUntil = 0L
    @Synchronized fun peers(): List<StreamPeer> = peers.values.toList()
    @Synchronized fun requests(now: Long = System.currentTimeMillis()): List<PairRequest> { pending.entries.removeAll { it.value.expires <= now }; return pending.values.toList() }
    @Synchronized fun invite(now: Long = System.currentTimeMillis()): Pair<String, Long> {
        check(peers.size < StreamingProtocol.MAX_PEERS)
        val token = StreamingProtocol.token(); inviteHash = StreamingProtocol.hash(token); inviteUntil = now + 10 * 60_000
        return token to inviteUntil
    }
    @Synchronized fun pair(invite: String, token: String, name: String, now: Long = System.currentTimeMillis()): PairRequest? {
        requests(now)
        if (!StreamingProtocol.validToken(invite) || !StreamingProtocol.validToken(token) || inviteUntil <= now ||
            !StreamingProtocol.equal(StreamingProtocol.hash(invite), inviteHash ?: "") || peers.size >= StreamingProtocol.MAX_PEERS || pending.size >= 3) return null
        val hash = StreamingProtocol.hash(token)
        if (peers.values.any { StreamingProtocol.equal(hash, it.tokenHash) } || pending.values.any { StreamingProtocol.equal(hash, it.peer.tokenHash) }) return null
        val peer = StreamPeer(UUID.randomUUID().toString(), StreamingProtocol.name(name), hash, now)
        val request = PairRequest(peer, StreamingProtocol.code(token), now + 5 * 60_000)
        pending[peer.id] = request; inviteHash = null; inviteUntil = 0
        return request
    }
    @Synchronized fun status(token: String, now: Long = System.currentTimeMillis()): String {
        if (authorized(token) != null) return "approved"
        val hash = StreamingProtocol.hash(token)
        return if (requests(now).any { StreamingProtocol.equal(it.peer.tokenHash, hash) }) "pending" else "denied"
    }
    @Synchronized fun approve(id: String, now: Long = System.currentTimeMillis()): Boolean {
        val request = requests(now).firstOrNull { it.peer.id == id } ?: return false
        if (peers.size >= StreamingProtocol.MAX_PEERS) return false
        // Persist before publishing permission. A failed durable write never grants transient access.
        persist(peers.values.toList() + request.peer)
        peers[id] = request.peer; pending.remove(id); return true
    }
    @Synchronized fun revoke(id: String) {
        if (id in peers) { persist(peers.values.filterNot { it.id == id }); peers.remove(id) }
        pending.remove(id)
    }
    @Synchronized fun authorized(token: String): StreamPeer? {
        if (!StreamingProtocol.validToken(token)) return null
        val hash = StreamingProtocol.hash(token)
        return peers.values.firstOrNull { StreamingProtocol.equal(hash, it.tokenHash) }
    }
    @Synchronized fun approved(id: String) = id in peers
    @Synchronized fun closePairing() { inviteHash = null; inviteUntil = 0; pending.clear() }
    @Synchronized fun cancel(token: String) {
        val hash = StreamingProtocol.hash(token)
        pending.entries.removeAll { StreamingProtocol.equal(hash, it.value.peer.tokenHash) }
    }
}
