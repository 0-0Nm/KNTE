package com.knte.preview

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.knte.preview.ui.product.ContentOwner
import com.knte.preview.ui.product.contentOwner
import com.knte.preview.ui.product.NavigationExitDecision
import com.knte.preview.ui.product.navigationExitDecision
import com.knte.preview.ui.product.diarySourceVisible
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.knte.preview.reference.ReferenceScreen
import com.knte.preview.reference.ReferenceViewModel
import com.knte.core.reference.ReferencePool
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

/** VM state stays above routes. Leaving one editor never discards another editor's draft. */
@Composable
fun ProductRoot(notes: NoteViewModel, diary: DiaryViewModel, time: TimeViewModel,
    accounting: AccountingViewModel, source: SourceViewModel, chooseSource: () -> Unit,
    reading: ReadingViewModel, chooseBook: () -> Unit, habit: HabitViewModel, reference: ReferenceViewModel) {
    var showSource by rememberSaveable { mutableStateOf(true) }
    var allowSourceWithRetainedDraft by rememberSaveable { mutableStateOf(false) }
    val diaryState by diary.state.collectAsStateWithLifecycle()
    val noteState by notes.uiState.collectAsStateWithLifecycle()
    val timeState by time.state.collectAsStateWithLifecycle()
    val accountingState by accounting.state.collectAsStateWithLifecycle()
    val habitState by habit.state.collectAsStateWithLifecycle()
    val referenceState by reference.state.collectAsStateWithLifecycle()
    var pendingOwner by remember { mutableStateOf<ContentOwner?>(null) }
    var pendingNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var busyNotice by remember { mutableStateOf(false) }
    var reviewOwner by remember { mutableStateOf<ContentOwner?>(null) }
    var reviewNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun isBusy(owner: ContentOwner): Boolean = when(owner) {
        ContentOwner.PREVIEW_TOOL -> noteState.isBusy
        ContentOwner.DIARY -> diaryState.busy
        ContentOwner.TIME -> timeState.busy
        ContentOwner.ACCOUNTING -> accountingState.busy
        ContentOwner.HABIT -> habitState.busy
        ContentOwner.REFERENCE -> referenceState.busy
        ContentOwner.NONE -> false
    }
    fun requiresReview(owner: ContentOwner): Boolean = when(owner) {
        ContentOwner.DIARY -> diaryState.reviewRequired
        ContentOwner.TIME -> timeState.reviewRequired
        ContentOwner.ACCOUNTING -> accountingState.reviewRequired
        ContentOwner.HABIT -> habitState.reviewRequired
        ContentOwner.REFERENCE -> referenceState.reviewRequired
        ContentOwner.PREVIEW_TOOL, ContentOwner.NONE -> false
    }
    fun needsConfirmation(owner: ContentOwner): Boolean = when(owner) {
        ContentOwner.PREVIEW_TOOL -> noteState.draft != null
        ContentOwner.DIARY -> diaryState.hasUnsavedChanges || diaryState.confirmation != null
        ContentOwner.TIME -> timeState.draft != null || timeState.confirmation != null
        ContentOwner.ACCOUNTING -> accountingState.draft != null || accountingState.confirmation != null
        ContentOwner.HABIT -> habitState.draft != null || habitState.confirmation != null
        ContentOwner.REFERENCE -> referenceState.hasUnsavedChanges || referenceState.confirmation != null
        ContentOwner.NONE -> false
    }
    fun discardOnly(owner: ContentOwner, continuation: () -> Unit) {
        when(owner) {
            ContentOwner.PREVIEW_TOOL -> { notes.cancel();continuation() }
            ContentOwner.DIARY -> { diary.discard();continuation() }
            ContentOwner.TIME -> { time.discard();continuation() }
            ContentOwner.ACCOUNTING -> accounting.discard { continuation() }
            ContentOwner.HABIT -> habit.discard { continuation() }
            ContentOwner.REFERENCE -> reference.discard { continuation() }
            ContentOwner.NONE -> continuation()
        }
    }
    fun requestLeave(owner: ContentOwner, continuation: () -> Unit) {
        when (navigationExitDecision(isBusy(owner), requiresReview(owner), needsConfirmation(owner))) {
            NavigationExitDecision.WAIT -> busyNotice = true
            NavigationExitDecision.PRESERVE_FOR_REVIEW -> { reviewOwner=owner;reviewNavigation=continuation }
            NavigationExitDecision.CONFIRM_DISCARD -> { pendingOwner=owner;pendingNavigation=continuation }
            NavigationExitDecision.DISCARD_CLEAN -> discardOnly(owner,continuation)
        }
    }
    // A restored diary draft must remain visible even if a presentation flag was lost.
    val effectiveShowSource = diarySourceVisible(showSource,
        diaryState.draft != null || diaryState.confirmation != null, allowSourceWithRetainedDraft)
    ProductShell(
        noteToolContent={NotesScreen(notes)},
        diaryContent={
            Column {
                Row {
                    TextButton(onClick={requestLeave(ContentOwner.DIARY){showSource=true;allowSourceWithRetainedDraft=true}}){Text("所选目录 · 只读")}
                    TextButton(onClick={showSource=false;allowSourceWithRetainedDraft=false}){Text("隔离验证库")}
                }
                Box(Modifier.weight(1f)) {
                    if(effectiveShowSource)SourceScreen(source,chooseSource) else DiaryScreen(diary)
                }
            }
        },
        timeContent={TimeScreen(time)},habitContent={HabitScreen(habit)},
        referenceContent={section->ReferenceScreen(reference,if(section==Section.LESSONS)ReferencePool.PITFALL else ReferencePool.MEMO)},
        readingContent={ReadingScreen(reading,chooseBook)},
        accountingContent={section->AccountingScreen(accounting,when(section){
            Section.REPORTS->AccountingSection.MONTH
            Section.STATISTICS->AccountingSection.CATEGORY
            else->AccountingSection.LEDGER
        })},
        onNavigateAwayFrom={route,tool,continuation->requestLeave(contentOwner(route,tool),continuation)},
    )
    if(busyNotice)AlertDialog(onDismissRequest={busyNotice=false},title={Text("当前操作尚未结束")},
        text={Text("请等待当前页面的操作完成。不会清除其他模块的草稿。")},
        confirmButton={TextButton(onClick={busyNotice=false}){Text("留在当前页")}})
    val retainedOwner=reviewOwner
    if(retainedOwner!=null && reviewNavigation!=null)AlertDialog(
        onDismissRequest={reviewOwner=null;reviewNavigation=null},title={Text("结果待核对，草稿已保留")},
        text={Text("当前操作没有可确认的完成回执。离开只切换页面，不放弃草稿、不解除核对锁，也不会重试写入；返回后仍需核对隔离库。")},
        confirmButton={TextButton(enabled=!isBusy(retainedOwner),onClick={
            val go=reviewNavigation
            reviewOwner=null;reviewNavigation=null
            if(isBusy(retainedOwner))busyNotice=true else go?.invoke()
        }){Text("保留草稿并离开")}},
        dismissButton={TextButton(onClick={reviewOwner=null;reviewNavigation=null}){Text("留在当前页")}})
    val owner=pendingOwner
    if(owner!=null && pendingNavigation!=null)AlertDialog(
        onDismissRequest={pendingOwner=null;pendingNavigation=null},title={Text("离开前处理当前编辑")},
        text={Text("仅放弃当前页面的草稿与待确认计划；其他模块已恢复的输入保持不变。取消失败时仍留在当前页。")},
        confirmButton={TextButton(enabled=!isBusy(owner),onClick={
            val go=pendingNavigation
            pendingOwner=null;pendingNavigation=null
            when {
                isBusy(owner) -> busyNotice=true
                requiresReview(owner) -> { reviewOwner=owner;reviewNavigation=go }
                else -> discardOnly(owner){go?.invoke()}
            }
        }){Text("放弃当前编辑并离开")}},
        dismissButton={TextButton(onClick={pendingOwner=null;pendingNavigation=null}){Text("继续编辑")}})
}
