package com.knte.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.knte.preview.diary.DiaryScreen
import com.knte.preview.diary.DiaryViewModel
import com.knte.preview.ui.NotesScreen
import com.knte.preview.ui.product.ProductShell

/** Repository/VM state stays above route composition. Tool and diary never share a format. */
@Composable
fun ProductRoot(notes: NoteViewModel, diary: DiaryViewModel) {
    val noteState by notes.uiState.collectAsStateWithLifecycle()
    val diaryState by diary.state.collectAsStateWithLifecycle()
    var pendingNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var busyNotice by remember { mutableStateOf(false) }
    val busy = noteState.isBusy || diaryState.busy
    ProductShell(
        noteToolContent = { NotesScreen(notes) },
        diaryContent = { DiaryScreen(diary) },
        canLeaveContent = !busy && noteState.draft == null && !diaryState.hasUnsavedChanges,
        onNavigateAway = { continuation ->
            when {
                notes.uiState.value.isBusy || diary.state.value.busy -> busyNotice = true
                notes.uiState.value.draft != null || diary.state.value.hasUnsavedChanges -> pendingNavigation = continuation
                else -> {
                    if (diary.state.value.draft != null) diary.discard()
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
            if (notes.uiState.value.isBusy || diary.state.value.busy) {
                busyNotice = true
            } else {
                if (notes.uiState.value.draft != null) notes.cancel()
                if (diary.state.value.draft != null) diary.discard()
                go?.invoke()
            }
        }) { Text("放弃草稿并离开") } },
        dismissButton = { TextButton(onClick = { pendingNavigation = null }) { Text("继续编辑") } })
}
