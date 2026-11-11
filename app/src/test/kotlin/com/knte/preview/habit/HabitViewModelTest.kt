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

}
