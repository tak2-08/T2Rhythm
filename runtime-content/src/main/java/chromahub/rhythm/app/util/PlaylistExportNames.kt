// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import java.util.Locale

/** ZIP entries and extracted files must remain distinct even on case-insensitive filesystems. */
internal fun allocatePlaylistExportNames(names: List<String>): List<String> {
    val used = HashSet<String>()
    return names.map { name ->
        var candidate = name
        var copy = 2
        while (!used.add(candidate.lowercase(Locale.ROOT))) candidate = "${copy++}-$name"
        candidate
    }
}
