package com.knte.preview.time

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knte.core.time.projection.TimeRecord
import com.knte.core.time.repository.TimeAppendPlan
import com.knte.core.time.repository.TimeLedgerApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val PREFIX = "knte.isolated.time."

/** Core rejects a project name longer than this; the VM refuses it before planning. */
const val TIME_PROJECT_LIMIT = 100

/** Human label for the isolated destination. The VM never invents a path: the parent injects the store. */
const val TIME_STORE_LABEL = "本 App 私有隔离库 · IsolatedTimeRepository · timetrack.jsonl（追加式 JSONL，无真实数据源）"

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Unsaved input only: the project name of a start that has not been appended yet. */
data class TimeDraft(val project: String = "") {
    val dirty: Boolean get() = project.isNotEmpty()
}

/** Confirmation card payload: which op is about to be appended, plus the immutable plan. */
data class TimeConfirmation(
    val op: String,
    val plan: TimeAppendPlan,
    val targetId: String,
    val project: String,
    val date: String,
    val start: String,
    val end: String?,
    val endDate: String?
)

data class TimeUiState(
    val records: List<TimeRecord> = emptyList(),
    val loaded: Boolean = false,
    val draft: TimeDraft? = null,
    val busy: Boolean = false,
    val confirmation: TimeConfirmation? = null,
    val error: String? = null,
    val notice: String? = null,
    val reviewRequired: Boolean = false
) {
    /** Pure projection of the ledger: no status column is ever persisted. */
    val running: List<TimeRecord> get() = records.filter { it.running() }
    val hasUnsavedChanges: Boolean get() = draft?.dirty == true
    val canLeave: Boolean get() = !busy && !hasUnsavedChanges
}

/** Owns only the app-private isolated time ledger. No second data source, no live clock persistence. */
class TimeViewModel(
    private val store: () -> TimeLedgerApi,
    private val handle: SavedStateHandle = SavedStateHandle(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _state = MutableStateFlow(TimeUiState())
    val state: StateFlow<TimeUiState> = _state.asStateFlow()

    init {
        if (handle.get<Boolean>(PREFIX + "draft") == true) {
            val project = handle.get<String>(PREFIX + "project").orEmpty()
            if (project.codePointCount(0, project.length) <= TIME_PROJECT_LIMIT) {
                _state.update {
                    it.copy(draft = TimeDraft(project),
                        notice = "已恢复未提交的项目名草稿；确认卡片需重新生成，不会自动写入。")
                }
            }
        }
        if (handle.get<Boolean>(PREFIX + "writing") == true) {
            _state.update {
                it.copy(reviewRequired = true,
                    error = "上次追加结果未确认。请保留草稿并核对隔离库；不会自动重试。")
            }
        }
        reload()
    }

    fun reload() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val snapshot = withContext(ioDispatcher) { store().snapshot() }
                _state.update {
                    it.copy(records = snapshot.projection.records, loaded = true, busy = false,
                        error = if (it.reviewRequired) it.error else null)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(busy = false, error = "读取失败，未视为空库：${e.message}") }
            }
        }
    }

    // ---- start flow -------------------------------------------------------

    fun beginStart() {
        if (_state.value.busy || _state.value.reviewRequired || _state.value.draft != null) return
        draft(TimeDraft())
    }

    fun projectChanged(value: String) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        if (value.codePointCount(0, value.length) > TIME_PROJECT_LIMIT) {
            _state.update { it.copy(confirmation = null, notice = "输入未接受：项目名最多 $TIME_PROJECT_LIMIT 字符；原草稿保留。") }
            return
        }
        draft(d.copy(project = value))
    }

    /** Builds the start plan. date/start come from the real clock read here, never hardcoded. */
    fun prepareStart() {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        _state.update { it.copy(busy = true, error = null, confirmation = null) }
        viewModelScope.launch {
            try {
                val now = LocalDateTime.now()
                val eventId = UUID.randomUUID().toString()
                val recordId = "t-" + UUID.randomUUID().toString().replace("-", "")
                val date = now.toLocalDate().toString()
                val start = now.format(HM)
                val plan = withContext(ioDispatcher) {
                    store().planStart(eventId = eventId, id = recordId, date = date, start = start, project = d.project)
                }
                _state.update {
                    it.copy(busy = false,
                        confirmation = TimeConfirmation("start", plan, recordId, d.project, date, start, null, null))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(busy = false, error = "未追加，核对计划失败：${e.message}") }
            }
        }
    }

    fun commitStart() {
        val s = _state.value
        val c = s.confirmation?.takeIf { it.op == "start" } ?: return
        if (s.busy || s.reviewRequired) return
        handle[PREFIX + "writing"] = true
        _state.update { it.copy(busy = true, confirmation = null) }
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { store().commit(c.plan, c.plan.confirmation) }
                // Independent re-read of the real ledger state; the UI never assumes the write landed.
                val reread = withContext(ioDispatcher) { store().snapshot() }
                val record = reread.projection.find(c.targetId)
                check(record != null && record.running()) { "独立重读未发现运行中的记录" }
                clearDraft()
                handle[PREFIX + "writing"] = false
                _state.update {
                    it.copy(busy = false, draft = null, error = null, records = reread.projection.records,
                        loaded = true, notice = "已追加 start 并独立重读：记录运行中，未写入任何真实数据源。")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update {
                    it.copy(busy = false, reviewRequired = true,
                        error = "写入结果未确认，请保留草稿并核对隔离库，不会自动重试：${e.message}")
                }
            }
        }
    }

    // ---- stop flow --------------------------------------------------------

    /** Builds the stop plan for a running record. Cross-day stops carry end_date. */
    fun prepareStop(id: String) {
        val s = _state.value
        if (s.busy || s.reviewRequired) return
        val record = s.records.firstOrNull { it.id == id }
        if (record == null || !record.running()) {
            _state.update { it.copy(error = "目标记录不在运行中，未生成计划。") }
            return
        }
        _state.update { it.copy(busy = true, error = null, confirmation = null) }
        viewModelScope.launch {
            try {
                val now = LocalDateTime.now()
                val today = now.toLocalDate().toString()
                val end = now.format(HM)
                val endDate = if (record.date == today) null else today
                val eventId = UUID.randomUUID().toString()
                val plan = withContext(ioDispatcher) { store().planStop(eventId, record.id, end, endDate) }
                _state.update {
                    it.copy(busy = false,
                        confirmation = TimeConfirmation("stop", plan, record.id, record.project,
                            record.date, record.start, end, endDate))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(busy = false, error = "未追加，核对计划失败：${e.message}") }
            }
        }
    }

    fun commitStop() {
        val s = _state.value
        val c = s.confirmation?.takeIf { it.op == "stop" } ?: return
        if (s.busy || s.reviewRequired) return
        handle[PREFIX + "writing"] = true
        _state.update { it.copy(busy = true, confirmation = null) }
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { store().commit(c.plan, c.plan.confirmation) }
                val reread = withContext(ioDispatcher) { store().snapshot() }
                val record = reread.projection.find(c.targetId)
                check(record != null && !record.running()) { "独立重读未发现已结束的记录" }
                handle[PREFIX + "writing"] = false
                _state.update {
                    it.copy(busy = false, error = null, records = reread.projection.records, loaded = true,
                        notice = "已追加 stop 并独立重读：时长 ${record.minutes()} 分钟（由事件投影派生，非持久状态）。")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update {
                    it.copy(busy = false, reviewRequired = true,
                        error = "写入结果未确认，请核对隔离库，不会自动重试：${e.message}")
                }
            }
        }
    }

    // ---- shared -----------------------------------------------------------

    fun confirmCommit() {
        when (_state.value.confirmation?.op) {
            "start" -> commitStart()
            "stop" -> commitStop()
        }
    }

    fun dismissConfirmation() {
        if (_state.value.busy) return
        _state.update { it.copy(confirmation = null) }
    }

    fun discard() {
        if (_state.value.busy) return
        clearDraft()
        _state.update {
            it.copy(draft = null, confirmation = null, notice = "已放弃当前草稿；未向隔离库追加任何事件。")
        }
    }

    private fun draft(d: TimeDraft) {
        handle[PREFIX + "draft"] = true
        handle[PREFIX + "project"] = d.project
        _state.update { it.copy(draft = d, confirmation = null, error = null, notice = null) }
    }

    private fun clearDraft() {
        listOf("draft", "project").forEach { handle.remove<Any?>(PREFIX + it) }
    }
}
