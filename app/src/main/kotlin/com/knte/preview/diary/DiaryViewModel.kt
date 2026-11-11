package com.knte.preview.diary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knte.core.diary.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private const val PREFIX = "knte.isolated.diary."
const val DIARY_DRAFT_BYTES = 32768

data class DiaryDraft(val name: String? = null, val revision: String? = null,
    val date: String = LocalDate.now().toString(), val body: String = "")
data class DiaryUiState(val entries: List<DiaryEntry> = emptyList(), val loaded: Boolean = false,
    val draft: DiaryDraft? = null, val busy: Boolean = false,
    val confirmation: DiaryWritePlan? = null, val error: String? = null,
    val notice: String? = null, val reviewRequired: Boolean = false)

/** Owns only the app-private isolated Markdown validation store, never a live Vault. */
class DiaryViewModel(private val store: () -> IsolatedDiaryRepository,
    private val handle: SavedStateHandle = SavedStateHandle(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val _state = MutableStateFlow(DiaryUiState())
    val state: StateFlow<DiaryUiState> = _state.asStateFlow()
    init {
        if (handle.get<Boolean>(PREFIX + "draft") == true) {
            val body = handle.get<String>(PREFIX + "body").orEmpty()
            if (DiaryCodec.encode(body).size <= DIARY_DRAFT_BYTES) {
                _state.update { it.copy(draft = DiaryDraft(handle[PREFIX + "name"], handle[PREFIX + "revision"],
                    handle.get<String>(PREFIX + "date") ?: LocalDate.now().toString(), body),
                    notice = "已恢复隔离日记草稿；尚未保存。确认计划需要重新生成。") }
            }
        }
        if (handle.get<Boolean>(PREFIX + "writing") == true) {
            _state.update { it.copy(reviewRequired = true,
                error = "上次写入尚无可恢复的确认回执。请保留草稿并核对隔离库；不会自动重试。") }
        }
        reload()
    }
    fun reload() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val entries = withContext(ioDispatcher) { store().list() }
                _state.update { it.copy(entries = entries, loaded = true, busy = false,
                    error = if (it.reviewRequired) it.error else null) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(busy = false, error = "读取失败，未视为空库：${e.message}") }
            }
        }
    }
    fun newDiary() {
        if (_state.value.busy || _state.value.draft != null || _state.value.reviewRequired) return
        draft(DiaryDraft())
    }
    fun open(name: String) {
        if (_state.value.busy || _state.value.draft != null || _state.value.reviewRequired) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val entry = withContext(ioDispatcher) { store().get(name) } ?: error("文档不存在")
                check(entry.document.editable) { "只读文档：${entry.document.problems.joinToString()}" }
                check(DiaryCodec.encode(entry.document.body).size <= DIARY_DRAFT_BYTES) { "正文超过本切片32KiB草稿上限，不进入编辑且不截断原文" }
                val d = DiaryDraft(entry.name, entry.document.revision,
                    entry.document.metadata?.date ?: entry.name.removeSuffix(".md"), entry.document.body)
                _state.update { it.copy(busy = false) }; draft(d)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(busy = false, error = "打开失败：${e.message}") }
            }
        }
    }
    fun dateChanged(value: String) {
        val s = _state.value
        if (s.busy || s.reviewRequired || s.draft?.name != null || s.draft == null) return
        if (value.length > 10) return
        draft(s.draft.copy(date = value))
    }
    fun bodyChanged(value: String) {
        val s = _state.value; val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        val size = runCatching { DiaryCodec.encode(value).size }.getOrNull()
        if (size == null || size > DIARY_DRAFT_BYTES) {
            _state.update { it.copy(notice = "输入未接受：正文最多32KiB严格UTF-8；原草稿保留。", confirmation = null) }; return
        }
        draft(d.copy(body = value))
    }
    private fun draft(d: DiaryDraft) {
        handle[PREFIX + "draft"] = true; handle[PREFIX + "name"] = d.name
        handle[PREFIX + "revision"] = d.revision; handle[PREFIX + "date"] = d.date
        handle[PREFIX + "body"] = d.body
        _state.update { it.copy(draft = d, confirmation = null, error = null) }
    }
    fun prepare() {
        val s = _state.value;val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val plan = withContext(ioDispatcher) {
                    if (d.name == null) store().planCreate(DiaryCodec.create(d.date, d.body))
                    else store().planReplaceBody(d.name, requireNotNull(d.revision), d.body)
                }
                _state.update { it.copy(busy = false, confirmation = plan) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(busy = false, error = "未写入，核对计划失败：${e.message}") }
            }
        }
    }
    fun dismissConfirmation() { if (!_state.value.busy) _state.update { it.copy(confirmation = null) } }
    fun commit() {
        val s = _state.value; val plan = s.confirmation ?: return
        if (s.busy || s.reviewRequired) return
        // Keep an intent across process recreation. Do not reissue an unknown write.
        handle[PREFIX + "writing"] = true
        _state.update { it.copy(busy = true, confirmation = null) }
        viewModelScope.launch {
            try {
                val saved = withContext(ioDispatcher) { store().commit(plan, plan.confirmation) }
                // An independent repository read is required in addition to commit's readback.
                val reread = withContext(ioDispatcher) { store().get(saved.name) }
                check(reread?.document?.revision == plan.proposedRevision) { "独立读回不匹配" }
                clearDraft(); handle[PREFIX + "writing"] = false
                _state.update { it.copy(busy = false, draft = null, error = null,
                    notice = "隔离 Markdown 日记已保存并独立读回；未写入真实Vault。") }
                reload()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(busy = false, reviewRequired = true,
                    error = "写入结果未确认，请保留草稿并核对隔离库，不会自动重试：${e.message}") }
            }
        }
    }
    fun discard() {
        if (_state.value.busy) return
        clearDraft()
        _state.update { it.copy(draft = null, confirmation = null,
            notice = "已放弃当前草稿；不删除已存文档。") }
    }
    private fun clearDraft() {
        listOf("draft", "name", "revision", "date", "body").forEach { handle.remove<Any?>(PREFIX + it) }
    }
}
