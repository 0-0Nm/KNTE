package com.knte.preview.time

import android.os.SystemClock
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
import com.knte.preview.time.overlay.TimerOverlayElapsedClock
import com.knte.preview.time.overlay.TimerOverlayText
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.ZoneId

@Composable
fun TimeScreen(viewModel: TimeViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var discard by rememberSaveable { mutableStateOf(false) }
    var showWriteDetails by rememberSaveable { mutableStateOf(false) }
    val draft = state.draft
    val completed = state.records.filterNot { it.running() }.asReversed()

    fun cancelEditor() {
        if (!state.busy) {
            if (state.hasUnsavedChanges) discard = true else viewModel.discard()
        }
    }

    BackHandler(enabled = draft != null) { cancelEditor() }

    if (discard && draft != null) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text("放弃这个项目名？") },
        text = { Text("只放弃尚未提交的内容；已有记录和正在计时的项目不变。") },
        confirmButton = { TextButton(onClick = { discard = false; viewModel.discard() }) { Text("放弃草稿") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })

    state.confirmation?.takeUnless { state.reviewRequired }?.let { c ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirmation,
            title = { Text(if (c.op == "start") "开始「${c.project}」？" else "停止「${c.project}」？") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (c.op == "start") {
                        Text("开始时间：${c.date} ${c.start}\n确认后写入本机时记；取消不会写入。")
                    } else {
                        Text("开始：${c.date} ${c.start}\n停止：${c.endDate ?: c.date} ${c.end ?: "待读取"}\n只会结束这一条记录。")
                    }
                    TextButton(onClick = { showWriteDetails = !showWriteDetails }) {
                        Text(if (showWriteDetails) "收起记录信息" else "查看记录信息")
                    }
                    if (showWriteDetails) {
                        if (c.targetId.isNotBlank()) Text("记录编号：${c.targetId}", style = MaterialTheme.typography.bodySmall)
                        Text("来源：本机时记\n以本次核对的项目和时间为准；其他运行记录不受影响。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmCommit, enabled = !state.busy) {
                    Text(if (c.op == "start") "开始计时" else "停止计时")
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissConfirmation, enabled = !state.busy) { Text("取消") } })
    }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("时记", style = MaterialTheme.typography.titleLarge)
            Text("记下一件事的开始与结束。页面和悬浮窗读取同一份本机记录。", style = MaterialTheme.typography.bodySmall)
            if (draft == null && state.confirmation == null) {
                TimeMonitorControls(canStart = state.loaded && !state.busy && !state.remoteOperation && !state.reviewRequired && state.error == null)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (state.remoteOperation && !state.reviewRequired) {
                Text("另一个入口正在核对或写入；完成后这里会自动更新。")
            }
            if (state.reviewRequired) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("需要先人工核对", style = MaterialTheme.typography.titleMedium)
                        Text("上次写入结果尚未确认，或读取仍不完整。请先看下方记录；应用不会自动重试、清除待核标记或补写停止事件。")
                        state.recovery.notWritten.forEach { recovered ->
                            Text("恢复核对：${recovered.project}\n${if (recovered.op == "start") "开始" else "停止"} · ${recovered.date} ${recovered.start}")
                            Text("严格读取后没有找到对应事件。确认后只移除这条恢复提示，不补写原操作。", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { viewModel.dropRecoveredIntent(recovered.nonce) }, enabled = !state.busy) {
                                Text("确认未写入，移除此提示")
                            }
                        }
                        if (state.recovery.manual.isNotEmpty()) {
                            Text("有 ${state.recovery.manual.size} 条恢复记录与账本不一致或来源不可信。这里不能清除或重试；请人工核对数据后重启应用。")
                        }
                        Button(
                            onClick = viewModel::acknowledgeReview,
                            enabled = !state.busy && state.recovery.notWritten.isEmpty() && state.recovery.manual.isEmpty(),
                        ) { Text("我已核对，重新读取") }
                    }
                }
            }

            if (draft != null) {
                Text("新计时", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = draft.project,
                    onValueChange = viewModel::projectChanged,
                    singleLine = true,
                    enabled = !state.busy && !state.remoteOperation && !state.reviewRequired,
                    label = { Text("要做什么？") },
                    placeholder = { Text("例如：阅读、写作、晨练") },
                    supportingText = { Text("${draft.project.codePointCount(0, draft.project.length)} / $TIME_PROJECT_LIMIT 字符") },
                    modifier = Modifier.fillMaxWidth())
                if (state.running.isNotEmpty()) {
                    Text("开始后会新增一条记录；现有 ${state.running.size} 项继续计时，不会被停止。", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                if (state.loaded && state.records.isEmpty() && state.error == null) {
                    Text("还没有时记。点下方“开始计时”建立第一条记录。")
                }
                if (state.running.isNotEmpty()) {
                    Text("进行中 · ${state.running.size} 项", style = MaterialTheme.typography.labelMedium)
                    state.running.forEach { record ->
                        RunningRecordCard(
                            record = record,
                            enabled = !state.busy && !state.remoteOperation && !state.reviewRequired,
                            onStop = { viewModel.prepareStop(record.id) },
                        )
                    }
                }
                if (completed.isNotEmpty()) {
                    Text("最近记录", style = MaterialTheme.typography.labelMedium)
                    completed.forEach { record -> TimeRecordRow(record) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (draft == null) {
                Button(onClick = viewModel::beginStart, enabled = !state.busy && !state.remoteOperation && !state.reviewRequired) { Text("开始计时") }
                TextButton(onClick = viewModel::reload, enabled = !state.busy) { Text("刷新记录") }
            } else {
                Button(
                    onClick = viewModel::prepareStart,
                    enabled = !state.busy && !state.remoteOperation && !state.reviewRequired && TimerOverlayText.projectInputError(draft.project) == null,
                ) { Text("核对开始") }
                TextButton(onClick = { cancelEditor() }, enabled = !state.busy) { Text("取消") }
            }
        }
    }
}

@Composable
private fun RunningRecordCard(record: TimeRecord, enabled: Boolean, onStop: () -> Unit) {
    val elapsedClock = remember(record.id) { TimerOverlayElapsedClock() }
    var elapsed by remember(record.id) { mutableStateOf("正在读取耗时…") }
    LaunchedEffect(record.id, record.date, record.start) {
        while (true) {
            elapsed = runCatching {
                elapsedClock.estimate(
                    record.id, record.begin, LocalDateTime.now(), ZoneId.systemDefault(), SystemClock.elapsedRealtime()
                )
            }.getOrElse { "耗时暂不可用；记录仍在运行" }
            delay(1000)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(record.project, style = MaterialTheme.typography.titleMedium)
            Text("开始于 ${record.date} ${record.start}", style = MaterialTheme.typography.bodySmall)
            Text(elapsed, style = MaterialTheme.typography.titleLarge)
            if (record.orphan(LocalDateTime.now())) {
                Text("已超过 12 小时，请核对是否需要停止；不会自动补写。", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onStop, enabled = enabled, shape = RoundedCornerShape(3.dp), modifier = Modifier.fillMaxWidth()) {
                Text("停止此项")
            }
        }
    }
}

@Composable
private fun TimeRecordRow(record: TimeRecord) {
    val end = record.end
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(record.project, style = MaterialTheme.typography.bodyLarge)
        Text(buildString {
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
        }, style = MaterialTheme.typography.bodySmall)
    }
}
