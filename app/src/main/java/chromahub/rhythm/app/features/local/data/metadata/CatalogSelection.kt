// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.metadata

import kr.co.metadata.crawler.*

object CatalogSelection {
    fun selectable(field: CatalogField, lyricsOnly: Boolean) = if (lyricsOnly) field == CatalogField.TRACK_LYRICS else field !in setOf(
        CatalogField.ARTIST_DESCRIPTION, CatalogField.ARTIST_DEBUT_DATE, CatalogField.ARTIST_IMAGE, CatalogField.ALBUM_DESCRIPTION)
    fun defaults(document: CatalogDocument?, lyricsOnly: Boolean): Set<CatalogField> = document?.values.orEmpty()
        .filter { it.hasValue() && selectable(it.field, lyricsOnly) && (lyricsOnly || it.field != CatalogField.TRACK_LYRICS) }
        .map { it.field }.toSet()
}
