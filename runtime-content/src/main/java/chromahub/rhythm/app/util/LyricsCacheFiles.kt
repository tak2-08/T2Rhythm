// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import java.io.File
import kr.co.metadata.crawler.CatalogFileStore

/** Korean titles must not collapse into identical underscore-only cache filenames. */
object LyricsCacheFiles {
    fun name(artist: String, title: String) =
        "v2-" + CatalogFileStore.hash("$artist\u0000$title") + ".json"

    fun forReading(root: File, artist: String, title: String): File {
        val current = File(root, name(artist, title))
        if (current.isFile) return current
        // Preserve compatibility with old saved files; new writes never replace a colliding path.
        val legacy = "${artist}_${title}.json".replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(root, legacy)
    }
}
