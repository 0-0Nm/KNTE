package com.knte.preview.time

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knte.core.time.projection.TimeRecord
import com.knte.preview.time.host.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val TIME_PROJECT_LIMIT = 96
const val TIME_STORE_LABEL = "本 App 私有隔离库 · timetrack"
data class TimeDraft(val project: String = "") { val dirty get() = project.isNotEmpty() }
data class TimeConfirmation(val op: String, val handle: OpaqueHostHandle, val targetId: String, val project: String, val date: String, val start: String, val end: String?, val endDate: String?)
data class TimeUiState(val records: List<TimeRecord> = emptyList(), val loaded: Boolean = false, val draft: TimeDraft? = null, val busy: Boolean = false, val confirmation: TimeConfirmation? = null, val error: String? = null, val notice: String? = null, val reviewRequired: Boolean = false) { val running get() = records.filter { it.running() }; val hasUnsavedChanges get() = draft?.dirty == true; val canLeave get() = !busy && !hasUnsavedChanges }

class TimeViewModel(private val authority: AppTimeAuthority, private val handle: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    private val _state = MutableStateFlow(TimeUiState()); val state: StateFlow<TimeUiState> = _state.asStateFlow()
    init { if (handle.get<Boolean>("time.draft") == true) _state.value = TimeUiState(draft = TimeDraft(handle.get<String>("time.project").orEmpty())); reload() }
    fun reload() { if (_state.value.busy) return; _state.value = _state.value.copy(busy = true); viewModelScope.launch { runCatching { authority.reload() }.onSuccess { sync() }.onFailure { fail(it) } } }
    fun beginStart() { if (!_state.value.busy && !_state.value.reviewRequired && _state.value.draft == null) draft(TimeDraft()) }
    fun projectChanged(value: String) { if (!_state.value.busy && !_state.value.reviewRequired && _state.value.draft != null && value.codePointCount(0, value.length) <= TIME_PROJECT_LIMIT) draft(TimeDraft(value)) }
    fun prepareStart() { val d = _state.value.draft ?: return; if (_state.value.busy || _state.value.reviewRequired) return; _state.value = _state.value.copy(busy=true); viewModelScope.launch { runCatching { authority.prepareStart(d.project) }.onSuccess { h -> _state.value = _state.value.copy(busy=false, confirmation=TimeConfirmation("start",h,"","${d.project}","","",null,null)) }.onFailure { fail(it) } } }
    fun prepareStop(id: String) { if (_state.value.busy || _state.value.reviewRequired) return; _state.value = _state.value.copy(busy=true); viewModelScope.launch { runCatching { authority.prepareStop(id) }.onSuccess { h -> val r = _state.value.records.first { it.id == id }; _state.value = _state.value.copy(busy=false,confirmation=TimeConfirmation("stop",h,id,r.project,r.date,r.start,null,null)) }.onFailure { fail(it) } } }
    fun confirmCommit() { val c = _state.value.confirmation ?: return; if (_state.value.busy) return; _state.value = _state.value.copy(busy=true, confirmation=null); viewModelScope.launch { runCatching { authority.confirm(c.handle) }.onSuccess { result -> clearDraft(); sync(); _state.value = _state.value.copy(busy=false,notice="已确认 ${result.op}；历史回执 runningAfter=${result.runningAfter}，当前运行视图已独立重读") }.onFailure { fail(it, review=true) } } }
    fun dismissConfirmation() { if (!_state.value.busy) _state.value = _state.value.copy(confirmation=null) }
    fun discard() { if (!_state.value.busy) { clearDraft(); _state.value = _state.value.copy(draft=null,confirmation=null,notice="已放弃当前草稿；未写入") } }
    private fun draft(d: TimeDraft) { handle["time.draft"] = true; handle["time.project"] = d.project; _state.value = _state.value.copy(draft=d,confirmation=null,error=null,notice=null) }
    private fun clearDraft() { handle.remove<Boolean>("time.draft"); handle.remove<String>("time.project") }
    private fun sync() { val v=authority.currentView(); _state.value = _state.value.copy(records=v?.records.orEmpty(),loaded=v!=null,busy=false,reviewRequired=v?.reviewRequired==true,error=v?.readError) }
    private fun fail(t: Throwable, review: Boolean=false) { val v=authority.currentView(); _state.value = _state.value.copy(busy=false,reviewRequired=review || v?.reviewRequired==true,error=t.message ?: "计时操作失败") }
}
