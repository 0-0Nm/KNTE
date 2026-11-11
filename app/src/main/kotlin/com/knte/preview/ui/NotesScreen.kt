package com.knte.preview.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.knte.preview.*
import java.text.DateFormat
import java.util.Date

/** Scrollable editor with viewport-independent actions. No technical revision exposed by default. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(viewModel: NoteViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val draft = state.draft
    BackHandler(enabled = draft != null) { if (!state.isBusy) confirmDiscard = true }
    if (confirmDiscard && draft != null) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的编辑？") },
            text = { Text("本次草稿尚未保存。继续编辑可保留输入；放弃只清除此草稿，不删除已保存记录。") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; viewModel.cancel() }) { Text("放弃草稿") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
        )
    }
    Scaffold(
        modifier = modifier.imePadding(),
        topBar = {
            TopAppBar(title = { Text(if (draft == null) "知温·预览" else if (draft.id == null) "新建记录" else "编辑记录") },
                actions = {
                    if (draft == null) TextButton(onClick = viewModel::reload, enabled = !state.isBusy) { Text("重新读取") }
                })
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (draft == null) {
                        Button(onClick = viewModel::newNote, enabled = !state.isBusy, modifier = Modifier.weight(1f).semantics { contentDescription = "新建预览记录" }) { Text("新建") }
                    } else {
                        Button(onClick = viewModel::save, enabled = !state.isBusy, modifier = Modifier.weight(1f).semantics { contentDescription = "保存记录" }) { Text(if (state.isSaving) "保存中…" else "保存") }
                        OutlinedButton(onClick = { confirmDiscard = true }, enabled = !state.isBusy, modifier = Modifier.weight(1f)) { Text("取消") }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("仅预览 App 私有记录 · 不连接现有 Vault", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { error ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                    Text(error, Modifier.padding(10.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
            state.notice?.let { notice ->
                Row(Modifier.fillMaxWidth()) {
                    Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = viewModel::dismissNotice, contentPadding = PaddingValues(0.dp)) { Text("关闭") }
                }
            }
            if (draft != null) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("草稿，尚未保存", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(draft.title, viewModel::onTitleChange, enabled = !state.isBusy, singleLine = true,
                        label = { Text("标题") }, supportingText = { Text("最多 $MAX_TITLE_CHARS 个字符") },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "记录标题输入框" })
                    OutlinedTextField(draft.body, viewModel::onBodyChange, enabled = !state.isBusy, minLines = 6, maxLines = 12,
                        label = { Text("正文") }, supportingText = { Text("${draft.body.utf8ByteCount()} / $MAX_RESTORABLE_BODY_BYTES 字节") },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "记录正文输入框" })
                    Text("当前输入只有保存后才成为记录。预览版尚不提供旧日记迁移。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("${state.notes.size} 条记录", style = MaterialTheme.typography.titleSmall)
                if (state.notes.isEmpty() && !state.isBusy && state.error == null) Text("暂无记录，点击下方新建。")
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                    items(state.notes, key = { it.id }) { note ->
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !state.isBusy, role = Role.Button) { viewModel.open(note.id) }.semantics { contentDescription = "打开记录：${note.title}" }) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(note.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(note.body.ifEmpty { "（空正文）" }, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(note.updatedAtMillis)), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
