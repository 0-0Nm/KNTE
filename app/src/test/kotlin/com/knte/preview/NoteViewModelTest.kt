package com.knte.preview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.knte.core.notes.Note
import com.knte.core.notes.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM ViewModel-state tests only. FakeNoteRepository is in-memory and is NOT evidence of
 * real file persistence, Android runtime, or Compose rendering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteViewModelTest {

    private fun runVmTest(
        repo: FakeNoteRepository = FakeNoteRepository(),
        handle: SavedStateHandle = SavedStateHandle(),
        block: suspend TestScope.(NoteViewModel, FakeNoteRepository, SavedStateHandle) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val vm = NoteViewModel(repo, handle, dispatcher)
        try {
            block(vm, repo, handle)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun initialLoadShowsEmptyState() = runVmTest { vm, repo, _ ->
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(NotesStatus.Empty, state.status)
        assertTrue(state.notes.isEmpty())
        assertEquals(1, repo.listCalls)
        assertNull(state.error)
    }

    @Test
    fun newInputSaveThenReloadShowsRealNote() = runVmTest { vm, repo, _ ->
        advanceUntilIdle()
        vm.newNote()
        vm.onTitleChange("第一条")
        vm.onBodyChange("私有记录正文")
        assertNotNull(vm.uiState.value.draft)
        vm.save()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(1, repo.createCalls)
        assertEquals(1, repo.notes.size)
        assertEquals(1, state.notes.size)
        assertEquals("第一条", state.notes[0].title)
        assertEquals("私有记录正文", state.notes[0].body)
        assertEquals("r1", state.notes[0].revision)
        assertNull(state.draft)
        assertEquals(NotesStatus.Ready, state.status)
        assertNull(state.error)
        assertTrue(repo.listCalls >= 2)
    }

    @Test
    fun duplicateSaveAndReloadWhilePendingAreSuppressed() = runVmTest { vm, repo, _ ->
        advanceUntilIdle()
        vm.newNote()
        vm.onTitleChange("T")
        vm.onBodyChange("B")
        vm.save()

        assertEquals(PendingOperation.Save, vm.uiState.value.pending)
        assertEquals(NotesStatus.Saving, vm.uiState.value.status)
        assertEquals(0, repo.createCalls)

        vm.save()
        vm.reload()
        vm.newNote()
        advanceUntilIdle()

        assertEquals(1, repo.createCalls)
        assertEquals(1, repo.notes.size)
        assertEquals(2, repo.listCalls)
        assertNull(vm.uiState.value.pending)
    }

    @Test
    fun saveFailureKeepsDraftAndDoesNotClaimSaved() = runVmTest(
        repo = FakeNoteRepository(failOn = "create"),
    ) { vm, repo, _ ->
        advanceUntilIdle()
        vm.newNote()
        vm.onTitleChange("标题")
        vm.onBodyChange("正文")
        vm.save()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(NotesStatus.Error, state.status)
        assertTrue(state.error!!.contains("保存失败"))
        assertNotNull(state.draft)
        assertEquals("标题", state.draft!!.title)
        assertEquals("正文", state.draft.body)
        assertEquals(0, repo.notes.size)
        assertEquals(1, repo.createCalls)
    }

    @Test
    fun listFailureIsVisibleAndExplicitReloadRecovers() = runVmTest(
        repo = FakeNoteRepository(failOn = "list"),
    ) { vm, repo, _ ->
        advanceUntilIdle()
        assertEquals(NotesStatus.Error, vm.uiState.value.status)
        assertTrue(vm.uiState.value.error!!.contains("读取列表失败"))

        repo.failOn = null
        vm.reload()
        advanceUntilIdle()

        assertEquals(NotesStatus.Empty, vm.uiState.value.status)
        assertNull(vm.uiState.value.error)
        assertEquals(2, repo.listCalls)
    }

    @Test
    fun openThenSaveUsesFreshCasRevision() = runVmTest(
        repo = FakeNoteRepository().apply { seed("n1", "旧标题", "旧正文") },
    ) { vm, repo, _ ->
        advanceUntilIdle()
        vm.open("n1")
        advanceUntilIdle()
        assertEquals("r1", vm.uiState.value.draft?.revision)

        vm.onTitleChange("新标题")
        vm.save()
        advanceUntilIdle()

        assertEquals(1, repo.updateCalls)
        assertEquals(2, repo.getCalls)
        assertEquals("新标题", repo.notes[0].title)
        assertEquals("r2", repo.notes[0].revision)
        assertNull(vm.uiState.value.draft)
        assertEquals(1, vm.uiState.value.notes.size)
    }

    @Test
    fun staleDraftRevisionIsRejectedWithoutUpdate() = runVmTest(
        repo = FakeNoteRepository().apply { seed("n1", "标题", "正文") },
    ) { vm, repo, _ ->
        advanceUntilIdle()
        vm.open("n1")
        advanceUntilIdle()
        vm.onTitleChange("本地改动")
        repo.forceRevision("n1", "r9")

        vm.save()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(NotesStatus.Error, state.status)
        assertTrue(state.error!!.contains("已被其他操作更新"))
        assertEquals(0, repo.updateCalls)
        assertEquals("标题", repo.notes[0].title)
        assertNotNull(state.draft)
        assertEquals("本地改动", state.draft!!.title)
    }

    @Test
    fun savedStateHandleRestoresDraftAsUiMetadataOnly() = runVmTest { vm, repo, handle ->
        advanceUntilIdle()
        vm.newNote()
        vm.onTitleChange("恢复标题")
        vm.onBodyChange("恢复正文")
        assertEquals("恢复正文", handle.get<String>(KEY_DRAFT_BODY))

        val restored = NoteViewModel(repo, handle, StandardTestDispatcher(testScheduler))
        try {
            advanceUntilIdle()
            val state = restored.uiState.value
            assertEquals("恢复标题", state.draft?.title)
            assertEquals("恢复正文", state.draft?.body)
            assertTrue(state.notice!!.contains("UI 元数据"))
            assertEquals(0, repo.createCalls)
        } finally {
            restored.viewModelScope.cancel()
        }
    }

    @Test
    fun oversizedExistingBodyIsNotOpenedAndNotClaimedRestorable() = runVmTest(
        repo = FakeNoteRepository().apply { seed("big", "大记录", "x".repeat(33 * 1024)) },
    ) { vm, repo, handle ->
        advanceUntilIdle()
        vm.open("big")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNull(state.draft)
        assertTrue(state.error!!.contains("32 KiB"))
        assertTrue(state.notice!!.contains("不提供进程恢复草稿"))
        assertNull(handle.get<String>(KEY_DRAFT_BODY))
        assertEquals(0, repo.updateCalls)
    }

    @Test
    fun bodyInputAboveRestorableLimitIsRejected() = runVmTest { vm, _, _ ->
        advanceUntilIdle()
        vm.newNote()
        vm.onBodyChange("x".repeat(MAX_RESTORABLE_BODY_BYTES + 1))
        assertEquals("", vm.uiState.value.draft!!.body)
        assertTrue(vm.uiState.value.notice!!.contains("32 KiB"))
    }

    @Test
    fun cancelDiscardsDraftWithoutSaving() = runVmTest { vm, repo, handle ->
        advanceUntilIdle()
        vm.newNote()
        vm.onTitleChange("T")
        vm.cancel()
        assertNull(vm.uiState.value.draft)
        assertEquals(0, repo.createCalls)
        assertNull(handle.get<String>(KEY_DRAFT_TITLE))
    }

    private class FakeNoteRepository(
        var failOn: String? = null,
        initial: List<Note> = emptyList(),
    ) : NoteRepository {
        val notes = initial.toMutableList()
        var listCalls = 0
        var getCalls = 0
        var createCalls = 0
        var updateCalls = 0
        private var seq = 0

        override fun list(): List<Note> {
            listCalls++
            if (failOn == "list") throw IllegalStateException("模拟列表读取失败")
            return notes.toList()
        }

        override fun get(id: String): Note? {
            getCalls++
            if (failOn == "get") throw IllegalStateException("模拟读取记录失败")
            return notes.firstOrNull { it.id == id }
        }

        override fun create(title: String, body: String, nowMillis: Long): Note {
            createCalls++
            if (failOn == "create") throw IllegalStateException("模拟写入失败")
            val note = Note("id-${++seq}", title, body, nowMillis, nowMillis, "r1")
            notes += note
            return note
        }

        override fun update(
            id: String,
            expectedRevision: String,
            title: String,
            body: String,
            nowMillis: Long,
        ): Note {
            updateCalls++
            if (failOn == "update") throw IllegalStateException("模拟更新失败")
            val index = notes.indexOfFirst { it.id == id }
            if (index < 0) throw IllegalStateException("记录不存在")
            val current = notes[index]
            if (current.revision != expectedRevision) throw IllegalStateException("CAS 冲突")
            val next = current.copy(
                title = title,
                body = body,
                updatedAtMillis = nowMillis,
                revision = bump(current.revision),
            )
            notes[index] = next
            return next
        }

        fun seed(id: String, title: String, body: String, revision: String = "r1") {
            notes += Note(id, title, body, 1L, 1L, revision)
        }

        fun forceRevision(id: String, revision: String) {
            val index = notes.indexOfFirst { it.id == id }
            notes[index] = notes[index].copy(revision = revision)
        }

        private fun bump(revision: String): String =
            "r" + ((revision.removePrefix("r").toIntOrNull() ?: 0) + 1)
    }
}
