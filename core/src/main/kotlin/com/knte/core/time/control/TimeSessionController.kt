package com.knte.core.time.control

import com.knte.core.time.format.TimeEvent
import com.knte.core.time.format.TimeJson
import com.knte.core.time.projection.TimeRecord
import com.knte.core.time.repository.IsolatedTimeRepository
import com.knte.core.time.repository.TimeAppendPlan
import com.knte.core.time.repository.TimeLedgerApi
import com.knte.core.time.repository.TimeSnapshot
import com.knte.core.time.repository.TimeWriteUnknownStateException
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Shared timer session controller over the existing [TimeLedgerApi].
 *
 * Pure Kotlin, synchronous, no Android and no coroutine dependency: the app adapter owns
 * scheduling/IO and observation. One controller instance per source is the single command
 * authority for page, service and overlay surfaces; surfaces never build their own raw plans
 * and never hold a second event writer.
 *
 * Safety model:
 * - Exactly one unresolved operation at a time per controller. A second prepare while one is
 *   unresolved is refused (double-click start cannot create two record ids). After a confirm
 *   resolves the op, a new explicit prepare+confirm is allowed, so genuinely intended parallel
 *   records are never blocked.
 * - Issued [SessionHandle]s are privately constructed, immutable, and registered by exact
 *   object identity plus an independent nonce bound to op/target/project/time and the private
 *   raw plan. Handles are never derived from a deterministic hash; a handle from another
 *   controller instance, or any forged copy of a handle's public fields, is rejected before
 *   any write. Cancelling a handle permanently invalidates it; new prepares issue new handles.
 * - The raw [TimeAppendPlan] never leaves the controller.
 * - Repository pending markers or an unknown write result put the controller into a
 *   conservative review state: business prepares/confirms are refused until a human resolves
 *   the store AND the app explicitly acknowledges. Read-only reload never unlocks anything.
 * - A repeated confirm of an already-proven commit is answered read-only by matching the real
 *   event id and event token in the ledger; no second append is ever produced.
 * - No new pause/duration/status schema: only the existing start/stop minute-grain ops.
 */

/** Injectable clock for exact date/minute, cross-day and clock-change tests. Business minute
 *  fields (date/start, end/end_date) always come from this clock; the repository still stamps
 *  its own audit ts (at_trust=unverified) from the wall clock. */
interface TimeClock {
    fun now(): LocalDateTime
}

class SystemTimeClock : TimeClock {
    override fun now(): LocalDateTime = LocalDateTime.now()
}

/** Domain failure with a stable TIME_* code. The ledger is never changed by a thrown op
 *  unless the code is TIME_WRITE_RESULT_UNKNOWN or TIME_READBACK_UNCONFIRMED (state then
 *  counts as unproven, never auto-retried). */
class TimeControlException(
    val code: String,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/** Optional app-owned durable intent journal. The controller calls [save] BEFORE the write and
 *  [clear] after a verified receipt or a definite (pre-write) failure. The durable storage
 *  implementation is app adapter responsibility; [InMemoryIntentJournal] is for tests only. */
data class TimePendingIntent(
    val nonce: String,
    val op: String,
    val eventId: String,
    val token: String,
    val recordId: String,
    val project: String,
    val date: String,
    val start: String,
    val end: String?,
    val endDate: String?,
    val baselineRevision: String,
    val createdAt: String
)

interface TimeIntentJournal {
    fun save(intent: TimePendingIntent)
    fun clear(nonce: String)
    fun loadAll(): List<TimePendingIntent>
}

class InMemoryIntentJournal : TimeIntentJournal {
    private val entries = LinkedHashMap<String, TimePendingIntent>()
    @Synchronized override fun save(intent: TimePendingIntent) { entries[intent.nonce] = intent }
    @Synchronized override fun clear(nonce: String) { entries.remove(nonce) }
    @Synchronized override fun loadAll(): List<TimePendingIntent> = entries.values.toList()
}

/** Immutable, privately constructed operation handle. Fields are display/binding data only;
 *  the raw plan stays private to the issuing controller. */
class SessionHandle internal constructor(
    val instanceId: String,
    val nonce: String,
    val op: String,
    val eventId: String,
    val targetId: String,
    val project: String,
    val date: String,
    val start: String,
    val end: String?,
    val endDate: String?,
    val baselineRevision: String
)

/** Result of [TimeSessionController.confirm]. [replayed]=true means the op had already been
 *  proven committed and this result was derived read-only from the real ledger. */
data class ConfirmResult(
    val replayed: Boolean,
    val revision: String,
    val eventId: String,
    val token: String,
    val op: String,
    val recordId: String,
    val runningAfter: Boolean
)

/** Outcome of crash/process-recovery reconciliation of the app journal against the real ledger. */
data class RecoveryReport(
    /** Intents whose event id+token were proven present: stable replayed results, journal cleared. */
    val confirmed: List<ConfirmResult>,
    /** Intents with no event and no pending marker: the write did not land. Business ops stay
     *  refused until the app calls [TimeSessionController.resolveRecoveredIntent]. */
    val notWrittenNonces: List<String>,
    /** True when the ledger could not be read (e.g. pending marker): human review required. */
    val manualReviewRequired: Boolean
)

enum class RecoveredIntentDecision { DROP, RESUBMIT_CONFIRM_REQUIRED }

/** Read-only view. [readError] is non-null when the store refused to load (never reported as empty). */
data class TimeControlView(
    val sourceLabel: String,
    val revision: String?,
    val records: List<TimeRecord>,
    val runningCount: Int,
    val reviewRequired: Boolean,
    val unresolvedOp: UnresolvedOpView?,
    val readError: String?
)

data class UnresolvedOpView(
    val nonce: String,
    val op: String,
    val eventId: String,
    val targetId: String,
    val project: String,
    val date: String,
    val start: String,
    val end: String?,
    val endDate: String?,
    val baselineRevision: String
)

/** Pure display helper: same-boot monotonic-millis elapsed estimate. Anchors are captured at
 *  observation time and never persisted, never compared across boots; no uptime/deep-sleep
 *  semantics are claimed and no new data schema is introduced. */
class SessionDisplayModel(private val monotonicMillis: () -> Long) {
    fun anchor(): Long = monotonicMillis()
    fun elapsedMillis(anchorMs: Long): Long {
        val d = monotonicMillis() - anchorMs
        return if (d < 0) 0L else d
    }
}

/**
 * The one app-private command authority for a time store. Obtain it from [TimeSessionSource]
 * so every surface shares the same instance and the same repository root.
 */
class TimeSessionController private constructor(
    private val repository: IsolatedTimeRepository,
    private val clock: TimeClock,
    val sourceLabel: String
) {

    companion object {
        private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        private const val COMPLETED_KEEP = 64
        internal fun create(repository: IsolatedTimeRepository, clock: TimeClock, sourceLabel: String) =
            TimeSessionController(repository, clock, sourceLabel)
    }

    /** Unique per controller instance: a handle issued here can never be confirmed or cancelled
     *  through a different controller, even over the same directory with identical params. */
    val instanceId: String = UUID.randomUUID().toString()

    @Volatile private var journal: TimeIntentJournal? = null

    private class OpRecord(
        val handle: SessionHandle,
        val plan: TimeAppendPlan,
        val token: String,
        val createdAt: LocalDateTime
    )

    private val lock = Any()
    private var current: OpRecord? = null
    private val completed = LinkedHashMap<String, OpRecord>()
    private val cancelled = HashSet<String>()
    private var reviewFlag = false
    private val recoveredNotWritten = HashSet<String>()

    /** Attaches the app-owned durable intent journal (see [TimeIntentJournal]). May be called
     *  again to replace or detach (null) the journal. */
    fun setIntentJournal(j: TimeIntentJournal?) { synchronized(lock) { journal = j } }

    // ---- preparation -------------------------------------------------------

    /** Builds a start op. Refuses when a review state or another unresolved op exists.
     *  The plan is validated at prepare time against the real ledger; nothing is written. */
    fun prepareStart(
        project: String,
        tags: List<String> = emptyList(),
        note: String? = null,
        repeatId: String? = null
    ): SessionHandle = synchronized(lock) {
        refuseReview()
        refuseUnresolved()
        val snapshot = requireLoad()
        val now = clock.now()
        val date = now.toLocalDate().toString()
        val start = now.format(HM)
        val eventId = UUID.randomUUID().toString()
        val recordId = "t-" + UUID.randomUUID().toString().replace("-", "")
        val plan = try {
            repository.planAppend(startEvent(eventId, recordId, date, start, project, tags, note, repeatId), clockStamp())
        } catch (e: Exception) {
            throw wrap(e, "TIME_PLAN_START_FAILED", "start计划未生成，未写入任何数据")
        }
        register(plan, op = "start", eventId = eventId, targetId = recordId,
            project = project, date = date, start = start, end = null, endDate = null,
            createdAt = now)
    }

    /** Builds a stop op for one explicitly chosen running record. With multiple running records
     *  the id is mandatory and validated; a wrong or unknown id is refused by name. */
    fun prepareStop(recordId: String): SessionHandle = synchronized(lock) {
        refuseReview()
        refuseUnresolved()
        val snapshot = requireLoad()
        val record = snapshot.projection.find(recordId)
            ?: throw TimeControlException("TIME_TARGET_NOT_RUNNING",
                "stop目标不存在：$recordId（多任务运行时必须显式给出记录id）")
        if (!record.running()) {
            throw TimeControlException("TIME_TARGET_NOT_RUNNING",
                "stop目标不在运行中：$recordId（当前状态 ${record.date} ${record.start}–${record.end ?: "-"}）")
        }
        val now = clock.now()
        val today = now.toLocalDate().toString()
        val end = now.format(HM)
        val endDate = if (record.date != today) today else null
        val eventId = UUID.randomUUID().toString()
        val plan = try {
            repository.planAppend(stopEvent(eventId, recordId, end, endDate), clockStamp())
        } catch (e: Exception) {
            throw wrap(e, "TIME_PLAN_STOP_FAILED", "stop计划未生成，未写入任何数据")
        }
        register(plan, op = "stop", eventId = eventId, targetId = recordId,
            project = record.project, date = record.date, start = record.start,
            end = end, endDate = endDate, createdAt = now)
    }

    // ---- confirm / cancel --------------------------------------------------

    /** Applies the prepared op after explicit user confirmation, then independently re-reads the
     *  ledger and verifies the real new event id, its token and the exact projected target.
     *  Repeating a confirm of a proven commit returns a stable read-only result. */
    fun confirm(handle: SessionHandle): ConfirmResult = synchronized(lock) {
        requireOwnInstance(handle)
        val rec = current
        if (rec != null && rec.handle === handle) return doCommit(rec)
        if (handle.nonce in cancelled) {
            throw TimeControlException("TIME_HANDLE_CANCELLED",
                "handle已取消，不能确认或复活（nonce=${handle.nonce}）")
        }
        val done = completed[handle.nonce]
        if (done != null && done.handle === handle) return replayStable(done)
        throw TimeControlException("TIME_HANDLE_UNKNOWN",
            "handle未被本控制器签发或已是伪造/过期副本，写前拒绝")
    }

    /** Drops an unresolved op. The handle is permanently invalidated: confirming it later fails,
     *  and preparing the same params again issues a fresh handle with a new nonce. */
    fun cancel(handle: SessionHandle): Unit = synchronized(lock) {
        requireOwnInstance(handle)
        val rec = current
        if (rec != null && rec.handle === handle) {
            current = null
            cancelled.add(handle.nonce)
            return@synchronized
        }
        if (handle.nonce in cancelled) {
            throw TimeControlException("TIME_HANDLE_CANCELLED", "handle已取消，重复取消被拒绝")
        }
        if (completed.containsKey(handle.nonce)) {
            throw TimeControlException("TIME_HANDLE_ALREADY_APPLIED",
                "该op已提交；确认结果请用confirm（只读稳定重放），不能cancel")
        }
        throw TimeControlException("TIME_HANDLE_UNKNOWN", "handle未被本控制器签发或已是伪造/过期副本")
    }

    // ---- read-only ---------------------------------------------------------

    /** Strict re-read: throws a TIME_* error when the store refuses to load (pending marker,
     *  corrupt shape). Never clears the review state and never retries business writes. */
    fun reload(): TimeControlView = synchronized(lock) {
        val snapshot = requireLoad()
        viewOf(snapshot, readError = null)
    }

    /** Tolerant read-only view: a failing load is reported through [TimeControlView.readError]
     *  and marks reviewRequired, instead of throwing. Never unlocks anything. */
    fun viewSnapshot(): TimeControlView {
        val captured = synchronized(lock) {
            try {
                viewOf(repository.load(), readError = null)
            } catch (e: Exception) {
                viewOf(null, readError = wrap(e, "TIME_STORE_READ_FAILED", "读取失败").code)
            }
        }
        return captured
    }

    /** Explicit acknowledgement after a human resolved the store (e.g. reviewed a pending
     *  marker). Reloads; if the store still refuses to load the review state stays and this
     *  throws. Never called automatically. */
    fun acknowledgeReviewAfterHumanResolution(): TimeControlView = synchronized(lock) {
        val snapshot = requireLoad()
        reviewFlag = false
        recoveredNotWritten.clear()
        // An op stuck unproven (unknown write result) is dead after human review: its handle
        // becomes permanently invalid and the user prepares a fresh op if still wanted. No
        // automatic retry or replay of the unproven op ever happens.
        val stuck = current
        if (stuck != null) {
            cancelled.add(stuck.handle.nonce)
            current = null
        }
        viewOf(snapshot, readError = null)
    }

    // ---- process recovery --------------------------------------------------

    /** Reconciles app-persisted intents (write-ahead, saved before each write) with the real
     *  ledger after a process restart. Proven-committed intents yield stable results and are
     *  cleared; not-written intents block business ops until explicitly resolved. Requires
     *  [setIntentJournal]; without a journal nothing is recoverable and an empty report is
     *  returned (the app adapter must attach a durable journal to keep restart-unknown safe). */
    fun recoverPendingIntents(): RecoveryReport = synchronized(lock) {
        val j = journal ?: return RecoveryReport(emptyList(), emptyList(), manualReviewRequired = false)
        val intents = j.loadAll()
        if (intents.isEmpty()) {
            recoveredNotWritten.clear()
            return RecoveryReport(emptyList(), emptyList(), manualReviewRequired = false)
        }
        val snapshot = try {
            repository.load()
        } catch (e: Exception) {
            reviewFlag = true
            return RecoveryReport(emptyList(), intents.map { it.nonce }, manualReviewRequired = true)
        }
        val confirmedResults = mutableListOf<ConfirmResult>()
        val notWritten = mutableListOf<String>()
        for (intent in intents) {
            val ev = snapshot.findEvent(intent.eventId)
            val target = snapshot.projection.find(intent.recordId)
            if (ev != null && ev.token() == intent.token &&
                (target != null && if (intent.op == "start") target.running() else !target.running())
            ) {
                confirmedResults += ConfirmResult(
                    replayed = true, revision = snapshot.revision, eventId = intent.eventId,
                    token = intent.token, op = intent.op, recordId = intent.recordId,
                    runningAfter = intent.op == "start"
                )
                j.clear(intent.nonce)
            } else if (ev == null) {
                notWritten += intent.nonce
            } else {
                // Event present but token or projected state mismatched: never auto-judged.
                notWritten += intent.nonce
                reviewFlag = true
            }
        }
        if (notWritten.isNotEmpty()) {
            reviewFlag = true
            recoveredNotWritten.clear()
            recoveredNotWritten.addAll(notWritten)
        }
        RecoveryReport(confirmedResults, notWritten, manualReviewRequired = false)
    }

    /** Explicit decision for an intent reported as not written. DROP abandons it;
     *  RESUBMIT_CONFIRM_REQUIRED expects the app to run a fresh prepare+confirm (no automatic
     *  replay ever). Either way the old intent is cleared and the block lifts when none remain. */
    fun resolveRecoveredIntent(nonce: String, decision: RecoveredIntentDecision) = synchronized(lock) {
        require(decision != null) { "decision required" }
        if (nonce !in recoveredNotWritten) {
            throw TimeControlException("TIME_INTENT_UNKNOWN",
                "nonce不在待决恢复清单中（先recoverPendingIntents，且清单为空时无需处理）")
        }
        val j = journal
        if (j != null) j.clear(nonce)
        recoveredNotWritten.remove(nonce)
        if (recoveredNotWritten.isEmpty()) reviewFlag = false
    }

    // ---- internals ---------------------------------------------------------

    /** Audit stamp = the injected clock at prepare time (same minute grain as the fields). */
    private fun clockStamp(): String = clock.now().withSecond(0).withNano(0).format(STAMP)

    private fun startEvent(
        eventId: String, recordId: String, date: String, start: String,
        project: String, tags: List<String>, note: String?, repeatId: String?
    ): TimeEvent {
        val fields = linkedMapOf<String, TimeJson.Value>(
            "id" to TimeJson.Value.Str(recordId),
            "date" to TimeJson.Value.Str(date),
            "start" to TimeJson.Value.Str(start),
            "project" to TimeJson.Value.Str(project)
        )
        if (tags.isNotEmpty()) fields["tags"] = TimeJson.Value.Arr(tags.map { TimeJson.Value.Str(it) })
        if (note != null) fields["note"] = TimeJson.Value.Str(note)
        if (repeatId != null) fields["repeat_id"] = TimeJson.Value.Str(repeatId)
        return TimeEvent("start", eventId, "", fields, emptyList())
    }

    private fun stopEvent(eventId: String, recordId: String, end: String, endDate: String?): TimeEvent {
        val fields = linkedMapOf<String, TimeJson.Value>(
            "id" to TimeJson.Value.Str(recordId),
            "end" to TimeJson.Value.Str(end)
        )
        if (endDate != null) fields["end_date"] = TimeJson.Value.Str(endDate)
        return TimeEvent("stop", eventId, "", fields, emptyList())
    }

    private fun register(
        plan: TimeAppendPlan,
        op: String,
        eventId: String,
        targetId: String,
        project: String,
        date: String,
        start: String,
        end: String?,
        endDate: String?,
        createdAt: LocalDateTime
    ): SessionHandle {
        val nonce = UUID.randomUUID().toString()
        val handle = SessionHandle(
            instanceId = instanceId, nonce = nonce, op = op, eventId = eventId,
            targetId = targetId, project = project, date = date, start = start,
            end = end, endDate = endDate, baselineRevision = plan.baselineRevision
        )
        current = OpRecord(handle, plan, plan.token, createdAt)
        return handle
    }

    private fun doCommit(rec: OpRecord): ConfirmResult {
        if (reviewFlag) {
            throw TimeControlException("TIME_REVIEW_REQUIRED",
                "控制器处于待核状态，业务写入被拒绝；人工核对后需显式acknowledgeReviewAfterHumanResolution")
        }
        val j = journal
        j?.save(TimePendingIntent(
            nonce = rec.handle.nonce, op = rec.handle.op, eventId = rec.handle.eventId,
            token = rec.token, recordId = rec.handle.targetId, project = rec.handle.project,
            date = rec.handle.date, start = rec.handle.start, end = rec.handle.end,
            endDate = rec.handle.endDate, baselineRevision = rec.handle.baselineRevision,
            createdAt = rec.createdAt.toString()
        ))
        try {
            repository.commit(rec.plan, rec.plan.confirmation)
        } catch (e: TimeWriteUnknownStateException) {
            reviewFlag = true
            // Result unproven: keep the journal intent, refuse further business ops, never retry.
            throw TimeControlException("TIME_WRITE_RESULT_UNKNOWN", e.message ?: "unknown", e)
        } catch (e: Exception) {
            // Definite pre-write failure (baseline changed, target state, capacity...): nothing
            // of this op landed; drop the write-ahead intent, the handle stays cancelable.
            j?.clear(rec.handle.nonce)
            throw wrap(e, "TIME_COMMIT_FAILED", "提交被仓库拒绝，未产生新事件")
        }
        val after = try {
            repository.load()
        } catch (e: Exception) {
            reviewFlag = true
            throw TimeControlException("TIME_READBACK_UNCONFIRMED",
                "提交后独立重读失败，结果未证实，进入待核", e)
        }
        val ev = after.findEvent(rec.handle.eventId)
        if (ev == null || ev.token() != rec.token) {
            reviewFlag = true
            throw TimeControlException("TIME_READBACK_UNCONFIRMED",
                "独立重读未找到真实新事件或token不匹配，结果未证实，进入待核")
        }
        val target = after.projection.find(rec.handle.targetId)
        val verified = when (rec.handle.op) {
            "start" -> target != null && target.running()
            "stop" -> target != null && !target.running()
            else -> false
        }
        if (!verified) {
            reviewFlag = true
            throw TimeControlException("TIME_READBACK_UNCONFIRMED",
                "独立重读的投影目标与本次op不一致，结果未证实，进入待核")
        }
        j?.clear(rec.handle.nonce)
        completed[rec.handle.nonce] = rec
        trimCompleted()
        current = null
        return ConfirmResult(
            replayed = false, revision = after.revision, eventId = rec.handle.eventId,
            token = rec.token, op = rec.handle.op, recordId = rec.handle.targetId,
            runningAfter = target!!.running()
        )
    }

    /** Read-only stable answer for an already-proven commit: match the real event id and token
     *  in the current ledger. Never appends, never mutates state. */
    private fun replayStable(done: OpRecord): ConfirmResult {
        if (reviewFlag) {
            throw TimeControlException("TIME_REVIEW_REQUIRED",
                "控制器处于待核状态；先人工核对并显式acknowledge后再查询稳定结果")
        }
        val snapshot = requireLoad()
        val ev = snapshot.findEvent(done.handle.eventId)
            ?: throw TimeControlException("TIME_HANDLE_NOT_CONFIRMED",
                "曾证实提交的event ${done.handle.eventId} 在账本中不存在，请人工核对")
        if (ev.token() != done.token) {
            throw TimeControlException("TIME_HANDLE_NOT_CONFIRMED",
                "event存在但token不匹配，不能当作本次提交的稳定结果")
        }
        val target = snapshot.projection.find(done.handle.targetId)
        return ConfirmResult(
            replayed = true, revision = snapshot.revision, eventId = done.handle.eventId,
            token = done.token, op = done.handle.op, recordId = done.handle.targetId,
            runningAfter = target?.running() ?: false
        )
    }

    private fun requireOwnInstance(handle: SessionHandle) {
        if (handle.instanceId != instanceId) {
            throw TimeControlException("TIME_HANDLE_FOREIGN_INSTANCE",
                "handle由另一控制器实例签发，本实例不得confirm/cancel")
        }
    }

    private fun refuseUnresolved() {
        val rec = current ?: return
        throw TimeControlException("TIME_CONTROL_UNRESOLVED_OP",
            "已有一个未决op（${rec.handle.op} 目标=${rec.handle.targetId} event=${rec.handle.eventId}），" +
                "先confirm或cancel；双击start不会产生第二个记录id")
    }

    private fun refuseReview() {
        if (reviewFlag) {
            throw TimeControlException("TIME_REVIEW_REQUIRED",
                "控制器处于待核状态（pending/未知结果），业务准备被拒绝；不自动解锁、不自动重试")
        }
    }

    private fun requireLoad(): TimeSnapshot = try {
        repository.load()
    } catch (e: Exception) {
        reviewFlag = true
        throw wrap(e, "TIME_STORE_READ_FAILED", "读取失败，未视为空库；不自动清理或重试")
    }

    private fun viewOf(snapshot: TimeSnapshot?, readError: String?): TimeControlView {
        if (snapshot == null) {
            return TimeControlView(sourceLabel, null, emptyList(), 0,
                reviewRequired = true, unresolvedOp = current?.handle?.let(::view), readError = readError)
        }
        val view = current?.handle?.let(::view)
        return TimeControlView(sourceLabel, snapshot.revision, snapshot.projection.records,
            snapshot.projection.runningCount, reviewRequired = reviewFlag || readError != null,
            unresolvedOp = view, readError = readError)
    }

    private fun view(h: SessionHandle) = UnresolvedOpView(
        nonce = h.nonce, op = h.op, eventId = h.eventId, targetId = h.targetId,
        project = h.project, date = h.date, start = h.start, end = h.end,
        endDate = h.endDate, baselineRevision = h.baselineRevision
    )

    private fun trimCompleted() {
        while (completed.size > COMPLETED_KEEP) {
            val first = completed.keys.first()
            completed.remove(first)
        }
    }

    private fun wrap(e: Exception, fallback: String, message: String): TimeControlException {
        val raw = e.message?.trim()
        val code = if (raw != null && raw.startsWith("TIME_")) {
            raw.substringBefore(' ').substringBefore(':')
        } else fallback
        return TimeControlException(code, "$message：${raw ?: e.javaClass.simpleName}", e)
    }
}

/**
 * Source factory for the shared controller. Validates the store root through
 * [IsolatedTimeRepository.open] (no symlinks, canonical path, no invented labels) and hands out
 * ONE controller per source so page/service/overlay cannot create competing writers. The label
 * is the real canonical path, never fabricated. Production data range is isolated only.
 */
class TimeSessionSource private constructor(
    private val directory: File,
    private val repository: IsolatedTimeRepository
) {

    companion object {
        /** Opens/validates the isolated store directory and returns the single-controller source. */
        fun open(directory: File): TimeSessionSource {
            return TimeSessionSource(directory, IsolatedTimeRepository.open(directory))
        }

        /** Test-only factory enabling repository readback fault injection. Never production. */
        internal fun openWithFaultInjection(directory: File, fault: () -> Boolean): TimeSessionSource {
            return TimeSessionSource(directory, IsolatedTimeRepository.openForFaultInjection(directory, fault))
        }
    }

    /** Real canonical path of the isolated store root (shown to surfaces as-is). */
    val label: String = directory.canonicalFile.path

    @Volatile private var controller: TimeSessionController? = null

    /** Returns THE controller of this source, creating it on first use with the given clock.
     *  Later calls return the same instance regardless of the clock argument. */
    fun controller(clock: TimeClock = SystemTimeClock()): TimeSessionController {
        controller?.let { return it }
        synchronized(this) {
            controller?.let { return it }
            return TimeSessionController.create(repository, clock, label).also { controller = it }
        }
    }
}

/** Internal accessor used only by tests to assert the private raw plan is never exposed. */
internal val SessionHandle.testOnlyRegisteredIn: String get() = instanceId
