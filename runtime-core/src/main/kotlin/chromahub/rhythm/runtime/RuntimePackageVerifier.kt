// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.zip.ZipFile

internal object PackageLimits {
    const val ARCHIVE_BYTES = 224L * 1024 * 1024
    const val MANIFEST_BYTES = 16L * 1024
    const val SIGNATURE_BYTES = 1024L
    const val PAYLOAD_BYTES = 192L * 1024 * 1024
    const val NOTES_BYTES = 128L * 1024
    val entries = mapOf(
        "manifest.json" to MANIFEST_BYTES,
        "manifest.sig" to SIGNATURE_BYTES,
        "content.dex.jar" to PAYLOAD_BYTES,
        "notes.md" to NOTES_BYTES,
    )
}

/** Contains no loader or executable object. Consent refers to the hash of this exact archive. */
data class VerifiedModule internal constructor(
    val manifest: ModuleManifest,
    val archiveSha256: String,
    val releaseNotes: String,
) {
    val reference: ModuleReference get() = ModuleReference(manifest, archiveSha256)
}

data class ModuleReference internal constructor(val manifest: ModuleManifest, val archiveSha256: String)

/**
 * Verifies an already-completed, immutable, app-private download without extracting or running code.
 * The Android host must retain it in noBackupFilesDir, reverify it before loading, and enforce
 * read-only DEX files. This verifier is intentionally independent of Android lifecycle/storage.
 */
class RuntimePackageVerifier(trustedKeys: Collection<PublicKey>) {
    private val keys = trustedKeys.associateBy { key ->
        require(key is RSAPublicKey && key.modulus.bitLength() in 2048..4096)
        keyId(key)
    }.also { require(it.isNotEmpty()) }

    fun verify(archive: File, host: HostEnvironment): VerifiedModule {
        try {
            require(archive.isFile && archive.length() in 1..PackageLimits.ARCHIVE_BYTES)
            val archiveHash = archive.inputStream().use { digest(it).first }
            val result = ZipFile(archive).use { zip ->
                require(zip.size() == PackageLimits.entries.size)
                val entries = zip.entries().asSequence().toList()
                require(entries.map { it.name }.toSet() == PackageLimits.entries.keys)
                entries.forEach { entry ->
                    require(!entry.isDirectory && entry.size in 1..PackageLimits.entries.getValue(entry.name))
                }
                fun read(name: String): ByteArray = zip.getInputStream(zip.getEntry(name)).use { stream ->
                    val buffer = ByteArrayOutputStream()
                    val (_, size) = digest(stream, PackageLimits.entries.getValue(name), buffer)
                    require(size == zip.getEntry(name).size)
                    buffer.toByteArray()
                }

                val rawManifest = read("manifest.json")
                val manifest = ModuleManifest.parse(rawManifest)
                val key = keys[manifest.signingKeyId] ?: throw ModuleRejected(Rejection.UNKNOWN_SIGNING_KEY)
                val validSignature = try {
                    Signature.getInstance("SHA256withRSA").run {
                        initVerify(key)
                        update(rawManifest)
                        verify(read("manifest.sig"))
                    }
                } catch (error: java.security.GeneralSecurityException) {
                    throw ModuleRejected(Rejection.INVALID_SIGNATURE, error)
                }
                if (!validSignature) throw ModuleRejected(Rejection.INVALID_SIGNATURE)
                manifest.incompatibility(host)?.let { throw ModuleRejected(it) }
                zip.getInputStream(zip.getEntry("content.dex.jar")).use { stream ->
                    val (hash, size) = digest(stream, PackageLimits.PAYLOAD_BYTES)
                    if (hash != manifest.payloadSha256 || size != manifest.payloadSize) {
                        throw ModuleRejected(Rejection.PAYLOAD_INTEGRITY)
                    }
                }
                val notes = read("notes.md")
                if (sha256(notes) != manifest.notesSha256 || notes.size.toLong() != manifest.notesSize) {
                    throw ModuleRejected(Rejection.NOTES_INTEGRITY)
                }
                val notesText = try {
                    strictUtf8(notes)
                } catch (error: java.nio.charset.CharacterCodingException) {
                    throw ModuleRejected(Rejection.NOTES_INTEGRITY, error)
                }
                if (notesText.isBlank() || '\u0000' in notesText) throw ModuleRejected(Rejection.NOTES_INTEGRITY)
                VerifiedModule(manifest, archiveHash, notesText)
            }
            require(archive.inputStream().use { digest(it).first } == archiveHash) { "Archive changed during verification" }
            return result
        } catch (error: ModuleRejected) {
            throw error
        } catch (error: Exception) {
            throw ModuleRejected(Rejection.INVALID_ARCHIVE, error)
        }
    }

    companion object {
        fun keyId(key: PublicKey): String = sha256(key.encoded)
    }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

private fun digest(
    stream: InputStream,
    limit: Long = PackageLimits.ARCHIVE_BYTES,
    output: ByteArrayOutputStream? = null,
): Pair<String, Long> {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(16 * 1024)
    var count = 0L
    while (true) {
        val read = stream.read(buffer)
        if (read == -1) break
        count += read
        require(count <= limit) { "Entry exceeds size limit" }
        digest.update(buffer, 0, read)
        output?.write(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) } to count
}
