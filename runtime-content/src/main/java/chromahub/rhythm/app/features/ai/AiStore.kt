// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class AiSettings(val enabled: Boolean = false, val connections: List<AiConnection> = emptyList(), val rotate: Boolean = false, val fallback: Boolean = true) {
    val available get() = enabled && connections.any { it.selected }
}

/** Home reads one tiny availability marker. It never loads keys or starts a network client. */
object AiAvailability {
    private val started = AtomicBoolean()
    private val mutable = MutableStateFlow(false)
    val state = mutable.asStateFlow()
    fun initialize(context: Context) {
        if (started.compareAndSet(false, true)) CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            mutable.value = File(context.noBackupFilesDir, "music_ai/available").isFile
        }
    }
    internal fun update(available: Boolean) { mutable.value = available }
}

/** Instantiate on IO only, inside settings/AI screen. No credentials enter preferences or exports. */
class AiStore(context: Context, private val namespace: String = "music_ai") {
    private val root = File(context.noBackupFilesDir, namespace.also { require(it.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) }).apply { mkdirs() }
    private val file = AtomicFile(File(root, "connections.enc"))
    fun read(): AiSettings {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return AiSettings()
        val bytes = file.openRead().use { it.readNBytesBounded(64 * 1024) }
        require(bytes.size >= 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, storageKey(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        val j = JSONObject(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
        val array = j.getJSONArray("connections"); require(array.length() <= 16)
        val connections = (0 until array.length()).map { i ->
            val c = array.getJSONObject(i)
            AiConnection(c.getString("id"), AiProvider.valueOf(c.getString("provider")), c.getString("model"), c.getString("key"), c.getBoolean("selected"))
        }
        require(connections.map { it.id }.distinct().size == connections.size)
        return AiSettings(j.getBoolean("enabled"), connections, j.getBoolean("rotate"), j.getBoolean("fallback"))
            .also { marker(it.available) }
    }
    @Synchronized fun save(settings: AiSettings) {
        require(settings.connections.size <= 16)
        val j = JSONObject().put("enabled", settings.enabled).put("rotate", settings.rotate).put("fallback", settings.fallback)
            .put("connections", JSONArray().apply { settings.connections.forEach { c ->
                put(JSONObject().put("id", c.id).put("provider", c.provider.name).put("model", c.model).put("key", c.key).put("selected", c.selected))
            } })
        val plain = j.toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= 64 * 1024 - 29)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, storageKey())
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (error: Exception) { file.failWrite(stream); throw error }
        marker(settings.available)
    }
    private fun marker(available: Boolean) {
        val marker = File(root, "available")
        if (available) marker.writeText("1") else marker.delete()
        if (namespace == "music_ai") AiAvailability.update(available)
    }
    private fun storageKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey("t2-music-ai-v1", null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("t2-music-ai-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}

internal fun java.io.InputStream.readNBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        require(out.size() + read <= limit) { "Response exceeds limit" }
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
