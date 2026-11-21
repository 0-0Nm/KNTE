package com.knte.preview.time

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knte.core.time.control.TimeRecoveryView
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
data class TimeUiState(val records: List<TimeRecord> = emptyList(), val loaded: Boolean = false, val draft: TimeDraft? = null, val busy: Boolean = false, val confirmation: TimeConfirmation? = null, val error: String? = null, val notice: String? = null, val reviewRequired: Boolean = false, val remoteOperation: Boolean = false, val recovery: TimeRecoveryView = TimeRecoveryView()) { val running get() = records.filter { it.running() }; val hasUnsavedChanges get() = draft?.dirty == true; val canLeave get() = !busy && !hasUnsavedChanges }

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
        val phase = st.phase
        val view = st.latestView
        val review = phase is AuthorityPhase.Review || view?.reviewRequired == true
        _state.value = _state.value.copy(
            records = view?.records ?: _state.value.records,
            loaded = view != null || _state.value.loaded,
            reviewRequired = review,
            recovery = view?.recovery ?: _state.value.recovery,
            remoteOperation = phase is AuthorityPhase.Busy ||
                (phase is AuthorityPhase.Preparing && st.owners[owner]?.handle == null),
            error = when (phase) {
                is AuthorityPhase.Review -> phase.message
                is AuthorityPhase.Failed -> phase.message
                else -> view?.readError
            },
            confirmation = _state.value.confirmation.takeUnless { review },
        )
    }

    fun reload() { if (_state.value.busy) return; _state.value = _state.value.copy(busy = true); viewModelScope.launch { runCatching { authority.reload() }.onSuccess { sync() }.onFailure { fail(it) } } }
    fun beginStart() { if (!_state.value.busy && !_state.value.remoteOperation && !_state.value.reviewRequired && _state.value.draft == null) draft(TimeDraft()) }
    fun projectChanged(value: String) { if (!_state.value.busy && !_state.value.remoteOperation && !_state.value.reviewRequired && _state.value.draft != null && value.codePointCount(0, value.length) <= TIME_PROJECT_LIMIT) draft(TimeDraft(value)) }
    fun prepareStart() { val d = _state.value.draft ?: return; if (_state.value.busy || _state.value.remoteOperation || _state.value.reviewRequired) return; _state.value = _state.value.copy(busy=true); viewModelScope.launch {
        runCatching { authority.prepareStart(d.project, owner) }
            .onSuccess { h -> _state.value = _state.value.copy(busy=false, confirmation=confirmationFromAuthority("start", h, fallbackProject=d.project)) }
            .onFailure { fail(it) }
    } }
    fun prepareStop(id: String) { if (_state.value.busy || _state.value.remoteOperation || _state.value.reviewRequired) return; _state.value = _state.value.copy(busy=true); viewModelScope.launch {
        runCatching { authority.prepareStop(id, owner) }
            .onSuccess { h -> _state.value = _state.value.copy(busy=false, confirmation=confirmationFromAuthority("stop", h, fallbackProject=_state.value.records.firstOrNull { it.id == id }?.project ?: "")) }
            .onFailure { fail(it) }
    } }
    fun confirmCommit() { val c = _state.value.confirmation ?: return; if (_state.value.busy || _state.value.reviewRequired) return; _state.value = _state.value.copy(busy=true); viewModelScope.launch { runCatching { authority.confirm(c.handle, owner) }.onSuccess { clearDraft(); _state.value = _state.value.copy(
                    busy = false,
                    confirmation = null,
                    draft = null,
                    notice = if (c.op == "start") "已开始「${c.project}」；记录已重新读取。"
                        else "已停止「${c.project}」；记录已重新读取。",
                ) }.onFailure { t -> val review = authority.state.value.phase is AuthorityPhase.Review; fail(t, review); _state.value = _state.value.copy(confirmation=c.takeUnless { review }) } } }

    /** Dialog cancel/dismiss actually cancels the issued prepare through the authority
     *  (owner-scoped): no orphan host handle, no silent TIME_CONTROL_UNRESOLVED_OP. */
    fun dismissConfirmation() {
        val c = _state.value.confirmation ?: return
        if (_state.value.busy || _state.value.reviewRequired) return
        _state.value = _state.value.copy(confirmation=null, busy=true)
        viewModelScope.launch {
            runCatching { authority.cancel(c.handle, owner) }
                .onSuccess { _state.value = _state.value.copy(busy=false, notice="已取消本次操作；没有写入，现有计时不变。") }
                .onFailure { t -> fail(t); _state.value = _state.value.copy(confirmation=c) } // handle stays issued & discoverable; no auto retry/clear
        }
    }

    fun dropRecoveredIntent(nonce: String) {
        if (_state.value.busy || nonce.isBlank()) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            runCatching { authority.dropRecoveredIntent(nonce) }
                .onSuccess {
                    sync()
                    _state.value = _state.value.copy(notice = "已确认原操作没有写入；如需继续，请重新发起，不会自动重试。")
                }
                .onFailure { t ->
                    val review = authority.state.value.phase is AuthorityPhase.Review || authority.currentView()?.reviewRequired == true
                    fail(t, review)
                }
        }
    }

    /** Explicit user acknowledgement after inspecting/resolving the store. The authority
     *  still refuses unknown writes and unresolved recovery intents; this never clears them. */
    fun acknowledgeReview() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            runCatching { authority.acknowledgeReview() }
                .onSuccess {
                    sync()
                    _state.value = _state.value.copy(notice = "已重新读取记录，可以继续操作。")
                }
                .onFailure { t ->
                    val review = authority.state.value.phase is AuthorityPhase.Review || authority.currentView()?.reviewRequired == true
                    fail(t, review)
                }
        }
    }

    /** Preserve the draft and bound handle until exact cancellation has succeeded. */
    fun discard(onSuccess: () -> Unit = {}) {
        if (_state.value.busy || _state.value.reviewRequired) return
        val pending = _state.value.confirmation
        if (_state.value.draft == null && pending == null) { onSuccess(); return }
        fun finish() {
            clearDraft()
            _state.value = _state.value.copy(busy=false, draft=null, confirmation=null, notice="已放弃草稿；没有写入。")
            onSuccess()
        }
        if (pending == null) { finish(); return }
        _state.value = _state.value.copy(busy=true)
        viewModelScope.launch {
            runCatching { authority.cancel(pending.handle, owner) }.onSuccess { finish() }.onFailure { fail(it) }
        }
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
    private fun sync() {
        observe(authority.state.value)
        _state.value = _state.value.copy(busy=false)
    }
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
