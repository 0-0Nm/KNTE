package com.knte.preview.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.knte.core.notes.Note
import com.knte.preview.MAX_RESTORABLE_BODY_BYTES
import com.knte.preview.MAX_TITLE_CHARS
import com.knte.preview.NoteDraft
import com.knte.preview.NotesStatus
import com.knte.preview.NotesUiState
import com.knte.preview.NoteViewModel
import com.knte.preview.utf8ByteCount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(viewModel: NoteViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val busy = state.isBusy

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("预览记录") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                actions = {
                    TextButton(
                        onClick = { viewModel.reload() },
                        enabled = !busy,
                        modifier = Modifier.semantics { contentDescription = "重新读取记录列表" },
                    ) { Text("重新读取") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PreviewNotices()
            UnifiedStatus(state = state, onDismissError = viewModel::dismissError)
            state.notice?.let { text ->
                NoticeCard(text = text, onDismiss = viewModel::dismissNotice)
            }

            val draft = state.draft
            if (draft != null) {
                NoteEditor(
                    draft = draft,
                    enabled = !busy,
                    onTitleChange = viewModel::onTitleChange,
                    onBodyChange = viewModel::onBodyChange,
                    onSave = viewModel::save,
                    onCancel = viewModel::cancel,
                )
            } else {
                NoteList(
                    state = state,
                    enabled = !busy,
                    onOpen = viewModel::open,
                    onNew = viewModel::newNote,
                    onReload = viewModel::reload,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PreviewNotices() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("KNTE Preview", style = MaterialTheme.typography.titleMedium)
            Text("仅此预览App私有记录，不连接现有Vault", style = MaterialTheme.typography.bodySmall)
            Text("未实现旧日记迁移", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UnifiedStatus(state: NotesUiState, onDismissError: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "记录状态" },
    ) {
        when (state.status) {
            NotesStatus.Loading -> StatusLine("正在读取记录…", busy = true)
            NotesStatus.Saving -> StatusLine("正在保存，草稿尚未确认…", busy = true)
            NotesStatus.Empty -> Text(
                "暂无记录；点击「新建」创建第一条预览记录。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NotesStatus.Ready -> Text(
                "已读取 ${state.notes.size} 条记录。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NotesStatus.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "错误：${state.error ?: "未知错误"}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissError) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, busy: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NoticeCard(text: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "提示：$text" },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "关闭提示" },
            ) { Text("关闭") }
        }
    }
}

@Composable
private fun NoteList(
    state: NotesUiState,
    enabled: Boolean,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text("记录列表", style = MaterialTheme.typography.titleSmall)
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.notes, key = { it.id }) { note ->
            NoteRow(note = note, enabled = enabled, onClick = { onOpen(note.id) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onNew,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "新建预览记录" },
        ) { Text("新建") }
        OutlinedButton(
            onClick = onReload,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "重新读取记录列表" },
        ) { Text("重新读取") }
    }
}

@Composable
private fun NoteRow(note: Note, enabled: Boolean, onClick: () -> Unit) {
    val title = note.title.ifBlank { "(无标题)" }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "打开记录：$title" },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                note.body.ifBlank { "(空正文)" }.take(120),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "修订 ${note.revision} · 更新 ${note.updatedAtMillis}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun NoteEditor(
    draft: NoteDraft,
    enabled: Boolean,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val isNew = draft.id == null
    Text(
        if (isNew) {
            "新建记录（草稿，尚未确认保存）"
        } else {
            "编辑记录（草稿；修订 ${draft.revision ?: "未知"}，保存时重新核验）"
        },
        style = MaterialTheme.typography.titleSmall,
    )
    OutlinedTextField(
        value = draft.title,
        onValueChange = onTitleChange,
        enabled = enabled,
        singleLine = true,
        label = { Text("标题") },
        supportingText = { Text("最多 $MAX_TITLE_CHARS 字符") },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "记录标题输入框" },
    )
    val bodyBytes = draft.body.utf8ByteCount()
    OutlinedTextField(
        value = draft.body,
        onValueChange = onBodyChange,
        enabled = enabled,
        minLines = 4,
        maxLines = 8,
        label = { Text("正文") },
        supportingText = {
            val suffix = if (!draft.restorable) " · 本草稿不可在进程恢复后保留" else ""
            Text("上限 32 KiB · 当前 $bodyBytes / $MAX_RESTORABLE_BODY_BYTES 字节$suffix")
        },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "记录正文输入框" },
    )
    Text(
        if (isNew) {
            "当前为草稿，尚未保存；只有「保存」成功并重新读取列表后才算已保存。"
        } else {
            "当前为草稿，尚未保存；保存前会重新读取真实 revision 做 CAS 校验。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onSave,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "保存记录（写入仓库）" },
        ) { Text("保存") }
        OutlinedButton(
            onClick = onCancel,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "取消编辑并放弃草稿" },
        ) { Text("取消") }
    }
}
