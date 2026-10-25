// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.ai

import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.Song
import java.time.ZonedDateTime
import kotlinx.coroutines.*

private class PreparedAiRequest(val operation: AiOperation, val payload: String, val songs: Map<String, Song>, val settings: AiSettings)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiMusicScreen(viewModel: MusicViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val songs by viewModel.filteredSongs.collectAsState()
    val available by AiAvailability.state.collectAsState()
    var settings by remember { mutableStateOf<AiSettings?>(null) }
    var operation by remember { mutableStateOf(AiOperation.PLAYLIST) }
    var prompt by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var preferLikes by remember { mutableStateOf(true) }
    var situation by remember { mutableStateOf(ZonedDateTime.now().let { "${it.monthValue}월, ${it.hour}시" }) }
    var prepared by remember { mutableStateOf<PreparedAiRequest?>(null) }
    var request by remember { mutableStateOf<PreparedAiRequest?>(null) }
    var answer by remember { mutableStateOf<AiAnswer?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var playlistName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var consumed by remember { mutableStateOf(false) }
    var editConfirm by remember { mutableStateOf<Pair<Song, Song>?>(null) }
    var trashConfirm by remember { mutableStateOf<List<Song>?>(null) }
    var undos by remember { mutableStateOf(emptyList<AiUndo>()) }
    var forgetConfirm by remember { mutableStateOf<AiUndo?>(null) }
    val router = remember { AiRouter.session }
    val transport = remember { AiTransport() }
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            consumed = true
            viewModel.refreshLibrary(showMediaScanLoader = false)
            message = "시스템 휴지통으로 옮겼습니다. 시스템의 파일·사진 관리 화면에서 복원할 수 있습니다."
        } else message = "휴지통 이동을 취소했습니다."
    }
    LaunchedEffect(Unit) {
        try {
            settings = withContext(Dispatchers.IO) { AiStore(context).read() }
            undos = withContext(Dispatchers.IO) { AiLibraryEdits(context).entries() }
            if (settings?.available != true) onBack()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { message = "AI 설정을 읽지 못했습니다. 설정에서 연결을 확인해 주세요." }
    }
    LaunchedEffect(available) { if (!available && settings != null) { job?.cancel(); onBack() } }
    fun live(original: Song): Song = viewModel.songs.value.firstOrNull { it.id == original.id && it.uri == original.uri }
        ?.also { require(AiLibraryEdits.sameTags(it, original)) } ?: throw IllegalArgumentException("Stale song")
    fun send(snapshot: PreparedAiRequest) {
        job = scope.launch {
            busy = true; sending = true; message = null; answer = null; consumed = false
            try {
                val current = withContext(Dispatchers.IO) { AiStore(context).read() }
                require(current.available && snapshot.settings.connections.filter { it.selected }.all { old ->
                    current.connections.any { it.id == old.id && it.selected && it.key == old.key && it.model == old.model }
                })
                val result = router.request(snapshot.settings.connections, snapshot.settings.rotate, snapshot.settings.fallback,
                    snapshot.operation, snapshot.songs.keys) { connection -> transport.complete(connection, snapshot.payload) }
                request = snapshot; answer = result; selected = result.plan.ids.toSet(); playlistName = result.plan.title
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = "추천을 받지 못했습니다. 연결·모델·잔여 한도를 확인하고 잠시 후 다시 시도해 주세요. 음악 재생은 계속 사용할 수 있습니다." }
            finally { busy = false; sending = false }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("AI") }, navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("내 음악으로 지금 듣고 싶은 흐름을 만들어 보세요.", style = MaterialTheme.typography.titleMedium) }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiOperation.entries.forEach { option -> FilterChip(operation == option, onClick = { operation = option }, enabled = !busy, label = { Text(option.label) }) }
                }
            }
            item { OutlinedTextField(prompt, { if (it.length <= 2000) prompt = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("어떤 음악이나 정리가 필요한가요?") }, placeholder = { Text("비 오는 저녁에 걸으며 들을 잔잔한 곡 15개") }, minLines = 2, maxLines = 5) }
            item { OutlinedTextField(genre, { if (it.length <= 80) genre = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("장르로 범위 좁히기 (선택)") }, singleLine = true) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(preferLikes, enabled = !busy, onCheckedChange = { preferLikes = it })
                    Text("좋아요 음악을 후보에 우선 포함", style = MaterialTheme.typography.bodyMedium)
                }
                Text("선호도는 기기 안에서 후보를 고르는 데만 사용합니다.", style = MaterialTheme.typography.bodySmall)
            }
            item { OutlinedTextField(situation, { if (it.length <= 500) situation = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("상황 · 계절 · 날씨 · 이동 상태 (선택)") }, maxLines = 3) }
            item { TextButton(enabled = !busy, onClick = {
                scope.launch {
                    val value = withContext(Dispatchers.IO) {
                        val s = chromahub.rhythm.app.features.local.data.experience.ListeningContext.get(context).snapshot()
                        listOfNotNull("${ZonedDateTime.now().monthValue}월, ${s.hour}시", s.weather?.let { "${it.group}, ${it.temperatureC}°C" }, s.motion?.let { "이동 상태: ${it.kind}" }).joinToString(", ")
                    }
                    situation = value.take(500)
                }
            }) { Text("기존에 수집된 상황 불러오기") } }
            item { Text("전송 전 내용을 확인할 수 있습니다. 곡명·아티스트·앨범·장르·길이와 입력한 상황만 보냅니다. 음원, 가사, 파일 경로, 위치 좌표, API 키와 청취 기록은 요청 내용에 포함하지 않습니다. 좁힌 범위에서 최대 120곡을 골라 요청합니다.", style = MaterialTheme.typography.bodySmall) }
            item {
                Button(enabled = !busy && settings?.available == true && prompt.isNotBlank() && songs.isNotEmpty(), onClick = {
                    job = scope.launch {
                        busy = true
                        try {
                            val snapshot = songs
                            val favorites = if (preferLikes) viewModel.favoriteSongs.value else emptySet()
                            val s = settings ?: return@launch
                            prepared = withContext(Dispatchers.Default) {
                                val candidates = AiCandidates.select(snapshot.asSequence()
                                    .filter { it.uri.scheme == "content" || it.uri.scheme == "file" }
                                    .filter { genre.isBlank() || it.genre.orEmpty().contains(genre.trim(), true) }, prompt,
                                    text = { "${it.title} ${it.artist} ${it.album} ${it.genre}" },
                                    favorite = { it.id in favorites }, id = { it.id })
                                val mapping = candidates.mapIndexed { index, song -> "t${index + 1}" to song }.toMap()
                                val tracks = mapping.map { (id, song) -> AiTrack(id, song.title, song.artist, song.album, song.genre.orEmpty(), song.duration / 1000) }
                                PreparedAiRequest(operation, AiProtocol.payload(operation, prompt, situation, tracks), mapping, s)
                            }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { message = "선택한 범위에 음악이 없거나 요청이 너무 큽니다. 장르와 입력 내용을 확인해 주세요." }
                        finally { busy = false }
                    }
                }) { Text("전송 내용 확인") }
            }
            if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); if (sending) TextButton(onClick = { job?.cancel(); message = "요청을 취소했습니다." }) { Text("취소") } } }
            message?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
            answer?.let { result ->
                item { Text(result.plan.title, style = MaterialTheme.typography.titleLarge); Text(result.plan.explanation); Text("${result.provider} · ${result.attempts}회 시도", style = MaterialTheme.typography.bodySmall) }
                if (result.plan.operation == AiOperation.PLAYLIST) item { OutlinedTextField(playlistName, { if (it.length <= 120) playlistName = it }, label = { Text("플레이리스트 이름") }, enabled = !consumed) }
                items(result.plan.ids, key = { it }) { id ->
                    request?.songs?.get(id)?.let { original ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(id in selected, enabled = !consumed && !busy, onCheckedChange = { checked -> selected = if (checked) selected + id else selected - id })
                                    Column(Modifier.weight(1f)) { Text(original.title); Text("${original.artist} · ${original.album}", style = MaterialTheme.typography.bodySmall) }
                                    TextButton(onClick = { viewModel.playSong(original) }) { Text("듣기") }
                                }
                                result.plan.edits.firstOrNull { it.id == id }?.let { edit ->
                                    val changed = original.copy(title = edit.title ?: original.title, artist = edit.artist ?: original.artist, album = edit.album ?: original.album, genre = edit.genre ?: original.genre)
                                    Text(tagDiff(original, changed), style = MaterialTheme.typography.bodySmall)
                                    TextButton(enabled = !busy, onClick = { editConfirm = original to changed }) { Text("이 곡의 변경 검토") }
                                }
                            }
                        }
                    }
                }
                if (result.plan.operation != AiOperation.EDIT) item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = selected.isNotEmpty() && !consumed && !busy && playlistName.isNotBlank(), onClick = {
                            try {
                                val chosen = result.plan.ids.filter { it in selected }.map { live(requireNotNull(request?.songs?.get(it))) }
                                when (result.plan.operation) {
                                    AiOperation.TRASH -> trashConfirm = chosen
                                    AiOperation.SHARE -> {
                                        val text = buildString { appendLine(result.plan.title); chosen.forEach { appendLine("${it.title} — ${it.artist} · ${it.album}") } }
                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "음악 목록 공유"))
                                    }
                                    else -> {
                                        consumed = true
                                        viewModel.createPlaylist(playlistName.trim(), chosen) { message = it }
                                    }
                                }
                            } catch (_: Exception) { message = "음악 정보가 바뀌었거나 작업을 시작할 수 없습니다. 다시 요청해 주세요." }
                        }) { Text(when (result.plan.operation) { AiOperation.TRASH -> "휴지통 이동 검토"; AiOperation.SHARE -> "공유 화면 열기"; else -> "선택한 곡으로 저장" }) }
                        TextButton(enabled = selected.isNotEmpty(), onClick = {
                            try { viewModel.playSongs(result.plan.ids.filter { it in selected }.map { live(requireNotNull(request?.songs?.get(it))) }) }
                            catch (_: Exception) { message = "음악 목록을 새로 확인해 주세요." }
                        }) { Text("선택한 곡 듣기") }
                    }
                }
            }
            if (undos.isNotEmpty()) item { Text("보관함 정보 되돌리기", style = MaterialTheme.typography.titleMedium) }
            items(undos, key = { it.id }) { undo ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(undo.before.title, Modifier.weight(1f))
                    TextButton(enabled = !busy, onClick = {
                        job = scope.launch {
                            busy = true
                            try {
                                viewModel.applyReviewedLibraryMetadata(live(undo.after), undo.before)
                                withContext(Dispatchers.IO) { AiLibraryEdits(context).forget(undo) }
                                undos = undos.filterNot { it.id == undo.id }; message = "변경 전 정보로 되돌렸습니다."
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { message = "곡이 이후에 변경되었거나 접근할 수 없어 덮어쓰지 않았습니다." }
                            finally { busy = false }
                        }
                    }) { Text("되돌리기") }
                    TextButton(enabled = !busy, onClick = { forgetConfirm = undo }) { Text("기록 정리") }
                }
            }
        }
    }
    forgetConfirm?.let { undo ->
        AlertDialog(onDismissRequest = { forgetConfirm = null }, title = { Text("되돌리기 기록 정리") },
            text = { Text("${undo.before.title}\n현재 보관함 정보는 유지합니다. 이 기록을 지우면 해당 변경을 되돌리는 데 사용할 수 없습니다. 음원 파일은 변경하지 않습니다.") },
            confirmButton = { TextButton(onClick = {
                forgetConfirm = null
                job = scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) { AiLibraryEdits(context).forget(undo) }
                        undos = undos.filterNot { it.id == undo.id }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { message = "기록을 정리하지 못했습니다. 다시 시도해 주세요." }
                    finally { busy = false }
                }
            }) { Text("기록만 삭제") } },
            dismissButton = { TextButton(onClick = { forgetConfirm = null }) { Text("취소") } })
    }
    prepared?.let { snapshot ->
        AlertDialog(onDismissRequest = { prepared = null }, title = { Text("전송 내용 확인") }, text = {
            Column {
                Text("${snapshot.songs.size}곡 · ${snapshot.operation.label}")
                Text("대상: " + snapshot.settings.connections.filter { it.selected }.joinToString(" → ") { it.provider.label })
                Text(if (snapshot.settings.fallback) "실패 시 선택한 다음 제공자에게 같은 내용을 전송합니다." else "시작 제공자에게 한 번 요청합니다.", style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text(snapshot.payload, Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = { TextButton(onClick = { prepared = null; send(snapshot) }) { Text("전송") } }, dismissButton = { TextButton(onClick = { prepared = null }) { Text("취소") } })
    }
    editConfirm?.let { (before, after) ->
        AlertDialog(onDismissRequest = { editConfirm = null }, title = { Text("보관함 정보 수정") }, text = { Text(tagDiff(before, after) + "\n\n변경 전 정보를 보관하고 되돌리기를 제공합니다. 음원 파일의 태그·이름·내용은 변경하지 않습니다.") }, confirmButton = {
            TextButton(onClick = {
                editConfirm = null
                job = scope.launch {
                    busy = true
                    try {
                        val original = live(before)
                        val entry = withContext(Dispatchers.IO) { AiLibraryEdits(context).prepare(original, after) }
                        undos = undos + entry
                        viewModel.applyReviewedLibraryMetadata(original, after)
                        message = "보관함 정보를 수정했습니다. 아래에서 되돌릴 수 있습니다."
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { message = "변경을 완료하지 못했습니다. 현재 정보와 되돌리기 기록을 확인해 주세요." }
                    finally { busy = false }
                }
            }) { Text("수정") }
        }, dismissButton = { TextButton(onClick = { editConfirm = null }) { Text("취소") } })
    }
    trashConfirm?.let { originals ->
        AlertDialog(onDismissRequest = { trashConfirm = null }, title = { Text("${originals.size}곡을 휴지통으로 이동") }, text = { Text(originals.joinToString("\n") { "${it.title} · ${it.artist}" } + "\n\n다음 시스템 화면에서도 확인합니다. 복원 가능 기간은 시스템 정책을 따릅니다. Android 11 미만과 시스템 음원 외 파일에는 실행하지 않습니다.") }, confirmButton = {
            TextButton(onClick = {
                trashConfirm = null
                try {
                    if (Build.VERSION.SDK_INT < 30) throw IllegalArgumentException("System trash unavailable")
                    val chosen = originals.map(::live)
                    require(chosen.all { it.uri.scheme == "content" && it.uri.authority == MediaStore.AUTHORITY })
                    val intent = MediaStore.createTrashRequest(context.contentResolver, chosen.map { it.uri }, true)
                    trashLauncher.launch(IntentSenderRequest.Builder(intent.intentSender).build())
                } catch (_: Exception) { message = "이 기기 또는 파일은 안전한 시스템 휴지통을 지원하지 않습니다. 파일을 변경하지 않았습니다." }
            }) { Text("시스템 확인 열기") }
        }, dismissButton = { TextButton(onClick = { trashConfirm = null }) { Text("취소") } })
    }
}

private fun tagDiff(before: Song, after: Song): String = listOfNotNull(
    if (before.title != after.title) "곡명: ${before.title} → ${after.title}" else null,
    if (before.artist != after.artist) "아티스트: ${before.artist} → ${after.artist}" else null,
    if (before.album != after.album) "앨범: ${before.album} → ${after.album}" else null,
    if (before.genre != after.genre) "장르: ${before.genre.orEmpty()} → ${after.genre.orEmpty()}" else null,
).joinToString("\n").ifBlank { "변경된 항목이 없습니다." }
