package com.knte.preview.diary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.knte.core.diary.DiaryCodec
import com.knte.core.diary.IsolatedDiaryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Real host temporary-file repository + deterministic VM dispatch; not Android lifecycle evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModelTest {
    private fun runVm(handle: SavedStateHandle = SavedStateHandle(),
        block: suspend TestScope.(DiaryViewModel, IsolatedDiaryRepository, File, SavedStateHandle) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("knte-diary-vm-").toFile()
        val repository = IsolatedDiaryRepository(root)
        val vm = DiaryViewModel({ repository }, handle, dispatcher)
        try { block(vm, repository, root, handle) }
        finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun realEmptyLoadIsNotUnconnectedOrFailure() = runVm { vm, _, _, _ ->
        advanceUntilIdle()
        assertTrue(vm.state.value.loaded);assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.entries.isEmpty());assertNull(vm.state.value.error)
    }
    @Test fun prepareCancelDoesNotWriteAndCommitIndependentlyReadsBack() = runVm { vm, repo, _, _ ->
        advanceUntilIdle();vm.newDiary();vm.dateChanged("2026-11-11");vm.bodyChanged("real temporary md")
        vm.prepare();advanceUntilIdle();assertNotNull(vm.state.value.confirmation)
        assertTrue(repo.list().isEmpty())
        vm.dismissConfirmation();assertTrue(repo.list().isEmpty());assertNotNull(vm.state.value.draft)
        vm.prepare();advanceUntilIdle();vm.commit();advanceUntilIdle()
        assertNull(vm.state.value.draft);assertNull(vm.state.value.error)
        assertEquals("real temporary md",repo.get("2026-11-11.md")?.document?.body)
        assertEquals(1,vm.state.value.entries.size)
    }
    @Test fun invalidDateFailsBeforeWriteAndKeepsDraft() = runVm { vm, repo, _, _ ->
        advanceUntilIdle();vm.newDiary();vm.dateChanged("2026-02-30");vm.bodyChanged("keep me")
        vm.prepare();advanceUntilIdle()
        assertNotNull(vm.state.value.error);assertNull(vm.state.value.confirmation)
        assertEquals("keep me",vm.state.value.draft?.body);assertTrue(repo.list().isEmpty())
    }
    @Test fun overLimitKeepsAcceptedDraftAndInvalidatesConfirmation() = runVm { vm, repo, _, _ ->
        advanceUntilIdle();vm.newDiary();vm.dateChanged("2026-11-11");vm.bodyChanged("accepted")
        vm.prepare();advanceUntilIdle();assertNotNull(vm.state.value.confirmation)
        vm.bodyChanged("x".repeat(DIARY_DRAFT_BYTES+1))
        assertEquals("accepted",vm.state.value.draft?.body);assertNull(vm.state.value.confirmation)
        assertNotNull(vm.state.value.notice);assertTrue(repo.list().isEmpty())
    }
    @Test fun busyGuardsPreventDuplicateCommitOrDiscard() = runVm { vm, repo, _, _ ->
        advanceUntilIdle();vm.newDiary();vm.dateChanged("2026-11-11");vm.bodyChanged("once")
        vm.prepare();advanceUntilIdle();vm.commit()
        assertTrue(vm.state.value.busy);vm.commit();vm.discard();vm.newDiary();vm.bodyChanged("ignored")
        assertEquals("once",vm.state.value.draft?.body)
        advanceUntilIdle();assertEquals(1,repo.list().size);assertNull(vm.state.value.draft)
    }
    @Test fun conflictAfterConfirmationLocksRetryAndPreservesDraft() = runVm { vm, repo, root, _ ->
        advanceUntilIdle()
        val p=repo.planCreate(DiaryCodec.create("2026-11-11","original"));repo.commit(p,p.confirmation)
        vm.open("2026-11-11.md");advanceUntilIdle();vm.bodyChanged("my draft")
        vm.prepare();advanceUntilIdle();assertNotNull(vm.state.value.confirmation)
        val external=DiaryCodec.create("2026-11-11","external").bytes()
        root.resolve("2026-11-11.md").writeBytes(external)
        vm.commit();advanceUntilIdle()
        assertTrue(vm.state.value.reviewRequired);assertEquals("my draft",vm.state.value.draft?.body)
        vm.prepare();vm.commit();advanceUntilIdle()
        assertArrayEquals(external,root.resolve("2026-11-11.md").readBytes())
        vm.discard();assertTrue(vm.state.value.reviewRequired)
    }
    @Test fun restoredDraftDoesNotRestoreApprovalOrAutoWrite() = runVm(SavedStateHandle(mapOf(
        "knte.isolated.diary.draft" to true,
        "knte.isolated.diary.date" to "2026-11-11",
        "knte.isolated.diary.body" to "restored but unsaved"
    ))) { vm, repo, _, _ ->
        advanceUntilIdle();assertEquals("restored but unsaved",vm.state.value.draft?.body)
        assertNull(vm.state.value.confirmation);assertTrue(repo.list().isEmpty())
    }
    @Test fun unknownWritingIntentNeverAutomaticallyRetries() = runVm(SavedStateHandle(mapOf(
        "knte.isolated.diary.draft" to true,
        "knte.isolated.diary.date" to "2026-11-11",
        "knte.isolated.diary.body" to "uncertain",
        "knte.isolated.diary.writing" to true
    ))) { vm, repo, _, _ ->
        advanceUntilIdle();assertTrue(vm.state.value.reviewRequired)
        vm.prepare();vm.commit();advanceUntilIdle()
        assertTrue(repo.list().isEmpty());assertEquals("uncertain",vm.state.value.draft?.body)
    }
}
