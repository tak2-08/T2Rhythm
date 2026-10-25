// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

/** Service-owned signal. Optional analysis must leave CPU and I/O to audible playback. */
internal object PlaybackWorkload {
    @Volatile var active: Boolean = false
}
