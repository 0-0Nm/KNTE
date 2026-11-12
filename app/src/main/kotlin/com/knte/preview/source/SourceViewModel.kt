package com.knte.preview.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One persistence/IO lane plus generations: late IO cannot resurrect a previous root.
 * Blocking providers are not assumed cancellable. Waiting occurs off the UI thread. */
class SourceViewModel(private val source: ReadOnlyDiarySource,
    private val preferences: SourcePreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val _state = MutableStateFlow(SourceUiState())
    val state = _state.asStateFlow()
    private var generation = 0L
    private var session = SourceSession(source)
    private val lane = Mutex()
    init {
        val gen = ++generation
        _state.value = SourceUiState(status = SourceStatus.Loading, busy = true)
        viewModelScope.launch {
            try {
                lane.withLock {
                    // Always establish the persisted rollback baseline before queued writes,
                    // even if a selection superseded startup before this coroutine ran.
                    val selected = withContext(ioDispatcher) { preferences.load() }
                    val current = SourceSession(source)
                    if (selected != null) current.bind(selected)
                    session = current
                    if (gen != generation) return@withLock
                    if (selected == null) _state.value = SourceUiState()
                    else {
                        _state.value = current.uiState().copy(busy = true)
                        val result = withContext(ioDispatcher) { current.reload() }
                        if (gen == generation) _state.value = result.copy(busy = false)
                    }
                }
            } catch (e: Exception) { failure(gen, e, "来源绑定无法读取，请重新选择目录") }
        }
    }
    /** Called after explicit selection and read-grant persistence, never grants permission itself. */
    fun select(root: SourceRoot) {
        val gen = ++generation
        val next = SourceSession(source)
        next.bind(root)
        // Hide previous content while selecting; do not publish an uncommitted binding.
        _state.value = SourceUiState(status = SourceStatus.Loading, busy = true)
        viewModelScope.launch {
            try {
                lane.withLock {
                    if (gen != generation) return@withLock
                    // A newer selection/forget waits for this physical save to finish, then wins.
                    try {
                        withContext(ioDispatcher) { preferences.save(root) }
                    } catch (e: Exception) {
                        persistenceFailure(gen, e, "目录切换未保存；保留原来源，可重新选择。")
                        return@withLock
                    }
                    // Even an obsolete physical save becomes the rollback baseline until a newer save wins.
                    session = next
                    if (gen != generation) return@withLock
                    val result = withContext(ioDispatcher) { next.reload() }
                    if (gen == generation) _state.value = result.copy(busy = false)
                }
            } catch (e: Exception) { failure(gen, e, "来源绑定或读取失败；未回退到其他快照") }
        }
    }
    fun reload() { if (!_state.value.busy) runRead { it.reload() } }
    fun open(entry: SourceEntry) {
        if (_state.value.busy || entry.rootBinding != _state.value.rootBinding) return
        runRead { it.open(entry.documentId) }
    }
    private fun runRead(action: (SourceSession) -> SourceUiState) {
        val gen = generation; val current = session
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                lane.withLock {
                    if (gen != generation) return@withLock
                    val result = withContext(ioDispatcher) { action(current) }
                    if (gen == generation) _state.value = result.copy(busy = false)
                }
            } catch (e: Exception) { failure(gen, e, "只读来源操作失败") }
        }
    }
    fun closeDocument() { if (!_state.value.busy) _state.value = session.closeDocument() }
    fun forget() {
        val gen = ++generation
        val next = SourceSession(source)
        next.forget()
        _state.value = SourceUiState(status = SourceStatus.Loading, busy = true)
        viewModelScope.launch {
            try {
                lane.withLock {
                    if (gen != generation) return@withLock
                    try {
                        withContext(ioDispatcher) { preferences.save(null) }
                    } catch (e: Exception) {
                        persistenceFailure(gen, e, "断开来源未保存；保留原绑定，请重新断开。")
                        return@withLock
                    }
                    session = next
                    if (gen == generation) _state.value = next.uiState()
                }
            } catch (e: Exception) { failure(gen, e, "已清除显示内容，但断开绑定未持久化，请重试") }
        }
    }
    fun selectionFailed() {
        _state.value = _state.value.copy(notice = "目录选择未完成或未取得只读授权，来源未切换。")
    }
    private fun persistenceFailure(gen: Long, e: Exception, text: String) {
        if (e is CancellationException) throw e
        if (gen == generation) {
            val prior = session.uiState()
            _state.value = prior.copy(busy = false, opened = null, notice = null,
                status = SourceStatus.Error(SourceCodes.PERSIST_FAILED, text, prior.entries, prior.totalBytes))
        }
    }
    private fun failure(gen: Long, e: Exception, text: String) {
        if (e is CancellationException) throw e
        if (gen == generation) _state.value = _state.value.copy(busy = false,
            status = SourceStatus.Error(SourceCodes.READ_FAILED, text), opened = null)
    }
}
