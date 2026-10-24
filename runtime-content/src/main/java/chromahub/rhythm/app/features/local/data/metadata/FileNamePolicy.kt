// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import java.text.Normalizer
import kr.co.metadata.crawler.CatalogIdentity

/** Filename changes keep the directory and the original audio extension. */
object FileNamePolicy {
    fun base(title: String?, artist: String?, format: String): String? {
        fun missing(value: String?) = CatalogIdentity.missing(value) || value?.trim()?.matches(Regex("[-–—_.\\s]+")) == true
        if (missing(title)) return null
        fun clean(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]+"), " ").replace(Regex("\\s+"), " ").trim(' ', '.')
        val name = clean(title!!).takeIf(String::isNotBlank) ?: return null
        val performer = artist?.takeUnless(::missing)?.let(::clean)?.takeIf(String::isNotBlank)
        return if (format == "TITLE" || performer == null) name else "$performer - $name"
    }
    fun filename(base: String, extension: String, copy: Int = 1): String {
        require(extension.matches(Regex("[A-Za-z0-9]{1,12}")) && copy in 1..999)
        val suffix = (if (copy > 1) " ($copy)" else "") + ".$extension"
        val limit = 220 - suffix.toByteArray(Charsets.UTF_8).size
        val result = StringBuilder()
        for (point in base.codePoints().toArray()) {
            val next = String(Character.toChars(point))
            if ((result.toString() + next).toByteArray(Charsets.UTF_8).size > limit) break
            result.append(next)
        }
        return result.toString().trim(' ', '.').ifEmpty { "Track" } + suffix
    }
    fun available(base: String, extension: String, current: String, occupied: Set<String>): String {
        val names = occupied.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
        for (copy in 1..999) {
            val candidate = filename(base, extension, copy)
            if (candidate.equals(current, true) || candidate.lowercase(java.util.Locale.ROOT) !in names) return candidate
        }
        error("No available filename")
    }
}
