// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.updates

data class ParsedReleaseNotes(val whatsNew: List<String>, val knownIssues: List<String>)

/** Accept hand-written Korean notes as well as the historical release generator format. */
object ReleaseNotesParser {
    fun parse(body: String?): ParsedReleaseNotes {
        val changes = mutableListOf<String>()
        val issues = mutableListOf<String>()
        val fallback = mutableListOf<String>()
        var section = 0
        body.orEmpty().lineSequence().forEach { raw ->
            val line = raw.trim()
            val heading = line.trimStart('#').replace("**", "").trim().trimEnd(':').lowercase()
                .replace(Regex("[\\s’']"), "")
            when {
                heading in setOf("whatsnew", "changes", "changelog", "업데이트내용", "변경사항", "새로운기능") -> section = 1
                heading.startsWith("knownissues") || heading in setOf("알려진문제", "알려진문제점") -> section = 2
                heading in setOf("buildinformation", "검증결과", "검증", "downloads", "다운로드", "소스") -> section = 3
                line.startsWith("#") -> section = 0
                line.isNotBlank() -> {
                    val bullet = Regex("^[-*•]\\s+|^\\d+[.)]\\s+")
                    val text = line.replace(bullet, "")
                    if (section == 1) changes += html(text)
                    else if (section == 2) issues += html(text)
                    else if (section == 0 && bullet.containsMatchIn(line)) fallback += html(text)
                }
            }
        }
        val visible = changes.ifEmpty { fallback }.ifEmpty {
            if (issues.isEmpty() && !body.isNullOrBlank()) listOf(html(body.trim())) else emptyList()
        }
        return ParsedReleaseNotes(visible.distinct(), issues.distinct())
    }

    private fun html(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
}
