// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.host

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import chromahub.rhythm.runtime.HostEnvironment
import chromahub.rhythm.runtime.ModuleActivationPolicy
import chromahub.rhythm.runtime.ModuleActivationState
import chromahub.rhythm.runtime.StartupDecision
import chromahub.rhythm.runtime.StartupSafety
import chromahub.rhythm.runtime.VerifiedModule
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Durable host-owned policy journal; no downloaded code is executed here.
 * [resolveVerified] must reverify a local signed archive (or identify the trusted embedded module)
 * and return the verified package. Never resolve a reference from unsigned stored JSON.
 * Corruption/missing packages fail closed; the base must provide compatible built-in recovery UI.
 */
class ModuleStateStore(
    context: Context,
    directory: File,
    private val initial: VerifiedModule,
    private val resolveVerified: (String) -> VerifiedModule,
) {
    private val directory = directory.canonicalFile.also {
        val root = context.noBackupFilesDir.canonicalPath + File.separator
        require(it.path.startsWith(root))
        check(it.mkdirs() || it.isDirectory)
    }
    private val stateFile = File(this.directory, STATE_FILE_NAME)
    private val atomic = AtomicFile(stateFile)
    private val localLock = locks.computeIfAbsent(stateFile.path) { ReentrantLock() }

    fun snapshot(): ModuleActivationState = locked { read() }

    fun stage(verified: VerifiedModule): ModuleActivationState = update { ModuleActivationPolicy.stage(it, verified) }
    fun approve(archiveSha256: String): ModuleActivationState = update { ModuleActivationPolicy.approve(it, archiveSha256) }
    fun discardCandidate(): ModuleActivationState = update(ModuleActivationPolicy::discardCandidate)
    fun discardUnapproved(archiveHash: String): ModuleActivationState = update {
        val candidate = requireNotNull(it.candidate)
        require(candidate.module.archiveSha256 == archiveHash && !candidate.approved)
        ModuleActivationPolicy.discardCandidate(it)
    }
    fun confirmHealthy(archiveSha256: String): ModuleActivationState = update {
        ModuleActivationPolicy.confirmHealthy(it, archiveSha256)
    }
    fun reportFatalError(archiveSha256: String, host: HostEnvironment): ModuleActivationState = update {
        require(it.active.archiveSha256 == archiveSha256)
        ModuleActivationPolicy.recoverReportedCrash(it, host)
    }

    /** Only the installed base's explicit recovery UI calls this; ordinary reads never reset it. */
    fun restoreEmbedded(host: HostEnvironment): ModuleActivationState = locked {
        val highest = runCatching { read().highestApprovedVersion }.getOrDefault(initial.manifest.versionCode)
        val restored = ModuleActivationPolicy.restoreEmbeddedForRecovery(initial, host, highest)
        write(restored)
        restored
    }

    /** The returned decision is safe to consume only AFTER this method returns successfully. */
    fun prepareStartup(host: HostEnvironment, safety: StartupSafety): StartupDecision = locked {
        val before = read()
        val acceptedBase = if (safety.freshProcess) ModuleActivationPolicy.reconcileEmbeddedBaseUpdate(before, initial, host) else before
        val decision = ModuleActivationPolicy.prepareStartup(acceptedBase, host, safety)
        if (decision.state != before) write(decision.state)
        decision
    }

    private fun update(transform: (ModuleActivationState) -> ModuleActivationState): ModuleActivationState = locked {
        val before = read()
        val after = transform(before)
        if (after != before) write(after)
        after
    }

    private fun <T> locked(action: () -> T): T = localLock.withLock {
        RandomAccessFile(File(directory, "activation.lock"), "rw").use { file ->
            file.channel.lock().use { action() }
        }
    }

    private fun read(): ModuleActivationState {
        val bytes = try {
            atomic.openRead().use { input ->
                val bytes = input.readBytesBounded()
                require(bytes.size > 32)
                bytes
            }
        } catch (error: FileNotFoundException) {
            // FileInputStream also reports permission errors as FileNotFoundException.
            // An unreadable/unfinished existing record must never reset approvals to defaults.
            if (stateFile.exists() || File(stateFile.path + ".bak").exists() || File(stateFile.path + ".new").exists()) {
                throw CorruptModuleState(error)
            }
            return ModuleActivationState(initial.reference)
        } catch (error: Exception) {
            throw CorruptModuleState(error)
        }
        try {
            val payload = bytes.copyOfRange(0, bytes.size - 32)
            require(MessageDigest.isEqual(checksum(payload), bytes.copyOfRange(payload.size, bytes.size)))
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == MAGIC && input.readInt() == 1)
                fun boolean(): Boolean = input.readUnsignedByte().also { require(it in 0..1) } == 1
                fun reference(): VerifiedModule {
                    val hash = input.readUTF()
                    require(hash.matches(HASH))
                    return resolveVerified(hash).also {
                        require(it.archiveSha256 == hash && it.manifest.moduleId == initial.manifest.moduleId)
                    }
                }
                val active = reference()
                val previous = if (boolean()) reference() else null
                val candidateReference = if (boolean()) reference() else null
                val approved = boolean()
                val trial = boolean()
                val highest = input.readLong()
                val failed = if (boolean()) input.readUTF().also { require(it.matches(HASH)) } else null
                require(input.read() == -1)
                require(highest >= active.manifest.versionCode)
                require(!approved || candidateReference != null)
                require(!trial || (previous != null && candidateReference == null))
                require(previous == null || previous.manifest.versionCode <= highest)
                require(!approved || candidateReference!!.manifest.versionCode <= highest)
                var state = ModuleActivationState(active.reference, previousGood = previous?.reference,
                    trialInProgress = trial, highestApprovedVersion = highest, lastFailedArchive = failed)
                if (candidateReference != null) {
                    // Rebuild through policy rather than directly constructing an approved candidate.
                    require(!trial)
                    require(if (approved) candidateReference.manifest.versionCode == highest
                        else candidateReference.manifest.versionCode > highest)
                    val staged = ModuleActivationPolicy.stage(
                        state.copy(highestApprovedVersion = active.manifest.versionCode),
                        candidateReference,
                    )
                    state = if (approved) ModuleActivationPolicy.approve(staged, candidateReference.archiveSha256) else staged
                    state = state.copy(highestApprovedVersion = highest)
                }
                return state
            }
        } catch (error: Exception) {
            throw CorruptModuleState(error)
        }
    }

    private fun write(state: ModuleActivationState) {
        val payload = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(1)
                output.writeUTF(state.active.archiveSha256)
                output.writeBoolean(state.previousGood != null)
                state.previousGood?.let { output.writeUTF(it.archiveSha256) }
                output.writeBoolean(state.candidate != null)
                state.candidate?.let { output.writeUTF(it.module.archiveSha256) }
                output.writeBoolean(state.candidate?.approved ?: false)
                output.writeBoolean(state.trialInProgress)
                output.writeLong(state.highestApprovedVersion)
                output.writeBoolean(state.lastFailedArchive != null)
                state.lastFailedArchive?.let(output::writeUTF)
            }
        }.toByteArray()
        val bytes = payload + checksum(payload)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
        atomic.finishWrite(stream)
        val flags = OsConstants.O_RDONLY or
            (if (android.os.Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else 0)
        val descriptor = Os.open(directory.path, flags, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        check(atomic.openRead().use { it.readBytesBounded() }.contentEquals(bytes))
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(512)
        while (true) {
            val count = read(chunk)
            if (count == -1) break
            require(buffer.size() + count <= MAX_BYTES)
            buffer.write(chunk, 0, count)
        }
        return buffer.toByteArray()
    }

    private fun checksum(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    companion object {
        const val STATE_FILE_NAME = "activation.bin"
        private const val MAGIC = 0x54325253
        private const val MAX_BYTES = 4096
        private val HASH = Regex("[0-9a-f]{64}")
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
    }
}

class CorruptModuleState(cause: Throwable) : Exception("Module state requires recovery", cause)
