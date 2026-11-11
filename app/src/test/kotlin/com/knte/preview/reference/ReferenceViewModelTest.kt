package com.knte.preview.reference
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.knte.core.reference.*
import com.knte.core.reference.store.ReferenceStoreApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
@OptIn(ExperimentalCoroutinesApi::class)
class ReferenceViewModelTest {
    private fun runVm(handle:SavedStateHandle=SavedStateHandle(),block:suspend TestScope.(ReferenceViewModel,()->ReferenceStoreApi)->Unit)=runTest{
        val d=StandardTestDispatcher(testScheduler);Dispatchers.setMain(d)
        val dir=Files.createTempDirectory("knte-ref-vm-");val factory={ReferenceStoreApi.open(dir)}
        val vm=ReferenceViewModel(factory,handle,d)
        try{block(vm,factory)}finally{vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }
    @Test fun successfulEmptyIsRealSnapshot()=runVm{vm,_->advanceUntilIdle();assertTrue(vm.state.value.loaded);assertTrue(vm.state.value.snapshot!!.entries.isEmpty());assertNull(vm.state.value.error)}
    @Test fun prepareCancelDoesNotWrite()=runVm{vm,f->
        advanceUntilIdle();vm.begin(ReferencePool.MEMO);vm.change(title="synthetic");vm.prepareCreate();advanceUntilIdle()
        val p=vm.state.value.confirmation!!;assertTrue(f().snapshot().entries.isEmpty())
        vm.dismissConfirmation();advanceUntilIdle();assertNull(vm.state.value.confirmation);assertNotNull(vm.state.value.draft)
        try{f().confirm(p.plan,p.plan.confirmation);fail("cancelled plan") }catch(_:Exception){}
    }
    @Test fun memoSaveDoneArchiveAndIndependentReopen()=runVm{vm,f->
        advanceUntilIdle();vm.begin(ReferencePool.MEMO);vm.change(title="memo",body="synthetic body");vm.prepareCreate();advanceUntilIdle();vm.confirm();advanceUntilIdle()
        assertNull(vm.state.value.error);assertNull(vm.state.value.draft)
        val memo=f().snapshot().entries.single() as ReferenceEntry.Memo
        vm.prepareDone(memo);advanceUntilIdle();vm.confirm();advanceUntilIdle()
        assertTrue((f().snapshot().entries.single() as ReferenceEntry.Memo).done)
        vm.prepareArchive(f().snapshot().entries.single());advanceUntilIdle();vm.confirm();advanceUntilIdle()
        assertTrue(f().snapshot().entries.single().archived);assertNull(vm.state.value.error)
    }
    @Test fun pitfallCountIsManualNotAiMerge()=runVm{vm,f->
        advanceUntilIdle();vm.begin(ReferencePool.PITFALL);vm.change(title="pitfall",count="3");vm.prepareCreate();advanceUntilIdle();vm.confirm();advanceUntilIdle()
        assertEquals(3,(f().snapshot().entries.single() as ReferenceEntry.Pitfall).count)
    }
    @Test fun invalidDateKeepsDraftAndNoWrite()=runVm{vm,f->
        advanceUntilIdle();vm.begin(ReferencePool.MEMO);vm.change(title="memo",due="2026-02-30");vm.prepareCreate();advanceUntilIdle()
        assertNotNull(vm.state.value.error);assertNull(vm.state.value.confirmation);assertEquals("2026-02-30",vm.state.value.draft?.due);assertTrue(f().snapshot().entries.isEmpty())
    }
    @Test fun busyPreventsDoubleConfirmAndDiscard()=runVm{vm,f->
        advanceUntilIdle();vm.begin(ReferencePool.MEMO);vm.change(title="once");vm.prepareCreate();advanceUntilIdle();vm.confirm();vm.confirm();vm.discard();advanceUntilIdle()
        assertEquals(1,f().snapshot().entries.size);assertNull(vm.state.value.draft)
    }
    @Test fun changeCancelsPreparedContent()=runVm{vm,f->
        advanceUntilIdle();vm.begin(ReferencePool.MEMO);vm.change(title="first");vm.prepareCreate();advanceUntilIdle()
        vm.change(title="second");advanceUntilIdle();assertNull(vm.state.value.confirmation);assertEquals("second",vm.state.value.draft?.title);assertTrue(f().snapshot().entries.isEmpty())
    }
    @Test fun unknownIntentBlocksWriteOnRestore()=runVm(SavedStateHandle(mapOf("knte.isolated.reference.writing" to true))){vm,f->
        advanceUntilIdle();assertTrue(vm.state.value.reviewRequired);vm.begin(ReferencePool.MEMO);assertNull(vm.state.value.draft);assertTrue(f().snapshot().entries.isEmpty())
    }
    @Test fun draftRestoresWithoutApproval()=runVm(SavedStateHandle(mapOf("knte.isolated.reference.draft" to true,"knte.isolated.reference.pool" to "MEMO","knte.isolated.reference.id" to "m-12345678","knte.isolated.reference.title" to "unsaved"))){vm,f->
        advanceUntilIdle();assertEquals("unsaved",vm.state.value.draft?.title);assertNull(vm.state.value.confirmation);assertTrue(f().snapshot().entries.isEmpty())
    }
    @Test fun navigationWaitsForDurableCancellationAndDraftClear()=runVm{vm,f->
        advanceUntilIdle();vm.begin(ReferencePool.MEMO);vm.change(title="unsaved")
        vm.prepareCreate();advanceUntilIdle()
        val pending=vm.state.value.confirmation!!
        var navigated=false
        vm.discard { navigated=true }
        assertFalse(navigated)
        assertTrue(vm.state.value.busy)
        advanceUntilIdle()
        assertTrue(navigated);assertNull(vm.state.value.draft);assertNull(vm.state.value.confirmation)
        assertTrue(f().snapshot().entries.isEmpty())
        try {f().confirm(pending.plan,pending.plan.confirmation);fail("Cancelled plan must remain invalid")}
        catch (_:Exception){}
    }
    @Test fun failedCancellationDoesNotRunNavigationContinuation()=runVm{vm,f->
        advanceUntilIdle();vm.begin(ReferencePool.MEMO);vm.change(title="keep")
        vm.prepareCreate();advanceUntilIdle()
        // A separately confirmed copy cannot be reported as a successful cancellation.
        val prepared=vm.state.value.confirmation!!
        val external=f()
        external.confirm(prepared.plan,prepared.plan.confirmation)
        var navigated=false
        vm.discard {navigated=true};advanceUntilIdle()
        assertFalse(navigated);assertTrue(vm.state.value.reviewRequired)
        assertEquals("keep",vm.state.value.draft?.title)
    }

    @Test fun confirmationDisplaysExactProposedTextAndDeadline()=runVm{vm,_->
        advanceUntilIdle();vm.begin(ReferencePool.MEMO)
        vm.change(title="review",body="Exact proposed body 中文",due="2026-12-01")
        vm.prepareCreate();advanceUntilIdle()
        val card=vm.state.value.confirmation!!
        assertTrue(card.label.contains("Exact proposed body 中文"))
        assertTrue(card.label.contains("2026-12-01"))
        assertTrue(card.label.contains("review"))
    }

}
