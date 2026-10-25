// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Service-owned signal. Optional analysis must leave CPU and I/O to audible playback. */
internal object PlaybackWorkload {
    @Volatile var active: Boolean = false

    /** Suspend optional bulk probes without occupying a decoder/IO thread or dropping progress. */
    suspend fun awaitIdle() {
        currentCoroutineContext().ensureActive()
        while (active) delay(750)
    }
}
