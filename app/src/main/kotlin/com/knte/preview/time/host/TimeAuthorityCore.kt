package com.knte.preview.time.host

import com.knte.core.time.control.ConfirmResult
import com.knte.core.time.control.SessionHandle
import com.knte.core.time.control.TimeControlException
import com.knte.core.time.control.TimeControlView
import com.knte.core.time.control.TimeSessionController
import com.knte.core.time.control.TimeIntentJournal
import com.knte.core.time.control.TimeSessionSource
import com.knte.core.time.projection.TimeRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

/** Owner-scoped lifecycle record exposed in [AuthorityState]. */
data class AuthorityOwnerState(
    val closed: Boolean = false,
    val handle: OpaqueHostHandle? = null,
    val op: String? = null,
    val targetId: String? = null,
    val closeReason: String? = null,
)

data class AuthorityState(
    val phase: AuthorityPhase = AuthorityPhase.Starting,
    val monitor: Boolean = false,
    val owners: Map<String, AuthorityOwnerState> = emptyMap(),
    val latestView: TimeControlView? = null,
)

/** Context-free authority reducer over a real [TimeSessionController] and a real
 *  [TimeIntentJournal]. No Android types; UI/platform layers wrap it.
 *
 *  Ownership model: each surface (page / monitor) holds an explicit owner token registered
 *  in-process. A prepare is issued to exactly one open owner; confirm/cancel are accepted
 *  only from that owner while it is still open, so a cross-surface confirm/cancel cannot
 *  silently take over an active op. Closing an owner cancels only its own still-unconfirmed
 *  local prepare; a failed close-cancel leaves the issued handle untouched so the pending
 *  intent stays discoverable (review/recovery path) — never auto-retried, acknowledged or
 *  cleared. No locks on the UI thread: the serial mutex is suspended on, never blocked.
 */
class TimeAuthorityCore private constructor(
    private val controller: TimeSessionController,
    private val journal: TimeIntentJournal,
    private val io: CoroutineDispatcher,
) {
    private class OwnerRecord {
        var closed = false
        var closeReason: String? = null
        var confirmAttempted = false
        var handle: OpaqueHostHandle? = null
        var op: String? = null
        var targetId: String? = null

        fun clearHandle() { handle = null; op = null; targetId = null; confirmAttempted = false }
    }

    private val serial = Mutex()
    private val lifetime = CoroutineScope(SupervisorJob() + io)
    private val ownersLock = Any()
    private val _state = MutableStateFlow(AuthorityState())
    val state: StateFlow<AuthorityState> = _state.asStateFlow()
    private val issued = java.util.concurrent.ConcurrentHashMap<String, SessionHandle>()
    private val owners = LinkedHashMap<String, OwnerRecord>()
    private var initialized = false

    suspend fun initialize() = serial.withLock {
        if (initialized) return@withLock
        withContext(io) {
            controller.setIntentJournal(journal)
            controller.recoverPendingIntents()
            publish(controller.reload())
        }
        initialized = true
    }

    suspend fun reload() { initialize(); serial.withLock { withContext(io) { publish(controller.reload()) } } }
    suspend fun viewSnapshot() { initialize(); serial.withLock { withContext(io) { publish(controller.viewSnapshot()) } } }

    suspend fun prepareStart(project: String, owner: String): OpaqueHostHandle = run { initialize(); serial.withLock {
        val rec = requireOpen(owner)
        if (ownedByAny()) throw TimeControlException("TIME_CONTROL_UNRESOLVED_OP", "已有待确认操作")
        withContext(NonCancellable) {
        setPhase(AuthorityPhase.Busy("准备开始"))
        try {
            val session = withContext(io) { controller.prepareStart(project) }
            issue(session, "start", rec)
        } catch (t: Throwable) { fail(t); throw t }
        }
    } }

    suspend fun prepareStop(recordId: String, owner: String): OpaqueHostHandle = run { initialize(); serial.withLock {
        val rec = requireOpen(owner)
        if (ownedByAny()) throw TimeControlException("TIME_CONTROL_UNRESOLVED_OP", "已有待确认操作")
        withContext(NonCancellable) {
        setPhase(AuthorityPhase.Busy("准备停止"))
        try {
            val session = withContext(io) { controller.prepareStop(recordId) }
            issue(session, "stop", rec)
        } catch (t: Throwable) { fail(t); throw t }
        }
    } }

    suspend fun confirm(handle: OpaqueHostHandle, owner: String): ConfirmResult = run { initialize(); serial.withLock {
        ensureReady(); val h = exact(handle, owner)
        rejectUncertain(owner)
        withContext(NonCancellable) {
        synchronized(ownersLock) { checkNotNull(owners[owner]).confirmAttempted = true }
        setPhase(AuthorityPhase.Busy("写入并重读"))
        try {
            val result = withContext(io) { controller.confirm(h) }
            // Confirmed (or replayed): the host handle is consumed either way.
            issued.remove(handle.value)
            synchronized(ownersLock) { owners[owner]?.takeIf { it.handle?.value == handle.value }?.clearHandle() }
            withContext(io) { publish(controller.reload()) }
            result
        } catch (t: Throwable) {
            // Only this controller refusal explicitly proves no append was attempted.
            if (t is TimeControlException && t.code == "TIME_BASELINE_CHANGED") {
                synchronized(ownersLock) { owners[owner]?.confirmAttempted = false }
                withContext(io) { publish(controller.viewSnapshot()) }
            } else {
                withContext(io) { publish(controller.viewSnapshot()) }
            }
            throw t
        }
        }
    } }

    suspend fun cancel(handle: OpaqueHostHandle, owner: String) = run { initialize(); serial.withLock {
        val h = exact(handle, owner)
        rejectUncertain(owner)
        withContext(io) { controller.cancel(h) }
        issued.remove(handle.value)
        synchronized(ownersLock) { owners[owner]?.takeIf { it.handle?.value == handle.value }?.clearHandle() }
        withContext(io) { publish(controller.viewSnapshot()) }
    } }

    suspend fun acknowledgeReview() = serial.withLock {
        if (hasUncertain()) throw TimeControlException("TIME_REVIEW_REQUIRED", "关闭清理或提交结果待核")
        withContext(io) { publish(controller.acknowledgeReviewAfterHumanResolution()) } }

    /** Owner-scoped lifecycle close. Serialized with in-flight prepare/confirm, so a
     *  concurrent confirm finishes first and leaves nothing to cancel. Cancels only this
     *  owner's exact still-unconfirmed local prepare; a failed cancel keeps the issued
     *  handle so the unresolved intent remains visible to review/recovery. */
    suspend fun closeOwner(owner: String, reason: String) = serial.withLock {
        withContext(NonCancellable) {
            val rec = synchronized(ownersLock) { owners[owner] }
            val handleValue = synchronized(ownersLock) { rec?.takeIf { !it.closed && !it.confirmAttempted }?.handle?.value }
            synchronized(ownersLock) {
                rec?.closed = true
                rec?.closeReason = reason
            }
            if (handleValue != null) {
                val session = issued[handleValue]
                var cancelled = false
                if (session != null) {
                    try { withContext(io) { controller.cancel(session) }; cancelled = true }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* keep issued; unresolved op stays discoverable; no retry/ack/clear */ }
                }
                if (cancelled) {
                    issued.remove(handleValue)
                    synchronized(ownersLock) { rec?.clearHandle() }
                }
            }
            withContext(io) { publish(controller.viewSnapshot()) }
        }
    }

    /** (Re)opens an owner token in this process. Reopening never resurrects a stale handle
     *  association from a previous owner generation. */
    fun openOwner(owner: String) {
        synchronized(ownersLock) {
            val rec = owners.getOrPut(owner) { OwnerRecord() }
            check(rec.handle == null && !rec.closed) { "owner token already used" }
            rec.closed = false
            rec.closeReason = null
        }
        _state.update { it.copy(owners = ownerSnapshot()) }
    }

    fun ownedHandle(owner: String): OpaqueHostHandle? = synchronized(ownersLock) {
        owners[owner]?.takeIf { !it.closed }?.handle
    }

    fun setMonitor(value: Boolean) { _state.update { it.copy(monitor = value) } }

    /** Application-owned fire-and-forget close (process lifetime > owner lifetime, e.g.
     *  ViewModel.onCleared or Service.onDestroy). Runs through the same serialized closeOwner
     *  so it is ordered against in-flight prepare/confirm/cancel of this owner. */
    fun closeOwnerDetached(owner: String, reason: String) {
        // Application-owned lifetime (longer than any single owner): failures are contained
        // here and never attributed to a caller scope.
        lifetime.launch {
            try { closeOwner(owner, reason) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* owner stays closed; issued handle remains discoverable */ }
        }
    }


    private fun ownedByAny(): Boolean = synchronized(ownersLock) {
        owners.values.any { !it.closed && it.handle != null }
    }

    private fun requireOpen(owner: String): OwnerRecord {
        ensureReady()
        return synchronized(ownersLock) {
            val r = owners[owner]
            if (r == null || r.closed) throw TimeControlException("TIME_OWNER_CLOSED", "owner未注册或已关闭，拒绝发起新prepare")
            if (r.handle != null) throw TimeControlException("TIME_OWNER_BUSY", "该owner已有未完成的prepare，先确认或取消")
            r
        }
    }

    private fun issue(session: SessionHandle, op: String, owner: OwnerRecord): OpaqueHostHandle {
        val value = UUID.randomUUID().toString()
        issued[value] = session
        synchronized(ownersLock) {
            owner.handle = OpaqueHostHandle(value)
            owner.op = op
            owner.targetId = session.targetId
        }
        setPhase(AuthorityPhase.Preparing(session, op))
        return OpaqueHostHandle(value)
    }

    /** Exact handle lookup plus explicit ownership check: confirm/cancel are refused for any
     *  owner that is closed or does not hold this handle. */
    private fun exact(handle: OpaqueHostHandle, owner: String): SessionHandle {
        val session = issued[handle.value]
            ?: throw TimeControlException("TIME_HANDLE_UNKNOWN", "未知或已撤销的host handle")
        val ownedValue = synchronized(ownersLock) {
            owners[owner]?.takeIf { !it.closed }?.handle?.value
        }
        if (ownedValue != handle.value) {
            throw TimeControlException("TIME_OWNER_MISMATCH", "该handle不属于当前owner，拒绝跨surface确认或取消")
        }
        return session
    }

    private fun rejectUncertain(owner: String) {
        if (hasUncertain() || synchronized(ownersLock) { owners[owner]?.confirmAttempted == true }) {
            throw TimeControlException("TIME_REVIEW_REQUIRED", "提交结果待核，不可重新确认或取消")
        }
    }

    private fun hasUncertain(): Boolean = synchronized(ownersLock) {
        owners.values.any { it.handle != null && (it.confirmAttempted || it.closed) }
    }

    private fun ensureReady() {
        if (hasUncertain()) throw TimeControlException("TIME_REVIEW_REQUIRED", "提交结果待核")
        check(initialized) { "time authority initialization not complete" }
        check(controller.hasIntentJournal) { "persistent intent journal not attached" }
    }

    /** Publishes a fresh view without dropping an owner's still-active prepare: while any
     *  open owner holds a handle, the phase stays Preparing for that session (review still
     *  wins — fail closed). */
    private fun publish(view: TimeControlView) {
        val held = synchronized(ownersLock) { owners.values.firstOrNull { !it.closed && it.handle != null } }
        val phase = when {
            hasUncertain() || view.reviewRequired || view.readError != null ->
                AuthorityPhase.Review(view, view.readError ?: "待人工核对")
            held != null -> {
                val session = checkNotNull(issued[held.handle!!.value])
                AuthorityPhase.Preparing(session, held.op ?: "")
            }
            else -> AuthorityPhase.Ready(view)
        }
        setPhase(phase, view)
    }

    private fun fail(t: Throwable) {
        val view = try { controller.viewSnapshot() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        setPhase(AuthorityPhase.Failed(view, t.message ?: t.javaClass.simpleName))
    }

    private fun setPhase(phase: AuthorityPhase, view: TimeControlView? = null) {
        _state.update { it.copy(phase = phase, owners = ownerSnapshot(), latestView = view ?: it.latestView) }
    }

    private fun ownerSnapshot(): Map<String, AuthorityOwnerState> = synchronized(ownersLock) {
        owners.mapValues { AuthorityOwnerState(it.value.closed, it.value.handle, it.value.op, it.value.targetId, it.value.closeReason) }
    }

    // ---- read-only views -----------------------------------------------------

    fun currentView(): TimeControlView? = _state.value.latestView
    fun currentHandle(value: String): OpaqueHostHandle? = value.takeIf { issued.containsKey(it) }?.let(::OpaqueHostHandle)

    /** Display-bound session lookup for an issued handle (real prepare timestamps come from
     *  here, never from another surface's phase). Mutations happen under the serial mutex;
     *  this read is best-effort presentation data only. */
    fun sessionOf(handle: OpaqueHostHandle): SessionHandle? = issued[handle.value]
    fun currentOpaqueHandle(): OpaqueHostHandle? = when (val p = _state.value.phase) {
        is AuthorityPhase.Preparing -> issued.entries.firstOrNull { it.value === p.handle }?.key?.let(::OpaqueHostHandle)
        else -> null
    }
    fun records(): List<TimeRecord> = currentView()?.records.orEmpty()

    companion object {
        /** Opens a core over real ledger + journal roots (temp-isolated in tests). */
        fun open(ledgerRoot: File, journalRoot: File, io: CoroutineDispatcher = Dispatchers.IO): TimeAuthorityCore {
            val source = TimeSessionSource.open(ledgerRoot)
            return TimeAuthorityCore(source.controller(), FileTimeIntentJournal(journalRoot), io)
        }
    }
}
