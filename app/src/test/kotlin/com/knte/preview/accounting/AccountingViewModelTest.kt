package com.knte.preview.accounting

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.knte.core.accounting.AccountingDraft
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


    /** 离页 callback 必须等真实 cancel IO 成功：飞行期间 busy=true，旧计划已不可确认，草稿/handle 已清。 */
    @Test
    fun discardCallbackRunsOnlyAfterDurableCancel() = runVm { vm, ledger, _, handle ->
        advanceUntilIdle()
        vm.newEntry()
        vm.dateChanged("2026-11-11")
        vm.amountChanged("6")
        vm.rawInputChanged("wait cancel")
        vm.prepare()
        advanceUntilIdle()
        val plan = vm.state.value.confirmation!!
        var navigated = false
        vm.discard { navigated = true }
        assertFalse("cancel still in flight", navigated)
        assertTrue(vm.state.value.busy)
        assertNull(vm.state.value.confirmation)
        advanceUntilIdle()
        assertTrue("navigation must follow successful cancel", navigated)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.draft)
        assertNull(handle.get<Boolean>("knte.isolated.accounting.draft"))
        assertTrue(ledger.load().rows.isEmpty())
        try {
            ledger.confirm(plan, plan.confirmation)
            fail("cancelled plan must never confirm")
        } catch (e: IllegalStateException) {
            assertEquals("PLAN_CANCELLED", e.message)
        }
    }

    /** 取消已 CONFIRMED 的计划失败：不 callback、不清稿、不解除 writing 锁。 */
    @Test
    fun failedCancelKeepsDraftAndWritingLock() = runVm { vm, ledger, _, handle ->
        advanceUntilIdle()
        vm.newEntry()
        vm.dateChanged("2026-11-11")
        vm.amountChanged("7")
        vm.rawInputChanged("keep draft")
        vm.prepare()
        advanceUntilIdle()
        val plan = vm.state.value.confirmation!!
        // 用同一真实 ledger 实例确认该计划，模拟 cancel 时已 CONFIRMED：不能把失败当成功。
        ledger.confirm(plan, plan.confirmation)
        var navigated = false
        vm.discard { navigated = true }
        advanceUntilIdle()
        assertFalse(navigated)
        assertTrue(vm.state.value.reviewRequired)
        assertFalse(vm.state.value.busy)
        assertEquals("keep draft", vm.state.value.draft?.rawInput)
        assertEquals(true, handle.get<Boolean>("knte.isolated.accounting.draft"))
        assertEquals(1, ledger.load().rows.size)
        vm.prepare()
        advanceUntilIdle()
        assertNull(vm.state.value.confirmation)
        assertEquals(1, ledger.load().rows.size)
        // 嵌套 unknown writing 锁：discard 不能解除 reviewRequired，也不能触发 callback。
        vm.discard { navigated = true }
        advanceUntilIdle()
        assertFalse(navigated)
        assertTrue(vm.state.value.reviewRequired)
    }

    /** dismiss/字段取消飞行 busy：重复 prepare 被拒绝，字段串行落在新值且不在失败时先清 error。 */
    @Test
    fun cancelInFlightBlocksPrepareAndSerializesFieldChange() = runVm { vm, ledger, _, _ ->
        advanceUntilIdle()
        vm.newEntry()
        vm.dateChanged("2026-11-11")
        vm.amountChanged("8")
        vm.rawInputChanged("first")
        vm.prepare()
        advanceUntilIdle()
        val plan = vm.state.value.confirmation!!
        vm.dismissConfirmation()
        assertTrue(vm.state.value.busy)
        vm.prepare()
        vm.noteChanged("during flight")
        assertNull("busy must block duplicate prepare", vm.state.value.confirmation)
        assertEquals("", vm.state.value.draft?.note) // busy must block early field persist
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.error)
        assertNull(vm.state.value.confirmation)
        assertNotNull(vm.state.value.draft)
        try {
            ledger.confirm(plan, plan.confirmation)
            fail("dismissed plan must remain cancelled")
        } catch (e: IllegalStateException) {
            assertEquals("PLAN_CANCELLED", e.message)
        }
        // 下一步字段操作从已取消状态同步进入，不再有残留 confirmation。
        vm.noteChanged("after flight")
        assertEquals("after flight", vm.state.value.draft?.note)
        assertTrue(ledger.load().rows.isEmpty())
    }

    /** 字段变化路径同样串行：旧计划 cancel 成功后才保存新字段，失败则保原稿且不覆盖为 error。 */
    @Test
    fun fieldChangeWaitsForCancelSuccessAndDoesNotOverwriteFailure() = runVm { vm, ledger, _, _ ->
        advanceUntilIdle()
        vm.newEntry()
        vm.dateChanged("2026-11-11")
        vm.amountChanged("9")
        vm.rawInputChanged("before change")
        vm.prepare()
        advanceUntilIdle()
        val plan = vm.state.value.confirmation!!
        vm.noteChanged("after cancel")
        assertTrue(vm.state.value.busy)
        assertEquals("", vm.state.value.draft?.note)
        advanceUntilIdle()
        assertEquals("after cancel", vm.state.value.draft?.note)
        try {
            ledger.confirm(plan, plan.confirmation)
            fail("field change must cancel old plan")
        } catch (e: IllegalStateException) {
            assertEquals("PLAN_CANCELLED", e.message)
        }
        // 第二份计划外部先确认：下一字段变化 cancel 失败，原 note 不丢，不 callback、不静默成功。
        vm.prepare()
        advanceUntilIdle()
        val failedPlan = vm.state.value.confirmation!!
        ledger.confirm(failedPlan, failedPlan.confirmation)
        vm.noteChanged("must not land")
        advanceUntilIdle()
        assertTrue(vm.state.value.reviewRequired)
        assertEquals("after cancel", vm.state.value.draft?.note)
        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.error!!.contains("取消未确认"))
        assertEquals(1, ledger.load().rows.size)
    }

    /** 无 pending 计划时同步清稿回调；已写记录不删除。 */
    @Test
    fun discardWithoutPlanClearsDraftSynchronouslyAndKeepsRows() = runVm { vm, ledger, _, handle ->
        advanceUntilIdle()
        vm.newEntry()
        vm.amountChanged("1")
        vm.rawInputChanged("plain")
        vm.dateChanged("2026-11-01")
        vm.prepare()
        advanceUntilIdle()
        vm.commit()
        advanceUntilIdle()
        assertEquals(1, ledger.load().rows.size)
        vm.newEntry()
        vm.amountChanged("2")
        var navigated = false
        vm.discard { navigated = true }
        assertTrue(navigated)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.draft)
        assertNull(handle.get<Boolean>("knte.isolated.accounting.draft"))
        assertEquals(1, ledger.load().rows.size)
    }


    /** unknown writing 锁：discard 不得解除 reviewRequired，也不得触发离页 callback。 */
    @Test
    fun unknownWritingLockCannotBeDiscarded() = runVm(SavedStateHandle(mapOf(
        "knte.isolated.accounting.draft" to true,
        "knte.isolated.accounting.date" to "2026-11-11",
        "knte.isolated.accounting.amountText" to "5",
        "knte.isolated.accounting.rawInput" to "locked draft",
        "knte.isolated.accounting.writing" to true,
    ))) { vm, ledger, _, handle ->
        advanceUntilIdle()
        assertTrue(vm.state.value.reviewRequired)
        var navigated = false
        vm.discard { navigated = true }
        vm.dismissConfirmation()
        advanceUntilIdle()
        assertFalse(navigated)
        assertTrue(vm.state.value.reviewRequired)
        assertFalse(vm.state.value.busy)
        assertEquals("locked draft", vm.state.value.draft?.rawInput)
        assertEquals(true, handle.get<Boolean>("knte.isolated.accounting.draft"))
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
    fun reportsAreExactCentsAndCategoryComplete() = runVm { vm, ledger, _, _ ->
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
    // ---- cancel intent 持久锁定回归（隔离副本新增） -----------------------

    private fun accountingCancelIntentKey() = "knte.isolated.accounting.cancelling"

    /** 取消成功才清 intent：正常 dismiss 后 handle 不再有取消 intent，且未进入 reviewRequired。 */
    @Test
    fun cancelSuccessClearsPersistedIntent() = runVm { vm, ledger, _, handle ->
        advanceUntilIdle()
        vm.newEntry()
        vm.dateChanged("2026-11-11")
        vm.amountChanged("3")
        vm.rawInputChanged("cancel success")
        vm.prepare()
        advanceUntilIdle()
        assertNotNull(vm.state.value.confirmation)
        vm.dismissConfirmation()
        assertTrue(vm.state.value.busy)
        assertEquals(true, handle.get<Boolean>(accountingCancelIntentKey()))
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertFalse(vm.state.value.reviewRequired)
        assertNull(vm.state.value.error)
        assertFalse(handle.get<Boolean>(accountingCancelIntentKey()) == true)
        assertTrue(ledger.load().rows.isEmpty())
    }

    /** 取消失败后同 SavedStateHandle 重建：init 识别取消 intent 并明确「取消未确认」锁定，不自动重试。 */
    @Test
    fun failedCancelIntentKeepsRebuiltVmLocked() = runVm { vm, ledger, _, handle ->
        advanceUntilIdle()
        vm.newEntry()
        vm.dateChanged("2026-11-11")
        vm.amountChanged("4")
        vm.rawInputChanged("cancel failed")
        vm.prepare()
        advanceUntilIdle()
        val plan = vm.state.value.confirmation!!
        // 真实 ledger 外部确认，使 cancel 必须失败；intent 已落键且不可被清。
        ledger.confirm(plan, plan.confirmation)
        vm.dismissConfirmation()
        advanceUntilIdle()
        assertTrue(vm.state.value.reviewRequired)
        assertEquals(true, handle.get<Boolean>(accountingCancelIntentKey()))
        assertEquals(1, ledger.load().rows.size)

        val rebuilt = AccountingViewModel({ ledger }, handle, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            assertTrue(rebuilt.state.value.reviewRequired)
            assertFalse(rebuilt.state.value.busy)
            assertTrue(rebuilt.state.value.error!!.contains("取消"))
            rebuilt.newEntry()
            rebuilt.prepare()
            rebuilt.commit()
            advanceUntilIdle()
            assertEquals(1, ledger.load().rows.size)
        } finally {
            rebuilt.viewModelScope.cancel()
        }
    }

    /** cancel 飞行中销毁 scope：intent 未被清，重建后仍以「取消未确认」锁定。 */
    @Test
    fun scopeDestroyedDuringCancelKeepsIntentLockAfterRebuild() = runVm { vm, ledger, _, handle ->
        advanceUntilIdle()
        vm.newEntry()
        vm.dateChanged("2026-11-11")
        vm.amountChanged("5")
        vm.rawInputChanged("scope destroyed")
        vm.prepare()
        advanceUntilIdle()
        assertNotNull(vm.state.value.confirmation)
        vm.dismissConfirmation()
        assertTrue(vm.state.value.busy)
        assertEquals(true, handle.get<Boolean>(accountingCancelIntentKey()))
        // 未 advance：cancel IO 未执行即销毁 scope；intent 已同步落 handle，绝不能在此被清。
        vm.viewModelScope.cancel()
        assertEquals(true, handle.get<Boolean>(accountingCancelIntentKey()))
        assertTrue(ledger.load().rows.isEmpty())

        val rebuilt = AccountingViewModel({ ledger }, handle, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            assertTrue(rebuilt.state.value.reviewRequired)
            assertFalse(rebuilt.state.value.busy)
            assertTrue(rebuilt.state.value.error!!.contains("取消"))
            rebuilt.newEntry()
            rebuilt.commit()
            advanceUntilIdle()
            assertTrue(ledger.load().rows.isEmpty())
        } finally {
            rebuilt.viewModelScope.cancel()
        }
    }

    /** reload 失败保留原不明结果提示并补当前读取错误码；重复 reload 不叠加文案。 */
    @Test
    fun reloadFailurePreservesUnclearResultAndAppendsReadCodeOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("knte-accounting-cancel-reload-").toFile()
        root.resolve("db").writeText("占位：使库结构异常")
        val ledger = IsolatedAccountingLedger(root)
        val handle = SavedStateHandle(mapOf("knte.isolated.accounting.cancelling" to true))
        val vm = AccountingViewModel({ ledger }, handle, dispatcher)
        try {
            advanceUntilIdle()
            assertTrue(vm.state.value.reviewRequired)
            val first = vm.state.value.error!!
            assertTrue(first.contains("取消"))
            assertTrue(first.contains("当前读取错误"))
            assertTrue(first.contains("DB_CHANGED"))
            vm.reload()
            advanceUntilIdle()
            val second = vm.state.value.error!!
            assertTrue(second.contains("取消"))
            assertTrue(second.contains("DB_CHANGED"))
            assertEquals(1, second.split("当前读取错误").size - 1)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    /** discard 成功清过期 error；reviewRequired 锁下 discard 仍被拒绝（由既有测试守护）。 */
    @Test
    fun discardSuccessClearsStaleError() = runVm { vm, ledger, _, _ ->
        advanceUntilIdle()
        vm.newEntry()
        vm.amountChanged("12.345")
        vm.rawInputChanged("keep")
        vm.prepare()
        advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.reviewRequired)
        var navigated = false
        vm.discard { navigated = true }
        assertTrue(navigated)
        assertNull(vm.state.value.error)
        assertNull(vm.state.value.draft)
        assertTrue(ledger.load().rows.isEmpty())
    }

    private fun seedActiveRow(ledger: IsolatedAccountingLedger, ts: String = "2026-11-13T18:00:00") {
        val draft = AccountingDraft.prepare(
            id = "r-0123abcd", date = "2026-11-13", amountText = "12.50", income = false,
            category = "餐饮", location = "本地", note = "原记录", rawInput = "seed lunch",
        )
        val plan = ledger.prepareManual(draft, ts)
        ledger.confirm(plan, plan.confirmation)
    }

    @Test
    fun alreadyWrittenUsesCoreExistingIdentityAndLeavesOnePhysicalRow() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("knte-accounting-already-written-").toFile()
        val ledger = IsolatedAccountingLedger(root)
        val id = "r-0123abcd"
        val ts = "2026-11-13T18:00:00"
        try {
            val seeded = AccountingDraft.prepare(
                id = id, date = "2026-11-13", amountText = "12.50", income = false,
                category = "餐饮", location = "本地", note = "原记录", rawInput = "seed lunch",
            )
            val seedPlan = ledger.prepareManual(seeded, ts)
            ledger.confirm(seedPlan, seedPlan.confirmation)
            val beforeBytes = root.resolve("db/accounting.jsonl").readBytes()
            val before = ledger.load()
            val handle = SavedStateHandle(mapOf(
                "knte.isolated.accounting.draft" to true,
                "knte.isolated.accounting.id" to id,
                "knte.isolated.accounting.date" to "2026-11-13",
                "knte.isolated.accounting.income" to false,
                "knte.isolated.accounting.amountText" to "12.50",
                "knte.isolated.accounting.category" to "餐饮",
                "knte.isolated.accounting.location" to "本地",
                "knte.isolated.accounting.note" to "原记录",
                "knte.isolated.accounting.rawInput" to "seed lunch",
            ))
            val vm = AccountingViewModel({ ledger }, handle, dispatcher)
            try {
                advanceUntilIdle()
                vm.prepare()
                advanceUntilIdle()
                val prepared = vm.state.value.confirmation!!
                assertTrue(prepared.ts.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")))
                assertNotEquals(ts, prepared.ts)
                vm.commit()
                advanceUntilIdle()
                val after = ledger.load()
                assertEquals(1, after.rows.size)
                assertArrayEquals(beforeBytes, root.resolve("db/accounting.jsonl").readBytes())
                assertEquals(before.revision, after.revision)
                assertNull(vm.state.value.draft)
                assertFalse(vm.state.value.busy)
                assertFalse(vm.state.value.reviewRequired)
                assertNull(vm.state.value.error)
                assertEquals("ts:$ts", vm.state.value.rows.single().viewKey)
            } finally {
                vm.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun reversalPrepareShowsFrozenBindingAndKeepsNoBusinessWrite() = runVm { vm, ledger, root, _ ->
        seedActiveRow(ledger)
        vm.reload(); advanceUntilIdle()
        val row = vm.state.value.rows.single()
        vm.prepareReversal(row.viewKey); advanceUntilIdle()
        val plan = vm.state.value.confirmation
        assertNotNull(plan)
        assertEquals("REVERSAL", plan?.entryKind?.name)
        assertEquals(row.viewKey, plan?.reversal?.originalViewKey)
        assertEquals(row.cents, plan?.reversal?.originalCents)
        assertEquals(1, ledger.load().rows.size)
        assertTrue(root.resolve("db/accounting.jsonl").exists())
    }

    @Test
    fun reversalCancelPreservesDraftlessListAndPlanCannotConfirm() = runVm { vm, ledger, _, _ ->
        seedActiveRow(ledger)
        vm.reload(); advanceUntilIdle()
        vm.prepareReversal(vm.state.value.rows.single().viewKey); advanceUntilIdle()
        val plan = vm.state.value.confirmation!!
        vm.dismissConfirmation(); advanceUntilIdle()
        assertNull(vm.state.value.confirmation)
        assertEquals(1, ledger.load().rows.size)
        try { ledger.confirm(plan, plan.confirmation); fail("cancelled reversal must not confirm") }
        catch (e: IllegalStateException) { assertEquals("PLAN_CANCELLED", e.message) }
    }

    @Test
    fun reversalConfirmIndependentReadbackZeroesReportsAndKeepsAuditRows() = runVm { vm, ledger, _, _ ->
        seedActiveRow(ledger)
        vm.reload(); advanceUntilIdle()
        vm.prepareReversal(vm.state.value.rows.single().viewKey); advanceUntilIdle()
        vm.commit(); advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.reviewRequired)
        assertNotNull(s.reversalOutcome)
        assertEquals(2, s.rows.size)
        assertEquals(0L, s.net)
        assertEquals(0L, s.monthReport?.net)
        assertTrue(s.notice?.contains("原记录仍保留") == true)
        assertEquals(2, ledger.load().rows.size)
    }

    @Test
    fun alreadyReversedTargetFailsClosedAndLocksReview() = runVm { vm, ledger, _, _ ->
        seedActiveRow(ledger)
        val first = ledger.load().rows.single()
        val plan = ledger.prepareReversal(first.viewKey, "2026-11-13T18:01:00")
        ledger.confirm(plan, plan.confirmation)
        vm.reload(); advanceUntilIdle()
        assertTrue(vm.state.value.rows.none { it.viewKey == first.viewKey && it.active })
        vm.prepareReversal(first.viewKey); advanceUntilIdle()
        assertNull(vm.state.value.confirmation)
        assertFalse(vm.state.value.reviewRequired)
        assertTrue(vm.state.value.error?.contains("VOID_TARGET_NOT_ACTIVE") == true)
    }


    @Test
    fun failedCancelPersistsFrozenSummaryAcrossSavedStateRebuildWithoutConfirmation() = runVm { vm, ledger, _, handle ->
        seedActiveRow(ledger)
        vm.reload(); advanceUntilIdle()
        vm.prepareReversal(vm.state.value.rows.single().viewKey); advanceUntilIdle()
        val plan = vm.state.value.confirmation!!
        val expected = vm.state.value.reviewSummary!!
        ledger.confirm(plan, plan.confirmation)
        vm.dismissConfirmation(); advanceUntilIdle()
        assertTrue(vm.state.value.reviewRequired)
        assertEquals(expected, vm.state.value.reviewSummary)
        assertTrue(handle.keys().any { it.contains("reviewSummary") })

        val rebuilt = AccountingViewModel({ ledger }, SavedStateHandle(handle.snapshot()), StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            assertTrue(rebuilt.state.value.reviewRequired)
            assertNull(rebuilt.state.value.confirmation)
            assertEquals(expected, rebuilt.state.value.reviewSummary)
        } finally {
            rebuilt.viewModelScope.cancel()
        }
    }

    @Test
    fun newEntryCancelsPreparedReversalBeforeCreatingManualDraft() = runVm { vm, ledger, _, _ ->
        seedActiveRow(ledger)
        vm.reload(); advanceUntilIdle()
        vm.prepareReversal(vm.state.value.rows.single().viewKey); advanceUntilIdle()
        val plan = vm.state.value.confirmation!!
        vm.newEntry()
        assertTrue(vm.state.value.busy)
        assertNull(vm.state.value.confirmation)
        advanceUntilIdle()
        assertNotNull(vm.state.value.draft)
        assertNull(vm.state.value.confirmation)
        assertEquals("CANCELLED", ledger.planState(plan).name)
        try {
            ledger.confirm(plan, plan.confirmation)
            fail("new entry must not leave old reversal confirmable")
        } catch (e: IllegalStateException) {
            assertEquals("PLAN_CANCELLED", e.message)
        }
    }

}
