package com.knte.preview.reference

import com.knte.core.reference.*
import com.knte.core.reference.store.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReferencePresentationTest {
    private val stamp="2026-11-13T11:00:00"
    private val today=LocalDate.of(2026,11,13)
    private val memo=ReferenceEntry.Memo("m-00000001","同名",stamp,stamp)
    private val pitfall=ReferenceEntry.Pitfall("p-00000001","同名",3,stamp,stamp)
    private fun snapshot(m:List<String> = emptyList(),p:List<String> = emptyList())=
        ReferenceStoreSnapshot(listOf(memo,pitfall),ReferenceStoreQueues(m,p),emptyList(),"head",0,null,false)
    private fun show(s:ReferenceUiState,pool:ReferencePool=ReferencePool.MEMO)=referencePoolPresentation(s,pool,today)
    @Test fun poolsStayIndependentAndDuplicatesRetainOrder() {
        val state=ReferenceUiState(loaded=true,snapshot=snapshot(listOf(memo.id,"m-00000002",memo.id),listOf(pitfall.id)))
        val m=show(state);val p=show(state,ReferencePool.PITFALL)
        assertEquals(3,m.recent.size);assertTrue(m.recent[0].endsWith(memo.id));assertTrue(m.recent[2].endsWith(memo.id))
        assertTrue(m.recent[1].contains("当前快照无对应条目"));assertFalse(m.recent[1].contains("同名"))
        assertEquals(1,p.recent.size);assertTrue(p.recent.single().endsWith(pitfall.id))
        assertTrue(m.rows.single().inRecent);assertTrue(p.rows.single().inRecent)
    }
    @Test fun crossPoolIdNeverBorrowsTitleOrRecentFlag() {
        val s=ReferenceUiState(loaded=true,snapshot=snapshot(listOf(pitfall.id)))
        assertTrue(show(s).recent.single().contains("无对应条目"))
        assertFalse(show(s).rows.single().inRecent)
        assertFalse(show(s,ReferencePool.PITFALL).rows.single().inRecent)
    }
    @Test fun unknownUsageStaysNullAndWeightStaysBase() {
        val s=ReferenceUiState(loaded=true,snapshot=snapshot(listOf(memo.id),listOf(pitfall.id)))
        val m=show(s).rows.single();val p=show(s,ReferencePool.PITFALL).rows.single()
        assertNull(m.usage);assertNull(p.usage);assertEquals("1",m.weight);assertEquals("2",p.weight)
        assertEquals("备忘无期限",referenceWeightReason(m.reason));assertEquals("依据人工确认次数",referenceWeightReason(p.reason))
    }
    @Test fun initialFailureAndSuccessfulEmptyAreDistinct() {
        val initial=show(ReferenceUiState());val failure=show(ReferenceUiState(readFailed=true))
        val reading=show(ReferenceUiState(busy=true,activity=ReferenceActivity.READING))
        val empty=show(ReferenceUiState(loaded=true,snapshot=snapshot().copy(entries=emptyList())))
        assertTrue(initial.recentMessage.contains("首次读取"));assertTrue(failure.recentMessage.contains("不可用"))
        assertTrue(reading.snapshotLabel.contains("正在读取"));assertTrue(empty.emptyRowsMessage.contains("读取成功"))
        assertEquals("",failure.emptyRowsMessage);assertFalse(failure.recentMessage.contains("暂无"))
    }
    @Test fun lastGoodLabelsCoverBusyFailurePendingAndReview() {
        val base=ReferenceUiState(loaded=true,snapshot=snapshot(listOf(memo.id)))
        val states=listOf(base.copy(busy=true,activity=ReferenceActivity.READING),base.copy(readFailed=true),
            base.copy(snapshot=base.snapshot!!.copy(hasPending=true)),base.copy(reviewRequired=true))
        states.forEach { s ->
            val view=show(s);assertTrue(view.snapshotLabel.contains("last-good"))
            assertTrue(view.recentMessage.contains("上次确认"));assertEquals(1,view.recent.size)
            assertFalse(view.emptyRowsMessage.contains("读取成功"))
        }
        assertTrue(show(states[1]).snapshotLabel.contains("重读失败"));assertTrue(show(states[2]).snapshotLabel.contains("未决"))
    }
    @Test fun staleEmptyNeverClaimsFreshEmpty() {
        val s=ReferenceUiState(loaded=true,snapshot=snapshot().copy(entries=emptyList()),readFailed=true)
        assertTrue(referenceRecentEmptyMessage(s).contains("上次确认"))
        assertTrue(show(s).emptyRowsMessage.contains("不表示当前库为空"))
        assertEquals("此隔离库本池暂无已记录引用。",referenceRecentEmptyMessage(s.copy(readFailed=false)))
    }
    @Test fun pendingMessagesAreChineseAndKeepLockMeaning() {
        ReferencePendingClass.values().forEach {
            val text=referencePendingMessage(it);assertFalse(text.contains(it.name));assertTrue(text.contains("未解锁"))
        }
        assertTrue(referencePendingMessage(ReferencePendingClass.UNKNOWN).contains("无法判断"))
        assertTrue(referencePendingMessage(ReferencePendingClass.NOT_APPLIED).contains("观察到变更未落地"))
    }
    @Test fun everyKnownReasonHasChineseLabel() {
        (MemoTier.values().map{it.reason}+listOf("pitfall_archived","pitfall_occasions")).forEach {
            assertFalse(referenceWeightReason(it).contains("未识别"))
        }
    }
    @Test fun stateCannotLeaveWithReviewOrBusy() {
        assertFalse(ReferenceUiState(reviewRequired=true).canLeave)
        assertFalse(ReferenceUiState(busy=true).canLeave)
        assertTrue(ReferenceUiState().canLeave)
    }
}
