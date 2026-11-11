package com.knte.preview.accounting

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.knte.core.accounting.IsolatedAccountingLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 真实临时文件隔离库 + 确定性 VM 调度（复用 diary 测试形式）；非 Android 生命周期证据。
 * 双写提交之间用真实等待跨秒：core 对同秒时间戳抛 TIMESTAMP_BUSY，契约要求等待重试
 * 而不盲改时间，测试同样遵守。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountingViewModelTest {
    private fun runVm(
        handle: SavedStateHandle = SavedStateHandle(),
        block: suspend TestScope.(AccountingViewModel, IsolatedAccountingLedger, File, SavedStateHandle) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("knte-accounting-vm-").toFile()
        val ledger = IsolatedAccountingLedger(root)
        val vm = AccountingViewModel({ ledger }, handle, dispatcher)
        try {
            block(vm, ledger, root, handle)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun realEmptyLoadIsNotFailureAndReportsComeOnlyFromRealSnapshot() = runVm { vm, ledger, root, _ ->
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.loaded)
        assertFalse(s.busy)
        assertNull(s.error)
        assertTrue(s.rows.isEmpty())
        assertTrue(s.findings.isEmpty())
        // 真实空库：0 是快照事实，不是把读取错误当0。
        assertEquals(0L, s.income)
        assertEquals(0L, s.expense)
        assertEquals(0L, s.net)
        assertNotNull(s.revision)
        assertNotNull(s.monthReport)
        assertEquals(0L, s.monthReport?.income)
        assertEquals(10, s.categoryTotals.size)
        assertFalse(root.resolve("db/accounting.jsonl").exists())
    }

    @Test
    fun prepareThenCancelDoesNotWriteAndCancelsPlanOnLedger() = runVm { vm, ledger, root, _ ->
        advanceUntilIdle()
        vm.newEntry()
        vm.dateChanged("2026-11-11")
        vm.amountChanged("12.5")
        vm.rawInputChanged("lunch 12.5")
        vm.prepare()
        advanceUntilIdle()
        val plan = vm.state.value.confirmation!!
        assertNotNull(plan)
        assertFalse(root.resolve("db/accounting.jsonl").exists())
        vm.dismissConfirmation()
        advanceUntilIdle()
        assertNull(vm.state.value.confirmation)
        assertNotNull(vm.state.value.draft)
        assertFalse(root.resolve("db/accounting.jsonl").exists())
        assertTrue(ledger.load().rows.isEmpty())
        // 取消必须回执到 ledger：旧确认继续使用会被拒绝。
        try {
            ledger.confirm(plan, plan.confirmation)
            fail("已取消的计划不得确认")
        } catch (e: IllegalStateException) {
            assertEquals("PLAN_CANCELLED", e.message)
        }
    }

    @Test
    fun incomeAndExpenseCommitWritesAndIndependentRereadUpdatesReports() = runVm { vm, ledger, root, _ ->
        advanceUntilIdle()
        vm.newEntry()
        vm.dateChanged("2026-11-01")
        vm.amountChanged("12.5")
        vm.rawInputChanged("lunch 12.5")
        vm.prepare()
        advanceUntilIdle()
        assertNotNull(vm.state.value.confirmation)
        vm.commit()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        // 同秒会触发 TIMESTAMP_BUSY（不盲改时间），真实等待跨秒后再写第二笔。
        Thread.sleep(1100)
        vm.newEntry()
        vm.dateChanged("2026-11-02")
        vm.incomeChanged(true)
        vm.amountChanged("100")
        vm.rawInputChanged("sold book")
        vm.prepare()
        advanceUntilIdle()
        assertNotNull(vm.state.value.confirmation)
        vm.commit()
        advanceUntilIdle()
        val s = vm.state.value
        assertNull(s.error)
        assertNull(s.draft)
        assertEquals(2, s.rows.size)
        assertTrue(s.findings.isEmpty())
        assertEquals(10000L, s.income)
        assertEquals(1250L, s.expense)
        assertEquals(8750L, s.net)
        // 独立重读（绕过 VM 缓存）核对真实文件与原文保留。
        val snap = ledger.load()
        assertEquals(2, snap.rows.size)
        val pairs = snap.rows.map { it.rawInput to it.cents }
        assertTrue(pairs.contains("lunch 12.5" to -1250L))
        assertTrue(pairs.contains("sold book" to 10000L))
        vm.reportMonthChanged("2026-11")
        assertEquals(10000L, vm.state.value.monthReport?.income)
        assertEquals(1250L, vm.state.value.monthReport?.expense)
        assertEquals(30, vm.state.value.monthReport?.days?.size)
        assertEquals(1, vm.state.value.monthlyTotals.size)
        assertEquals("2026-11", vm.state.value.monthlyTotals.first().month)
    }

    @Test
    fun subCentAmountRejectedBeforeWriteAndDraftPreserved() = runVm { vm, ledger, root, _ ->
        advanceUntilIdle()
        vm.newEntry()
        vm.amountChanged("12.345")
        vm.rawInputChanged("keep me")
        vm.dateChanged("2026-11-11")
        vm.prepare()
        advanceUntilIdle()
        val s = vm.state.value
        assertNull(s.confirmation)
        assertNotNull(s.error)
        assertTrue(s.error!!.contains("AMOUNT_TEXT_INVALID"))
        assertEquals("12.345", s.draft?.amountText)
        assertEquals("keep me", s.draft?.rawInput)
        assertTrue(s.dirty)
        assertFalse(root.resolve("db/accounting.jsonl").exists())
        assertTrue(ledger.load().rows.isEmpty())
    }

    @Test
    fun dirtyTracksDraftContent() = runVm { vm, _, _, _ ->
        advanceUntilIdle()
        vm.newEntry()
        assertFalse(vm.state.value.dirty)
        assertTrue(vm.state.value.canLeave)
        vm.amountChanged("3.5")
        assertTrue(vm.state.value.dirty)
        assertFalse(vm.state.value.canLeave)
        vm.amountChanged("")
        vm.rawInputChanged("typed")
        assertTrue(vm.state.value.dirty)
        vm.rawInputChanged("")
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun restoredDraftRestoresFieldsButNotPlanAndNeverAutoWrites() = runVm(SavedStateHandle(mapOf(
        "knte.isolated.accounting.draft" to true,
        "knte.isolated.accounting.id" to "r-0123abcd",
        "knte.isolated.accounting.date" to "2026-11-11",
        "knte.isolated.accounting.amountText" to "9.99",
        "knte.isolated.accounting.rawInput" to "restored draft",
    ))) { vm, ledger, root, _ ->
        advanceUntilIdle()
        val d = vm.state.value.draft
        assertNotNull(d)
        assertEquals("9.99", d?.amountText)
        assertEquals("restored draft", d?.rawInput)
        assertEquals("r-0123abcd", d?.id)
        assertNull(vm.state.value.confirmation)
        assertTrue(vm.state.value.dirty)
        assertFalse(root.resolve("db/accounting.jsonl").exists())
        assertTrue(ledger.load().rows.isEmpty())
    }

    @Test
    fun editAfterPlanInvalidatesConfirmationAndCancelsPlanOnLedger() = runVm { vm, ledger, root, _ ->
        advanceUntilIdle()
        vm.newEntry()
        vm.dateChanged("2026-11-11")
        vm.amountChanged("8")
        vm.rawInputChanged("orig input")
        vm.prepare()
        advanceUntilIdle()
        val plan = vm.state.value.confirmation!!
        assertNotNull(plan)
        vm.noteChanged("changed")
        advanceUntilIdle()
        assertNull(vm.state.value.confirmation)
        assertEquals("changed", vm.state.value.draft?.note)
        // 草稿变化使旧计划失效，且失效已回执 ledger.cancel。
        try {
            ledger.confirm(plan, plan.confirmation)
            fail("草稿变化后旧确认必须已被取消")
        } catch (e: IllegalStateException) {
            assertEquals("PLAN_CANCELLED", e.message)
        }
        vm.commit()
        advanceUntilIdle()
        assertFalse(root.resolve("db/accounting.jsonl").exists())
        assertEquals(0, ledger.load().rows.size)
    }

    @Test
    fun busyRejectsDuplicateActionsWhileWriting() = runVm { vm, ledger, root, _ ->
        advanceUntilIdle()
        vm.newEntry()
        vm.dateChanged("2026-11-11")
        vm.amountChanged("5")
        vm.rawInputChanged("once")
        vm.prepare()
        advanceUntilIdle()
        vm.commit()
        assertTrue(vm.state.value.busy)
        assertNotNull(vm.state.value.draft)
        vm.commit()
        vm.discard()
        vm.prepare()
        vm.newEntry()
        // busy 期间上述动作全部被拒绝，草稿保持原状。
        assertEquals("once", vm.state.value.draft?.rawInput)
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertNull(vm.state.value.draft)
        assertEquals(1, ledger.load().rows.size)
    }

    @Test
    fun unknownWritingIntentRestoresLockedAndNeverAutoRetries() = runVm(SavedStateHandle(mapOf(
        "knte.isolated.accounting.draft" to true,
        "knte.isolated.accounting.date" to "2026-11-11",
        "knte.isolated.accounting.amountText" to "7",
        "knte.isolated.accounting.rawInput" to "uncertain write",
        "knte.isolated.accounting.writing" to true,
    ))) { vm, ledger, root, _ ->
        advanceUntilIdle()
        assertTrue(vm.state.value.reviewRequired)
        vm.prepare()
        vm.commit()
        advanceUntilIdle()
        assertTrue(vm.state.value.reviewRequired)
        assertFalse(vm.state.value.busy)
        assertFalse(root.resolve("db/accounting.jsonl").exists())
        assertEquals("uncertain write", vm.state.value.draft?.rawInput)
        assertTrue(ledger.load().rows.isEmpty())
    }

    @Test
    fun storeReadFailureIsSurfacedNotFabricatedAsZero() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("knte-accounting-broken-").toFile()
        root.resolve("db").writeText("占位文件：使库结构异常")
        val ledger = IsolatedAccountingLedger(root)
        val vm = AccountingViewModel({ ledger }, SavedStateHandle(), dispatcher)
        try {
            advanceUntilIdle()
            val s = vm.state.value
            assertFalse(s.loaded)
            assertNotNull(s.error)
            assertTrue(s.error!!.contains("读取失败"))
            assertNull(s.income)
            assertNull(s.expense)
            assertNull(s.net)
            assertNull(s.monthReport)
            assertTrue(s.categoryTotals.isEmpty())
            // 读取错误下写路径同样失败关闭：起草可进，但不产生计划、不落库。
            vm.newEntry()
            vm.amountChanged("1")
            vm.rawInputChanged("plain")
            vm.prepare()
            advanceUntilIdle()
            assertNotNull(vm.state.value.error)
            assertNull(vm.state.value.confirmation)
            assertNotNull(vm.state.value.draft)
            assertFalse(root.resolve("db").isDirectory)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun reportsAreExactCentsAndCategoryComplete() = runVm { vm, ledger, _ ->
        advanceUntilIdle()
        vm.newEntry()
        vm.dateChanged("2026-11-01")
        vm.amountChanged("12.5")
        vm.rawInputChanged("noodles")
        vm.prepare()
        advanceUntilIdle()
        vm.commit()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        Thread.sleep(1100)
        vm.newEntry()
        vm.dateChanged("2026-11-02")
        vm.incomeChanged(true)
        vm.amountChanged("0.01")
        vm.rawInputChanged("refund")
        vm.categoryChanged("其他")
        vm.prepare()
        advanceUntilIdle()
        vm.commit()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        vm.reportMonthChanged("2026-11")
        val s = vm.state.value
        assertEquals(1250L, s.monthReport?.expense)
        assertEquals(1L, s.monthReport?.income)
        assertEquals(2, s.monthReport?.days?.count { it.income != 0L || it.expense != 0L })
        assertEquals(10, s.categoryTotals.size)
        val food = s.categoryTotals.first { it.category == "餐饮" }
        assertEquals(1, food.count)
        assertEquals(1250L, food.expense)
        assertEquals(0L, food.income)
        val other = s.categoryTotals.first { it.category == "其他" }
        assertEquals(1, other.count)
        assertEquals(1L, other.income)
        assertEquals(0L, other.expense)
        val monthly = s.monthlyTotals.single()
        assertEquals("2026-11", monthly.month)
        assertEquals(2, monthly.count)
        assertEquals(-1249L, monthly.net)
        assertEquals(2, ledger.load().rows.size)
    }
}
