package com.knte.preview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.knte.core.notes.FileNoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/** Actual file repository + ViewModel in a host synthetic directory. Not Android device proof. */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivateNoteFlowIntegrationTest {
    @Test
    fun createReopenEditAndIndependentRepositoryReadback() = runTest {
        val root = Files.createTempDirectory("knte-preview-flow-").toFile()
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val vm = NoteViewModel(FileNoteRepository(root), SavedStateHandle(), dispatcher)
        try {
            advanceUntilIdle()
            vm.newNote()
            vm.onTitleChange("Synthetic preview")
            vm.onBodyChange("First body\n原始内容")
            vm.save()
            vm.save()
            advanceUntilIdle()
            assertNull(vm.uiState.value.error)
            val reopened = FileNoteRepository(root).list()
            assertEquals(1, reopened.size)
            assertEquals("First body\n原始内容", reopened.single().body)
            val previousRevision = reopened.single().revision
            vm.open(reopened.single().id)
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.draft)
            vm.onBodyChange("Edited body")
            vm.save()
            advanceUntilIdle()
            assertNull(vm.uiState.value.error)
            val independent = FileNoteRepository(root).get(reopened.single().id)!!
            assertEquals("Edited body", independent.body)
            check(independent.revision != previousRevision)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
            // Delete this test-created synthetic temp directory only.
            root.deleteRecursively()
        }
    }
}
