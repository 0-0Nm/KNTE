package com.knte.preview.time

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knte.core.time.projection.TimeRecord
import com.knte.preview.time.host.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

const val TIME_PROJECT_LIMIT = 96
const val TIME_STORE_LABEL = "本 App 私有隔离库 · timetrack"
data class TimeDraft(val project: String = "") { val dirty get() = project.isNotEmpty() }
data class TimeConfirmation(val op: String, val handle: OpaqueHostHandle, val targetId: String, val project: String, val date: String, val start: String, val end: String?, val endDate: String?)
data class TimeUiState(val records: List<TimeRecord> = emptyList(), val loaded: Boolean = false, val draft: TimeDraft? = null, val busy: Boolean = false, val confirmation: TimeConfirmation? = null, val error: String? = null, val notice: String? = null, val reviewRequired: Boolean = false) { val running get() = records.filter { it.running() }; val hasUnsavedChanges get() = draft?.dirty == true; val canLeave get() = !busy && !hasUnsavedChanges }

class TimeViewModel(private val authority: AppTimeAuthority, private val handle: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    // Per-instance owner token: prepare/confirm/cancel are bound to THIS surface instance,
    // and onCleared closes exactly this owner (never a newer page instance's owner).
    private val owner = "${AppTimeAuthority.OWNER_PAGE}-${UUID.randomUUID()}"
    private val _state = MutableStateFlow(TimeUiState()); val state: StateFlow<TimeUiState> = _state.asStateFlow()
    private var collectJob: Job? = null

    init {
        authority.openOwner(owner)
        if (handle.get<Boolean>("time.draft") == true) _state.value = TimeUiState(draft = TimeDraft(handle.get<String>("time.project").orEmpty()))
        // Live subscription: records/review/error follow the authority as it publishes,
        // so cross-surface changes (monitor confirm/cancel) no longer show stale data.
        collectJob = viewModelScope.launch {
            authority.state.collect { st -> observe(st) }
        }
        reload()
    }

    private fun observe(st: AuthorityState) {
        val v = when (val p = st.phase) {
            is AuthorityPhase.Ready -> p.view
            is AuthorityPhase.Review -> p.view
            is AuthorityPhase.Failed -> p.view
            else -> null
        }
        // Never overwrites busy/confirmation/draft: those belong to THIS surface's explicit
        // local flow; records/review/error follow the authority so cross-surface changes
        // (monitor confirm/cancel, review transitions) no longer show stale data.
        _state.value = _state.value.copy(
            records = v?.records.orEmpty(),
            loaded = v != null || _state.value.loaded,
            reviewRequired = v?.reviewRequired == true,
            error = v?.readError,
        )
    }

    fun reload() { if (_state.value.busy) return; _state.value = _state.value.copy(busy = true); viewModelScope.launch { runCatching { authority.reload() }.onSuccess { sync() }.onFailure { fail(it) } } }
    fun beginStart() { if (!_state.value.busy && !_state.value.reviewRequired && _state.value.draft == null) draft(TimeDraft()) }
    fun projectChanged(value: String) { if (!_state.value.busy && !_state.value.reviewRequired && _state.value.draft != null && value.codePointCount(0, value.length) <= TIME_PROJECT_LIMIT) draft(TimeDraft(value)) }
    fun prepareStart() { val d = _state.value.draft ?: return; if (_state.value.busy || _state.value.reviewRequired) return; _state.value = _state.value.copy(busy=true); viewModelScope.launch {
        runCatching { authority.prepareStart(d.project, owner) }
            .onSuccess { h -> _state.value = _state.value.copy(busy=false, confirmation=confirmationFromAuthority("start", h, fallbackProject=d.project)) }
            .onFailure { fail(it) }
    } }
    fun prepareStop(id: String) { if (_state.value.busy || _state.value.reviewRequired) return; _state.value = _state.value.copy(busy=true); viewModelScope.launch {
        runCatching { authority.prepareStop(id, owner) }
            .onSuccess { h -> _state.value = _state.value.copy(busy=false, confirmation=confirmationFromAuthority("stop", h, fallbackProject=_state.value.records.firstOrNull { it.id == id }?.project ?: "")) }
            .onFailure { fail(it) }
    } }
    fun confirmCommit() { val c = _state.value.confirmation ?: return; if (_state.value.busy) return; _state.value = _state.value.copy(busy=true); viewModelScope.launch { runCatching { authority.confirm(c.handle, owner) }.onSuccess { result -> clearDraft(); _state.value = _state.value.copy(busy=false,confirmation=null,notice="已确认 ${result.op}；历史回执 runningAfter=${result.runningAfter}，当前运行视图已独立重读") }.onFailure { t -> fail(t, review=false); _state.value = _state.value.copy(confirmation=c) } } }

    /** Dialog cancel/dismiss actually cancels the issued prepare through the authority
     *  (owner-scoped): no orphan host handle, no silent TIME_CONTROL_UNRESOLVED_OP. */
    fun dismissConfirmation() {
        val c = _state.value.confirmation ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(confirmation=null, busy=true)
        viewModelScope.launch {
            runCatching { authority.cancel(c.handle, owner) }
                .onSuccess { _state.value = _state.value.copy(busy=false, notice="已取消未确认的${c.op}；未写入任何事件") }
                .onFailure { t -> fail(t); _state.value = _state.value.copy(confirmation=c) } // handle stays issued & discoverable; no auto retry/clear
        }
    }

    /** Discard clears the draft AND cancels any pending unconfirmed prepare of this page. */
    fun discard() {
        if (_state.value.busy) return
        val pending = _state.value.confirmation
        if (_state.value.draft?.dirty != true && pending == null) return
        clearDraft()
        _state.value = _state.value.copy(draft=null, confirmation=null, notice="已放弃当前草稿；未写入")
        if (pending != null) viewModelScope.launch { runCatching { authority.cancel(pending.handle, owner) }.onFailure { fail(it) } }
    }

    private fun confirmationFromAuthority(op: String, h: OpaqueHostHandle, fallbackProject: String): TimeConfirmation {
        // Real prepare timestamps come from THIS handle's authoritative SessionHandle, never
        // blank and never another surface's phase.
        val session = authority.sessionOf(h)
        return TimeConfirmation(
            op = op, handle = h,
            targetId = session?.targetId ?: "",
            project = session?.project?.takeIf { it.isNotBlank() } ?: fallbackProject,
            date = session?.date ?: "",
            start = session?.start ?: "",
            end = session?.end, endDate = session?.endDate,
        )
    }

    private fun draft(d: TimeDraft) { handle["time.draft"] = true; handle["time.project"] = d.project; _state.value = _state.value.copy(draft=d,confirmation=null,error=null,notice=null) }
    private fun clearDraft() { handle.remove<Boolean>("time.draft"); handle.remove<String>("time.project") }
    private fun sync() { val v=authority.currentView(); _state.value = _state.value.copy(records=v?.records.orEmpty(),loaded=v!=null,busy=false,reviewRequired=v?.reviewRequired==true,error=v?.readError) }
    private fun fail(t: Throwable, review: Boolean=false) { val v=authority.currentView(); _state.value = _state.value.copy(busy=false,reviewRequired=review || v?.reviewRequired==true,error=t.message ?: "计时操作失败") }

    override fun onCleared() {
        collectJob?.cancel()
        // Application-owned cleanup launched off the main thread; the core serializes it with
        // any in-flight prepare/confirm. An in-progress confirm is not interrupted in a way
        // that hides uncertain data: only this owner's exact unconfirmed prepare is cancelled.
        authority.closeOwnerDetached(owner, "page-vm-cleared")
        super.onCleared()
    }
}
