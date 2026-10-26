// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.shared.presentation.screens.settings

import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SettingsSearchRegressionTest {
    private fun item(title: String, vararg keywords: String) = SearchableSettingItem(
        title, title, "", keywords.toList(), MaterialSymbolIcon("search"), null, "설정")
    @Test fun everyWordMustMatchAndKoreanInitialsWorkAcrossFields() {
        val queue = item("대기열 기억", "queue", "remember")
        val unrelated = item("셔플", "queue", "shuffle")
        val index = buildSettingsTextIndex(listOf(unrelated, queue))
        assertEquals(listOf(queue), index.search("queue remember"))
        assertEquals(listOf(queue), index.search("ㄷㄱ열 ㄱ억"))
        assertTrue(index.search("queue missing").isEmpty())
    }
    @Test fun searchNormalizationDoesNotDependOnTurkishLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val entry = item("MINI PLAYER", "compact")
            assertEquals(listOf(entry), buildSettingsTextIndex(listOf(entry)).search("ｍｉｎｉ compact"))
        } finally { Locale.setDefault(before) }
    }
}
