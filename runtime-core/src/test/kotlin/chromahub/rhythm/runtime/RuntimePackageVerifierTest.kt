// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimePackageVerifierTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture = PackageFixture
    private val verifier get() = RuntimePackageVerifier(listOf(fixture.signingKey.public))

    private fun reject(
        reason: Rejection,
        entries: Map<String, ByteArray> = fixture.entries(),
        host: HostEnvironment = fixture.host,
    ) {
        val archive = fixture.archive(temporary.root, entries)
        assertEquals(reason, assertThrows(ModuleRejected::class.java) { verifier.verify(archive, host) }.reason)
    }

    @Test fun validSignedPackageProvidesVerifiedNotesAndExactArchiveIdentity() {
        val archive = fixture.archive(temporary.root)
        val verified = verifier.verify(archive, fixture.host)
        assertEquals(2L, verified.manifest.versionCode)
        assertEquals(fixture.notes.toString(Charsets.UTF_8), verified.releaseNotes)
        assertEquals(sha256(archive.readBytes()), verified.archiveSha256)
        assertEquals(1, temporary.root.listFiles()!!.size) // Verification must not extract code.
    }

    @Test fun entryOrderDoesNotAffectVerification() {
        val entries = fixture.entries().entries.reversed().associate { it.key to it.value }
        assertEquals(2L, verifier.verify(fixture.archive(temporary.root, entries), fixture.host).manifest.versionCode)
    }

    @Test fun unpinnedSigningKeyIsRejected() {
        val manifest = fixture.manifest().apply { this["signingKeyId"] = RuntimePackageVerifier.keyId(fixture.otherKey.public) }
        reject(Rejection.UNKNOWN_SIGNING_KEY, fixture.entries(manifest, key = fixture.otherKey))
    }

    @Test fun claimedTrustedKeyWithAnotherSignatureIsRejected() {
        reject(Rejection.INVALID_SIGNATURE, fixture.entries(key = fixture.otherKey))
    }

    @Test fun modifiedSignatureIsRejected() {
        val entries = fixture.entries()
        entries["manifest.sig"] = entries.getValue("manifest.sig").copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        reject(Rejection.INVALID_SIGNATURE, entries)
    }

    @Test fun releaseMetadataCannotBeEditedWithoutResigning() {
        val entries = fixture.entries()
        entries["manifest.json"] = Gson().toJson(fixture.manifest(3)).toByteArray()
        reject(Rejection.INVALID_SIGNATURE, entries)
    }

    @Test fun payloadTamperingIsRejected() {
        reject(Rejection.PAYLOAD_INTEGRITY, fixture.entries(payload = fixture.payload + 1.toByte()))
    }

    @Test fun payloadSizeIsCheckedSeparatelyFromHash() {
        val manifest = fixture.manifest().apply { this["payloadSize"] = fixture.payload.size + 1 }
        reject(Rejection.PAYLOAD_INTEGRITY, fixture.entries(manifest))
    }

    @Test fun notesTamperingIsRejected() {
        reject(Rejection.NOTES_INTEGRITY, fixture.entries(notes = "misleading unsigned notes".toByteArray()))
    }

    @Test fun notesSizeIsCheckedSeparatelyFromHash() {
        val manifest = fixture.manifest().apply { this["notesSize"] = fixture.notes.size + 1 }
        reject(Rejection.NOTES_INTEGRITY, fixture.entries(manifest))
    }

    @Test fun blankSignedReleaseNotesAreRejected() {
        val notes = " \n ".toByteArray()
        reject(Rejection.NOTES_INTEGRITY, fixture.entries(fixture.manifest(notes = notes), notes = notes))
    }

    @Test fun malformedUtf8NotesAreRejectedEvenWhenSigned() {
        val notes = byteArrayOf(0xc3.toByte(), 0x28)
        reject(Rejection.NOTES_INTEGRITY, fixture.entries(fixture.manifest(notes = notes), notes = notes))
    }

    @Test fun nulInSignedNotesIsRejected() {
        val notes = "valid\u0000hidden".toByteArray()
        reject(Rejection.NOTES_INTEGRITY, fixture.entries(fixture.manifest(notes = notes), notes = notes))
    }

    @Test fun traversalEntryIsRejectedWithoutExtraction() {
        reject(Rejection.INVALID_ARCHIVE, fixture.entries().apply { this["../escape.dex"] = byteArrayOf(1) })
        assertEquals(1, temporary.root.listFiles()!!.size)
    }

    @Test fun unknownAndMissingEntriesAreRejected() {
        reject(Rejection.INVALID_ARCHIVE, fixture.entries().apply { remove("content.dex.jar"); this["other.dex"] = byteArrayOf(1) })
    }

    @Test fun duplicateEntryNamesAreRejected() {
        val entries = fixture.entries().apply { remove("content.dex.jar"); this["extra.md"] = fixture.notes }
        val file = fixture.archive(temporary.root, entries)
        val bytes = file.readBytes()
        val from = "extra.md".toByteArray()
        val to = "notes.md".toByteArray()
        for (index in 0..bytes.size - from.size) {
            if (from.indices.all { bytes[index + it] == from[it] }) to.copyInto(bytes, index)
        }
        file.writeBytes(bytes)
        assertEquals(Rejection.INVALID_ARCHIVE, assertThrows(ModuleRejected::class.java) { verifier.verify(file, fixture.host) }.reason)
    }

    @Test fun compressedOversizedNotesAreRejected() {
        val notes = ByteArray((PackageLimits.NOTES_BYTES + 1).toInt()) { ' '.code.toByte() }
        val entries = fixture.entries(notes = notes)
        val archive = fixture.archive(temporary.root, entries)
        assertTrue(archive.length() < PackageLimits.NOTES_BYTES)
        assertEquals(Rejection.INVALID_ARCHIVE, assertThrows(ModuleRejected::class.java) { verifier.verify(archive, fixture.host) }.reason)
    }

    @Test fun oversizedManifestIsRejectedBeforeParsing() {
        reject(Rejection.INVALID_ARCHIVE, fixture.entries().apply {
            this["manifest.json"] = ByteArray((PackageLimits.MANIFEST_BYTES + 1).toInt()) { ' '.code.toByte() }
        })
    }

    @Test fun duplicateManifestFieldsAreRejectedEvenWithAValidSignature() {
        val raw = Gson().toJson(fixture.manifest()).replaceFirst("{", "{\"versionCode\":3,").toByteArray()
        reject(Rejection.INVALID_MANIFEST, fixture.entries(rawManifest = raw))
    }

    @Test fun unknownManifestFieldsAreRejected() {
        reject(Rejection.INVALID_MANIFEST, fixture.entries(fixture.manifest().apply { this["downloadUrl"] = "https://example.invalid/code" }))
    }

    @Test fun trailingJsonCannotHideAnotherManifest() {
        val raw = (Gson().toJson(fixture.manifest()) + " {}").toByteArray()
        reject(Rejection.INVALID_MANIFEST, fixture.entries(rawManifest = raw))
    }

    @Test fun versionMustBeAnIntegerNotAStringOrFraction() {
        reject(Rejection.INVALID_MANIFEST, fixture.entries(fixture.manifest().apply { this["versionCode"] = "2" }))
        reject(Rejection.INVALID_MANIFEST, fixture.entries(fixture.manifest().apply { this["versionCode"] = 2.5 }))
    }

    @Test fun overflowingIntegersAreRejected() {
        val raw = Gson().toJson(fixture.manifest()).replace("\"versionCode\":2", "\"versionCode\":9223372036854775808").toByteArray()
        reject(Rejection.INVALID_MANIFEST, fixture.entries(rawManifest = raw))
    }

    @Test fun backwardsCompatibilityRangesAreRejected() {
        reject(Rejection.INVALID_MANIFEST, fixture.entries(fixture.manifest().apply { this["maxBaseVersion"] = 1 }))
        reject(Rejection.INVALID_MANIFEST, fixture.entries(fixture.manifest().apply { this["maxSdk"] = 25 }))
        reject(Rejection.INVALID_MANIFEST, fixture.entries(fixture.manifest().apply { this["minDataSchema"] = 4 }))
    }

    @Test fun unexpectedEntryPointNamespaceIsRejected() {
        reject(Rejection.INVALID_MANIFEST, fixture.entries(fixture.manifest().apply { this["entryPointClass"] = "some.other.Application" }))
    }

    @Test fun malformedUtf8ManifestIsRejected() {
        val raw = "{\"versionName\":\"".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray()
        reject(Rejection.INVALID_MANIFEST, fixture.entries(rawManifest = raw))
    }

    @Test fun moduleIdentityMustMatchHost() {
        reject(Rejection.WRONG_MODULE, host = fixture.host.copy(moduleId = "another.module"))
    }

    @Test fun baseVersionBoundsAreEnforced() {
        reject(Rejection.BASE_VERSION, host = fixture.host.copy(baseVersion = 554991325))
        reject(Rejection.BASE_VERSION, host = fixture.host.copy(baseVersion = 554991400))
    }

    @Test fun hostApiMustMatchExactly() {
        reject(Rejection.HOST_API, host = fixture.host.copy(hostApi = 2))
    }

    @Test fun composeAndSharedRuntimeFingerprintMustMatch() {
        reject(Rejection.RUNTIME_FINGERPRINT, host = fixture.host.copy(runtimeFingerprint = "b".repeat(64)))
    }

    @Test fun androidVersionBoundsAreEnforced() {
        reject(Rejection.ANDROID_VERSION, host = fixture.host.copy(sdk = 25))
        reject(Rejection.ANDROID_VERSION, host = fixture.host.copy(sdk = 38))
    }

    @Test fun dataSchemaCompatibilityIsEnforced() {
        reject(Rejection.DATA_SCHEMA, host = fixture.host.copy(dataSchema = 4))
    }

    @Test fun baseSecurityFloorCanRejectAnOtherwiseValidRelease() {
        reject(Rejection.VERSION_REVOKED, host = fixture.host.copy(minimumModuleVersion = 3))
    }
}
