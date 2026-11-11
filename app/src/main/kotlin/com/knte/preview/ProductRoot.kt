package com.knte.preview

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.knte.preview.habit.HabitScreen
import com.knte.preview.habit.HabitViewModel
import com.knte.preview.diary.DiaryScreen
import com.knte.preview.diary.DiaryViewModel
import com.knte.preview.reading.ReadingScreen
import com.knte.preview.reading.ReadingViewModel
import com.knte.preview.source.SourceScreen
import com.knte.preview.source.SourceViewModel
import com.knte.preview.accounting.AccountingScreen
import com.knte.preview.accounting.AccountingViewModel
import com.knte.preview.accounting.AccountingSection
import com.knte.preview.ui.product.Section
import com.knte.preview.time.TimeScreen
import com.knte.preview.time.TimeViewModel
import com.knte.preview.ui.NotesScreen
import com.knte.preview.ui.product.ProductShell

/** Repository/VM state stays above route composition. Tool and diary never share a format. */
@Composable
fun ProductRoot(notes: NoteViewModel, diary: DiaryViewModel, time: TimeViewModel, accounting: AccountingViewModel, source: SourceViewModel, chooseSource: () -> Unit, reading: ReadingViewModel, chooseBook: () -> Unit, habit: HabitViewModel) {
    var showSource by remember { mutableStateOf(true) }
    val noteState by notes.uiState.collectAsStateWithLifecycle()
    val diaryState by diary.state.collectAsStateWithLifecycle()
    val timeState by time.state.collectAsStateWithLifecycle()
    val accountingState by accounting.state.collectAsStateWithLifecycle()
    val habitState by habit.state.collectAsStateWithLifecycle()
    var pendingNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var busyNotice by remember { mutableStateOf(false) }
    val busy = noteState.isBusy || diaryState.busy || timeState.busy || accountingState.busy || habitState.busy
    ProductShell(
        noteToolContent = { NotesScreen(notes) },
        diaryContent = {
            Column {
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = {
                        if (diary.state.value.busy) busyNotice = true
                        else if (diary.state.value.hasUnsavedChanges) busyNotice = true
                        else { diary.discard(); showSource = true }
                    }) { Text("所选目录 · 只读") }
                    TextButton(onClick = { showSource = false }) { Text("隔离验证库") }
                }
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) {
                    if (showSource) SourceScreen(source, chooseSource) else DiaryScreen(diary)
                }
            }
        },
        timeContent = { TimeScreen(time) },
        habitContent = { HabitScreen(habit) },
        readingContent = { ReadingScreen(reading, chooseBook) },
        accountingContent = { section -> AccountingScreen(accounting, when (section) {
            Section.REPORTS -> AccountingSection.MONTH
            Section.STATISTICS -> AccountingSection.CATEGORY
            else -> AccountingSection.LEDGER
        }) },
        canLeaveContent = !busy && noteState.draft == null && !diaryState.hasUnsavedChanges && !timeState.hasUnsavedChanges && !accountingState.hasUnsavedChanges && !habitState.hasUnsavedChanges,
        onNavigateAway = { continuation ->
            when {
                notes.uiState.value.isBusy || diary.state.value.busy || time.state.value.busy || accounting.state.value.busy || habit.state.value.busy -> busyNotice = true
                notes.uiState.value.draft != null || diary.state.value.hasUnsavedChanges || time.state.value.hasUnsavedChanges || accounting.state.value.hasUnsavedChanges || habit.state.value.hasUnsavedChanges -> pendingNavigation = continuation
                else -> {
                    if (diary.state.value.draft != null) diary.discard()
                    if (time.state.value.draft != null) time.discard()
                    if (accounting.state.value.draft != null) accounting.discard()
                    if (habit.state.value.draft != null) habit.discard()
                    continuation()
                }
            }
        },
    )
    if (busyNotice) AlertDialog(onDismissRequest = { busyNotice = false },
        title = { Text("操作尚未结束") }, text = { Text("请等待当前读写结束。不会借切换页面中断或重复提交。") },
        confirmButton = { TextButton(onClick = { busyNotice = false }) { Text("留在当前页") } })
    if (pendingNavigation != null) AlertDialog(onDismissRequest = { pendingNavigation = null },
        title = { Text("离开前处理未保存草稿") },
        text = { Column {
            Text("继续编辑可保留输入。放弃只清除当前草稿，不删除已存记录，也不执行保存。")
            if (diaryState.reviewRequired) Text("隔离库仍处于待核对状态；放弃草稿不会解除写入锁。")
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val go = pendingNavigation; pendingNavigation = null
            if (notes.uiState.value.isBusy || diary.state.value.busy || time.state.value.busy || accounting.state.value.busy || habit.state.value.busy) {
                busyNotice = true
            } else {
                if (notes.uiState.value.draft != null) notes.cancel()
                if (diary.state.value.draft != null) diary.discard()
                    if (time.state.value.draft != null) time.discard()
                    if (accounting.state.value.draft != null) accounting.discard()
                    if (habit.state.value.draft != null) habit.discard()
                go?.invoke()
            }
        }) { Text("放弃草稿并离开") } },
        dismissButton = { TextButton(onClick = { pendingNavigation = null }) { Text("继续编辑") } })
}
