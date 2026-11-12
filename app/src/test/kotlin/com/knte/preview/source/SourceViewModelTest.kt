package com.knte.preview.source

import androidx.lifecycle.viewModelScope
import com.knte.core.diary.DiaryCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Deterministic fake-provider tests; not physical SAF/permission evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceViewModelTest {
    private fun root(name: String) = SourceRoot.bind("content://synthetic/$name", "root", SourcePermission.Granted)
    private class Prefs(var selected: SourceRoot? = null) : SourcePreferences {
        var onSave: ((SourceRoot?) -> Unit)? = null
        var fail = false
        var onLoad: (() -> Unit)? = null
        override fun load(): SourceRoot? {
            val persisted = selected
            onLoad?.invoke()
            return persisted
        }
        override fun save(root: SourceRoot?) {
            check(!fail) { "injected binding failure" }
            onSave?.invoke(root);selected = root
        }
    }
    private class Source : ReadOnlyDiarySource {
        var revoked = false;var fail = false
        var onList: ((SourceRoot) -> Unit)? = null
        fun entry(r: SourceRoot) = SourceEntry("doc", "day.md", "text/plain", 4,
            r.identity, r.binding, "doc-binding-${r.identity}")
        override fun list(root: SourceRoot): SourceListing {
            onList?.invoke(root)
            if (revoked) throw SourceException(SourceCodes.PERMISSION_LOST,"synthetic revocation")
            if (fail) throw SourceException(SourceCodes.QUERY_FAILED,"synthetic failure")
            return SourceListing(listOf(entry(root)),4,root.identity,root.binding)
        }
        override fun read(root: SourceRoot, documentId: String): SourceDocument {
            if (revoked) throw SourceException(SourceCodes.PERMISSION_LOST,"synthetic revocation")
            val d=DiaryCodec.parse(DiaryCodec.encode("body")); val e=entry(root)
            return SourceDocument("doc",e.displayName,e.mimeType,4,d.revision,e.documentBinding,d,d.metadata,root.identity,root.binding)
        }
    }
    private fun runVm(initial: SourceRoot? = null,
        block: suspend TestScope.(SourceViewModel,Source,Prefs) -> Unit) = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);Dispatchers.setMain(dispatcher)
        val src=Source();val prefs=Prefs(initial);val vm=SourceViewModel(src,prefs,dispatcher)
        try { block(vm,src,prefs) } finally { vm.viewModelScope.cancel();Dispatchers.resetMain() }
    }
    @Test fun initialNoRootIsNotEmpty()=runVm { vm,_,_ ->
        advanceUntilIdle();assertTrue(vm.state.value.status is SourceStatus.NotSelected);assertFalse(vm.state.value.busy)
    }
    @Test fun persistedRootLoadsWithoutBusyDeadlock()=runVm(root("a")) { vm,_,_ ->
        advanceUntilIdle();assertTrue(vm.state.value.status is SourceStatus.Ready);assertEquals(1,vm.state.value.entries.size)
    }
    @Test fun latestRapidSelectionWinsBothStateAndPreferences()=runVm { vm,_,prefs ->
        advanceUntilIdle();vm.select(root("a"));vm.select(root("b"));advanceUntilIdle()
        assertEquals(root("b"),prefs.selected);assertEquals(root("b").identity,vm.state.value.rootIdentity)
    }
    @Test fun selectionDuringOlderSaveCannotLosePersistedIdentity()=runVm { vm,_,prefs ->
        advanceUntilIdle();prefs.onSave={r -> if(r==root("a")) { prefs.onSave=null;vm.select(root("b")) } }
        vm.select(root("a"));advanceUntilIdle()
        assertEquals(root("b"),prefs.selected);assertEquals(root("b").identity,vm.state.value.rootIdentity)
    }
    @Test fun forgetDuringSaveWinsAfterOlderPhysicalSave()=runVm { vm,_,prefs ->
        advanceUntilIdle();prefs.onSave={r -> if(r!=null) { prefs.onSave=null;vm.forget() } }
        vm.select(root("a"));advanceUntilIdle()
        assertNull(prefs.selected);assertTrue(vm.state.value.status is SourceStatus.NotSelected)
    }
    @Test fun lateOldReadNeverPublishesAfterNewSelection()=runVm { vm,src,prefs ->
        advanceUntilIdle();src.onList={r -> if(r==root("a")) { src.onList=null;vm.select(root("b")) } }
        vm.select(root("a"));advanceUntilIdle()
        assertEquals(root("b"),prefs.selected);assertEquals(root("b").identity,vm.state.value.entries.single().rootIdentity)
    }
    @Test fun forgetDropsLateReadAndOpenedDocument()=runVm { vm,src,prefs ->
        advanceUntilIdle();vm.select(root("a"));advanceUntilIdle();vm.open(vm.state.value.entries.single());advanceUntilIdle()
        assertNotNull(vm.state.value.opened)
        src.onList={src.onList=null;vm.forget()};vm.reload();advanceUntilIdle()
        assertNull(vm.state.value.opened);assertNull(prefs.selected);assertTrue(vm.state.value.entries.isEmpty())
    }
    @Test fun sameRootFailureRetainsOnlyStaleListAsError()=runVm { vm,src,_ ->
        advanceUntilIdle();vm.select(root("a"));advanceUntilIdle();src.fail=true;vm.reload();advanceUntilIdle()
        assertTrue(vm.state.value.status is SourceStatus.Error);assertEquals(1,vm.state.value.entries.size)
    }
    @Test fun revocationClearsDisplayedContents()=runVm { vm,src,_ ->
        advanceUntilIdle();vm.select(root("a"));advanceUntilIdle();vm.open(vm.state.value.entries.single());advanceUntilIdle()
        src.revoked=true;vm.reload();advanceUntilIdle()
        assertTrue(vm.state.value.status is SourceStatus.PermissionLost);assertNull(vm.state.value.opened);assertTrue(vm.state.value.entries.isEmpty())
    }
    @Test fun sourceSwitchImmediatelyClearsPreviousBodyAndList()=runVm { vm,_,_ ->
        advanceUntilIdle();vm.select(root("a"));advanceUntilIdle();vm.open(vm.state.value.entries.single());advanceUntilIdle()
        vm.select(root("b"));assertTrue(vm.state.value.entries.isEmpty());assertNull(vm.state.value.opened)
        advanceUntilIdle();assertEquals(root("b").identity,vm.state.value.rootIdentity)
    }
    @Test fun selectionCancellationDoesNotChangeBinding()=runVm { vm,_,prefs ->
        advanceUntilIdle();vm.select(root("a"));advanceUntilIdle();vm.selectionFailed()
        assertEquals(root("a"),prefs.selected);assertEquals(root("a").identity,vm.state.value.rootIdentity)
    }
    @Test fun persistenceFailureVisibleNotSuccessfulEmpty()=runVm { vm,_,prefs ->
        advanceUntilIdle();prefs.fail=true;vm.select(root("a"));advanceUntilIdle()
        assertTrue(vm.state.value.status is SourceStatus.Error);assertFalse(vm.state.value.busy)
    }
    @Test fun closeDocumentAndRepeatedReloadKeepOneSource()=runVm { vm,_,_ ->
        advanceUntilIdle();vm.select(root("a"));advanceUntilIdle();vm.open(vm.state.value.entries.single());advanceUntilIdle()
        vm.closeDocument();assertNull(vm.state.value.opened)
        vm.reload();vm.reload();advanceUntilIdle();assertEquals(root("a").identity,vm.state.value.rootIdentity)
    }
    @Test fun failedSelectionRestoresLastPersistedRootAndReloadNeverUsesRejectedRoot()=runVm(root("a")) { vm,_,prefs ->
        advanceUntilIdle();prefs.fail=true;vm.select(root("b"));advanceUntilIdle()
        assertEquals(root("a"),prefs.selected);assertEquals(root("a").identity,vm.state.value.rootIdentity)
        assertEquals(SourceCodes.PERSIST_FAILED,(vm.state.value.status as SourceStatus.Error).code)
        vm.reload();advanceUntilIdle()
        assertEquals(root("a").identity,vm.state.value.entries.single().rootIdentity)
    }
    @Test fun failedForgetKeepsRetryableRootAndCanLaterDisconnect()=runVm(root("a")) { vm,_,prefs ->
        advanceUntilIdle();prefs.fail=true;vm.forget();advanceUntilIdle()
        assertTrue(vm.state.value.hasRoot);assertEquals(root("a"),prefs.selected)
        assertEquals(SourceCodes.PERSIST_FAILED,(vm.state.value.status as SourceStatus.Error).code)
        assertNull(vm.state.value.notice)
        prefs.fail=false;vm.forget();advanceUntilIdle()
        assertNull(prefs.selected);assertTrue(vm.state.value.status is SourceStatus.NotSelected)
    }
    @Test fun savedBindingWithProviderFailureRemainsNewPersistedRoot()=runVm(root("a")) { vm,src,prefs ->
        advanceUntilIdle();src.fail=true;vm.select(root("b"));advanceUntilIdle()
        assertEquals(root("b"),prefs.selected);assertEquals(root("b").identity,vm.state.value.rootIdentity)
        assertEquals(SourceCodes.QUERY_FAILED,(vm.state.value.status as SourceStatus.Error).code)
    }
    @Test fun staleSaveSuccessThenLatestSaveFailureRollsBackToPhysicalWinner()=runVm { vm,_,prefs ->
        advanceUntilIdle()
        prefs.onSave={r -> if(r==root("a")){prefs.onSave=null;vm.select(root("b"));prefs.fail=true}}
        vm.select(root("a"));advanceUntilIdle()
        assertEquals(root("a"),prefs.selected)
        assertEquals(root("a").identity,vm.state.value.rootIdentity)
        assertEquals(SourceCodes.PERSIST_FAILED,(vm.state.value.status as SourceStatus.Error).code)
    }

    @Test fun failedSelectionBeforeStartupRunsKeepsPersistedBaseline()=runVm(root("a")) { vm,_,prefs ->
        prefs.fail=true
        vm.select(root("b"))
        advanceUntilIdle()
        assertEquals(root("a"),prefs.selected)
        assertEquals(root("a").identity,vm.state.value.rootIdentity)
        assertEquals(SourceCodes.PERSIST_FAILED,(vm.state.value.status as SourceStatus.Error).code)
        vm.reload();advanceUntilIdle()
        assertEquals(root("a").identity,vm.state.value.entries.single().rootIdentity)
    }
    @Test fun failedForgetBeforeStartupRunsKeepsPersistedBaseline()=runVm(root("a")) { vm,_,prefs ->
        prefs.fail=true
        vm.forget()
        advanceUntilIdle()
        assertEquals(root("a"),prefs.selected)
        assertEquals(root("a").identity,vm.state.value.rootIdentity)
        assertFalse(vm.state.value.busy)
        assertEquals(SourceCodes.PERSIST_FAILED,(vm.state.value.status as SourceStatus.Error).code)
    }
    @Test fun selectionDuringInitialLoadFailureRetainsLoadedBaseline()=runVm(root("a")) { vm,_,prefs ->
        prefs.onLoad={prefs.onLoad=null;prefs.fail=true;vm.select(root("b"))}
        advanceUntilIdle()
        assertEquals(root("a"),prefs.selected)
        assertEquals(root("a").identity,vm.state.value.rootIdentity)
        assertEquals(SourceCodes.PERSIST_FAILED,(vm.state.value.status as SourceStatus.Error).code)
        vm.reload();advanceUntilIdle()
        assertEquals(root("a").identity,vm.state.value.entries.single().rootIdentity)
    }

}
