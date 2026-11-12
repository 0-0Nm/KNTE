package com.knte.preview.habit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.knte.core.habit.HabitCodec
import com.knte.core.habit.HabitLedgerApi
import com.knte.core.habit.HabitLedgerStore
import com.knte.core.habit.HabitMark
import com.knte.core.habit.HabitProjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/** Real host temp-file ledger + deterministic VM dispatch; not Android lifecycle evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
class HabitViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun runVm(
        handle: SavedStateHandle = SavedStateHandle(),
        block: suspend TestScope.(HabitViewModel, HabitLedgerApi, File, HabitLedgerStore) -> Unit
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = folder.newFolder("habit-vm")
        val store = HabitLedgerStore.open(root)
        val api = HabitLedgerApi(store)
        val vm = HabitViewModel({ api }, handle, dispatcher)
        try { block(vm, api, root, store) }
        finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    private fun newId(): String = HabitCodec.newEventId()

    private suspend fun TestScope.createHabit(
        vm: HabitViewModel, name: String, target: Int
    ): String {
        vm.beginCreate(); advanceUntilIdle()
        vm.nameChanged(name); vm.targetDaysChanged(target.toString())
        vm.prepareIdentity(); advanceUntilIdle()
        val id = vm.state.value.confirmation?.habitId ?: error("no plan")
        vm.confirmCommit(); advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.reviewRequired.not())
        return id
    }

    /** 初始读取成功、真实空库不被当成未连接或失败。 */
    @Test fun initialSuccessEmptyIsNotFailure() = runVm { vm, api, root, _ ->
        advanceUntilIdle()
        assertTrue(vm.state.value.loaded)
        assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.identities.isEmpty())
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.reviewRequired)
        assertFalse(root.resolve("db/habits.jsonl").exists())
        assertEquals(0, api.snapshot().eventCount)
    }

    /** 创建 prepare 后取消：不写任何事件，草稿保留。 */
    @Test fun createPrepareCancelWritesNothing() = runVm { vm, api, root, _ ->
        advanceUntilIdle()
        vm.beginCreate(); advanceUntilIdle()
        vm.nameChanged("早读"); vm.targetDaysChanged("21")
        vm.prepareIdentity(); advanceUntilIdle()
        assertNotNull(vm.state.value.confirmation)
        assertEquals("create", vm.state.value.confirmation?.op)
        assertEquals(0, api.snapshot().eventCount)
        vm.dismissConfirmation()
        advanceUntilIdle()
        assertNull(vm.state.value.confirmation)
        assertEquals(0, api.snapshot().eventCount)
        assertNotNull(vm.state.value.draft)
        assertEquals("早读", vm.state.value.draft?.name)
        assertFalse(root.resolve("db/habits.jsonl").exists())
    }

    /** 确认后独立重读：习惯真实存在，名称与目标天数与计划一致。 */
    @Test fun confirmReadsBackCreatedHabit() = runVm { vm, api, root, _ ->
        advanceUntilIdle()
        val id = createHabit(vm, "早读", 21)
        val identity = api.snapshot().identity(id)
        assertNotNull(identity)
        assertEquals("早读", identity!!.name)
        assertEquals(21, identity.targetDays)
        assertFalse(identity.archived)
        // The UI state is the independent re-read, not the write's own readback.
        assertEquals(identity, vm.state.value.snapshot?.identity(id))
        assertEquals(id, vm.state.value.selectedId)
        assertNull(vm.state.value.draft)
        assertTrue(vm.state.value.notice?.contains("独立重读") == true)
        assertEquals(1, api.snapshot().eventCount)
    }

    /** 同日改答：旧行仍在盘上，界面只显示最新一条，同源 streak 反映最新答案。 */
    @Test fun sameDayCorrectionKeepsHistory() = runVm { vm, api, root, _ ->
        advanceUntilIdle()
        val id = createHabit(vm, "早读", 21)
        val today = LocalDate.now().toString()
        // first answer: done
        vm.beginCheck(id); advanceUntilIdle()
        vm.prepareCheck(); advanceUntilIdle()
        vm.confirmCommit(); advanceUntilIdle()
        assertEquals(HabitMark.DONE, api.snapshot().mark(id, LocalDate.parse(today)))
        // same day correction: not done
        vm.beginCheck(id); advanceUntilIdle()
        vm.checkDoneChanged(false); vm.checkNoteChanged("改主意")
        vm.prepareCheck(); advanceUntilIdle()
        vm.confirmCommit(); advanceUntilIdle()
        val snapshot = api.snapshot()
        assertEquals(3, snapshot.eventCount)
        assertEquals(HabitMark.FAILED, snapshot.mark(id, LocalDate.parse(today)))
        assertTrue(snapshot.sameDayCorrectionCount >= 1)
        assertEquals(1, snapshot.history(id).size)
        assertEquals(HabitMark.FAILED, vm.state.value.snapshot?.mark(id, LocalDate.parse(today)))
    }

    /** 连续天数为投影：未打错的今天不算断，缺失日不是失败。 */
    @Test fun streakUnknownDayIsNotFailure() = runVm { vm, api, root, _ ->
        advanceUntilIdle()
        val id = createHabit(vm, "早读", 21)
        val today = LocalDate.now()
        val ts = HabitCodec.stamp(java.time.LocalDateTime.of(today, java.time.LocalTime.of(9, 0, 0)))
        fun check(day: LocalDate, done: Boolean) {
            val plan = api.planCheck(id, day.toString(), done, null, newId(), ts)
            api.commit(plan, plan.confirmation)
        }
        check(today.minusDays(3), true)
        check(today.minusDays(2), true)
        // yesterday has no row at all: UNKNOWN, never FAILED
        check(today.minusDays(1), false)
        advanceUntilIdle()
        val snapshot = api.snapshot()
        assertEquals(HabitMark.UNKNOWN, snapshot.mark(id, today.minusDays(4)))
        assertEquals(0, snapshot.currentStreak(id, today))
        assertEquals(2, snapshot.streak(id, today.minusDays(2)))
        // heat: 28 cells, one per day, same source as the marks above
        assertEquals(HABIT_HEAT_DAYS, snapshot.heat(id, today, HABIT_HEAT_DAYS).size)
        assertEquals(HabitMark.UNKNOWN, snapshot.heat(id, today, HABIT_HEAT_DAYS).first())
        assertEquals("尚未开始", HabitProjection.stage(snapshot.currentStreak(id, today)))
    }

    /** 归档后不能继续打卡；恢复后可继续，历史一行不删。 */
    @Test fun archiveThenRestoreKeepsHistoryAndGate() = runVm { vm, api, root, _ ->
        advanceUntilIdle()
        val id = createHabit(vm, "早读", 21)
        vm.beginCheck(id); advanceUntilIdle()
        vm.prepareCheck(); advanceUntilIdle()
        vm.confirmCommit(); advanceUntilIdle()
        val before = api.snapshot().eventCount

        vm.prepareArchive(id, true); advanceUntilIdle()
        assertNotNull(vm.state.value.confirmation)
        assertEquals("archive", vm.state.value.confirmation?.op)
        vm.confirmCommit(); advanceUntilIdle()
        assertTrue(api.snapshot().identity(id)!!.archived)

        // archived habit cannot check in
        vm.beginCheck(id); advanceUntilIdle()
        assertNull(vm.state.value.draft)
        assertNotNull(vm.state.value.error)
        assertEquals(before + 1, api.snapshot().eventCount)

        vm.prepareArchive(id, false); advanceUntilIdle()
        assertNotNull(vm.state.value.confirmation)
        assertEquals("restore", vm.state.value.confirmation?.op)
        vm.confirmCommit(); advanceUntilIdle()
        assertFalse(api.snapshot().identity(id)!!.archived)
        vm.beginCheck(id); advanceUntilIdle()
        assertNotNull(vm.state.value.draft)
        assertEquals(before + 2, api.snapshot().eventCount)
    }

    /** 非法目标天数：报错保留草稿，不写入。 */
    @Test fun invalidTargetKeepsDraftAndWritesNothing() = runVm { vm, api, root, _ ->
        advanceUntilIdle()
        vm.beginCreate(); advanceUntilIdle()
        vm.nameChanged("早读")
        listOf("", "0", "-1", "3651", "abc", "2.5", "007", "12345").forEach { text ->
            vm.targetDaysChanged(text)
            vm.prepareIdentity(); advanceUntilIdle()
            assertNull("target=$text must not plan", vm.state.value.confirmation)
            assertNotNull(vm.state.value.error)
        }
        assertEquals(0, api.snapshot().eventCount)
        // Numeric draft stays editable even when invalid for saving; never erase user input.
        assertEquals("007", vm.state.value.draft?.targetDaysText)
        // Rejected nonnumeric input preserves the last draft and cannot create a plan.
        vm.targetDaysChanged("abc")
        assertEquals("007", vm.state.value.draft?.targetDaysText)
        assertNull(vm.state.value.confirmation)
        assertNotNull(vm.state.value.notice)
    }

    /** 未来日期一律拒绝；过去日期可补记。 */
    @Test fun futureCheckRejectedPastCheckAllowed() = runVm { vm, api, root, _ ->
        advanceUntilIdle()
        val id = createHabit(vm, "早读", 21)
        val today = LocalDate.now()
        vm.beginCheck(id); advanceUntilIdle()
        vm.checkDateChanged(today.plusDays(1).toString())
        vm.prepareCheck(); advanceUntilIdle()
        assertNull(vm.state.value.confirmation)
        assertTrue(vm.state.value.error?.contains("未来") == true)
        assertEquals(1, api.snapshot().eventCount)

        vm.checkDateChanged(today.minusDays(2).toString())
        vm.prepareCheck(); advanceUntilIdle()
        assertNotNull(vm.state.value.confirmation)
        vm.confirmCommit(); advanceUntilIdle()
        assertEquals(HabitMark.DONE, api.snapshot().mark(id, today.minusDays(2)))
        assertEquals(2, api.snapshot().eventCount)
    }

    /** busy 单闸门：写入在飞时重复确认、放弃、新建都不生效。 */
    @Test fun busyGateRejectsDuplicateActions() = runVm { vm, api, root, _ ->
        advanceUntilIdle()
        vm.beginCreate(); advanceUntilIdle()
        vm.nameChanged("单闸门"); vm.targetDaysChanged("7")
        vm.prepareIdentity(); advanceUntilIdle()
        val plan = vm.state.value.confirmation!!
        vm.confirmCommit()
        assertTrue(vm.state.value.busy)
        vm.confirmCommit(); vm.discard(); vm.beginCreate(); vm.beginEdit(plan.habitId)
        advanceUntilIdle()
        assertEquals(1, api.snapshot().eventCount)
        assertNull(vm.state.value.draft)
        assertNull(vm.state.value.confirmation)
        assertFalse(vm.state.value.busy)
    }

    /** 恢复的草稿只恢复输入，不恢复核对计划，不自动写入。 */
    @Test fun restoredDraftDoesNotRestoreApprovalOrAutoWrite() = runVm(SavedStateHandle(mapOf(
        "knte.isolated.habit.draft" to true,
        "knte.isolated.habit.kind" to "CREATE",
        "knte.isolated.habit.name" to "恢复但未保存",
        "knte.isolated.habit.targetDaysText" to "30",
    ))) { vm, api, root, _ ->
        advanceUntilIdle()
        val d = vm.state.value.draft
        assertNotNull(d)
        assertEquals("恢复但未保存", d?.name)
        assertEquals("30", d?.targetDaysText)
        assertNull(vm.state.value.confirmation)
        assertEquals(0, api.snapshot().eventCount)
        assertTrue(vm.state.value.hasUnsavedChanges)
        assertFalse(vm.state.value.canLeave)
        // even an explicit confirm call cannot write without a plan
        vm.confirmCommit(); advanceUntilIdle()
        assertEquals(0, api.snapshot().eventCount)
    }

    /** 未知 writing intent：持久标记后禁止一切写入，不自动重试。 */
    @Test fun unknownWritingIntentNeverAutomaticallyRetries() = runVm(SavedStateHandle(mapOf(
        "knte.isolated.habit.writing" to true
    ))) { vm, api, root, _ ->
        advanceUntilIdle()
        assertTrue(vm.state.value.reviewRequired)
        assertNotNull(vm.state.value.error)
        vm.beginCreate(); advanceUntilIdle()
        assertNull(vm.state.value.draft)
        vm.nameChanged("被阻止"); vm.targetDaysChanged("7")
        vm.prepareIdentity(); vm.confirmCommit(); advanceUntilIdle()
        assertEquals(0, api.snapshot().eventCount)
        assertTrue(vm.state.value.reviewRequired)
        vm.discard()
        assertTrue(vm.state.value.reviewRequired)
        assertFalse(root.resolve("db/habits.jsonl").exists())
    }

    /** pending 阻塞的库：读取失败不当空库，也不把失败洗成成功。 */
    @Test fun pendingMarkerBlocksReadAndIsNotSuccess() = runVm { vm, api, root, _ ->
        advanceUntilIdle()
        val id = createHabit(vm, "待核对", 7)
        // A durable pending receipt blocks every further read: fail closed, no auto-recovery.
        root.resolve("db/habits.jsonl.pending").writeText("blocked\n")
        vm.reload(); advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.error!!.contains("HABIT_PENDING_REVIEW_REQUIRED"))
        val blocked = runCatching { api.snapshot() }
        assertTrue(blocked.isFailure)
        assertTrue(blocked.exceptionOrNull()?.message?.contains("HABIT_PENDING_REVIEW_REQUIRED") == true)
        // Nothing is presented as a successful write, and the marker is not auto-cleared.
        vm.confirmCommit(); advanceUntilIdle()
        assertTrue(root.resolve("db/habits.jsonl.pending").exists())
        assertEquals(id, vm.state.value.selectedId)
    }

    /** 已归档在「显示已归档」开关关闭时从列表消失，但数据仍在。 */
    @Test fun showArchivedToggleNeverDeletesData() = runVm { vm, api, root, _ ->
        advanceUntilIdle()
        val id = createHabit(vm, "会归档", 7)
        vm.prepareArchive(id, true); advanceUntilIdle()
        vm.confirmCommit(); advanceUntilIdle()
        assertTrue(vm.state.value.identities.isEmpty())
        vm.showArchivedChanged(true)
        assertEquals(1, vm.state.value.identities.size)
        assertEquals(id, vm.state.value.identities.first().id)
        assertEquals(2, api.snapshot().eventCount)
    }

    /** 离页 callback 必须等真实 cancel IO 成功：飞行期间 busy=true，旧计划已不可确认。 */
    @Test fun discardCallbackRunsOnlyAfterDurableCancel() = runVm { vm, api, _, _ ->
        advanceUntilIdle()
        vm.beginCreate(); advanceUntilIdle()
        vm.nameChanged("待取消"); vm.targetDaysChanged("21")
        vm.prepareIdentity(); advanceUntilIdle()
        val plan = vm.state.value.confirmation!!.plan
        var navigated = false
        vm.discard { navigated = true }
        assertFalse("cancel still in flight", navigated)
        assertTrue(vm.state.value.busy)
        assertNull(vm.state.value.confirmation)
        advanceUntilIdle()
        assertTrue("navigation must follow successful cancel", navigated)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.draft)
        assertEquals(0, api.snapshot().eventCount)
        try {
            api.commit(plan, plan.confirmation)
            fail("cancelled plan must never commit")
        } catch (e: IllegalStateException) {
            assertEquals("PLAN_CANCELLED", e.message)
        }
    }

    /** 取消已 CONFIRMED 的计划失败：不 callback、不清稿、writing 锁不解除。 */
    @Test fun failedCancelKeepsDraftAndWritingLock() = runVm { vm, api, _, _ ->
        advanceUntilIdle()
        vm.beginCreate(); advanceUntilIdle()
        vm.nameChanged("保留"); vm.targetDaysChanged("7")
        vm.prepareIdentity(); advanceUntilIdle()
        val plan = vm.state.value.confirmation!!.plan
        // 真实 store 外部确认该计划；cancel 必须对 CONFIRMED 显式失败，不能默默成功。
        api.commit(plan, plan.confirmation)
        var navigated = false
        vm.discard { navigated = true }
        advanceUntilIdle()
        assertFalse(navigated)
        assertTrue(vm.state.value.reviewRequired)
        assertFalse(vm.state.value.busy)
        assertEquals("保留", vm.state.value.draft?.name)
        assertEquals(1, api.snapshot().eventCount)
        vm.prepareIdentity()
        advanceUntilIdle()
        assertNull(vm.state.value.confirmation)
        assertEquals(1, api.snapshot().eventCount)
        vm.discard { navigated = true }
        advanceUntilIdle()
        assertFalse(navigated)
        assertTrue(vm.state.value.reviewRequired)
    }

    /** dismiss/字段取消飞行 busy：重复 prepare 被拒绝，字段串行落在新值且失败不先清 error。 */
    @Test fun cancelInFlightBlocksPrepareAndSerializesFieldChange() = runVm { vm, api, _, _ ->
        advanceUntilIdle()
        vm.beginCreate(); advanceUntilIdle()
        vm.nameChanged("初始"); vm.targetDaysChanged("7")
        vm.prepareIdentity(); advanceUntilIdle()
        val plan = vm.state.value.confirmation!!.plan
        vm.dismissConfirmation()
        assertTrue(vm.state.value.busy)
        vm.prepareIdentity()
        vm.nameChanged("飞行中")
        assertNull("busy must block duplicate prepare", vm.state.value.confirmation)
        assertEquals("初始", vm.state.value.draft?.name)
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.error)
        assertNull(vm.state.value.confirmation)
        assertNotNull(vm.state.value.draft)
        try {
            api.commit(plan, plan.confirmation)
            fail("dismissed plan must remain cancelled")
        } catch (e: IllegalStateException) {
            assertEquals("PLAN_CANCELLED", e.message)
        }
        vm.nameChanged("飞行后")
        assertEquals("飞行后", vm.state.value.draft?.name)
        assertEquals(0, api.snapshot().eventCount)
    }

    /** 字段变化路径同样串行：旧计划 cancel 成功后才保存新字段，失败则保原稿且不覆盖 error。 */
    @Test fun fieldChangeWaitsForCancelSuccessAndDoesNotOverwriteFailure() = runVm { vm, api, _, _ ->
        advanceUntilIdle()
        vm.beginCreate(); advanceUntilIdle()
        vm.nameChanged("旧值"); vm.targetDaysChanged("7")
        vm.prepareIdentity(); advanceUntilIdle()
        val plan = vm.state.value.confirmation!!.plan
        vm.nameChanged("新值")
        assertTrue(vm.state.value.busy)
        assertEquals("旧值", vm.state.value.draft?.name)
        advanceUntilIdle()
        assertEquals("新值", vm.state.value.draft?.name)
        try {
            api.commit(plan, plan.confirmation)
            fail("field change must cancel old plan")
        } catch (e: IllegalStateException) {
            assertEquals("PLAN_CANCELLED", e.message)
        }
        vm.prepareIdentity()
        advanceUntilIdle()
        val failedPlan = vm.state.value.confirmation!!.plan
        api.commit(failedPlan, failedPlan.confirmation)
        vm.nameChanged("不能落")
        advanceUntilIdle()
        assertTrue(vm.state.value.reviewRequired)
        assertEquals("新值", vm.state.value.draft?.name)
        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.error!!.contains("取消未确认"))
        assertEquals(1, api.snapshot().eventCount)
    }

    /** 无 pending 计划时同步清稿回调；已存事件不删除。 */
    @Test fun discardWithoutPlanClearsDraftSynchronouslyAndKeepsEvents() = runVm { vm, api, _, _ ->
        advanceUntilIdle()
        val id = createHabit(vm, "已存", 7)
        assertEquals(1, api.snapshot().eventCount)
        assertEquals(id, api.snapshot().identity(id)?.id)
        vm.beginCreate(); advanceUntilIdle()
        vm.nameChanged("草稿")
        var navigated = false
        vm.discard { navigated = true }
        assertTrue(navigated)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.draft)
        assertEquals(1, api.snapshot().eventCount)
    }


    /** unknown writing 锁：discard 不得解除 reviewRequired，也不得触发离页 callback。 */
    @Test fun unknownWritingLockCannotBeDiscarded() = runVm(SavedStateHandle(mapOf(
        "knte.isolated.habit.writing" to true,
        "knte.isolated.habit.draft" to true,
        "knte.isolated.habit.kind" to "CREATE",
        "knte.isolated.habit.name" to "locked",
    ))) { vm, api, _, _ ->
        advanceUntilIdle()
        assertTrue(vm.state.value.reviewRequired)
        var navigated = false
        vm.discard { navigated = true }
        vm.dismissConfirmation()
        advanceUntilIdle()
        assertFalse(navigated)
        assertTrue(vm.state.value.reviewRequired)
        assertFalse(vm.state.value.busy)
        assertEquals("locked", vm.state.value.draft?.name)
        assertEquals(0, api.snapshot().eventCount)
    }

    @Test fun editNameUsesNewConfirmedNameAndIndependentReadback() = runVm { vm, api, _, _ ->
        advanceUntilIdle()
        val id = createHabit(vm, "before", 21)
        vm.beginEdit(id); vm.nameChanged("after"); vm.prepareIdentity(); advanceUntilIdle()
        assertEquals("after", vm.state.value.confirmation?.habitName)
        vm.confirmCommit(); advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals("after", api.snapshot().identity(id)?.name)
        assertFalse(vm.state.value.reviewRequired)
    }
    @Test fun invalidEditedTargetCannotHideBehindNameChange() = runVm { vm, api, _, _ ->
        advanceUntilIdle()
        val id = createHabit(vm, "before", 21)
        vm.beginEdit(id);vm.nameChanged("after");vm.targetDaysChanged("0")
        vm.prepareIdentity();advanceUntilIdle()
        assertNull(vm.state.value.confirmation);assertNotNull(vm.state.value.error)
        assertEquals("before", api.snapshot().identity(id)?.name)
    }

    // ---- cancel intent 持久锁定回归（隔离副本新增） -----------------------

    private fun habitCancelIntentKey() = "knte.isolated.habit.cancelling"

    /** 取消成功才清 intent：正常 dismiss 后 handle 不再有取消 intent，且未进入 reviewRequired。 */
    @Test fun cancelSuccessClearsPersistedIntent() {
        val handle = SavedStateHandle()
        runVm(handle) { vm, api, _, _ ->
            advanceUntilIdle()
            vm.beginCreate(); advanceUntilIdle()
            vm.nameChanged("待取消"); vm.targetDaysChanged("21")
            vm.prepareIdentity(); advanceUntilIdle()
            assertNotNull(vm.state.value.confirmation)
            vm.dismissConfirmation()
            assertTrue(vm.state.value.busy)
            assertEquals(true, handle.get<Boolean>(habitCancelIntentKey()))
            advanceUntilIdle()
            assertFalse(vm.state.value.busy)
            assertFalse(vm.state.value.reviewRequired)
            assertNull(vm.state.value.error)
            assertFalse(handle.get<Boolean>(habitCancelIntentKey()) == true)
            assertEquals(0, api.snapshot().eventCount)
        }
    }

    /** 取消失败后同 SavedStateHandle 重建：init 识别取消 intent 并明确「取消未确认」锁定。 */
    @Test fun failedCancelIntentKeepsRebuiltVmLocked() {
        val handle = SavedStateHandle()
        runVm(handle) { vm, api, _, _ ->
            advanceUntilIdle()
            vm.beginCreate(); advanceUntilIdle()
            vm.nameChanged("取消失败"); vm.targetDaysChanged("7")
            vm.prepareIdentity(); advanceUntilIdle()
            val plan = vm.state.value.confirmation!!.plan
            api.commit(plan, plan.confirmation)
            vm.dismissConfirmation()
            advanceUntilIdle()
            assertTrue(vm.state.value.reviewRequired)
            assertEquals(true, handle.get<Boolean>(habitCancelIntentKey()))
            assertEquals(1, api.snapshot().eventCount)

            val rebuilt = HabitViewModel({ api }, handle, StandardTestDispatcher(testScheduler))
            try {
                advanceUntilIdle()
                assertTrue(rebuilt.state.value.reviewRequired)
                assertFalse(rebuilt.state.value.busy)
                assertTrue(rebuilt.state.value.error!!.contains("取消"))
                rebuilt.beginCreate(); rebuilt.prepareIdentity(); rebuilt.confirmCommit()
                advanceUntilIdle()
                assertEquals(1, api.snapshot().eventCount)
            } finally {
                rebuilt.viewModelScope.cancel()
            }
        }
    }

    /** cancel 飞行中销毁 scope：intent 未被清，重建后仍以「取消未确认」锁定。 */
    @Test fun scopeDestroyedDuringCancelKeepsIntentLockAfterRebuild() {
        val handle = SavedStateHandle()
        runVm(handle) { vm, api, _, _ ->
            advanceUntilIdle()
            vm.beginCreate(); advanceUntilIdle()
            vm.nameChanged("飞行中"); vm.targetDaysChanged("7")
            vm.prepareIdentity(); advanceUntilIdle()
            assertNotNull(vm.state.value.confirmation)
            vm.dismissConfirmation()
            assertTrue(vm.state.value.busy)
            assertEquals(true, handle.get<Boolean>(habitCancelIntentKey()))
            vm.viewModelScope.cancel()
            assertEquals(true, handle.get<Boolean>(habitCancelIntentKey()))
            assertEquals(0, api.snapshot().eventCount)

            val rebuilt = HabitViewModel({ api }, handle, StandardTestDispatcher(testScheduler))
            try {
                advanceUntilIdle()
                assertTrue(rebuilt.state.value.reviewRequired)
                assertFalse(rebuilt.state.value.busy)
                assertTrue(rebuilt.state.value.error!!.contains("取消"))
                rebuilt.beginCreate(); rebuilt.confirmCommit()
                advanceUntilIdle()
                assertEquals(0, api.snapshot().eventCount)
            } finally {
                rebuilt.viewModelScope.cancel()
            }
        }
    }

    /** reload 失败保留原不明结果提示并补当前读取错误码；重复 reload 不叠加文案。 */
    @Test fun reloadFailurePreservesUnclearResultAndAppendsReadCodeOnce() {
        val handle = SavedStateHandle(mapOf("knte.isolated.habit.cancelling" to true))
        runVm(handle) { vm, _, root, _ ->
            val pending = root.resolve("db/habits.jsonl.pending")
            pending.parentFile?.mkdirs()
            pending.writeText("blocked\n")
            advanceUntilIdle()
            assertTrue(vm.state.value.reviewRequired)
            val first = vm.state.value.error!!
            assertTrue(first.contains("取消"))
            assertTrue(first.contains("当前读取错误"))
            assertTrue(first.contains("HABIT_PENDING_REVIEW_REQUIRED"))
            vm.reload()
            advanceUntilIdle()
            val second = vm.state.value.error!!
            assertTrue(second.contains("取消"))
            assertTrue(second.contains("HABIT_PENDING_REVIEW_REQUIRED"))
            assertEquals(1, second.split("当前读取错误").size - 1)
        }
    }

    /** discard 成功清过期 error；reviewRequired 锁下 discard 仍被拒绝（由既有测试守护）。 */
    @Test fun discardSuccessClearsStaleError() = runVm { vm, api, _, _ ->
        advanceUntilIdle()
        vm.beginCreate(); advanceUntilIdle()
        vm.nameChanged(""); vm.targetDaysChanged("0")
        vm.prepareIdentity(); advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.reviewRequired)
        var navigated = false
        vm.discard { navigated = true }
        assertTrue(navigated)
        assertNull(vm.state.value.error)
        assertNull(vm.state.value.draft)
        assertEquals(0, api.snapshot().eventCount)
    }

}
