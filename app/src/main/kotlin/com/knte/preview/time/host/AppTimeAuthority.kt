package com.knte.preview.time.host

import android.content.Context
import com.knte.core.time.control.*
import com.knte.core.time.projection.TimeRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed interface AuthorityPhase {
    data object Starting : AuthorityPhase
    data class Ready(val view: TimeControlView) : AuthorityPhase
    data class Preparing(val handle: SessionHandle, val op: String) : AuthorityPhase
    data class Busy(val message: String) : AuthorityPhase
    data class Review(val view: TimeControlView, val message: String) : AuthorityPhase
    data class Failed(val view: TimeControlView?, val message: String) : AuthorityPhase
}
data class AuthorityState(val phase: AuthorityPhase = AuthorityPhase.Starting, val monitor: Boolean = false)

class AppTimeAuthority private constructor(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val app = context.applicationContext
    private val serial = Mutex()
    private val root = File(app.filesDir.canonicalFile, "isolated-time-intents-v1")
    private val ledgerRoot = File(app.filesDir.canonicalFile, "timetrack")
    private val source = TimeSessionSource.open(ledgerRoot)
    private val controller = source.controller()
    private val journal = FileTimeIntentJournal(root)
    private val _state = MutableStateFlow(AuthorityState())
    val state: StateFlow<AuthorityState> = _state.asStateFlow()
    private val issued = LinkedHashMap<String, SessionHandle>()
    private var initialized = false

    suspend fun initialize() = serial.withLock {
        if (initialized) return@withLock
        withContext(io) {
            controller.setIntentJournal(journal)
            controller.recoverPendingIntents()
            val view = controller.reload()
            _state.value = AuthorityState(AuthorityPhase.Ready(view), _state.value.monitor)
        }
        initialized = true
    }
    suspend fun reload() { initialize(); serial.withLock { withContext(io) { publish(controller.reload()) } } }
    suspend fun viewSnapshot() { initialize(); serial.withLock { withContext(io) { publish(controller.viewSnapshot()) } } }

    suspend fun prepareStart(project: String): OpaqueHostHandle = run { initialize(); serial.withLock {
        ensureReady(); _state.value = AuthorityState(AuthorityPhase.Busy("准备开始"), _state.value.monitor)
        try {
            val session = withContext(io) { controller.prepareStart(project) }
            return issue(session, "start")
        } catch (t: Throwable) { fail(t); throw t }
    } }
    suspend fun prepareStop(recordId: String): OpaqueHostHandle = run { initialize(); serial.withLock {
        ensureReady(); _state.value = AuthorityState(AuthorityPhase.Busy("准备停止"), _state.value.monitor)
        try {
            val session = withContext(io) { controller.prepareStop(recordId) }
            return issue(session, "stop")
        } catch (t: Throwable) { fail(t); throw t }
    } }
    suspend fun confirm(handle: OpaqueHostHandle): ConfirmResult = run { initialize(); serial.withLock {
        ensureReady(); val h = exact(handle); _state.value = AuthorityState(AuthorityPhase.Busy("写入并重读"), _state.value.monitor)
        try { withContext(io) { controller.confirm(h) }.also { controller.reload(); publish(controller.viewSnapshot()) } }
        catch (t: Throwable) { fail(t); throw t }
    } }
    suspend fun cancel(handle: OpaqueHostHandle) = run { initialize(); serial.withLock {
        val h = exact(handle)
        withContext(io) { controller.cancel(h) }
        issued.remove(handle.value)
        publish(controller.viewSnapshot())
    } }
    suspend fun acknowledgeReview() = serial.withLock { withContext(io) { publish(controller.acknowledgeReviewAfterHumanResolution()) } }

    private fun issue(handle: SessionHandle, op: String): OpaqueHostHandle {
        val value = UUID.randomUUID().toString()
        issued[value] = handle
        _state.value = AuthorityState(AuthorityPhase.Preparing(handle, op), _state.value.monitor)
        return OpaqueHostHandle(value)
    }
    private fun exact(handle: OpaqueHostHandle): SessionHandle = issued[handle.value]
        ?: throw TimeControlException("TIME_HANDLE_UNKNOWN", "未知或已撤销的host handle")
    private fun ensureReady() {
        check(initialized) { "time authority initialization not complete" }
        check(controller.hasIntentJournal) { "persistent intent journal not attached" }
    }
    private fun publish(view: TimeControlView) {
        _state.value = if (view.reviewRequired || view.readError != null)
            AuthorityState(AuthorityPhase.Review(view, view.readError ?: "待人工核对"), _state.value.monitor)
        else AuthorityState(AuthorityPhase.Ready(view), _state.value.monitor)
    }
    private fun fail(t: Throwable) { _state.value = AuthorityState(AuthorityPhase.Failed(controller.viewSnapshot(), t.message ?: t.javaClass.simpleName), _state.value.monitor) }
    fun setMonitor(value: Boolean) { _state.value = _state.value.copy(monitor = value) }
    fun currentView(): TimeControlView? = when (val p = _state.value.phase) { is AuthorityPhase.Ready -> p.view; is AuthorityPhase.Review -> p.view; is AuthorityPhase.Failed -> p.view; else -> null }
    fun currentHandle(value: String): OpaqueHostHandle? = value.takeIf { issued.containsKey(it) }?.let(::OpaqueHostHandle)
    fun currentOpaqueHandle(): OpaqueHostHandle? = when (val p = _state.value.phase) { is AuthorityPhase.Preparing -> issued.entries.firstOrNull { it.value === p.handle }?.key?.let(::OpaqueHostHandle); else -> null }
    fun records(): List<TimeRecord> = currentView()?.records.orEmpty()
    companion object { @Volatile private var singleton: AppTimeAuthority? = null; fun get(context: Context): AppTimeAuthority = singleton ?: synchronized(this) { singleton ?: AppTimeAuthority(context).also { singleton = it } } }
}

data class OpaqueHostHandle(val value: String)

fun OpaqueHostHandle.toPanelHandle() = com.knte.preview.time.overlay.OpaqueHandle(value)
