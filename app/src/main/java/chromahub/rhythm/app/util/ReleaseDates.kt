// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

object ReleaseDates {
    fun display(value: String?, year: Int = 0): String? {
        val text = value.orEmpty().trim()
        val date = Regex("^(\\d{4})[-./]?(\\d{2})[-./]?(\\d{2})(?:\\D.*)?$").matchEntire(text)
        if (date != null) runCatching {
            java.time.LocalDate.of(date.groupValues[1].toInt(), date.groupValues[2].toInt(), date.groupValues[3].toInt())
        }.getOrNull()?.takeIf { it.year in 1800..2199 }?.let { return it.toString() }
        return Regex("^(?:18|19|20|21)\\d{2}$").matchEntire(text)?.value ?: year.takeIf { it in 1800..2199 }?.toString()
    }
}
