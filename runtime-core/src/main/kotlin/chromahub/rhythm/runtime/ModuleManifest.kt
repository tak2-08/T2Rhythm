// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Signed metadata; the APK's version and the downloadable module's version are independent. */
data class ModuleManifest internal constructor(
    val moduleId: String,
    val versionCode: Long,
    val versionName: String,
    val signingKeyId: String,
    val entryPointClass: String,
    val hostApi: Int,
    val runtimeFingerprint: String,
    val minBaseVersion: Long,
    val maxBaseVersion: Long,
    val minSdk: Int,
    val maxSdk: Int,
    val minDataSchema: Int,
    val maxDataSchema: Int,
    val payloadSize: Long,
    val payloadSha256: String,
    val notesSize: Long,
    val notesSha256: String,
) {
    companion object {
        private val fields = setOf(
            "formatVersion", "moduleId", "versionCode", "versionName", "signingKeyId",
            "entryPointClass", "hostApi", "runtimeFingerprint", "minBaseVersion", "maxBaseVersion",
            "minSdk", "maxSdk", "minDataSchema", "maxDataSchema", "payloadSize", "payloadSha256",
            "notesSize", "notesSha256",
        )

        internal fun parse(bytes: ByteArray): ModuleManifest {
            try {
                val values = linkedMapOf<String, Pair<JsonToken, String>>()
                JsonReader(StringReader(strictUtf8(bytes))).use { reader ->
                    reader.strictness = Strictness.STRICT
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        require(name in fields && name !in values) { "Unknown or duplicate manifest field" }
                        val token = reader.peek()
                        require(token == JsonToken.STRING || token == JsonToken.NUMBER)
                        values[name] = token to reader.nextString()
                    }
                    reader.endObject()
                    require(reader.peek() == JsonToken.END_DOCUMENT)
                }
                require(values.keys == fields) { "Missing manifest field" }
                fun string(name: String): String = values.getValue(name).let {
                    require(it.first == JsonToken.STRING)
                    it.second
                }
                fun number(name: String): Long = values.getValue(name).let {
                    require(it.first == JsonToken.NUMBER && it.second.matches(Regex("0|[1-9][0-9]*")))
                    it.second.toLong()
                }
                fun integer(name: String): Int = number(name).also { require(it <= Int.MAX_VALUE) }.toInt()
                fun hash(name: String): String = string(name).also { require(it.matches(SHA256_PATTERN)) }

                require(number("formatVersion") == 1L)
                return ModuleManifest(
                    moduleId = string("moduleId").also { require(it.matches(Regex("[a-z][a-z0-9.]{0,95}"))) },
                    versionCode = number("versionCode").also { require(it > 0) },
                    versionName = string("versionName").also {
                        require(it.isNotBlank() && it.length <= 96 && it.none(Char::isISOControl))
                    },
                    signingKeyId = hash("signingKeyId"),
                    entryPointClass = string("entryPointClass").also {
                        require(it.matches(Regex("chromahub\\.rhythm\\.module\\.[A-Za-z_$][A-Za-z0-9_$.]{0,191}")))
                    },
                    hostApi = integer("hostApi").also { require(it > 0) },
                    runtimeFingerprint = hash("runtimeFingerprint"),
                    minBaseVersion = number("minBaseVersion").also { require(it > 0) },
                    maxBaseVersion = number("maxBaseVersion"),
                    minSdk = integer("minSdk").also { require(it >= 26) },
                    maxSdk = integer("maxSdk"),
                    minDataSchema = integer("minDataSchema"),
                    maxDataSchema = integer("maxDataSchema"),
                    payloadSize = number("payloadSize").also { require(it in 1..PackageLimits.PAYLOAD_BYTES) },
                    payloadSha256 = hash("payloadSha256"),
                    notesSize = number("notesSize").also { require(it in 1..PackageLimits.NOTES_BYTES) },
                    notesSha256 = hash("notesSha256"),
                ).also {
                    require(it.minBaseVersion <= it.maxBaseVersion)
                    require(it.minSdk <= it.maxSdk)
                    require(it.minDataSchema <= it.maxDataSchema)
                }
            } catch (error: Exception) {
                throw ModuleRejected(Rejection.INVALID_MANIFEST, error)
            }
        }
    }
}

internal val SHA256_PATTERN = Regex("[0-9a-f]{64}")

internal fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()

data class HostEnvironment(
    val moduleId: String,
    val baseVersion: Long,
    val hostApi: Int,
    val runtimeFingerprint: String,
    val sdk: Int,
    val dataSchema: Int,
    val minimumModuleVersion: Long = 1,
)

enum class Rejection {
    INVALID_ARCHIVE, INVALID_MANIFEST, UNKNOWN_SIGNING_KEY, INVALID_SIGNATURE,
    WRONG_MODULE, BASE_VERSION, HOST_API, RUNTIME_FINGERPRINT, ANDROID_VERSION,
    DATA_SCHEMA, VERSION_REVOKED, PAYLOAD_INTEGRITY, NOTES_INTEGRITY,
}

class ModuleRejected(val reason: Rejection, cause: Throwable? = null) :
    Exception("Module rejected: $reason", cause)

/** Rechecked at activation: the base app or data schema may have changed after download. */
internal fun ModuleManifest.incompatibility(host: HostEnvironment): Rejection? = when {
    moduleId != host.moduleId -> Rejection.WRONG_MODULE
    versionCode < host.minimumModuleVersion -> Rejection.VERSION_REVOKED
    host.baseVersion !in minBaseVersion..maxBaseVersion -> Rejection.BASE_VERSION
    hostApi != host.hostApi -> Rejection.HOST_API
    runtimeFingerprint != host.runtimeFingerprint -> Rejection.RUNTIME_FINGERPRINT
    host.sdk !in minSdk..maxSdk -> Rejection.ANDROID_VERSION
    host.dataSchema !in minDataSchema..maxDataSchema -> Rejection.DATA_SCHEMA
    else -> null
}
