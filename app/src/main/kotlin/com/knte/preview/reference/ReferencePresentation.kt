package com.knte.preview.reference

import com.knte.core.reference.*
import com.knte.core.reference.store.*
import java.time.LocalDate

enum class ReferenceActivity(val message:String) {
    IDLE(""), READING("正在读取隔离库"), PREPARING("正在准备核对摘要"),
    CONFIRMING("正在提交并独立核对"), CANCELLING("正在记录计划取消状态"),
    CLASSIFYING("正在只读核对未决状态")
}

data class ReferenceDraft(val pool:ReferencePool,val id:String,val title:String="",
    val body:String="",val due:String="",val count:String="1") {
    val dirty:Boolean get()=title.isNotEmpty() || body.isNotEmpty() || due.isNotEmpty() || count!="1"
}
data class ReferenceConfirmation(val plan:ReferenceStorePlan,val id:String,val label:String)
data class ReferenceUiState(val loaded:Boolean=false,val snapshot:ReferenceStoreSnapshot?=null,
    val draft:ReferenceDraft?=null,val confirmation:ReferenceConfirmation?=null,val busy:Boolean=false,
    val activity:ReferenceActivity=ReferenceActivity.IDLE,val readFailed:Boolean=false,
    val reviewRequired:Boolean=false,val error:String?=null,val notice:String?=null) {
    val hasUnsavedChanges:Boolean get()=draft?.dirty==true
    val canLeave:Boolean get()=!busy && !reviewRequired && confirmation==null && !hasUnsavedChanges
}

/** UI-only projection. Never infers history or final selection probability. */
data class ReferencePoolPresentation(
    val snapshotLabel:String,
    val recentMessage:String,
    val recent:List<String>,
    val rows:List<ReferenceListRow>,
    val emptyRowsMessage:String,
)

fun referencePoolPresentation(s:ReferenceUiState,pool:ReferencePool,today:LocalDate):ReferencePoolPresentation {
    val snap=s.snapshot
    if(snap==null) {
        val message=when {
            s.busy -> "尚无已确认快照；"+s.activity.message
            s.readFailed -> "读取失败，最近引用历史不可用；不视为空历史"
            else -> "首次读取尚未完成，最近引用历史未知"
        }
        return ReferencePoolPresentation(message,message,emptyList(),emptyList(),"")
    }
    val stale=s.busy || s.readFailed || snap.hasPending || s.reviewRequired
    val label=if(stale) buildString {
        append("上次确认快照（last-good）")
        if(snap.hasPending) append(" · 存在未决写入，仅展示旧快照")
        if(s.readFailed) append(" · 本次重读失败")
        if(s.reviewRequired) append(" · 结果待核对，安全锁保留")
        if(s.busy) append(" · ").append(s.activity.message)
    } else "隔离库读取成功 · 当前已确认快照"
    val ids=when(pool){ReferencePool.MEMO->snap.recentQueues.memo;ReferencePool.PITFALL->snap.recentQueues.pitfall}
    val titles=snap.entries.filter{it.pool==pool}.associateBy{it.id}
    val recent=ids.mapIndexed{index,id -> "${index+1}. ${titles[id]?.title ?: "当前快照无对应条目"} · $id"}
    val rows=ReferenceList.list(snap.entries,ReferenceScope.all(),today,pool=pool,recent=mapOf(pool to ids))
    return ReferencePoolPresentation(label,
        if(stale) "上次确认的本池最近引用（非本次读取结果）" else "本池最近引用（隔离库）",
        recent,rows,if(stale) "上次确认快照中本池无记录；不表示当前库为空。" else "隔离库读取成功，本池暂无记录。")
}

fun referenceRecentEmptyMessage(s:ReferenceUiState):String =
    if(s.busy || s.readFailed || s.reviewRequired || s.snapshot?.hasPending==true)
        "上次确认历史中本池暂无已记录引用；当前结果待核对。"
    else "此隔离库本池暂无已记录引用。"

fun referencePendingMessage(state:ReferencePendingClass):String = "只读核对："+when(state) {
    ReferencePendingClass.APPLIED -> "观察到变更已落地，仍待人工核对"
    ReferencePendingClass.NOT_APPLIED -> "观察到变更未落地，仍不重发"
    ReferencePendingClass.UNKNOWN -> "无法判断写入结果，保留草稿与安全锁"
}+"。未重发、未解锁、未删除未决标记。"

fun referenceWeightReason(reason:String):String = when(reason) {
    "memo_archived" -> "备忘已完成或归档"
    "memo_no_due" -> "备忘无期限"
    "memo_expired" -> "备忘已过期"
    "memo_due_0_3" -> "备忘距期限0至3天（含边界）"
    "memo_due_3_7" -> "备忘距期限大于3至7天"
    "memo_due_7_14" -> "备忘距期限大于7至14天"
    "memo_due_14_30" -> "备忘距期限大于14至30天"
    "memo_due_30_60" -> "备忘距期限大于30至60天"
    "memo_due_60_90" -> "备忘距期限大于60至90天"
    "memo_due_90_plus" -> "备忘距期限大于90天"
    "pitfall_archived" -> "吃一堑已归档"
    "pitfall_occasions" -> "依据人工确认次数"
    else -> "原因未识别（$reason）"
}
