// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.math.BigInteger
import java.util.Date
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class StreamConnection(val server: String, val name: String, val endpoint: String, val pin: String, val token: String) {
    fun at(address: String) = StreamConnection(server, name, StreamingProtocol.endpoint(address), pin, token)
    override fun toString() = "StreamConnection($server, $name)"
}

/** Device credentials are encrypted with Android Keystore and never included in backup/export. */
class StreamingStore(context: Context, namespace: String = "private_streaming") {
    private val root = File(context.noBackupFilesDir, namespace.also { require(Regex("[a-zA-Z0-9_-]{1,80}").matches(it)) }).apply { mkdirs() }
    private val hostFile = AtomicFile(File(root, "host.json"))
    private val clientFile = AtomicFile(File(root, "connections.enc"))
    private var host = runCatching { JSONObject(read(hostFile).decodeToString()) }
        .getOrElse { JSONObject().put("all", false).put("uris", JSONArray()) }
    val serverId: String = host.optString("server").takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
        ?: UUID.randomUUID().toString().also { host.put("server", it); writeHost() }
    private val _connections = MutableStateFlow(runCatching { readConnections() }.getOrDefault(emptyList()))
    val connections = _connections.asStateFlow()

    @Synchronized fun peers(): List<StreamPeer> {
        val array = host.optJSONArray("peers") ?: return emptyList()
        return (0 until minOf(array.length(), StreamingProtocol.MAX_PEERS)).mapNotNull { i -> runCatching {
            val j = array.getJSONObject(i); val hash = j.getString("hash"); require(StreamingProtocol.validId(hash))
            StreamPeer(j.getString("id"), StreamingProtocol.name(j.getString("name")), hash, j.getLong("added"))
        }.getOrNull() }
    }
    @Synchronized fun savePeers(peers: List<StreamPeer>) {
        val old = host.optJSONArray("peers")
        host.put("peers", JSONArray().apply { peers.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("hash", it.tokenHash).put("added", it.added)) } })
        try { writeHost() } catch (e: Exception) { host.put("peers", old); throw e }
    }
    @Synchronized fun selection(): Set<String>? = if (host.optBoolean("all", true)) null else
        host.optJSONArray("uris")?.let { j -> (0 until minOf(j.length(), 50_000)).map { j.getString(it) }.toSet() } ?: emptySet()
    @Synchronized fun select(uris: Set<String>?) {
        require(uris == null || uris.size <= 50_000)
        val oldAll = host.optBoolean("all", false); val oldUris = host.optJSONArray("uris")
        host.put("all", uris == null).put("uris", JSONArray(uris?.toList() ?: emptyList<String>()))
        try { writeHost() } catch (e: Exception) { host.put("all", oldAll).put("uris", oldUris); throw e }
    }
    @Synchronized fun connection(server: String) = _connections.value.firstOrNull { it.server == server }
    @Synchronized fun save(connection: StreamConnection) {
        val list = (_connections.value.filterNot { it.server == connection.server } + connection)
        require(list.size <= 8)
        val json = JSONArray().apply { list.forEach { c -> put(JSONObject().put("server", c.server).put("name", c.name).put("endpoint", c.endpoint).put("pin", c.pin).put("token", c.token)) } }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, storageKey())
        write(clientFile, byteArrayOf(1) + cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)))
        _connections.value = list
    }
    @Synchronized fun forget(server: String) {
        val list = _connections.value.filterNot { it.server == server }
        val json = JSONArray().apply { list.forEach { c -> put(JSONObject().put("server", c.server).put("name", c.name).put("endpoint", c.endpoint).put("pin", c.pin).put("token", c.token)) } }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, storageKey())
        write(clientFile, byteArrayOf(1) + cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))); _connections.value = list
    }
    private fun readConnections(): List<StreamConnection> {
        if (!clientFile.baseFile.exists() && !File(clientFile.baseFile.path + ".bak").exists()) return emptyList()
        val bytes = read(clientFile); require(bytes.size >= 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, storageKey(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        val array = JSONArray(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8)); require(array.length() <= 8)
        return (0 until array.length()).map { i ->
            val j = array.getJSONObject(i); val id = j.getString("server"); require(UUID.fromString(id).toString() == id)
            val pin = j.getString("pin"); val token = j.getString("token"); require(StreamingProtocol.validId(pin) && StreamingProtocol.validToken(token))
            StreamConnection(id, StreamingProtocol.name(j.getString("name")), StreamingProtocol.endpoint(j.getString("endpoint")), pin, token)
        }
    }
    private fun storageKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey("t2-stream-storage-v1", null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("t2-stream-storage-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun writeHost() = write(hostFile, host.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= 2 * 1024 * 1024) })
    private fun read(file: AtomicFile): ByteArray = file.openRead().use { input ->
        val data = input.readBytesLimited(2 * 1024 * 1024); data
    }
    private fun write(file: AtomicFile, bytes: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
}

internal fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break; if (out.size() + n > limit) throw IOException("Response too large"); out.write(buffer, 0, n) }
    return out.toByteArray()
}

/** Non-exportable server identity. A client must pin this exact certificate from its invitation. */
class StreamingIdentity(private val alias: String = "t2-stream-tls-v2") {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    init {
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    // Conscrypt signs the TLS transcript digest using NONEwithECDSA. Hashing is
                    // already performed by TLS; Keystore must permit that standard raw operation.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(X500Principal("CN=T2Rhythm Streaming"))
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000))
                    .setCertificateNotAfter(Date(System.currentTimeMillis() + 20L * 366 * 86_400_000)).build())
            }.generateKeyPair()
        }
    }
    val pin: String get() = StreamingProtocol.hash(store.getCertificate(alias).encoded)
    fun serverContext(): SSLContext {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); factory.init(store, null)
        return SSLContext.getInstance("TLS").apply { init(factory.keyManagers, null, null) }
    }
}
