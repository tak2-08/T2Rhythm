// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<AiSettings?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var add by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { settings = withContext(Dispatchers.IO) { AiStore(context).read() } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = "연결 설정을 열 수 없습니다. 기기의 보안 저장소를 확인해 주세요." }
    }
    fun save(next: AiSettings) {
        if (saving) return
        scope.launch {
            saving = true
            try { withContext(Dispatchers.IO) { AiStore(context).save(next) }; settings = next; error = null }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "설정을 저장하지 못했습니다. 다시 시도해 주세요." }
            finally { saving = false }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("AI 연결") }, navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("원할 때만 내 음악 추천과 정리를 도와줍니다. 연결을 켜면 홈에 작은 AI 버튼이 나타납니다.")
                Spacer(Modifier.height(8.dp))
                Text("API 키는 기기의 보안 키로 암호화되며 백업·공유에서 제외됩니다. ChatGPT·Claude 등의 구독과 API 사용 요금은 별도입니다. 모델 이름은 제공자의 현재 지원 목록에 맞게 수정할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            settings?.let { s ->
                item { AiToggle("AI 기능 사용", s.enabled, !saving && s.connections.isNotEmpty()) { save(AiSettings(it, s.connections, s.rotate, s.fallback)) } }
                item { AiToggle("실패 시 다음 연결 사용", s.fallback, !saving) { save(AiSettings(s.enabled, s.connections, s.rotate, it)) } }
                item { AiToggle("요청마다 시작 연결 순환", s.rotate, !saving) { save(AiSettings(s.enabled, s.connections, it, s.fallback)) } }
                item { Text("선택한 연결만 위에서 아래 순서로 사용합니다. 순환을 켜면 매 요청의 시작점이 바뀝니다. 시간 제한·응답 크기 제한·실패 대기 시간이 적용됩니다.", style = MaterialTheme.typography.bodySmall) }
                itemsIndexed(s.connections, key = { _, c -> c.id }) { index, c ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(c.selected, enabled = !saving, onCheckedChange = { checked ->
                                    save(AiSettings(s.enabled, s.connections.map { if (it.id == c.id) AiConnection(c.id, c.provider, c.model, c.key, checked) else it }, s.rotate, s.fallback))
                                })
                                Column(Modifier.weight(1f)) { Text(c.provider.label); Text(c.model, style = MaterialTheme.typography.bodySmall) }
                            }
                            Row {
                                TextButton(enabled = !saving && index > 0, onClick = {
                                    val list = s.connections.toMutableList(); list[index] = list[index - 1].also { list[index - 1] = c }
                                    save(AiSettings(s.enabled, list, s.rotate, s.fallback))
                                }) { Text("위로") }
                                TextButton(enabled = !saving && index < s.connections.lastIndex, onClick = {
                                    val list = s.connections.toMutableList(); list[index] = list[index + 1].also { list[index + 1] = c }
                                    save(AiSettings(s.enabled, list, s.rotate, s.fallback))
                                }) { Text("아래로") }
                                TextButton(enabled = !saving, onClick = { save(AiSettings(s.enabled, s.connections.filterNot { it.id == c.id }, s.rotate, s.fallback)) }) { Text("연결 삭제") }
                            }
                        }
                    }
                }
                item { Button(enabled = !saving && s.connections.size < 16, onClick = { add = true }) { Text("연결 추가") } }
                if (saving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
        }
    }
    if (add) {
        var provider by remember { mutableStateOf(AiProvider.GOOGLE) }
        var expanded by remember { mutableStateOf(false) }
        var model by remember { mutableStateOf(provider.defaultModel) }
        var key by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { add = false }, title = { Text("API 연결 추가") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text(provider.label) }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                        AiProvider.entries.forEach { p -> DropdownMenuItem(text = { Text(p.label) }, onClick = { provider = p; model = p.defaultModel; expanded = false }) }
                    }
                }
                Text(provider.endpoint.substringBeforeLast('/'), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(model, { if (it.length <= 160) model = it }, label = { Text("모델 이름") }, singleLine = true)
                OutlinedTextField(key, { if (it.length <= 4096) key = it }, label = { Text("API 키") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Text("키는 저장 후 다시 표시하지 않습니다. 바꾸려면 해당 연결을 삭제하고 추가하세요.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {
            TextButton(enabled = model.isNotBlank() && key.isNotBlank() && model.none(Char::isISOControl) && key.none(Char::isISOControl), onClick = {
                settings?.let { s -> save(AiSettings(s.enabled, s.connections + AiConnection(UUID.randomUUID().toString(), provider, model.trim(), key.trim()), s.rotate, s.fallback)) }
                key = ""; add = false
            }) { Text("저장") }
        }, dismissButton = { TextButton(onClick = { key = ""; add = false }) { Text("취소") } })
    }
}

@Composable
private fun AiToggle(label: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChecked, enabled = enabled)
    }
}
