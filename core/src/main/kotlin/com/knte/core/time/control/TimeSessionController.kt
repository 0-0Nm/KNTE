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
 * scheduling/IO and observation. ONE controller instance per [TimeSessionSource] object is the
 * single command authority for page, service and overlay surfaces; surfaces never build their own
 * raw plans and never hold a second event writer. The singleton is per source OBJECT, not per
 * directory: opening the same directory twice yields two sources/controllers, so app-level single
 * ownership must be enforced by the app (single authority holder) — see API_CONTRACT.
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
 * - Once a durable intent journal is attached it cannot be replaced or detached: recovery
 *   blocking must not be bypassed through [setIntentJournal]. After attach, recovery must run
 *   (explicitly or via the lazy gate on the first business op) before any new business write.
 * - Recovery classification is three-way: (a) fully bound proof (event op/id/token plus the
 *   intent's own business fields matched against the real event) → read-only confirmed result,
 *   journal cleared; (b) strictly readable ledger without pending marker AND the exact event is
 *   genuinely absent → resolvable not-written intent (explicit DROP / RESUBMIT, never an
 *   automatic append); (c) everything else (token mismatch, field/target mismatch, corrupted
 *   intent, unreadable store or journal) → manualReviewRequired, NOT resolvable by any enum.
 * - A repeated confirm of an already-proven commit is answered read-only by matching the real
 *   event id and event token in the ledger; no second append is ever produced. The receipt is
 *   historical: runningAfter reflects the op semantics at its event time, never the current
 *   projection.
 * - Repository pending markers or an unknown write result put the controller into a
 *   conservative review state: business prepares/confirms are refused until a human resolves
 *   the store AND the app explicitly acknowledges. Read-only reload never unlocks anything.
 *   After the commit call has started, any exception that cannot prove "nothing was written"
 *   is treated as an UNKNOWN result (intent retained, review latched, never retried): only a
 *   read-only pre-commit baseline check yields a definite pre-write refusal.
 * - No new pause/duration/status schema: only the existing start/stop minute-grain ops.
 */

/** Injectable clock for exact date/minute, cross-day and clock-change tests. Business minute
 *  fields (date/start, end/end_date) always come from this clock; the repository still stamps
 *  its own audit ts (at_trust=unverified) — which is also derived from the injected clock here
 *  (same minute grain), so trust stays unverified and no wall clock is consulted directly. */
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
 *  implementation is app adapter responsibility; [InMemoryIntentJournal] is for tests only.
 *  PRODUCTION HOSTS MUST attach a persistent journal before the first business op; running
 *  without one is an explicitly unattached test/isolated mode with NO restart-unknown safety
 *  (recoverPendingIntents then returns an empty report and cannot prove anything). */
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
 *  proven committed and this result was derived read-only from the real ledger. [runningAfter]
 *  is the HISTORICAL fact of this op's own event (start → was running afterwards, stop → was
 *  not), never the current projection state; [revision] is the ledger revision observed at read
 *  time. */
data class ConfirmResult(
    val replayed: Boolean,
    val revision: String,
    val eventId: String,
    val token: String,
    val op: String,
    val recordId: String,
    val runningAfter: Boolean
)

/** Outcome of crash/process-recovery reconciliation of the app journal against the real ledger.
 *  [confirmed]: fully bound proven-committed intents (read-only stable results, journal cleared).
 *  [notWrittenNonces]: ONLY intents proven not-written by a strictly readable, pending-free
 *  ledger where the exact event id is genuinely absent; they block business ops until resolved
 *  via [TimeSessionController.resolveRecoveredIntent]. Intents that mismatch in any way are NOT
 *  listed here — they surface only as [manualReviewRequired] and cannot be resolved by any enum;
 *  a human must reconcile the store/journal and restart the process. */
data class RecoveryReport(
    val confirmed: List<ConfirmResult>,
    val notWrittenNonces: List<String>,
    val manualReviewRequired: Boolean
)

enum class RecoveredIntentDecision { DROP, RESUBMIT_CONFIRM_REQUIRED }

data class TimeRecoveryView(
    val notWritten: List<TimePendingIntent> = emptyList(),
    val manual: List<TimePendingIntent> = emptyList(),
)

/** Read-only view. [readError] is non-null when the store refused to load (never reported as empty). */
data class TimeControlView(
    val sourceLabel: String,
    val revision: String?,
    val records: List<TimeRecord>,
    val runningCount: Int,
    val reviewRequired: Boolean,
    val unresolvedOp: UnresolvedOpView?,
    val readError: String?,
    val recovery: TimeRecoveryView = TimeRecoveryView(),
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

    /** Store-level review latch: unreadable store, unknown write result, readback unconfirmed,
     *  journal clear/load failure. Cleared only by an explicit human acknowledgement. */
    private var reviewFlag = false

    /** Recovery state since the last reconcile: intents PROVEN not-written (resolvable) and
     *  intents that mismatch in any way (manual review only, never resolvable by an enum). */
    private val recoveredNotWritten = HashSet<String>()
    private val recoveredManual = HashSet<String>()
    private var recoveredDetails: Map<String, TimePendingIntent> = emptyMap()

    /** True once the attached journal has been classified by a full reconcile in this process. */
    private var reconciled = false

    /** True when a durable intent journal is attached. Production hosts MUST assert this is
     *  true before enabling business writes; without a journal restart-unknown is NOT covered. */
    val hasIntentJournal: Boolean get() = synchronized(lock) { journal != null }

    /** Attaches the app-owned durable intent journal (see [TimeIntentJournal]). Once a journal
     *  is attached it can never be replaced or detached: that would bypass recovery blocking.
     *  Re-attaching the same instance is a no-op. */
    fun setIntentJournal(j: TimeIntentJournal?) { synchronized(lock) {
        val cur = journal
        if (cur != null) {
            if (j !== cur) {
                throw TimeControlException("TIME_JOURNAL_ALREADY_ATTACHED",
                    "意图journal已挂接：不能替换或置空来绕过恢复阻塞（同实例重复挂接为无操作）")
            }
            return@synchronized
        }
        journal = j
        reconciled = j == null
    } }

    // ---- preparation -------------------------------------------------------

    /** Builds a start op. Refuses when a review state, an unresolved recovery intent or another
     *  unresolved op exists. The plan is validated at prepare time against the real ledger;
     *  nothing is written. If the ledger changed between the prepare-time snapshot and the
     *  plan's own baseline load, the prepare is refused (TIME_SNAPSHOT_DRIFT), never silently
     *  re-based. */
    fun prepareStart(
        project: String,
        tags: List<String> = emptyList(),
        note: String? = null,
        repeatId: String? = null
    ): SessionHandle = synchronized(lock) {
        ensureReconciledLocked()
        refuseReview()
        refuseUnresolved()
        val snapshot = requireLoad()
        val now = clock.now()
        val date = now.toLocalDate().toString()
        val start = now.format(HM)
        val eventId = UUID.randomUUID().toString()
        val recordId = "t-" + UUID.randomUUID().toString().replace("-", "")
        val plan = try {
            repository.planAppend(startEvent(eventId, recordId, date, start, project, tags, note, repeatId), clockStamp(now))
        } catch (e: Exception) {
            throw wrap(e, "TIME_PLAN_START_FAILED", "start计划未生成，未写入任何数据")
        }
        if (plan.baselineRevision != snapshot.revision) {
            throw TimeControlException("TIME_SNAPSHOT_DRIFT",
                "start计划基线与准备时快照不一致（期间发生外部写入），prepare废弃，未写入任何数据")
        }
        register(plan, op = "start", eventId = eventId, targetId = recordId,
            project = project, date = date, start = start, end = null, endDate = null,
            createdAt = now)
    }

    /** Builds a stop op for one explicitly chosen running record. With multiple running records
     *  the id is mandatory and validated; a wrong or unknown id is refused by name. The stop
     *  summary (project/date/start shown for approval) and the raw plan use the SAME prepare-time
     *  snapshot identity: if the plan's own baseline load sees a different revision, the prepare
     *  fails with TIME_SNAPSHOT_DRIFT and nothing is registered or written. */
    fun prepareStop(recordId: String): SessionHandle = synchronized(lock) {
        ensureReconciledLocked()
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
            repository.planAppend(stopEvent(eventId, recordId, end, endDate), clockStamp(now))
        } catch (e: Exception) {
            throw wrap(e, "TIME_PLAN_STOP_FAILED", "stop计划未生成，未写入任何数据")
        }
        if (plan.baselineRevision != snapshot.revision) {
            throw TimeControlException("TIME_SNAPSHOT_DRIFT",
                "stop计划基线与批准摘要快照不一致（期间发生外部写入），prepare废弃，未写入任何数据")
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
     *  throws. Acknowledgement never clears unresolved recovery intents: while any recovered
     *  intent (resolvable or manual) remains, this throws and the block stays. Never called
     *  automatically. */
    fun acknowledgeReviewAfterHumanResolution(): TimeControlView = synchronized(lock) {
        val snapshot = requireLoad()
        if (journal != null) {
            try {
                reconcileLocked()
            } catch (e: TimeControlException) {
                throw e
            } catch (e: Exception) {
                throw TimeControlException("TIME_REVIEW_REQUIRED", "意图journal不可读，保持待核", e)
            }
        }
        if (recoveredNotWritten.isNotEmpty() || recoveredManual.isNotEmpty()) {
            throw TimeControlException("TIME_INTENT_PENDING_RESOLUTION",
                "尚有未决恢复意图（可决=${recoveredNotWritten.size} 待人工=${recoveredManual.size}），" +
                    "ack不能越过；先用resolveRecoveredIntent处理可决意图，不一致意图须人工核对账本后重启进程")
        }
        reviewFlag = false
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
     *  ledger after a process restart. Proven-committed intents (exact op/id/token plus the
     *  intent's own business fields matched against the real event) yield stable historical
     *  results and are cleared — read-only, the ledger is never written. Intents proven
     *  not-written (strictly readable ledger, no pending marker, exact event genuinely absent)
     *  block business ops until explicitly resolved. Everything else (token mismatch, field or
     *  target mismatch, corrupted intent, unreadable store/journal) is manualReviewRequired and
     *  cannot be resolved by any enum: a human reconciles the data and restarts the process.
     *  Requires [setIntentJournal]; without a journal nothing is recoverable and an empty report
     *  is returned (that unattached mode has NO restart-unknown safety; production hosts must
     *  attach a persistent journal). */
    fun recoverPendingIntents(): RecoveryReport = synchronized(lock) {
        reconcileLocked()
    }

    /** Explicit decision for an intent reported as PROVEN not-written. DROP abandons it;
     *  RESUBMIT_CONFIRM_REQUIRED expects the app to run a fresh prepare+confirm (no automatic
     *  replay ever). Both are non-writing resolutions of an intent whose event is genuinely
     *  absent from a strictly readable, pending-free ledger AT DECISION TIME: the resolution
     *  always re-reconciles from the real journal and ledger first, so a stale absence proof
     *  (event committed meanwhile by another writer) never clears the intent — the event is
     *  then judged on current evidence (proven-committed is cleared read-only, mismatch goes
     *  manual) and the enum decision itself is refused (TIME_INTENT_UNKNOWN). Mismatched/
     *  uncertain intents can never be resolved here: manual review + process restart only. */
    fun resolveRecoveredIntent(nonce: String, decision: RecoveredIntentDecision) = synchronized(lock) {
        require(decision != null) { "decision required" }
        // Re-verify against the REAL journal and a strictly readable ledger before acting on
        // any earlier classification: a previous "absent" proof is stale the moment another
        // writer committed that event. Journal read failure, an unreadable store (pending
        // marker) or any reclassification to manual blocks the resolution here; each intent
        // is judged on its own current evidence, never one DROP for a whole stale batch.
        if (journal != null) {
            reconcileLocked()
        }
        if (nonce in recoveredManual) {
            throw TimeControlException("TIME_INTENT_UNKNOWN",
                "该意图与账本事件/token/字段不一致或来源不可信：只能人工核对账本与journal后重启进程，" +
                    "不能用DROP/RESUBMIT解除")
        }
        if (nonce !in recoveredNotWritten) {
            throw TimeControlException("TIME_INTENT_UNKNOWN",
                "nonce不在可决恢复清单中（仅限『账本严格可读、无pending且事件确实缺失』的意图；先recoverPendingIntents）")
        }
        val j = journal
        if (j != null) {
            try {
                j.clear(nonce)
            } catch (e: Exception) {
                reviewFlag = true
                throw TimeControlException("TIME_JOURNAL_CLEAR_FAILED",
                    "意图清除失败，保持阻塞待核", e)
            }
        }
        recoveredNotWritten.remove(nonce)
        recoveredDetails = recoveredDetails - nonce
    }

    // ---- internals ---------------------------------------------------------

    /** Audit stamp = the injected clock captured ONCE at prepare time (same minute grain as the
     *  fields); no second clock read that could land in a different minute or fire test callbacks
     *  between unrelated reads. */
    private fun clockStamp(now: LocalDateTime): String = now.withSecond(0).withNano(0).format(STAMP)

    /** If a journal is attached and not yet classified in this process, classify it now: an
     *  attach with pending intents gates every business op until recovery has run. Journal read
     *  failure latches the store review and refuses the op (TIME_REVIEW_REQUIRED). */
    private fun ensureReconciledLocked() {
        if (journal == null || reconciled) return
        try {
            reconcileLocked()
        } catch (e: TimeControlException) {
            throw e
        } catch (e: Exception) {
            throw TimeControlException("TIME_REVIEW_REQUIRED",
                "意图journal不可读，业务操作被拒绝；人工核对后显式acknowledge", e)
        }
    }

    /** Full three-way classification. Raw journal-read exceptions propagate (explicit recovery
     *  callers see the real failure); the review latch is set either way. */
    private fun reconcileLocked(): RecoveryReport {
        val j = journal ?: run {
            reconciled = true
            return RecoveryReport(emptyList(), emptyList(), manualReviewRequired = false)
        }
        val intents = try {
            j.loadAll()
        } catch (e: Exception) {
            reviewFlag = true
            throw e
        }
        if (intents.isEmpty()) {
            recoveredNotWritten.clear()
            recoveredManual.clear()
            recoveredDetails = emptyMap()
            reconciled = true
            return RecoveryReport(emptyList(), emptyList(), manualReviewRequired = false)
        }
        val snapshot = try {
            repository.load()
        } catch (e: Exception) {
            // Store unreadable (pending marker/corruption): nothing can be proven.
            reviewFlag = true
            val nonces = intents.map { it.nonce }
            recoveredNotWritten.clear()
            recoveredManual.clear()
            recoveredDetails = intents.associateBy { it.nonce }
            recoveredManual.addAll(nonces)
            reconciled = true
            return RecoveryReport(emptyList(), emptyList(), manualReviewRequired = true)
        }
        val confirmedResults = mutableListOf<ConfirmResult>()
        val notWritten = mutableListOf<String>()
        val manual = mutableListOf<String>()
        for (intent in intents) {
            if (!intentIsWellFormed(intent)) {
                manual += intent.nonce
                continue
            }
            val ev = snapshot.findEvent(intent.eventId)
            if (ev == null) {
                // Strictly readable, pending-free ledger (repository.load() passed inventory)
                // and the exact event id is genuinely absent: the write did not land.
                notWritten += intent.nonce
                continue
            }
            if (ev.token() == intent.token && eventMatchesIntent(ev, intent)) {
                try {
                    j.clear(intent.nonce)
                    confirmedResults += ConfirmResult(
                        replayed = true, revision = snapshot.revision, eventId = intent.eventId,
                        token = intent.token, op = intent.op, recordId = intent.recordId,
                        runningAfter = intent.op == "start"
                    )
                } catch (e: Exception) {
                    reviewFlag = true
                    manual += intent.nonce
                }
            } else {
                // Event present but token or any bound field mismatched: never auto-judged,
                // never resolvable by DROP/RESUBMIT.
                manual += intent.nonce
            }
        }
        recoveredNotWritten.clear()
        recoveredNotWritten.addAll(notWritten)
        recoveredManual.clear()
        recoveredManual.addAll(manual)
        recoveredDetails = intents.filter { it.nonce in notWritten || it.nonce in manual }.associateBy { it.nonce }
        reconciled = true
        return RecoveryReport(confirmedResults, notWritten.toList(), manual.isNotEmpty())
    }

    /** The intent must carry a complete, self-consistent field set before any proof is attempted;
     *  a corrupted/blank intent is manual review material, never evidence. */
    private fun intentIsWellFormed(i: TimePendingIntent): Boolean {
        val core = i.nonce.isNotBlank() && (i.op == "start" || i.op == "stop") &&
            i.eventId.isNotBlank() && i.token.isNotBlank() && i.recordId.isNotBlank()
        return if (i.op == "start") core && i.project.isNotBlank() && i.date.isNotBlank() && i.start.isNotBlank()
        else core && i.end != null && i.end.isNotBlank()
    }

    /** Full binding check between the real event and the intent's own fields. The event token
     *  alone proves only that THIS event exists; the binding proves it is THE op the intent
     *  described (exact op, record id and, for starts, the exact project/date/start). */
    private fun eventMatchesIntent(ev: TimeEvent, intent: TimePendingIntent): Boolean = when (intent.op) {
        "start" -> ev.op == "start" &&
            ev.text("id") == intent.recordId &&
            ev.text("project") == intent.project &&
            ev.text("date") == intent.date &&
            ev.text("start") == intent.start
        "stop" -> ev.op == "stop" &&
            ev.text("id") == intent.recordId &&
            ev.text("end") == intent.end &&
            ev.text("end_date") == intent.endDate
        else -> false
    }

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
        refuseReview()
        ensureReconciledLocked()
        // Post-reconcile gate: classification during ensureReconciled may have just revealed
        // unresolved recovery intents (e.g. a journal attached after this handle was prepared).
        // Re-check AFTER reconciliation completes and BEFORE any journal intent is saved or any
        // ledger write starts, so late-attached unresolved intents block the business write.
        refuseReview()
        val j = journal
        if (j != null) {
            j.save(TimePendingIntent(
                nonce = rec.handle.nonce, op = rec.handle.op, eventId = rec.handle.eventId,
                token = rec.token, recordId = rec.handle.targetId, project = rec.handle.project,
                date = rec.handle.date, start = rec.handle.start, end = rec.handle.end,
                endDate = rec.handle.endDate, baselineRevision = rec.handle.baselineRevision,
                createdAt = rec.createdAt.toString()
            ))
        }
        // Read-only pre-commit guard: makes the baseline refusal a DEFINITE pre-write failure
        // (zero side effects, provable by a pure read). Anything after this point that cannot
        // prove "nothing was written" is treated as UNKNOWN, never as a definite not-written.
        val before = try {
            repository.load()
        } catch (e: Exception) {
            reviewFlag = true
            throw wrap(e, "TIME_REVIEW_REQUIRED", "提交前账本不可读，进入待核，未产生新事件")
        }
        if (before.revision != rec.plan.baselineRevision) {
            if (j != null) {
                try { j.clear(rec.handle.nonce) } catch (e: Exception) { reviewFlag = true }
            }
            throw TimeControlException("TIME_BASELINE_CHANGED",
                "基线已变化，零副作用，未产生新事件；op仍可cancel清理")
        }
        try {
            repository.commit(rec.plan, rec.plan.confirmation)
        } catch (e: TimeWriteUnknownStateException) {
            reviewFlag = true
            // Result unproven: keep the journal intent, refuse further business ops, never retry.
            throw TimeControlException("TIME_WRITE_RESULT_UNKNOWN", e.message ?: "unknown", e)
        } catch (e: Exception) {
            // Once the commit call has started, a generic exception (I/O, storage, anything that
            // is not the dedicated unknown-state signal) can NOT prove the write did not land.
            // Keep the write-ahead intent, latch review, never retry; no substring heuristics.
            reviewFlag = true
            throw TimeControlException("TIME_WRITE_RESULT_UNKNOWN",
                "提交阶段出现无法定性为写前失败的异常：按未证实处理，保留写前意图并进入待核", e)
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
        // The commit is proven: register the stable receipt BEFORE clearing the journal, so a
        // clear failure still leaves a resolvable state (review latched; repeated confirm is
        // answered read-only, never re-committed).
        completed[rec.handle.nonce] = rec
        trimCompleted()
        current = null
        if (j != null) {
            try {
                j.clear(rec.handle.nonce)
            } catch (e: Exception) {
                reviewFlag = true
                throw e
            }
        }
        return ConfirmResult(
            replayed = false, revision = after.revision, eventId = rec.handle.eventId,
            token = rec.token, op = rec.handle.op, recordId = rec.handle.targetId,
            runningAfter = target!!.running()
        )
    }

    /** Read-only stable answer for an already-proven commit: match the real event id and token
     *  in the current ledger. Never appends, never mutates state, never consults the CURRENT
     *  projection for the receipt: runningAfter is the op's own historical fact. */
    private fun replayStable(done: OpRecord): ConfirmResult {
        if (reviewFlag || recoveredNotWritten.isNotEmpty() || recoveredManual.isNotEmpty()) {
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
        return ConfirmResult(
            replayed = true, revision = snapshot.revision, eventId = done.handle.eventId,
            token = done.token, op = done.handle.op, recordId = done.handle.targetId,
            runningAfter = done.handle.op == "start"
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
        if (recoveredNotWritten.isNotEmpty() || recoveredManual.isNotEmpty()) {
            throw TimeControlException("TIME_REVIEW_REQUIRED",
                "存在未决恢复意图（可决=${recoveredNotWritten.size} 待人工=${recoveredManual.size}），" +
                    "业务准备被拒绝；先用resolveRecoveredIntent处理可决意图，其余人工核对后重启")
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
                reviewRequired = true, unresolvedOp = current?.handle?.let(::view), readError = readError,
                recovery = recoveryView())
        }
        val view = current?.handle?.let(::view)
        return TimeControlView(sourceLabel, snapshot.revision, snapshot.projection.records,
            snapshot.projection.runningCount,
            reviewRequired = reviewFlag || readError != null ||
                recoveredNotWritten.isNotEmpty() || recoveredManual.isNotEmpty(),
            unresolvedOp = view, readError = readError, recovery = recoveryView())
    }

    private fun recoveryView() = TimeRecoveryView(
        notWritten = recoveredDetails.values.filter { it.nonce in recoveredNotWritten },
        manual = recoveredDetails.values.filter { it.nonce in recoveredManual },
    )

    private fun view(h: SessionHandle) = UnresolvedOpView(
        nonce = h.nonce, op = h.op, eventId = h.eventId, targetId = h.targetId,
        project = h.project, date = h.date, start = h.start,
        end = h.end, endDate = h.endDate, baselineRevision = h.baselineRevision
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
 * ONE controller per source OBJECT so page/service/overlay sharing one source cannot create
 * competing writers. The singleton is per source object, NOT per directory: calling [open] twice
 * on the same directory yields two independent sources/controllers with separate instance ids
 * (they still refuse each other's handles). App-level single ownership (one authority holding
 * THE source for the app's store) must be enforced by the app wiring. The label is the real
 * canonical path, never fabricated. Production data range is isolated only.
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
