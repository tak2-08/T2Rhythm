// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.util

import org.junit.Assert.*
import org.junit.Test

class PlaylistExportNamesTest {
    @Test fun duplicateNamesAndGeneratedNameCollisionsStayDistinct() {
        assertEquals(listOf("Mix.json", "2-mix.json", "2-2-mix.json", "3-MIX.json"),
            allocatePlaylistExportNames(listOf("Mix.json", "mix.json", "2-mix.json", "MIX.json")))
    }
    @Test fun koreanNamesArePreservedAndDisambiguated() {
        assertEquals(listOf("내 음악.m3u8", "2-내 음악.m3u8", "여름.m3u8"),
            allocatePlaylistExportNames(listOf("내 음악.m3u8", "내 음악.m3u8", "여름.m3u8")))
    }
}
