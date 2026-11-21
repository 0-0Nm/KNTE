package com.knte.preview.time

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.knte.core.time.projection.TimeRecord
import com.knte.preview.time.host.TimeMonitorControls

@Composable
fun TimeScreen(viewModel: TimeViewModel) {
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
        onDismissRequest = { discard = false },
        title = { Text("放弃未提交的项目名？") },
        text = { Text("取消不会向隔离库追加任何事件；已追加的记录保持原样。") },
        confirmButton = { TextButton(onClick = { discard = false; viewModel.discard() }) { Text("放弃草稿") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续") } })

    state.confirmation?.takeUnless { state.reviewRequired }?.let { c ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirmation,
            title = { Text(if (c.op == "start") "核对后追加 start" else "核对后追加 stop") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        if (c.op == "start") {
                            "项目：${c.project}\n开始时间：${c.date} ${c.start}\n隔离目的地：$TIME_STORE_LABEL"
                        } else {
                            "项目：${c.project}\n开始时间：${c.date} ${c.start}\n结束时间：${c.endDate ?: c.date} ${c.end}"
                        }
                    )
                    Text(
                        "以上时间来自权威控制器签发的本核 prepare 会话；取消不写入任何事件。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("取消不会写入。记录状态与时长均由事件投影实时派生，不写入状态列。")
                    TextButton(onClick = { showWriteDetails = !showWriteDetails }) {
                        Text(if (showWriteDetails) "收起校验详情" else "查看校验详情")
                    }
                    if (showWriteDetails) {
                        Text("host handle：${c.handle.value}", style = MaterialTheme.typography.bodySmall)
                        Text("目标 recordId：${c.targetId.ifBlank { "确认后由主机绑定" }}", style = MaterialTheme.typography.bodySmall)
                        Text("计划由唯一 authority 保持；原始 ledger plan 不离开 controller。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmCommit, enabled = !state.busy) { Text("确认追加") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissConfirmation) { Text("取消") } })
    }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("时间 · 隔离追加验证", style = MaterialTheme.typography.titleLarge)
            Text(TIME_STORE_LABEL, style = MaterialTheme.typography.bodySmall)
            if (state.draft == null && state.confirmation == null) {
                TimeMonitorControls(canStart = state.loaded && !state.busy && !state.remoteOperation && !state.reviewRequired && state.error == null)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (state.remoteOperation && !state.reviewRequired) Text("另一入口正在准备或提交，请在原入口处理。")
            if (state.reviewRequired) Text("待人工核对：该状态下禁止再次追加，不自动重试、不自动清理 pending。")

            val d = state.draft
            if (d != null) {
                Text(if (state.hasUnsavedChanges) "草稿，尚未追加" else "内容未修改", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = d.project,
                    onValueChange = viewModel::projectChanged,
                    singleLine = true,
                    enabled = !state.busy && !state.remoteOperation && !state.reviewRequired,
                    label = { Text("项目名") },
                    supportingText = { Text("${d.project.codePointCount(0, d.project.length)} / $TIME_PROJECT_LIMIT 字符") },
                    modifier = Modifier.fillMaxWidth())
                Text("日期与开始时刻取当前真时钟生成；空项目名或超出上限由 core 拒绝，不补默认值。",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                if (state.loaded && state.records.isEmpty() && state.error == null) {
                    Text("隔离库读取成功，暂无记录。")
                }
                if (state.running.isNotEmpty()) {
                    Text("运行中 · 未结束不会自动补 stop", style = MaterialTheme.typography.labelMedium)
                    state.running.forEach { record ->
                        OutlinedButton(
                            onClick = { viewModel.prepareStop(record.id) },
                            enabled = !state.busy && !state.remoteOperation && !state.reviewRequired,
                            shape = RoundedCornerShape(3.dp),
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("${record.project} · 开始 ${record.date} ${record.start}")
                                Text(
                                    if (record.orphan(java.time.LocalDateTime.now())) "运行超过 12 小时，请人工核对；不自动补 stop"
                                    else "运行中",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (state.records.isNotEmpty()) {
                    Text("记录 · 真实投影", style = MaterialTheme.typography.labelMedium)
                    state.records.forEach { record -> TimeRecordRow(record) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.draft == null) {
                Button(onClick = viewModel::beginStart, enabled = !state.busy && !state.remoteOperation && !state.reviewRequired) { Text("开始项目") }
                TextButton(onClick = viewModel::reload, enabled = !state.busy) { Text("重新读取") }
            } else {
                Button(onClick = viewModel::prepareStart, enabled = !state.busy && !state.remoteOperation && !state.reviewRequired) { Text("核对追加") }
                TextButton(onClick = { cancelEditor() }, enabled = !state.busy) { Text("取消") }
            }
        }
    }
}

@Composable
private fun TimeRecordRow(record: TimeRecord) {
    val end = record.end
    val line = buildString {
        append(record.project)
        append(" · ")
        append(record.date)
        append(" ")
        append(record.start)
        if (end != null) {
            append(" → ")
            append(record.endDate ?: record.date)
            append(" ")
            append(end)
            append(" · ")
            append(record.minutes())
            append(" 分钟")
        } else {
            append(" · 运行中")
        }
        if (record.invalid) append(" · 区间无效，仅标记")
        if (record.amendCount > 0) append(" · 修正 ${record.amendCount} 次")
    }
    Text(line, style = MaterialTheme.typography.bodyMedium)
}
