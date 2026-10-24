// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.updates

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.shared.presentation.viewmodel.AppVersion

@Composable fun InstalledReleaseNotesPrompt(version: AppVersion, enabled: Boolean) {
    val prefs = LocalContext.current.getSharedPreferences("installed_release_notes", 0)
    var unread by remember(version.versionCode) { mutableStateOf(prefs.getInt("seen", -1) != version.versionCode) }
    if (enabled && unread && version.releaseNotes.isNotBlank()) ReleaseNotesDialog(version) {
        prefs.edit().putInt("seen", version.versionCode).apply()
        unread = false
    }
}

@Composable fun InstalledReleaseNotesCard(version: AppVersion) {
    var open by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().testTag("installed-release-notes")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.installed_release_notes), style = MaterialTheme.typography.titleMedium)
            Text(version.versionName, style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton({ open = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.release_notes_open)) }
        }
    }
    if (open) ReleaseNotesDialog(version) { open = false }
}

@Composable fun ReleaseNotesDialog(version: AppVersion, onDismiss: () -> Unit) {
    val issuesTitle = stringResource(R.string.updates_known_issues)
    val text = remember(version.releaseNotes, issuesTitle) {
        val parsed = ReleaseNotesParser.parse(version.releaseNotes)
        fun bullets(items: List<String>) = items.joinToString("\n\n") {
            "• " + androidx.core.text.HtmlCompat.fromHtml(it, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
        }
        listOf(bullets(parsed.whatsNew), if (parsed.knownIssues.isEmpty()) "" else issuesTitle + "\n\n" + bullets(parsed.knownIssues))
            .filter { it.isNotBlank() }.joinToString("\n\n")
    }
    AlertDialog(onDismissRequest = onDismiss,
        modifier = Modifier.testTag("installed-release-notes-dialog"),
        title = { Text(stringResource(R.string.release_notes_title) + " · " + version.versionName) },
        text = { SelectionContainer {
            Text(text, Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()))
        } },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.release_notes_done)) } })
}
