package com.knte.preview.diary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.knte.core.diary.DiaryCodec

@Composable
fun DiaryScreen(viewModel: DiaryViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var discard by rememberSaveable { mutableStateOf(false) }
    var showWriteDetails by rememberSaveable { mutableStateOf(false) }
    fun cancelEditor() {
        if (!state.busy) {
            if (state.hasUnsavedChanges) discard = true else viewModel.discard()
        }
    }
    BackHandler(enabled = state.draft != null) { cancelEditor() }
    if (discard && state.draft != null) AlertDialog(
        onDismissRequest = { discard = false }, title = { Text("放弃隔离日记草稿？") },
        text = { Text("不会修改或删除已保存的 Markdown 文档。") },
        confirmButton = { TextButton(onClick = { discard = false; viewModel.discard() }) { Text("放弃草稿") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    state.confirmation?.let { plan ->
        AlertDialog(onDismissRequest = viewModel::dismissConfirmation,
            title = { Text("核对后写入隔离库") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("目标：${plan.name}\n范围：本App私有隔离验证库，非真实Vault\n操作：${if (plan.expectedRevision == null) "新建；同名拒绝" else "修订正文；保留原元信息与前镜像"}")
                Text("日期：${state.draft?.date}\n${if (plan.expectedRevision == null) "source=self · type=journal" else "元信息按原字节保留"}")
                Text("取消不会写入。请勿同时从其他应用修改同一文档。")
                TextButton(onClick = { showWriteDetails = !showWriteDetails }) {
                    Text(if (showWriteDetails) "收起校验详情" else "查看校验详情")
                }
                if (showWriteDetails) {
                    Text("新内容 SHA-256：${plan.proposedRevision}", style = MaterialTheme.typography.bodySmall)
                    Text("仅本进程合作式锁；不承诺多应用外部并发安全。", style = MaterialTheme.typography.bodySmall)
                }
            } },
            confirmButton = { TextButton(onClick = viewModel::commit, enabled = !state.busy) { Text("确认写入") } },
            dismissButton = { TextButton(onClick = viewModel::dismissConfirmation) { Text("取消") } })
    }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("日记 · Markdown 隔离验证", style = MaterialTheme.typography.titleLarge)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (state.reviewRequired) Text("待人工核对：该状态下禁止再次写入，不自动恢复或清理 pending。")
            val d = state.draft
            if (d != null) {
                Text(if (state.hasUnsavedChanges) "草稿，尚未保存" else "内容未修改", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(d.date, viewModel::dateChanged, singleLine = true,
                    enabled = !state.busy && !state.reviewRequired && d.name == null,
                    label = { Text("日期 YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.body, viewModel::bodyChanged, minLines = 6,
                    enabled = !state.busy && !state.reviewRequired, label = { Text("正文") },
                    supportingText = { Text("${runCatching { DiaryCodec.encode(d.body).size }.getOrDefault(0)} / $DIARY_DRAFT_BYTES UTF-8字节") },
                    modifier = Modifier.fillMaxWidth())
                Text(if (d.name == null) "新建使用 date/source/type/tags 元信息；同日同名不覆盖。" else "只修订正文，原 frontmatter、未知键、注释与换行前缀保持不变。",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                if (state.loaded && state.entries.isEmpty() && state.error == null) Text("隔离库读取成功，暂无日记。")
                state.entries.forEach { entry ->
                    OutlinedButton(onClick = { viewModel.open(entry.name) }, enabled = !state.busy && !state.reviewRequired,
                        shape = RoundedCornerShape(3.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth()) {
                        Text(entry.name); Text(entry.document.body.take(160), style = MaterialTheme.typography.bodyMedium)
                        if (!entry.document.editable) Text("只读：${entry.document.problems.joinToString()}")
                    } }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.draft == null) {
                Button(onClick = viewModel::newDiary, enabled = !state.busy && !state.reviewRequired) { Text("新建日记") }
                TextButton(onClick = viewModel::reload, enabled = !state.busy) { Text("重新读取") }
            } else {
                Button(onClick = viewModel::prepare, enabled = !state.busy && !state.reviewRequired) { Text("核对保存") }
                TextButton(onClick = { cancelEditor() }, enabled = !state.busy) { Text("取消编辑") }
            }
        }
    }
}
