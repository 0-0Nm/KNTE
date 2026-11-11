package com.knte.preview.reading

import androidx.lifecycle.viewModelScope
import com.knte.core.reading.BookTextCodec
import com.knte.core.reading.position.InMemoryReadingPositionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic books + in-memory positions; not device document-provider evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingViewModelTest {
    private class Loader:BookInputLoader {
        var fail=false;var onLoad:(()->Unit)?=null
        override fun load(reference:String):BookInput {
            onLoad?.invoke()
            check(!fail){"synthetic failure"}
            return BookInput(BookTextCodec.encodeStrict("# First\nHello $reference\n# Second\nWorld\n"),reference,"book.md","md")
        }
    }
    private fun runVm(block:suspend TestScope.(ReadingViewModel,Loader)->Unit)=runTest {
        val d=StandardTestDispatcher(testScheduler);Dispatchers.setMain(d)
        val loader=Loader();val positions=InMemoryReadingPositionStore()
        val vm=ReadingViewModel(loader,{positions},d)
        try{block(vm,loader)}finally{vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }
    @Test fun initialHasNoFabricatedBook()=runVm{vm,_->assertNull(vm.state.value.book);assertFalse(vm.state.value.busy)}
    @Test fun selectBuildsRealTocAndExactChapterText()=runVm{vm,_->
        vm.select("a");advanceUntilIdle();val b=vm.state.value.book!!
        assertEquals(2,b.toc.size);assertEquals(listOf(1,2),b.toc.map{it.ordinal});assertEquals("# First\nHello a\n",vm.state.value.chapterText)
        vm.chapter(b.toc[1].chapterId);assertEquals("# Second\nWorld\n",vm.state.value.chapterText)
    }
    @Test fun savePositionAndReopenRestoresSameChapter()=runVm{vm,_->
        vm.select("a");advanceUntilIdle();vm.chapter(vm.state.value.book!!.toc[1].chapterId)
        vm.saveChapterPosition();advanceUntilIdle();assertTrue(vm.state.value.positionSaved)
        vm.closeBook();vm.select("a");advanceUntilIdle();assertEquals("# Second\nWorld\n",vm.state.value.chapterText)
    }
    @Test fun failureNotConvertedToEmptyOrOldBook()=runVm{vm,loader->
        vm.select("a");advanceUntilIdle();loader.fail=true;vm.select("b");advanceUntilIdle()
        assertNull(vm.state.value.book);assertNotNull(vm.state.value.error);assertFalse(vm.state.value.busy)
    }
    @Test fun lateImportCannotReplaceNewSelection()=runVm{vm,loader->
        loader.onLoad={loader.onLoad=null;vm.select("b")};vm.select("a");advanceUntilIdle()
        assertTrue(vm.state.value.chapterText!!.contains("Hello b"))
    }
    @Test fun closeDuringImportDoesNotResurrectBook()=runVm{vm,loader->
        loader.onLoad={loader.onLoad=null;vm.closeBook()};vm.select("a");advanceUntilIdle()
        assertNull(vm.state.value.book);assertFalse(vm.state.value.busy)
    }
    @Test fun unknownChapterDoesNotShowOtherContentAsRequested()=runVm{vm,_->
        vm.select("a");advanceUntilIdle();val before=vm.state.value.chapterText
        vm.chapter("c-9999");assertNotNull(vm.state.value.error);assertEquals(before,vm.state.value.chapterText)
    }
    @Test fun cancellationKeepsExistingBook()=runVm{vm,_->
        vm.select("a");advanceUntilIdle();val b=vm.state.value.book;vm.selectionFailed();assertSame(b,vm.state.value.book)
    }
}
