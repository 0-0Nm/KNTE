package com.knte.preview.time

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.knte.core.time.repository.IsolatedTimeRepository
import com.knte.core.time.repository.TimeLedgerApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Real host temporary-file ledger + deterministic VM dispatch; not Android lifecycle evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimeViewModelTest {

    private fun runVm(
        handle: SavedStateHandle = SavedStateHandle(),
        block: suspend TestScope.(TimeViewModel, IsolatedTimeRepository, File, SavedStateHandle) -> Unit
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("knte-time-vm-").toFile()
        val repository = IsolatedTimeRepository.open(root)
        val api = TimeLedgerApi(repository)
        val vm = TimeViewModel({ api }, handle, dispatcher)
        try { block(vm, repository, root, handle) }
        finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun realEmptyLoadIsNotUnconnectedOrFailure() = runVm { vm, repo, _, _ ->
        advanceUntilIdle()
        assertTrue(vm.state.value.loaded)
        assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.records.isEmpty())
        assertNull(vm.state.value.error)
        assertTrue(repo.list().events.isEmpty())
    }

    @Test fun prepareCancelDoesNotAppend() = runVm { vm, repo, _, _ ->
        advanceUntilIdle()
        vm.beginStart()
        vm.projectChanged("真实项目")
        vm.prepareStart()
        advanceUntilIdle()
        assertNotNull(vm.state.value.confirmation)
        assertTrue(repo.list().events.isEmpty())
        vm.dismissConfirmation()
        assertTrue(repo.list().events.isEmpty())
        assertNotNull(vm.state.value.draft)
        assertEquals("真实项目", vm.state.value.draft?.project)
    }

    @Test fun startConfirmReadsBackRunningRecord() = runVm { vm, repo, _, _ ->
        advanceUntilIdle()
        vm.beginStart()
        vm.projectChanged("写后重读")
        vm.prepareStart()
        advanceUntilIdle()
        val c = vm.state.value.confirmation
        assertNotNull(c)
        assertEquals("start", c?.op)
        assertNotNull(c?.date)
        assertNotNull(c?.start)
        vm.commitStart()
        advanceUntilIdle()
        assertNull(vm.state.value.draft)
        assertNull(vm.state.value.error)
        val record = repo.list().projection.find(c?.targetId!!)
        assertNotNull(record)
        assertTrue(record!!.running())
        assertEquals("写后重读", record.project)
        // The UI state is the independent re-read, not the write's own readback.
        assertEquals(1, vm.state.value.records.size)
        assertTrue(vm.state.value.records.first().running())
        assertEquals(record.start, vm.state.value.records.first().start)
    }

    @Test fun stopConfirmReadsBackDuration() = runVm { vm, repo, _, _ ->
        advanceUntilIdle()
        vm.beginStart(); vm.projectChanged("计时项目"); vm.prepareStart(); advanceUntilIdle()
        val target = vm.state.value.confirmation!!.targetId
        vm.commitStart(); advanceUntilIdle()
        assertTrue(repo.list().projection.find(target)!!.running())
        vm.prepareStop(target); advanceUntilIdle()
        val c = vm.state.value.confirmation
        assertNotNull(c)
        assertEquals("stop", c?.op)
        assertNotNull(c?.end)
        vm.commitStop(); advanceUntilIdle()
        val record = repo.list().projection.find(target)
        assertNotNull(record)
        assertFalse(record!!.running())
        assertNotNull(record.finish)
        assertTrue(record.minutes() >= 0)
        assertNull(vm.state.value.error)
        assertEquals(record.minutes(), vm.state.value.records.first().minutes())
    }

    @Test fun busyGateRejectsDuplicateCommitAndDiscard() = runVm { vm, repo, _, _ ->
        advanceUntilIdle()
        vm.beginStart(); vm.projectChanged("单闸门"); vm.prepareStart(); advanceUntilIdle()
        vm.commitStart()
        // The commit is still in flight: busy must block a second commit, a discard and new input.
        assertTrue(vm.state.value.busy)
        vm.commitStart(); vm.discard(); vm.beginStart(); vm.projectChanged("忽略")
        assertEquals("单闸门", vm.state.value.draft?.project)
        advanceUntilIdle()
        assertEquals(1, repo.list().events.size)
        assertNull(vm.state.value.draft)
    }

    @Test fun emptyProjectNameKeepsDraftAndWritesNothing() = runVm { vm, repo, _, _ ->
        advanceUntilIdle()
        vm.beginStart(); vm.projectChanged("")
        vm.prepareStart(); advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        assertNull(vm.state.value.confirmation)
        assertNotNull(vm.state.value.draft)
        assertTrue(repo.list().events.isEmpty())
    }

    @Test fun overLimitProjectKeepsAcceptedDraftAndInvalidatesConfirmation() = runVm { vm, repo, _, _ ->
        advanceUntilIdle()
        vm.beginStart(); vm.projectChanged("可接受"); vm.prepareStart(); advanceUntilIdle()
        assertNotNull(vm.state.value.confirmation)
        vm.projectChanged("x".repeat(TIME_PROJECT_LIMIT + 1))
        assertEquals("可接受", vm.state.value.draft?.project)
        assertNull(vm.state.value.confirmation)
        assertNotNull(vm.state.value.notice)
        assertTrue(repo.list().events.isEmpty())
    }

    /** Uncertain outcome after the append: the store keeps its pending marker, the VM locks retries. */
    @Test fun failedCommitLocksRetryAndPreservesDraft() = runVm { vm, repo, root, _ ->
        advanceUntilIdle()
        vm.beginStart(); vm.projectChanged("结果未确认"); vm.prepareStart(); advanceUntilIdle()
        val plan = vm.state.value.confirmation!!.plan
        // A durable pending receipt blocks every further read/commit: fail closed, no auto-recovery.
        root.resolve("timetrack.jsonl.pending").writeText("blocked\n")
        vm.commitStart(); advanceUntilIdle()
        assertTrue(vm.state.value.reviewRequired)
        assertNotNull(vm.state.value.error)
        assertEquals("结果未确认", vm.state.value.draft?.project)
        // Fail closed: the store refuses every read while the pending marker is there.
        val blocked = runCatching { repo.list() }
        assertTrue(blocked.isFailure)
        assertTrue(blocked.exceptionOrNull()?.message?.contains("TIME_PENDING_REVIEW_REQUIRED") == true)
        assertFalse(root.resolve("timetrack.jsonl").exists())
        // No automatic retry: a later manual attempt is refused too, and the ledger stays untouched.
        vm.prepareStart(); vm.commitStart(); advanceUntilIdle()
        assertTrue(vm.state.value.reviewRequired)
        assertFalse(root.resolve("timetrack.jsonl").exists())
        // The refused plan is the immutable one the store issued: same op, same baseline.
        assertEquals("start", plan.op)
        assertTrue(plan.eventId.isNotEmpty())
        assertTrue(plan.token.isNotEmpty())
        assertFalse(root.resolve(".journal").listFiles()?.any { it.isDirectory } ?: false)
    }

    @Test fun unknownWritingIntentNeverAutomaticallyRetries() = runVm(SavedStateHandle(mapOf(
        "knte.isolated.time.writing" to true
    ))) { vm, repo, _, _ ->
        advanceUntilIdle()
        assertTrue(vm.state.value.reviewRequired)
        assertNotNull(vm.state.value.error)
        vm.beginStart()
        assertNull(vm.state.value.draft)
        vm.projectChanged("被阻止")
        vm.prepareStart(); vm.commitStart(); advanceUntilIdle()
        assertTrue(repo.list().events.isEmpty())
        assertTrue(vm.state.value.reviewRequired)
        vm.discard()
        assertTrue(vm.state.value.reviewRequired)
    }

    @Test fun restoredDraftDoesNotRestoreApprovalOrAutoWrite() = runVm(SavedStateHandle(mapOf(
        "knte.isolated.time.draft" to true,
        "knte.isolated.time.project" to "恢复但未保存"
    ))) { vm, repo, _, _ ->
        advanceUntilIdle()
        assertEquals("恢复但未保存", vm.state.value.draft?.project)
        assertNull(vm.state.value.confirmation)
        assertTrue(repo.list().events.isEmpty())
        assertTrue(vm.state.value.hasUnsavedChanges)
        assertFalse(vm.state.value.canLeave)
    }

    @Test fun emptyStartIsCleanButTypedProjectRequiresProtection() = runVm { vm, _, _, _ ->
        advanceUntilIdle()
        vm.beginStart()
        assertFalse(vm.state.value.hasUnsavedChanges)
        assertTrue(vm.state.value.canLeave)
        vm.projectChanged("草稿")
        assertTrue(vm.state.value.hasUnsavedChanges)
        vm.projectChanged("")
        assertFalse(vm.state.value.hasUnsavedChanges)
    }

    @Test fun orphanRunningRecordIsOnlyHintedNeverAutoStopped() = runVm { vm, repo, _, _ ->
        advanceUntilIdle()
        vm.beginStart(); vm.projectChanged("孤儿提示"); vm.prepareStart(); advanceUntilIdle()
        val target = vm.state.value.confirmation!!.targetId
        vm.commitStart(); advanceUntilIdle()
        // Nothing synthesises a stop: the record stays running until a human confirms one.
        assertTrue(repo.list().projection.find(target)!!.running())
        assertTrue(vm.state.value.running.isNotEmpty())
        vm.reload(); advanceUntilIdle()
        assertTrue(vm.state.value.running.isNotEmpty())
        assertTrue(vm.state.value.records.first().running())
    }
}
