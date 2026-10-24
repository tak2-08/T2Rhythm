// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.host

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** A process lease keeps lazy Android asset readers alive while old process copies are removed. */
internal object RuntimeResourceCache {
    private val process = "${android.os.Process.myPid()}-${java.util.UUID.randomUUID()}"
    private val leases = mutableMapOf<String, Pair<RandomAccessFile, FileLock>>()

    @Synchronized fun directory(context: Context, archiveHash: String): File {
        require(archiveHash.matches(Regex("[0-9a-f]{64}")))
        val root = File(context.codeCacheDir, "runtime-resources")
        val current = File(root, "$archiveHash/$process")
        if (current.path !in leases) {
            check(root.mkdirs() || root.isDirectory)
            RandomAccessFile(File(root, ".cleanup.lock"), "rw").use { guard -> guard.channel.lock().use {
            check(current.mkdirs() || current.isDirectory)
            val handle = RandomAccessFile(File(current, ".lease"), "rw")
            leases[current.path] = handle to handle.channel.lock()
            // Every resource owner in a live process shares this lease. A kernel releases it on
            // process death, including crashes; PID reuse cannot preserve a stale lease.
            root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[0-9a-f]{64}")) }
                .forEach { version -> version.listFiles().orEmpty().filter { it.isDirectory && it != current }.forEach { old ->
                    try {
                        RandomAccessFile(File(old, ".lease"), "rw").use { candidate ->
                            candidate.channel.tryLock()?.use { old.deleteRecursively() }
                        }
                    } catch (_: OverlappingFileLockException) {
                        // Another module resource owner in this JVM still uses the directory.
                    } catch (_: java.io.IOException) {
                        // Cache cleanup is optional and must never prevent music from starting.
                    }
                } }
            } }
        }
        return current
    }
}
