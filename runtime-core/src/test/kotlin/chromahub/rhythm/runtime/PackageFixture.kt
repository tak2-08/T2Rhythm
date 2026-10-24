// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime

import com.google.gson.Gson
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object PackageFixture {
    val signingKey: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    val otherKey: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    val host = HostEnvironment("t2rhythm.main", 554991327, 1, "a".repeat(64), 37, 1)
    // Deliberately not executable: these tests verify packaging/policy, not Android DEX loading.
    val payload = "non-executable package verifier fixture".toByteArray()
    val notes = "이번 버전의 변경 내용\n- 기존 음악을 계속 이용합니다.".toByteArray()

    fun manifest(
        version: Long = 2,
        payload: ByteArray = this.payload,
        notes: ByteArray = this.notes,
    ): MutableMap<String, Any> = linkedMapOf(
        "formatVersion" to 1,
        "moduleId" to "t2rhythm.main",
        "versionCode" to version,
        "versionName" to "module-$version",
        "signingKeyId" to RuntimePackageVerifier.keyId(signingKey.public),
        "entryPointClass" to "chromahub.rhythm.module.FeatureEntry",
        "hostApi" to 1,
        "runtimeFingerprint" to host.runtimeFingerprint,
        "minBaseVersion" to 554991326L,
        "maxBaseVersion" to 554991399L,
        "minSdk" to 26,
        "maxSdk" to 37,
        "minDataSchema" to 1,
        "maxDataSchema" to 3,
        "payloadSize" to payload.size,
        "payloadSha256" to sha256(payload),
        "notesSize" to notes.size,
        "notesSha256" to sha256(notes),
    )

    fun entries(
        manifest: Map<String, Any> = manifest(),
        rawManifest: ByteArray = Gson().toJson(manifest).toByteArray(),
        key: KeyPair = signingKey,
        payload: ByteArray = this.payload,
        notes: ByteArray = this.notes,
    ): MutableMap<String, ByteArray> = linkedMapOf(
        "manifest.json" to rawManifest,
        "manifest.sig" to Signature.getInstance("SHA256withRSA").run {
            initSign(key.private)
            update(rawManifest)
            sign()
        },
        "content.dex.jar" to payload,
        "notes.md" to notes,
    )

    fun archive(directory: File, entries: Map<String, ByteArray> = entries()): File =
        File.createTempFile("runtime-", ".t2module", directory).also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, data) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }

    fun verified(directory: File, version: Long = 2): VerifiedModule =
        RuntimePackageVerifier(listOf(signingKey.public)).verify(archive(directory, entries(manifest(version))), host)
}
