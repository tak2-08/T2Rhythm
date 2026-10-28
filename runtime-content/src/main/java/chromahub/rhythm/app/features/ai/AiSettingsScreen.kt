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
import androidx.compose.foundation.layout.FlowRow
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
    var editing by remember { mutableStateOf<AiConnection?>(null) }
    var testing by remember { mutableStateOf<String?>(null) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    val testResults = remember { mutableStateMapOf<String, String>() }
    val transport = remember { AiTransport() }
    LaunchedEffect(Unit) {
        try { settings = withContext(Dispatchers.IO) { AiStore(context).read() } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = "연결 설정을 열 수 없습니다. 기기의 보안 저장소를 확인해 주세요." }
    }
    fun save(next: AiSettings, onSaved: () -> Unit = {}) {
        if (saving) return
        scope.launch {
            saving = true
            try { withContext(Dispatchers.IO) { AiStore(context).save(next) }; settings = next; error = null; onSaved() }
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
                Text("API 키는 기기의 보안 키로 암호화되며 백업·공유에서 제외됩니다. ChatGPT·Claude 등의 구독과 API 사용 요금은 별도입니다. 모델 목록을 불러오거나 모델 이름을 직접 입력할 수 있습니다. 연결 테스트는 가상 곡 하나로 요청하며 소량의 API 사용료가 발생할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
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
                            FlowRow {
                                TextButton(enabled = !saving && index > 0, onClick = {
                                    val list = s.connections.toMutableList(); list[index] = list[index - 1].also { list[index - 1] = c }
                                    save(AiSettings(s.enabled, list, s.rotate, s.fallback))
                                }) { Text("위로") }
                                TextButton(enabled = !saving && index < s.connections.lastIndex, onClick = {
                                    val list = s.connections.toMutableList(); list[index] = list[index + 1].also { list[index + 1] = c }
                                    save(AiSettings(s.enabled, list, s.rotate, s.fallback))
                                }) { Text("아래로") }
                                TextButton(enabled = !saving && testing == null, onClick = { editing = c; add = true }) { Text("연결 수정") }
                                TextButton(enabled = !saving && testing == null, onClick = {
                                    testing = c.id
                                    testResults.remove(c.id)
                                    testJob = scope.launch {
                                        try {
                                            transport.testConnection(c)
                                            AiRouter.session.resetConnection(c.id)
                                            testResults[c.id] = "연결 성공 · 모델 응답과 음악 결과 형식을 확인했습니다."
                                        } catch (cancel: CancellationException) { throw cancel }
                                        catch (failure: AiFailure) { testResults[c.id] = failure.userMessage() }
                                        catch (_: Exception) { testResults[c.id] = "모델의 응답을 확인하지 못했습니다. 연결 설정을 확인해 주세요." }
                                        finally { testing = null }
                                    }
                                }) { Text("연결 테스트") }
                                TextButton(enabled = !saving && testing == null, onClick = { save(AiSettings(s.enabled, s.connections.filterNot { it.id == c.id }, s.rotate, s.fallback)) }) { Text("연결 삭제") }
                            }
                            if (testing == c.id) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                TextButton(onClick = { testJob?.cancel(); testResults[c.id] = "연결 테스트를 취소했습니다." }) { Text("테스트 취소") }
                            }
                            testResults[c.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                item { Button(enabled = !saving && s.connections.size < 16, onClick = { editing = null; add = true }) { Text("연결 추가") } }
                if (saving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
        }
    }
    if (add) {
        AiConnectionDialog(editing, saving, onDismiss = { add = false; editing = null }, onSave = { connection ->
            settings?.let { s ->
                val connections = if (editing == null) s.connections + connection else s.connections.map { if (it.id == connection.id) connection else it }
                save(AiSettings(s.enabled, connections, s.rotate, s.fallback)) {
                    testResults.remove(connection.id)
                    scope.launch { AiRouter.session.resetConnection(connection.id) }
                    add = false; editing = null
                }
            }
        })
    }
}

@Composable
private fun AiToggle(label: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChecked, enabled = enabled)
    }
}


@Composable
private fun AiConnectionDialog(existing: AiConnection?, saving: Boolean, onDismiss: () -> Unit, onSave: (AiConnection) -> Unit) {
    val scope = rememberCoroutineScope()
    val transport = remember { AiTransport() }
    var provider by remember { mutableStateOf(existing?.provider ?: AiProvider.GOOGLE) }
    var expanded by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(existing?.model ?: provider.defaultModel) }
    var key by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var modelMenu by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var lookupJob by remember { mutableStateOf<Job?>(null) }
    fun connection(): AiConnection = AiConnection(existing?.id ?: UUID.randomUUID().toString(), provider,
        model.trim().ifBlank { provider.defaultModel }, key.trim().ifBlank { existing?.key.orEmpty() }, existing?.selected ?: true)
    val validKey = (key.isNotBlank() || existing != null) && key.none(Char::isISOControl)
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(if (existing == null) "API 연결 추가" else "API 연결 수정") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                OutlinedButton(enabled = existing == null && !loading && !saving, onClick = { expanded = true }) { Text(provider.label) }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    AiProvider.entries.forEach { p -> DropdownMenuItem(text = { Text(p.label) }, onClick = {
                        provider = p; model = p.defaultModel; key = ""; models = emptyList(); modelError = null; expanded = false
                    }) }
                }
            }
            OutlinedTextField(model, { if (it.length <= 160) model = it }, label = { Text("모델 이름") }, singleLine = true, enabled = !saving)
            OutlinedTextField(key, { if (it.length <= 4096) { key = it; models = emptyList(); modelError = null } },
                label = { Text(if (existing == null) "API 키" else "새 API 키 (빈칸이면 기존 키 유지)") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !saving && !loading)
            Box {
                OutlinedButton(enabled = validKey && !loading && !saving, onClick = {
                    loading = true; modelError = null
                    lookupJob = scope.launch {
                        try {
                            models = transport.models(connection())
                            if (models.isEmpty()) modelError = "모델 목록이 비어 있습니다. 제공사에서 확인한 이름을 직접 입력해 주세요."
                            else modelMenu = true
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (failure: AiFailure) { modelError = failure.userMessage() }
                        catch (_: Exception) { modelError = "모델 목록을 읽지 못했습니다. 모델 이름을 직접 입력할 수 있습니다." }
                        finally { loading = false }
                    }
                }) { Text("제공사 모델 목록 불러오기") }
                DropdownMenu(modelMenu, onDismissRequest = { modelMenu = false }, modifier = Modifier.heightIn(max = 300.dp)) {
                    models.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { model = name; modelMenu = false }) }
                }
            }
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { lookupJob?.cancel() }) { Text("목록 조회 취소") }
            }
            modelError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text("목록에 없는 모델도 직접 입력할 수 있습니다. 텍스트 생성 모델을 선택하고 저장 후 연결 테스트로 확인하세요. 저장된 키는 다시 표시하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TextButton(enabled = !saving && !loading && validKey && model.isNotBlank() && model.none(Char::isISOControl), onClick = { onSave(connection()) }) { Text("저장") }
    }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("취소") } })
}
