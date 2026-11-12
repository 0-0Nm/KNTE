package com.knte.preview.modelconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Configuration ViewModel. Testable by constructor injection; all persistence runs on
 * [ioDispatcher]. The draft secret is a private short-lived VM field: it is never copied
 * into published state, never serialized, never logged and never put in a SavedStateHandle.
 * There is deliberately no public getter that could reveal the draft secret.
 *
 * Draft lifecycle (the UI mirrors it through the non-secret
 * [ModelConfigUiState.secretResetEpoch]):
 *  - a typed draft SURVIVES a failed save: both the VM field and
 *    [ModelConfigUiState.draftCredentialPending] stay set, so the user can retry without
 *    retyping and the dirty/leave gates keep reporting a pending key;
 *  - the draft is cleared and the epoch is bumped ONLY on a successful save, a successful
 *    reload, or an explicit cancel/discard — never on a failure and never implicitly;
 *  - busy/loading is a hard gate: field edits, replace, cancel and discard are rejected.
 *
 * [save] publishes `busy` and captures the draft (including the typed key)
 * synchronously, BEFORE launching its coroutine: a same-frame second save is rejected by
 * the already-published busy flag, and the captured copy can no longer race a discard.
 *
 * The draft key is part of the dirty state: [ModelConfigUiState.draftCredentialPending]
 * feeds both hasUnsavedChanges and canLeave, so a typed-but-unsaved key can never be lost
 * by a silent leave. */
class ModelConfigViewModel(
    private val repository: ModelConfigRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val _state = MutableStateFlow(ModelConfigUiState())
    val state: StateFlow<ModelConfigUiState> = _state.asStateFlow()

    /** Short-lived plaintext draft key. VM-private; never published, never persisted. */
    private var draftSecret: String = ""
    private val gate = Mutex()

    init {
        reload()
    }

    fun reload() {
        val current = _state.value
        if (current.busy) return
        // Publish before launch so edits/save/discard cannot race a queued reload.
        _state.value = current.copy(busy = true, error = null, notice = null, saved = false)
        viewModelScope.launch {
            gate.withLock {
                try {
                    val snapshot = withContext(ioDispatcher) { repository.load() }
                    val profile = snapshot.profile
                    // A stored endpoint that no longer satisfies the policy is reported as
                    // untrusted instead of being presented as a usable address.
                    val endpoint = profile?.endpoint ?: ""
                    val trusted = profile == null || snapshot.endpointTrusted
                    _state.value = _state.value.copy(
                        loading = false,
                        busy = false,
                        endpoint = endpoint,
                        model = profile?.model ?: "",
                        credentialPresence = profile?.credentialPresence
                            ?: CredentialPresence.NotConfigured,
                        savedEndpoint = endpoint,
                        savedModel = profile?.model ?: "",
                        draftCredentialPending = false,
                        saved = false,
                        notice = null,
                        error = if (trusted) null else
                            "已保存的接口地址不符合当前规则（需要完整地址并以 " +
                                "${ModelConfigLimits.REQUIRED_PATH_SUFFIX} 结尾）；请修正后保存，" +
                                "未修改凭据。",
                        // The VM secret is consumed here: signal the UI-local copy to clear.
                        secretResetEpoch = _state.value.secretResetEpoch + 1L
                    )
                    draftSecret = ""
                } catch (e: ModelConfigException) {
                    // A read failure is never presented as "nothing saved": the previous
                    // values stay, an explicit error is shown and no success is claimed.
                    _state.value = _state.value.copy(
                        loading = false,
                        busy = false,
                        error = "配置读取失败（${e.code}）；未自动重试，未声称成功。"
                    )
                } catch (e: CancellationException) {
                    // Structured cancellation is rethrown, never downgraded.
                    throw e
                } catch (e: Exception) {
                    // The code is fixed; an exception message is never echoed.
                    _state.value = _state.value.copy(
                        loading = false,
                        busy = false,
                        error = "配置读取失败（READ_FAILED）；未自动重试，未声称成功。"
                    )
                }
            }
        }
    }

    /** UI-only field edits. Rejected while busy/loading; the state stays unchanged. */
    fun onEndpointChanged(value: String) {
        val current = _state.value
        if (current.busy || current.loading) return
        _state.value = current.copy(endpoint = value, saved = false, error = null, notice = null)
    }

    fun onModelChanged(value: String) {
        val current = _state.value
        if (current.busy || current.loading) return
        _state.value = current.copy(model = value, saved = false, error = null, notice = null)
    }

    /** A newly typed key. Stays VM-private; only presence becomes visible. */
    fun onCredentialChanged(value: String) {
        val current = _state.value
        if (current.busy || current.loading) return
        draftSecret = value
        _state.value = current.copy(
            draftCredentialPending = value.isNotEmpty(),
            saved = false,
            error = null,
            notice = null
        )
    }

    /** Explicit replace: a non-empty key replaces the stored credential for the SAME
     * binding. Rejected while busy/loading. */
    fun replaceCredential(newKey: String) {
        val current = _state.value
        if (current.busy || current.loading) return
        if (newKey.isEmpty()) {
            _state.value = current.copy(
                error = "替换密钥需要输入新密钥；输入为空表示保留已保存密钥。"
            )
            return
        }
        onCredentialChanged(newKey)
    }

    /** Clears the short-lived draft secret only. Never deletes the stored credential.
     * Rejected while busy/loading; on success the non-secret reset epoch is bumped so the
     * UI drops its local field copy. */
    fun discardDraftSecret() {
        val current = _state.value
        if (current.busy || current.loading) return
        draftSecret = ""
        _state.value = current.copy(
            draftCredentialPending = false,
            secretResetEpoch = current.secretResetEpoch + 1L
        )
    }

    /** Cancel: no persistence at all. Non-secret inputs are restored, the secret draft is
     * dropped and the reset epoch is bumped so the UI drops its local copy too. */
    fun cancel() {
        val current = _state.value
        if (current.busy || current.loading) return
        draftSecret = ""
        _state.value = current.copy(
            endpoint = current.savedEndpoint,
            model = current.savedModel,
            draftCredentialPending = false,
            notice = "已取消，未写入任何更改；未发送网络请求。",
            error = null,
            saved = false,
            secretResetEpoch = current.secretResetEpoch + 1L
        )
    }

    /** Called by the UI when the page (re)enters composition. Clears ONLY the ephemeral
     * presentation flags [ModelConfigUiState.notice] and [ModelConfigUiState.saved]:
     * this ViewModel outlives the screen, so a stale cancel/save banner from a previous
     * visit would otherwise reappear on re-entry. Must be called on the UI thread.
     *
     * It never touches error, busy, loading, dirty tracking, the non-secret inputs or
     * the VM-private draft secret, never triggers a reload, network access or any
     * persistence, and never bumps the secret reset epoch: while busy it leaves the
     * busy flag and the draft exactly as they are. */
    fun onPageEntered() {
        val current = _state.value
        _state.value = current.copy(notice = null, saved = false)
    }

    /** Save. Single gate, IO off the main thread, no network access.
     *
     * `busy` is published and the draft (endpoint + model + typed key) is captured
     * SYNCHRONOUSLY, BEFORE launch: a second save() in the same frame sees busy and is
     * rejected, and the captured copy is immune to a mid-flight discard racing the
     * coroutine scheduling. */
    fun save() {
        val current = _state.value
        if (current.busy || current.loading) {
            _state.value = current.copy(error = "正在处理，请稍候；未重复提交。")
            return
        }
        val draft = ModelConfigDraft.of(current.endpoint, current.model, draftSecret)
        _state.value = current.copy(busy = true, error = null, notice = null, saved = false)
        viewModelScope.launch {
            gate.withLock {
                try {
                    val profile = withContext(ioDispatcher) { repository.commit(draft) }
                    draftSecret = ""
                    _state.value = _state.value.copy(
                        busy = false,
                        saved = true,
                        savedEndpoint = profile.endpoint,
                        savedModel = profile.model,
                        endpoint = profile.endpoint,
                        model = profile.model,
                        credentialPresence = profile.credentialPresence,
                        draftCredentialPending = false,
                        error = null,
                        notice = saveNotice(profile),
                        // The VM secret was consumed by this save: signal the UI copy.
                        secretResetEpoch = _state.value.secretResetEpoch + 1L
                    )
                } catch (e: ModelConfigException) {
                    // Failure keeps BOTH the VM draft secret and draftCredentialPending
                    // so the user can retry without retyping. e.message is the fixed
                    // "[code] text" of the exception itself; a cause text is never echoed.
                    _state.value = _state.value.copy(
                        busy = false,
                        saved = false,
                        error = "保存未完成 [${e.code}]；草稿保留，未声称成功。",
                        notice = null
                    )
                } catch (e: CancellationException) {
                    // Structured cancellation is rethrown: it is never downgraded to a
                    // "save failed" message and can never be reported as success.
                    throw e
                } catch (e: Exception) {
                    // The code is fixed; an exception message could contain anything.
                    _state.value = _state.value.copy(
                        busy = false,
                        saved = false,
                        error = "保存未完成（COMMIT_FAILED）；未声称成功。",
                        notice = null
                    )
                }
            }
        }
    }

    private fun saveNotice(profile: ModelProfile): String = when (profile.credentialPresence) {
        CredentialPresence.Present ->
            "已保存到本机；凭据以 Keystore 加密后存放，不回显。保存过程不联网、不测试连接。"
        CredentialPresence.NotConfigured ->
            "已保存接口与模型；尚未配置凭据。保存过程不联网、不测试连接。"
    }

    /** Parent gate helper: may the user leave? */
    fun canLeave(): Boolean = _state.value.canLeave

    /** Parent gate helper: are there non-secret changes (or a pending key draft) to confirm? */
    fun hasUnsavedChanges(): Boolean = _state.value.hasUnsavedChanges
}
