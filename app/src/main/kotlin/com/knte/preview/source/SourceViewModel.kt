package com.knte.preview.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Selection generations isolate late IO from another source. No write capability. */
class SourceViewModel(private val source: ReadOnlyDiarySource,
    private val preferences: SourcePreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val _state = MutableStateFlow(SourceUiState())
    val state = _state.asStateFlow()
    private var generation = 0L
    private var session = SourceSession(source)
    init {
        val gen = ++generation
        _state.value = SourceUiState(status = SourceStatus.Loading, busy = true)
        viewModelScope.launch {
            try {
                val selected = withContext(ioDispatcher) { preferences.load() }
                if (gen != generation) return@launch
                if (selected == null) _state.value = SourceUiState()
                else {
                    session.bind(selected)
                    reload()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (gen == generation) _state.value = SourceUiState(status = SourceStatus.Error(
                    SourceCodes.READ_FAILED, "来源绑定无法读取，请重新选择目录"))
            }
        }
    }
    /** Parent calls only after explicit tree selection and read grant persistence. */
    fun select(root: SourceRoot) {
        val gen = ++generation
        val next = SourceSession(source)
        session = next
        _state.value = next.bind(root).copy(busy = true)
        viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { preferences.save(root); next.reload() }
                if (gen == generation) _state.value = result.copy(busy = false)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (gen == generation) _state.value = SourceUiState(status = SourceStatus.Error(
                    SourceCodes.READ_FAILED, "来源绑定或读取失败；未切换到其他快照"))
            }
        }
    }
    fun reload() {
        if (_state.value.busy && _state.value.rootIdentity != null) return
        runRead { it.reload() }
    }
    fun open(entry: SourceEntry) {
        if (_state.value.busy || entry.rootBinding != _state.value.rootBinding) return
        runRead { it.open(entry.documentId) }
    }
    private fun runRead(action: (SourceSession) -> SourceUiState) {
        val gen = generation; val current = session
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { action(current) }
                if (gen == generation) _state.value = result.copy(busy = false)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (gen == generation) _state.value = _state.value.copy(busy = false,
                    status = SourceStatus.Error(SourceCodes.READ_FAILED, "只读来源操作失败"), opened = null)
            }
        }
    }
    fun closeDocument() { if (!_state.value.busy) _state.value = session.closeDocument() }
    fun forget() {
        val gen = ++generation
        session = SourceSession(source)
        _state.value = session.forget().copy(busy = true)
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { preferences.save(null) }
                if (gen == generation) _state.value = session.uiState()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (gen == generation) _state.value = SourceUiState(notice = "已清除显示内容，但断开绑定未持久化，请重试")
            }
        }
    }
    fun selectionFailed() {
        _state.value = _state.value.copy(notice = "未取得持久只读授权，来源未切换。请重新选择支持的目录。")
    }
}
