// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.base

import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.RandomAccessFile

internal fun <T> withArchiveLock(directory: File, operation: () -> T): T = synchronized(directory.path.intern()) {
    RandomAccessFile(File(directory, "archive.lock"), "rw").use { file ->
        file.channel.lock().use { operation() }
    }
}

internal fun syncRuntimeDirectory(directory: File) {
    val flags = OsConstants.O_RDONLY or (if (Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else 0)
    val descriptor = Os.open(directory.path, flags, 0)
    try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
}
