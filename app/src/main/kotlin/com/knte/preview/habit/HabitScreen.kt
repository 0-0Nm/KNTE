package com.knte.preview.habit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.knte.core.habit.HabitIdentity
import com.knte.core.habit.HabitMark
import java.time.LocalDate

/** 习惯页：纸墨主题、3dp 小圆角、可滚动、固定主操作；技术细节收在折叠里。 */
@Composable
fun HabitScreen(viewModel: HabitViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var discardAsk by rememberSaveable { mutableStateOf(false) }
    var archiveAsk by rememberSaveable { mutableStateOf<String?>(null) }
    var techOpen by rememberSaveable { mutableStateOf(false) }

    fun cancelEditor() {
        if (!state.busy) {
            if (state.hasUnsavedChanges) discardAsk = true else viewModel.discard()
        }
    }

    BackHandler(enabled = state.draft != null) { cancelEditor() }

    if (discardAsk && state.draft != null) AlertDialog(
        onDismissRequest = { discardAsk = false },
        title = { Text("放弃未提交的输入？") },
        text = { Text("取消不会向隔离库追加任何事件；已追加的打卡与历史记录一行不改。") },
        confirmButton = { TextButton(onClick = { discardAsk = false; viewModel.discard() }) { Text("放弃草稿") } },
        dismissButton = { TextButton(onClick = { discardAsk = false }) { Text("继续编辑") } })

    archiveAsk?.let { id -> ArchiveDialog(id, viewModel, state.snapshot?.identity(id)?.archived == true) { archiveAsk = null } }

    state.confirmation?.let { c ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirmation,
            title = { Text(titleFor(c.op)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(bodyFor(c))
                    Text("取消不会写入。连续天数、成长阶段与热力图均由事件投影实时派生，不作为状态列保存。",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmCommit, enabled = !state.busy) { Text("确认追加") } },
            dismissButton = { TextButton(onClick = viewModel::dismissConfirmation) { Text("取消") } })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("习惯", style = MaterialTheme.typography.titleLarge)
        Text(HABIT_STORE_LABEL, style = MaterialTheme.typography.bodySmall)
        Text("私有隔离格式（knte-habit-v1），不是正式 §9.1 check-only 文件，也不是真实数据库。",
            style = MaterialTheme.typography.bodySmall)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.reviewRequired) Text("待人工核对：该状态下禁止再次写入，不自动重试、不自动清理 pending。")

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val draft = state.draft
            if (draft != null) {
                DraftCard(draft, state.busy || state.reviewRequired, viewModel)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示已归档", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = state.showArchived, onCheckedChange = viewModel::showArchivedChanged,
                        enabled = !state.busy)
                }
                val identities = state.identities
                if (state.loaded && identities.isEmpty()) {
                    Text(if (state.showArchived) "读取成功，暂无习惯（含已归档）。" else "读取成功，暂无进行中的习惯。")
                }
                identities.forEach { identity ->
                    HabitCard(identity, state, viewModel, onArchive = { archiveAsk = identity.id })
                }
                val selected = state.selected
                if (selected != null) {
                    DetailCard(state, selected, techOpen) { techOpen = !techOpen }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.draft == null) {
                Button(onClick = viewModel::beginCreate,
                    enabled = !state.busy && !state.reviewRequired) { Text("新建习惯") }
                TextButton(onClick = viewModel::reload, enabled = !state.busy) { Text("重新读取") }
            } else {
                Button(
                    onClick = {
                        when (state.draft?.kind) {
                            HabitDraftKind.CHECK -> viewModel.prepareCheck()
                            else -> viewModel.prepareIdentity()
                        }
                    },
                    enabled = !state.busy && !state.reviewRequired) { Text("核对追加") }
                TextButton(onClick = { cancelEditor() }, enabled = !state.busy) { Text("取消") }
            }
        }
    }
}

@Composable
private fun DraftCard(draft: HabitDraft, disabled: Boolean, viewModel: HabitViewModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(3.dp)) {
        Column(Modifier.padding(horizontal = 17.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(when (draft.kind) {
                HabitDraftKind.CREATE -> "新建习惯 · 尚未追加"
                HabitDraftKind.EDIT -> "编辑习惯 · 尚未追加"
                HabitDraftKind.CHECK -> "当日打卡 · 尚未追加"
            }, style = MaterialTheme.typography.labelMedium)
            when (draft.kind) {
                HabitDraftKind.CHECK -> {
                    OutlinedTextField(
                        value = draft.date,
                        onValueChange = viewModel::checkDateChanged,
                        singleLine = true,
                        enabled = !disabled,
                        label = { Text("打卡日期") },
                        supportingText = { Text("过去日期可补记；未来日期一律拒绝。") },
                        modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("完成", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = draft.done, onCheckedChange = viewModel::checkDoneChanged,
                            enabled = !disabled)
                        Text(if (draft.done) "已完成" else "未完成", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        value = draft.note,
                        onValueChange = viewModel::checkNoteChanged,
                        enabled = !disabled,
                        label = { Text("备注（可选）") },
                        modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = viewModel::nameChanged,
                        singleLine = true,
                        enabled = !disabled,
                        label = { Text("习惯名称") },
                        supportingText = { Text("最多 80 字符；不能为空。") },
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = draft.targetDaysText,
                        onValueChange = viewModel::targetDaysChanged,
                        singleLine = true,
                        enabled = !disabled,
                        label = { Text("目标天数") },
                        supportingText = { Text("严格整数 1..3650；不接受小数、符号或空值。") },
                        modifier = Modifier.fillMaxWidth())
                }
            }
            Text("追加前必须人工核对；核对计划取消后不会自动重新生成。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HabitCard(
    identity: HabitIdentity,
    state: HabitUiState,
    viewModel: HabitViewModel,
    onArchive: () -> Unit,
) {
    val disabled = state.busy || state.reviewRequired || state.draft != null
    val selected = state.selectedId == identity.id
    Surface(
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(3.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 17.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(identity.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (identity.archived) Text("已归档", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
            Text("目标 ${identity.targetDays} 天 · 创建于 ${identity.createdTs}",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { viewModel.select(identity.id) }, enabled = !disabled) { Text("查看") }
                TextButton(onClick = { viewModel.beginEdit(identity.id) }, enabled = !disabled) { Text("编辑") }
                if (!identity.archived) {
                    TextButton(onClick = { viewModel.beginCheck(identity.id) }, enabled = !disabled) { Text("打卡") }
                }
                TextButton(onClick = onArchive, enabled = !disabled) {
                    Text(if (identity.archived) "恢复" else "归档")
                }
            }
        }
    }
}

@Composable
private fun DetailCard(state: HabitUiState, identity: HabitIdentity, techOpen: Boolean, toggleTech: () -> Unit) {
    val streak = state.selectedStreak ?: 0
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(3.dp)) {
        Column(Modifier.padding(horizontal = 17.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${identity.name} · 同源投影", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("累计完成", state.selectedTotalDone?.toString() ?: "—")
                Metric("当前连续", streak.toString())
                Metric("成长阶段", state.selectedStage ?: "尚未开始")
            }
            Text("阶段阈值 1/3/7/21/66 天；未打卡日保持「未知」，不计为失败。",
                style = MaterialTheme.typography.bodySmall)
            HeatMap(state.selectedHeat)
            Text("近 ${HABIT_HEAT_DAYS} 天热力图：实心为已完成，空心为已记录的未完成，空白为当日无记录（未知）。",
                style = MaterialTheme.typography.bodySmall)
            val history = state.selectedHistory
            if (history.isNotEmpty()) {
                Text("最近记录", style = MaterialTheme.typography.labelMedium)
                history.take(7).forEach { row ->
                    Text("${row.date} · ${if (row.done) "完成" else "未完成"}" +
                        (row.note?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (state.selectedSameDayCorrections > 0) {
                    Text("库内存在同日多次打卡；界面只显示最新一条，历史行仍完整保留在盘上。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = toggleTech) { Text(if (techOpen) "收起技术详情" else "技术详情") }
            if (techOpen) {
                Text("事件 id 由 core 签发；追加后 SHA-256 在核对卡中给出，仅用于人工核对。",
                    style = MaterialTheme.typography.bodySmall)
                Text("写入顺序：prepare（只读）→ 人工核对 → commit → 独立重读；结果未知时不重试。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 28 天热力图：DONE 实心、FAILED 描边、UNKNOWN 空白；绝不把未知画成失败。 */
@Composable
private fun HeatMap(heat: List<HabitMark>) {
    if (heat.isEmpty()) return
    val done = MaterialTheme.colorScheme.primary
    val failed = MaterialTheme.colorScheme.secondary
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        heat.forEach { mark ->
            Box(Modifier.weight(1f).aspectRatio(1f).background(
                color = when (mark) {
                    HabitMark.DONE -> done
                    HabitMark.FAILED -> failed.copy(alpha = 0.28f)
                    HabitMark.UNKNOWN -> MaterialTheme.colorScheme.surface
                },
                shape = RoundedCornerShape(3.dp)))
        }
    }
}

@Composable
private fun ArchiveDialog(id: String, viewModel: HabitViewModel, archived: Boolean, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (archived) "恢复这个习惯？" else "归档这个习惯？") },
        text = { Text(if (archived) {
            "恢复后可继续打卡；历史记录原样保留，不会被重建。"
        } else {
            "归档不物理删除任何记录，归档后不能继续打卡；之后可以随时恢复。"
        }) },
        confirmButton = {
            TextButton(onClick = {
                viewModel.prepareArchive(id, !archived)
                close()
            }) { Text(if (archived) "恢复" else "归档") }
        },
        dismissButton = { TextButton(onClick = close) { Text("取消") } })
}

private fun titleFor(op: String): String = when (op) {
    "create" -> "核对后创建习惯"
    "edit" -> "核对后更新习惯"
    "archive" -> "核对后归档习惯"
    "restore" -> "核对后恢复习惯"
    else -> "核对后追加打卡"
}

private fun bodyFor(c: HabitConfirmation): String = when (c.op) {
    "create" -> "名称：${c.habitName}\n目标天数：${c.targetDays}\n隔离目的地：$HABIT_STORE_LABEL"
    "edit" -> "名称：${c.habitName}\n目标天数：${c.targetDays}\n创建时间保持原值，不会重建身份。"
    "archive" -> "归档 ${c.habitName}：不删除任何历史记录，归档后不能继续打卡。"
    "restore" -> "恢复 ${c.habitName}：历史记录原样保留，之后可继续打卡。"
    else -> {
        val today = LocalDate.now().toString()
        val when = if (c.date == today) "今天 $today" else "补记 ${c.date.orEmpty()}"
        "习惯：${c.habitName}\n日期：$when\n结果：${if (c.done == true) "完成" else "未完成"}" +
            (c.note?.let { "\n备注：$it" } ?: "")
    }
}
