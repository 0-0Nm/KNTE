package com.knte.preview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knte.core.notes.Note
import com.knte.core.notes.NoteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** App-private preview record flow. No Vault access, no migration, no network, no recording. */
const val MAX_RESTORABLE_BODY_BYTES = 32 * 1024
const val MAX_STORABLE_BODY_BYTES = 256 * 1024
const val MAX_TITLE_CHARS = 120

const val KEY_DRAFT_ID = "knte.preview.draft.id"
const val KEY_DRAFT_TITLE = "knte.preview.draft.title"
const val KEY_DRAFT_BODY = "knte.preview.draft.body"
const val KEY_DRAFT_REVISION = "knte.preview.draft.revision"
const val KEY_DRAFT_RESTORABLE = "knte.preview.draft.restorable"
const val KEY_DRAFT_BODY_BYTES = "knte.preview.draft.bodyBytes"

/** One unified status object: empty / loading / error / saving are all expressed here. */
enum class NotesStatus { Loading, Empty, Ready, Saving, Error }

/** A non-null pending operation blocks every duplicate UI action. */
enum class PendingOperation { Reload, Open, Save }

data class NoteDraft(
    val id: String? = null,
    val title: String = "",
    val body: String = "",
    /** UI metadata recovered from SavedStateHandle; NOT a durable CAS credential. */
    val revision: String? = null,
    val restorable: Boolean = true,
    val originalBodyBytes: Int = 0,
)

data class NotesUiState(
    val status: NotesStatus = NotesStatus.Loading,
    val notes: List<Note> = emptyList(),
    val draft: NoteDraft? = null,
    val pending: PendingOperation? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val isLoading: Boolean get() = status == NotesStatus.Loading
    val isEmpty: Boolean get() = status == NotesStatus.Empty
    val isSaving: Boolean get() = status == NotesStatus.Saving
    val isBusy: Boolean get() = pending != null
}

fun String.utf8ByteCount(): Int = toByteArray(Charsets.UTF_8).size

private fun Throwable.shortMessage(): String = message?.take(240) ?: javaClass.simpleName

class NoteViewModel(
    private val repository: NoteRepository,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    /** Defaults to IO; injectable so JVM tests can drive viewModelScope deterministically. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotesUiState())
    val uiState: StateFlow<NotesUiState> = _uiState.asStateFlow()

    // Each repository query captures a generation; a late result can never overwrite a newer query.
    private var listGeneration = 0L
    private var editGeneration = 0L
    private var saveGeneration = 0L

    init {
        restoreDraft()
        reloadInternal(initial = true)
    }

    // ---- public UI actions -------------------------------------------------

    fun reload() {
        if (_uiState.value.isBusy) return
        reloadInternal(initial = false)
    }

    fun newNote() {
        if (_uiState.value.isBusy || _uiState.value.draft != null) return
        val draft = NoteDraft()
        persistDraft(draft)
        _uiState.update {
            it.copy(
                draft = draft,
                status = NotesStatus.Ready,
                error = null,
                notice = "新建草稿尚未保存。",
            )
        }
    }

    fun onTitleChange(value: String) {
        if (_uiState.value.isBusy) return
        if (value.codePointCount(0, value.length) > MAX_TITLE_CHARS) {
            setNotice("标题最多 $MAX_TITLE_CHARS 个字符，超出输入未接受；原草稿保留。")
            return
        }
        updateDraft { it.copy(title = value) }
    }

    fun onBodyChange(value: String) {
        if (_uiState.value.isBusy) return
        val bytes = value.utf8ByteCount()
        if (bytes > MAX_RESTORABLE_BODY_BYTES) {
            // Unified UI limit: never store an oversized body in SavedStateHandle, never claim restorable.
            setNotice("正文超过草稿可恢复上限 32 KiB，输入未被接受；为避免 SavedStateHandle 过大且不假称草稿可恢复，预览编辑器统一限制为 32 KiB。")
            return
        }
        updateDraft { it.copy(body = value, originalBodyBytes = bytes) }
    }

    fun open(id: String) {
        if (_uiState.value.isBusy || _uiState.value.draft != null) return
        val gen = ++editGeneration
        _uiState.update { it.copy(pending = PendingOperation.Open, status = NotesStatus.Loading, error = null) }
        viewModelScope.launch {
            try {
                val note = withContext(ioDispatcher) { repository.get(id) }
                if (gen != editGeneration) return@launch
                if (note == null) {
                    fail("未找到记录 $id，未进入编辑，草稿未改动。")
                    return@launch
                }
                val bytes = note.body.utf8ByteCount()
                if (bytes > MAX_RESTORABLE_BODY_BYTES) {
                    val reason = if (bytes > MAX_STORABLE_BODY_BYTES) {
                        "正文 ${bytes / 1024} KiB 超过 256 KiB 存储上限"
                    } else {
                        "正文 ${bytes / 1024} KiB 超过 32 KiB 草稿可恢复上限"
                    }
                    clearDraftHandle()
                    _uiState.update {
                        it.copy(
                            pending = null,
                            draft = null,
                            status = NotesStatus.Error,
                            error = "$reason；为避免截断或不实的可恢复承诺，未打开编辑，也未写入任何草稿。",
                            notice = "超过 32 KiB 的正文不提供进程恢复草稿；预览编辑器统一按 32 KiB 处理。",
                        )
                    }
                    return@launch
                }
                val draft = NoteDraft(
                    id = note.id,
                    title = note.title,
                    body = note.body,
                    revision = note.revision,
                    restorable = true,
                    originalBodyBytes = bytes,
                )
                persistDraft(draft)
                _uiState.update {
                    it.copy(
                        pending = null,
                        draft = draft,
                        status = NotesStatus.Ready,
                        error = null,
                        notice = "已打开记录，修改后请保存。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                if (gen != editGeneration) return@launch
                fail("读取记录失败：${t.shortMessage()}；草稿未改动。")
            }
        }
    }

    fun save() {
        val snapshot = _uiState.value
        if (snapshot.isBusy) return
        val draft = snapshot.draft ?: return
        val gen = ++saveGeneration
        _uiState.update { it.copy(pending = PendingOperation.Save, status = NotesStatus.Saving, error = null) }
        viewModelScope.launch {
            try {
                val bodyBytes = draft.body.utf8ByteCount()
                val normalizedTitle = draft.title.trim()
                if (normalizedTitle.isEmpty()) {
                    fail("标题不能为空，未保存；草稿已保留。")
                    return@launch
                }
                if (normalizedTitle.codePointCount(0, normalizedTitle.length) > MAX_TITLE_CHARS || bodyBytes > MAX_STORABLE_BODY_BYTES) {
                    if (gen == saveGeneration) fail("正文超过 256 KiB 存储上限或标题过长，未保存；草稿已保留。")
                    return@launch
                }
                val now = System.currentTimeMillis()
                if (draft.id == null) {
                    withContext(ioDispatcher) { repository.create(normalizedTitle, draft.body, now) }
                } else {
                    // The handle revision is UI metadata only; re-read the real revision now.
                    val current = withContext(ioDispatcher) { repository.get(draft.id) }
                        ?: throw IllegalStateException("记录已不存在，未保存")
                    if (current.revision != draft.revision) {
                        throw IllegalStateException(
                            "记录已被其他操作更新（当前 revision=${current.revision}，草稿 revision=${draft.revision}）；请重新打开后再编辑，未保存"
                        )
                    }
                    withContext(ioDispatcher) { repository.update(draft.id, current.revision, normalizedTitle, draft.body, now) }
                }
                if (gen != saveGeneration) return@launch
                // Only a successful repository return clears the draft; nothing is claimed as saved early.
                clearDraftHandle()
                _uiState.update {
                    it.copy(
                        pending = null,
                        draft = null,
                        error = null,
                        notice = "保存完成，正在重读。",
                    )
                }
                reloadInternal(initial = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                if (gen != saveGeneration) return@launch
                // Error is visible and the draft is NEVER cleared on failure.
                _uiState.update {
                    it.copy(
                        pending = null,
                        status = NotesStatus.Error,
                        error = "保存失败：${t.shortMessage()}；草稿已保留，未标记为已保存。",
                        draft = draft,
                    )
                }
            }
        }
    }

    fun cancel() {
        if (_uiState.value.isBusy) return
        ++editGeneration
        ++saveGeneration
        clearDraftHandle()
        _uiState.update {
            it.copy(
                draft = null,
                status = if (it.notes.isEmpty()) NotesStatus.Empty else NotesStatus.Ready,
                error = null,
                notice = "已放弃当前草稿；未执行保存。",
            )
        }
    }

    fun dismissError() {
        _uiState.update {
            it.copy(error = null, status = if (it.notes.isEmpty()) NotesStatus.Empty else NotesStatus.Ready)
        }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    // ---- internals ---------------------------------------------------------

    private fun reloadInternal(initial: Boolean) {
        if (!initial && _uiState.value.isBusy) return
        val gen = ++listGeneration
        _uiState.update { it.copy(pending = PendingOperation.Reload, status = NotesStatus.Loading, error = null) }
        viewModelScope.launch {
            try {
                val notes = withContext(ioDispatcher) { repository.list() }
                if (gen != listGeneration) return@launch
                _uiState.update {
                    it.copy(
                        pending = null,
                        notes = notes,
                        status = if (notes.isEmpty()) NotesStatus.Empty else NotesStatus.Ready,
                        error = null,
                        notice = if (it.notice == "保存完成，正在重读。") "已保存并重新读取。" else it.notice,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                if (gen != listGeneration) return@launch
                _uiState.update {
                    it.copy(
                        pending = null,
                        status = NotesStatus.Error,
                        error = "读取列表失败：${t.shortMessage()}；可点击「重新读取」重试。",
                    )
                }
            }
        }
    }

    private fun restoreDraft() {
        val marker = savedStateHandle.get<Any>(KEY_DRAFT_RESTORABLE)
        if (marker == null &&
            savedStateHandle.get<Any>(KEY_DRAFT_ID) == null &&
            savedStateHandle.get<Any>(KEY_DRAFT_TITLE) == null &&
            savedStateHandle.get<Any>(KEY_DRAFT_BODY) == null
        ) return

        val restorable = marker as? Boolean ?: true
        val id = savedStateHandle.get<Any>(KEY_DRAFT_ID) as? String
        val title = savedStateHandle.get<Any>(KEY_DRAFT_TITLE) as? String ?: ""
        val revision = savedStateHandle.get<Any>(KEY_DRAFT_REVISION) as? String
        val storedBytes = savedStateHandle.get<Any>(KEY_DRAFT_BODY_BYTES) as? Int ?: 0

        if (!restorable) {
            // Body was deliberately never written to the handle: do not claim the draft is restorable.
            savedStateHandle.remove<String>(KEY_DRAFT_BODY)
            _uiState.update {
                it.copy(
                    draft = null,
                    notice = "上次正文 ${storedBytes / 1024} KiB 超过 32 KiB 可恢复上限，未写入 SavedStateHandle；未完成草稿不会在进程恢复后保留。已回到列表，请重新打开记录再编辑。",
                )
            }
            return
        }

        val body = savedStateHandle.get<Any>(KEY_DRAFT_BODY) as? String ?: ""
        val bytes = body.utf8ByteCount()
        if (bytes > MAX_RESTORABLE_BODY_BYTES || bytes > MAX_STORABLE_BODY_BYTES) {
            clearDraftHandle()
            _uiState.update {
                it.copy(draft = null, notice = "进程状态中的草稿正文超过上限，已丢弃且不假称可恢复；请重新输入。")
            }
            return
        }
        val draft = NoteDraft(id, title, body, revision, true, bytes)
        _uiState.update {
            it.copy(
                draft = draft,
                status = NotesStatus.Ready,
                notice = "已从进程状态恢复草稿；SavedStateHandle 只保存 UI 元数据，不代表业务已保存，保存前会重新核验真实 revision。",
            )
        }
    }

    private fun persistDraft(draft: NoteDraft) {
        savedStateHandle[KEY_DRAFT_ID] = draft.id
        savedStateHandle[KEY_DRAFT_TITLE] = draft.title
        savedStateHandle[KEY_DRAFT_REVISION] = draft.revision
        savedStateHandle[KEY_DRAFT_RESTORABLE] = draft.restorable
        savedStateHandle[KEY_DRAFT_BODY_BYTES] = draft.originalBodyBytes
        if (draft.restorable) {
            savedStateHandle[KEY_DRAFT_BODY] = draft.body
        } else {
            savedStateHandle.remove<String>(KEY_DRAFT_BODY)
        }
    }

    private fun clearDraftHandle() {
        savedStateHandle.remove<String>(KEY_DRAFT_ID)
        savedStateHandle.remove<String>(KEY_DRAFT_TITLE)
        savedStateHandle.remove<String>(KEY_DRAFT_BODY)
        savedStateHandle.remove<String>(KEY_DRAFT_REVISION)
        savedStateHandle.remove<Boolean>(KEY_DRAFT_RESTORABLE)
        savedStateHandle.remove<Int>(KEY_DRAFT_BODY_BYTES)
    }

    private fun updateDraft(transform: (NoteDraft) -> NoteDraft) {
        val current = _uiState.value.draft ?: return
        val next = transform(current)
        persistDraft(next)
        _uiState.update { it.copy(draft = next) }
    }

    private fun setNotice(message: String) {
        _uiState.update { it.copy(notice = message) }
    }

    private fun fail(message: String) {
        _uiState.update { it.copy(pending = null, status = NotesStatus.Error, error = message) }
    }
}
