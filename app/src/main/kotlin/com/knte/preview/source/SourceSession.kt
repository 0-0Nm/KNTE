package com.knte.preview.source

/** Pure-Kotlin state machine (no Android/androidx/coroutines types): host-testable.
 * Owns root binding, source switching, permission loss and failure semantics.
 * The ViewModel only switches dispatcher around these synchronous transitions. */
class SourceSession(private val source: ReadOnlyDiarySource) {
    @Volatile private var root: SourceRoot? = null
    @Volatile private var state: SourceUiState = SourceUiState()

    fun uiState(): SourceUiState = state

    /** Parent calls this AFTER ACTION_OPEN_DOCUMENT_TREE result and AFTER taking
     * persistable permission with user-granted flags. Switching clears old content
     * immediately: old entries are never shown as the new source. */
    fun bind(newRoot: SourceRoot): SourceUiState {
        root = newRoot
        state = SourceUiState(
            status = SourceStatus.Loading,
            permission = newRoot.permission,
            rootLabel = newRoot.label,
            rootIdentity = newRoot.identity,
            rootBinding = newRoot.binding,
            notice = if (newRoot.permission == SourcePermission.Granted)
                "已绑定用户选择的目录，等待读取。" else "目录尚未获得持久读取权限。"
        )
        return state
    }

    /** Logic-only disconnect: clears binding and all displayed content.
     * Does NOT delete any file and does NOT release a persisted URI permission grant.
     * Releasing a grant requires a separate explicit UI action by the parent. */
    fun forget(): SourceUiState {
        root = null
        state = SourceUiState(notice = "已断开来源；未删除任何文件，未释放持久权限。")
        return state
    }

    /** Synchronous read; call on an IO dispatcher from the ViewModel.
     * Failure -> Error (never ReadyEmpty) while retaining the last good list;
     * permission loss -> PermissionLost with cleared content. */
    fun reload(): SourceUiState {
        val r = root ?: run {
            state = SourceUiState(notice = "未选择目录。")
            return state
        }
        val retained = state.entries
        val retainedBytes = state.totalBytes
        state = state.copy(status = SourceStatus.Loading, notice = null)
        return try {
            val listing = source.list(r)
            if (!accepts(listing.rootIdentity, listing.rootBinding))
                return applyFailure(SourceCodes.ROOT_MISMATCH,
                    "读取结果与当前来源身份不匹配，已丢弃。", retained, retainedBytes)
            val status = if (listing.entries.isEmpty()) SourceStatus.ReadyEmpty
                else SourceStatus.Ready(listing.entries, listing.totalBytes)
            state = state.copy(status = status, permission = SourcePermission.Granted,
                opened = null, notice = null)
            state
        } catch (e: SourceException) {
            applyFailure(e.code, e.message ?: e.code, retained, retainedBytes)
        } catch (e: Exception) {
            applyFailure(SourceCodes.QUERY_FAILED, "读取失败：${e.javaClass.simpleName}",
                retained, retainedBytes)
        }
    }

    fun open(documentId: String): SourceUiState {
        val r = root ?: return state
        val ready = state.status is SourceStatus.Ready ||
            state.status is SourceStatus.ReadyEmpty || state.status is SourceStatus.Error
        if (!ready) return state
        return try {
            val doc = source.read(r, documentId)
            if (!accepts(doc.rootIdentity, doc.rootBinding))
                return applyOpenFailure(SourceCodes.ROOT_MISMATCH, "文档来源身份不匹配，已丢弃。")
            state = state.copy(opened = doc, notice = null)
            state
        } catch (e: SourceException) {
            applyOpenFailure(e.code, e.message ?: e.code)
        } catch (e: Exception) {
            applyOpenFailure(SourceCodes.READ_FAILED, "打开失败：${e.javaClass.simpleName}")
        }
    }

    fun closeDocument(): SourceUiState {
        state = state.copy(opened = null)
        return state
    }

    /** Result must match the CURRENT root identity AND its permission binding.
     * Drops late results from another root or a revoked permission state. */
    private fun accepts(identity: String, binding: String): Boolean {
        val r = root ?: return false
        return r.identity == identity && r.binding == binding &&
            r.permission == SourcePermission.Granted
    }

    private fun applyFailure(code: String, message: String,
        retained: List<SourceEntry> = emptyList(), retainedBytes: Long = 0L): SourceUiState {
        val r = root
        state = if (code == SourceCodes.PERMISSION_LOST || code == SourceCodes.PERMISSION_NOT_GRANTED)
            SourceUiState(
                status = SourceStatus.PermissionLost,
                permission = SourcePermission.Lost,
                rootLabel = r?.label,
                rootIdentity = r?.identity,
                rootBinding = r?.binding,
                notice = "权限不可用或已撤销：已清除旧内容，请重新选择目录。"
            )
        else SourceUiState(
                status = SourceStatus.Error(code, message, retained, retainedBytes),
                permission = r?.permission ?: SourcePermission.None,
                rootLabel = r?.label,
                rootIdentity = r?.identity,
                rootBinding = r?.binding,
                notice = "读取失败，未视为空目录，也未回退隔离快照；保留上次已读取的 ${retained.size} 条。"
            )
        return state
    }

    private fun applyOpenFailure(code: String, message: String): SourceUiState {
        if (code == SourceCodes.PERMISSION_LOST || code == SourceCodes.PERMISSION_NOT_GRANTED)
            return applyFailure(code, message)
        state = state.copy(opened = null, notice = "打开失败 [$code]：已读取的列表未归零。$message")
        return state
    }
}
