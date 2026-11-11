package com.knte.core.reference.store

import com.knte.core.reference.PickEventRecord
import com.knte.core.reference.RandomSource
import com.knte.core.reference.RecentQueue
import com.knte.core.reference.ReferenceEntry
import com.knte.core.reference.ReferencePick
import com.knte.core.reference.ReferenceScope
import com.knte.core.reference.TurnBinding
import com.knte.core.time.format.TimeFields
import com.knte.core.time.format.TimeHasher
import com.knte.core.time.format.TimeJson
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDate

/** Test-only failure injection point names; see [ReferenceStoreFailureGate]. */
internal const val GATE_AFTER_BEFORE_IMAGE: String = "after_before_image"
internal const val GATE_AFTER_PENDING: String = "after_pending"
internal const val GATE_AFTER_APPEND: String = "after_append"
internal const val GATE_AFTER_READBACK: String = "after_readback"
internal const val GATE_AFTER_RECEIPT: String = "after_receipt"

/** Test-only deterministic failure injection; the store itself never installs one. */
internal fun interface ReferenceStoreFailureGate {
    fun failpoint(name: String)
}

internal object NoFailureGate : ReferenceStoreFailureGate {
    override fun failpoint(name: String) {}
}

/**
 * App-private isolated reference aggregate store (数据契约 v2 §七 domains).
 *
 * WHAT THIS IS: an append-only, single-file JSONL event base plus a plan/confirm/cancel write
 * gate, for ONE app-private directory. One event line carries a full memo/pitfall row change
 * (entry state + ts) or a full pick turn (pick log record + both recent queues). Rows are
 * never physically deleted: memo done / archived are forward events.
 *
 * WHAT THIS IS NOT (explicit, not hidden):
 * - NOT the formal multi-file reference source (db/memo.jsonl, db/pitfall.jsonl,
 *   db/memo_pick.jsonl, db/pick_log.jsonl); NO export/import/projection toward it.
 * - NOT a formal multi-file transaction and NOT a guaranteed-atomic single-file append.
 *   A partial/torn append is possible and yields an UNKNOWN outcome: the store locks and
 *   NEVER auto-recovers, auto-retries, re-appends the proposal or completes a receipt.
 *   Unknown top-level event keys are preserved with their original bytes.
 * - NO automatic AI same-pitfall judgement, NO count merge/split (user-managed only).
 * - PRODUCTION writes with real material are UNSUPPORTED: confirm() fails closed past a
 *   bounded before-image; see [ReferenceStoreUnsupported.PRODUCTION_WRITES_SUPPORTED].
 * - No credentials, no network, no real material, no permission APIs; single-process
 *   single-instance usage is assumed (method-level synchronization only, cooperative drift
 *   detection, not an external compare-and-swap).
 *
 * Integrity model (all verifiable):
 * - Every line pins prev_head; the chain root head = SHA-256 chain from the genesis.
 * - plan*() return immutable hash plans whose token binds the store ROOT FINGERPRINT, the
 *   exact before-image (head + byte size + sha256), the exact line to append (suffixHash)
 *   and the exact resulting chain head (afterHash), plus identity and timestamp.
 * - Public confirm(plan, confirmation) requires an explicit confirmation token and rejects a
 *   foreign-root plan before any write. confirm() re-reads the real ledger bytes and binds
 *   them to the plan baseline; external drift refuses and leaves foreign bytes untouched.
 * - Before any append, a real before-image file and the pending proposal are made durable
 *   (temp + ATOMIC_MOVE + fsync + readback; NO fallback to a plain move - failures fail closed).
 * - Any failure AFTER the pending landed -> unknown lock: plan/confirm/cancel refuse. The
 *   only pending operation is the strictly READ-ONLY [classifyPending]; it never appends,
 *   never deletes the pending marker, never completes a receipt and never unlocks.
 * - A pending proposal (in memory or on disk) blocks every new plan/confirm first, including
 *   replaying the very same plan. Snapshot may show the last good projection but MUST be read
 *   together with [ReferenceStoreSnapshot.hasPending] / [ReferenceStoreSnapshot.pending].
 * - cancel() durably records a plan token; that plan can never be confirmed afterwards,
 *   including after reopen. Cancellation is checked before any ledger write.
 */
class ReferenceStore private constructor(
    private val dir: Path,
    private val eventsPath: Path,
    private val pendingPath: Path,
    private val beforePath: Path,
    private val cancelledPath: Path,
    private val receiptPath: Path,
    private val rootFingerprint: String,
    private val gate: ReferenceStoreFailureGate,
) {
    private var state: RState = RState(
        ReferenceStoreCodec.GENESIS_HEAD,
        LinkedHashMap(),
        ReferenceStoreQueues(emptyList(), emptyList()),
    )
    private var pendingProposal: ParsedPending? = null
    private var locked: Boolean = false

    // ------------------------------------------------------------------ open

    companion object {
        /** Opens (creating if needed) one app-private isolated store directory. */
        fun open(dir: Path): ReferenceStore = openInternal(dir, NoFailureGate)

        /** Test-only open with a failure injection gate. */
        internal fun openForTest(dir: Path, gate: ReferenceStoreFailureGate): ReferenceStore =
            openInternal(dir, gate)

        private fun openInternal(dir: Path, gate: ReferenceStoreFailureGate): ReferenceStore {
            val root = dir.toAbsolutePath().normalize()
            // Symbolic root / ancestor links are refused before anything is created.
            var ancestor: Path? = root
            while (ancestor != null) {
                if (Files.isSymbolicLink(ancestor)) {
                    throw ReferenceStoreException(ReferenceStoreCodes.DIR_INVALID, "symbolic root ancestor: $ancestor")
                }
                ancestor = ancestor.parent
            }
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw ReferenceStoreException(ReferenceStoreCodes.DIR_INVALID, "not a directory: $root")
            }
            try {
                Files.createDirectories(root)
            } catch (e: IOException) {
                throw ReferenceStoreException(ReferenceStoreCodes.IO, "createDirectories($root)", e)
            }
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) ||
                root.toFile().canonicalFile.toPath() != root
            ) {
                throw ReferenceStoreException(ReferenceStoreCodes.DIR_INVALID, "canonical root mismatch: $root")
            }
            val store = ReferenceStore(
                dir = root,
                eventsPath = root.resolve(ReferenceStoreCodec.EVENTS_FILE),
                pendingPath = root.resolve(ReferenceStoreCodec.PENDING_FILE),
                beforePath = root.resolve(ReferenceStoreCodec.BEFORE_FILE),
                cancelledPath = root.resolve(ReferenceStoreCodec.CANCELLED_FILE),
                receiptPath = root.resolve(ReferenceStoreCodec.RECEIPT_FILE),
                rootFingerprint = ReferenceStoreCodec.rootFingerprintOf(root),
                gate = gate,
            )
            store.load()
            return store
        }
    }

    private fun load() {
        // Every store entry must be a real file/dir, never a symlink.
        listOf(eventsPath, pendingPath, beforePath, cancelledPath, receiptPath).forEach { assertNoSymlink(it) }
        if (!Files.exists(eventsPath, LinkOption.NOFOLLOW_LINKS)) {
            createEmptyFile(eventsPath)
            syncDir()
        }
        if (!Files.exists(receiptPath, LinkOption.NOFOLLOW_LINKS)) {
            createEmptyFile(receiptPath)
            syncDir()
        }
        if (!Files.exists(cancelledPath, LinkOption.NOFOLLOW_LINKS)) {
            createEmptyFile(cancelledPath)
            syncDir()
        }
        state = replayAll()
        validateReceiptFile()
        parseCancelled(readCancelledText()) // strict validation on open
        pendingProposal = readPendingFileOrNull()
        // A pending proposal from another root is foreign and fails closed.
        if (pendingProposal != null && pendingProposal!!.rootFingerprint != rootFingerprint) {
            throw ReferenceStoreException(ReferenceStoreCodes.PENDING_CORRUPT, "pending root fingerprint mismatch")
        }
        // NOTE: we do NOT set locked here. A reopened store with a pending marker reports
        // PENDING_UNRESOLVED (not LOCKED_UNKNOWN); either way all writes are blocked.
    }

    // ------------------------------------------------------------------ views / capabilities

    /** Stable root fingerprint that every plan token is bound to. */
    fun rootFingerprint(): String = rootFingerprint

    /** Explicit list of capabilities this validation slice does NOT provide. */
    fun unsupportedCapabilities(): Set<String> = ReferenceStoreUnsupported.ALL

    /** Production writes with real material are explicitly unsupported. */
    fun productionWritesSupported(): Boolean = ReferenceStoreUnsupported.PRODUCTION_WRITES_SUPPORTED

    /**
     * Immutable replayed view. All collections are defensive copies. When [hasPending] is
     * true the projection is the last GOOD state only and must be read with the pending field.
     * This method performs no replay/recovery; it never hides a locked unknown outcome.
     */
    @Synchronized
    fun snapshot(): ReferenceStoreSnapshot = ReferenceStoreSnapshot(
        entries = ArrayList(state.entries.values),
        recentQueues = ReferenceStoreQueues(ArrayList(state.queues.memo), ArrayList(state.queues.pitfall)),
        pickHistory = ArrayList(state.picks),
        head = state.head,
        eventCount = state.eventIds.size,
        pending = pendingProposal?.toPublic(),
        hasPending = pendingProposal != null || Files.exists(pendingPath, LinkOption.NOFOLLOW_LINKS),
    )

    // ------------------------------------------------------------------ plans

    @Synchronized
    fun planCreateMemo(
        id: String,
        title: String,
        ts: String,
        due: String? = null,
        world: String? = null,
        body: String? = null,
        tags: List<String> = emptyList(),
    ): ReferenceStorePlan {
        val stamp = TimeFields.stamp(ts)
        checkMutable()
        if (state.entries.containsKey(id)) {
            throw ReferenceStoreException(ReferenceStoreCodes.DUPLICATE_ID, id)
        }
        val entry = ReferenceEntry.Memo(
            id = id,
            title = title,
            createdTs = stamp,
            ts = stamp,
            due = due,
            done = false,
            archived = false,
            tags = tags,
            world = world,
            body = body,
        )
        return buildPlan(
            ReferenceStoreCodec.OP_CREATE_MEMO,
            id,
            stamp,
            listOf("entry" to ReferenceStoreCodec.entryJson(entry)),
        )
    }

    @Synchronized
    fun planCreatePitfall(
        id: String,
        title: String,
        ts: String,
        count: Int,
        world: String? = null,
        body: String? = null,
        tags: List<String> = emptyList(),
    ): ReferenceStorePlan {
        val stamp = TimeFields.stamp(ts)
        checkMutable()
        if (state.entries.containsKey(id)) {
            throw ReferenceStoreException(ReferenceStoreCodes.DUPLICATE_ID, id)
        }
        val entry = ReferenceEntry.Pitfall(
            id = id,
            title = title,
            count = count,
            createdTs = stamp,
            ts = stamp,
            archived = false,
            tags = tags,
            world = world,
            body = body,
        )
        return buildPlan(
            ReferenceStoreCodec.OP_CREATE_PITFALL,
            id,
            stamp,
            listOf("entry" to ReferenceStoreCodec.entryJson(entry)),
        )
    }

    @Synchronized
    fun planMemoDone(id: String, ts: String): ReferenceStorePlan {
        val stamp = TimeFields.stamp(ts)
        checkMutable()
        val before = existingMemo(id)
        if (before.done || before.archived) {
            throw ReferenceStoreException(
                ReferenceStoreCodes.ILLEGAL_TRANSITION,
                "memo $id done=${before.done} archived=${before.archived}",
            )
        }
        val after = before.copy(done = true, ts = stamp)
        return buildPlan(
            ReferenceStoreCodec.OP_MEMO_DONE,
            ReferenceStoreCodec.derivedEventId(id, ReferenceStoreCodec.OP_MEMO_DONE, stamp),
            stamp,
            listOf("entry" to ReferenceStoreCodec.entryJson(after)),
        )
    }

    @Synchronized
    fun planArchive(id: String, ts: String): ReferenceStorePlan {
        val stamp = TimeFields.stamp(ts)
        checkMutable()
        val before = state.entries[id]
            ?: throw ReferenceStoreException(ReferenceStoreCodes.ENTRY_NOT_FOUND, id)
        if (before.archived) {
            throw ReferenceStoreException(ReferenceStoreCodes.ILLEGAL_TRANSITION, "$id already archived")
        }
        return when (before) {
            is ReferenceEntry.Memo -> {
                val after = before.copy(archived = true, ts = stamp)
                buildPlan(
                    ReferenceStoreCodec.OP_MEMO_ARCHIVE,
                    ReferenceStoreCodec.derivedEventId(id, ReferenceStoreCodec.OP_MEMO_ARCHIVE, stamp),
                    stamp,
                    listOf("entry" to ReferenceStoreCodec.entryJson(after)),
                )
            }
            is ReferenceEntry.Pitfall -> {
                val after = before.copy(archived = true, ts = stamp)
                buildPlan(
                    ReferenceStoreCodec.OP_PITFALL_ARCHIVE,
                    ReferenceStoreCodec.derivedEventId(id, ReferenceStoreCodec.OP_PITFALL_ARCHIVE, stamp),
                    stamp,
                    listOf("entry" to ReferenceStoreCodec.entryJson(after)),
                )
            }
        }
    }

    /**
     * Plans one turn pick using the pure domain weights unchanged. The pick event line binds
     * the pick log record AND both recent queues after the draw in the same event line.
     *
     * Returns null when BOTH pools are empty under the scope: no fake sample is fabricated
     * and nothing is written. The same binding (same turn) can never pick twice.
     */
    @Synchronized
    fun planPick(
        binding: TurnBinding,
        today: LocalDate,
        random: RandomSource,
        ts: String,
        scope: ReferenceScope = ReferenceScope.all(),
    ): ReferenceStorePlan? {
        val stamp = TimeFields.stamp(ts)
        checkMutable()
        if (binding.eventId in state.pickIds) {
            throw ReferenceStoreException(ReferenceStoreCodes.PICK_REPLAY, binding.eventId)
        }
        val pick = ReferencePick.pickTurn(
            state.entries.values.toList(),
            scope,
            today,
            stamp,
            random,
            state.queues.memo,
            state.queues.pitfall,
            binding,
        ) ?: return null
        val record = PickEventRecord.of(pick)
        return buildPlan(
            ReferenceStoreCodec.OP_PICK,
            pick.eventId,
            stamp,
            listOf(
                "pick" to record.toJson(),
                "recent_after" to ReferenceStoreCodec.queuesJson(
                    ReferenceStoreQueues(pick.memoRecentAfter, pick.pitfallRecentAfter)
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ confirm / cancel

    /**
     * Public write gate: applies exactly the plan the store produced, and ONLY with its
     * explicit [confirmation] token. A foreign-root plan, a cancelled plan, a wrong token,
     * a pending marker or any baseline drift is refused before ANY byte is written.
     *
     * No single-file atomicity is claimed. Any failure after the pending proposal landed
     * raises with the store locked; the outcome may only be inspected with [classifyPending].
     */
    @Synchronized
    fun confirm(plan: ReferenceStorePlan, confirmation: String): ReferenceStoreSnapshot {
        // ORDER MATTERS: lock/pending, foreign root, confirmation, plan integrity, terminal
        // states (cancelled/already-confirmed) are all checked BEFORE any ledger drift read,
        // so a cancelled or foreign plan is terminal regardless of later external changes.
        checkMutableStateOnly()
        if (plan.root() != rootFingerprint) {
            throw ReferenceStoreException(
                ReferenceStoreCodes.FOREIGN_ROOT,
                "plan.root=${plan.root()} store.root=$rootFingerprint; refusing, bytes untouched",
            )
        }
        if (confirmation != plan.confirmation) {
            throw ReferenceStoreException(ReferenceStoreCodes.CONFIRMATION_INVALID, "confirmation token mismatch")
        }
        verifyPlanIntegrity(plan)
        if (plan.token in parseCancelled(readCancelledText())) {
            throw ReferenceStoreException(ReferenceStoreCodes.PLAN_CANCELLED, plan.token)
        }
        if (receiptHasToken(plan.token)) {
            throw ReferenceStoreException(ReferenceStoreCodes.PLAN_ALREADY_CONFIRMED, plan.token)
        }
        // Independent read of the real ledger bytes, bound to the plan before-image.
        // This deliberately does NOT trust the in-memory head alone.
        val currentBytes = checkMutableBaseline()
        val currentSize = currentBytes.size.toLong()
        val currentSha = TimeHasher.sha256(currentBytes)
        if (currentSize != plan.beforeSize || currentSha != plan.beforeSha256 || state.head != plan.beforeHash) {
            throw ReferenceStoreException(
                ReferenceStoreCodes.BASELINE_DRIFT,
                "ledger size=$currentSize sha=$currentSha head=${state.head} != plan before; refusing, external bytes untouched",
            )
        }
        // Production-scale / unbounded before-image is explicitly unsupported.
        if (plan.beforeSize > ReferenceStoreCodec.MAX_BEFORE_IMAGE_BYTES) {
            throw ReferenceStoreException(
                ReferenceStoreCodes.PRODUCTION_WRITE_UNSUPPORTED,
                "before-image ${plan.beforeSize}B exceeds bounded validation ceiling ${ReferenceStoreCodec.MAX_BEFORE_IMAGE_BYTES}B",
            )
        }
        val appendBytes = ReferenceStoreCodec.lineAppendBytes(plan.line)
        if (appendBytes.size > ReferenceStoreCodec.MAX_LINE_BYTES) {
            throw ReferenceStoreException(ReferenceStoreCodes.CAPACITY, "line too long")
        }
        if (currentSize + appendBytes.size > ReferenceStoreCodec.MAX_FILE_BYTES) {
            throw ReferenceStoreException(ReferenceStoreCodes.CAPACITY, "event file too large")
        }
        val pendingBytes = TimeJson.encodeUtf8Strict(ReferenceStoreCodec.pendingLine(plan))
        if (pendingBytes.size > ReferenceStoreCodec.MAX_PENDING_BYTES) {
            throw ReferenceStoreException(ReferenceStoreCodes.CAPACITY, "pending too large")
        }

        // 1) Real before-image journal, durable BEFORE the target is touched.
        writeMarkerAtomically(beforePath, currentBytes, ReferenceStoreCodec.BEFORE_TMP, "writeBeforeImage")
        gate.failpoint(GATE_AFTER_BEFORE_IMAGE) // fault BEFORE pending: no lock, no pending marker

        // 2) Pending proposal, durable. No ATOMIC_MOVE fallback: failure fails closed.
        writeMarkerAtomically(pendingPath, pendingBytes, ReferenceStoreCodec.PENDING_TMP, "writePending")

        // From here on the outcome of the append is UNKNOWN on any failure.
        try {
            gate.failpoint(GATE_AFTER_PENDING)
            appendBytes(eventsPath, appendBytes)
            gate.failpoint(GATE_AFTER_APPEND)
            val fresh = replayAll()
            if (fresh.head != plan.afterHash) {
                throw ReferenceStoreException(
                    ReferenceStoreCodes.APPEND_READBACK,
                    "head=${fresh.head} expected=${plan.afterHash}",
                )
            }
            state = fresh
            gate.failpoint(GATE_AFTER_READBACK)
            appendReceiptChecked(plan.token, plan.eventId, plan.op, plan.ts, plan.beforeHash, plan.afterHash)
            gate.failpoint(GATE_AFTER_RECEIPT)
            deletePending()
            deleteBeforeImageBestEffort()
        } catch (t: Throwable) {
            locked = true
            pendingProposal = ParsedPending(
                rootFingerprint = plan.root(),
                token = plan.token,
                eventId = plan.eventId,
                op = plan.op,
                ts = plan.ts,
                beforeHash = plan.beforeHash,
                beforeSize = plan.beforeSize,
                beforeSha256 = plan.beforeSha256,
                proposalLine = plan.line,
                suffixHash = plan.suffixHash,
                afterHash = plan.afterHash,
            )
            // No recovery, no retry, no receipt completion, no pending deletion.
            throw t
        }
        return snapshot()
    }

    /** Internal/test-only compatibility shim. The PUBLIC surface requires a confirmation token. */
    internal fun confirm(plan: ReferenceStorePlan): ReferenceStoreSnapshot = confirm(plan, plan.confirmation)

    /**
     * Cancels a plan durably. The token is recorded in a strict LF-terminated marker file, so
     * the plan can never be confirmed afterwards - including after reopening the store or
     * after the ledger drifts. Cancellation is checked before any ledger write.
     */
    @Synchronized
    fun cancel(plan: ReferenceStorePlan) {
        checkMutableStateOnly()
        if (plan.root() != rootFingerprint) {
            throw ReferenceStoreException(ReferenceStoreCodes.FOREIGN_ROOT, "cancel foreign root")
        }
        verifyPlanIntegrity(plan)
        val existing = parseCancelled(readCancelledText())
        if (plan.token in existing) {
            throw ReferenceStoreException(ReferenceStoreCodes.PLAN_CANCELLED, "already cancelled: ${plan.token}")
        }
        if (receiptHasToken(plan.token)) {
            throw ReferenceStoreException(ReferenceStoreCodes.PLAN_ALREADY_CONFIRMED, plan.token)
        }
        val updated = LinkedHashSet(existing).also { it += plan.token }
        val text = buildString { updated.forEach { append(ReferenceStoreCodec.cancelledLine(it)) } }
        writeMarkerAtomically(cancelledPath, TimeJson.encodeUtf8Strict(text), ReferenceStoreCodec.CANCELLED_TMP, "writeCancelled")
        if (parseCancelled(readCancelledText()) != updated) {
            throw ReferenceStoreException(ReferenceStoreCodes.IO, "cancelled readback mismatch")
        }
    }

    /**
     * Strictly READ-ONLY classification of an unresolved pending proposal.
     *
     * It independently reads the current ledger bytes, verifies the durable before-image,
     * replays the strict chain (when parseable) and reports APPLIED / NOT_APPLIED / UNKNOWN.
     * It NEVER appends the proposal, NEVER deletes the pending marker, NEVER completes a
     * receipt and NEVER unlocks the store. There is no automatic recovery in this task.
     */
    @Synchronized
    fun classifyPending(): ReferencePendingClassification {
        val pending = pendingProposal ?: readPendingFileOrNull()
            ?: throw ReferenceStoreException(ReferenceStoreCodes.NO_PENDING, "nothing to classify")
        if (pending.rootFingerprint != rootFingerprint) {
            throw ReferenceStoreException(ReferenceStoreCodes.PENDING_CORRUPT, "pending root fingerprint mismatch")
        }
        val bytes = readBytesChecked(eventsPath)
        val observedSize = bytes.size.toLong()
        val observedSha256 = TimeHasher.sha256(bytes)
        val beforeImageMatches = runCatching {
            val before = readBytesChecked(beforePath)
            before.size.toLong() == pending.beforeSize && TimeHasher.sha256(before) == pending.beforeSha256
        }.getOrDefault(false)
        val replay = runCatching { replayAll() }.getOrNull()
        val observedHead = replay?.head ?: "(unverifiable)"
        val classified = when {
            replay != null && replay.head == pending.afterHash -> ReferencePendingClass.APPLIED
            replay != null && replay.head == pending.beforeHash -> ReferencePendingClass.NOT_APPLIED
            observedSize == pending.beforeSize && observedSha256 == pending.beforeSha256 ->
                ReferencePendingClass.NOT_APPLIED
            else -> ReferencePendingClass.UNKNOWN
        }
        return ReferencePendingClassification(
            pending = pending.toPublic(),
            observedHead = observedHead,
            observedSize = observedSize,
            observedSha256 = observedSha256,
            beforeImageMatches = beforeImageMatches,
            state = classified,
        )
    }

    /**
     * Compatibility name retained ONLY as explicit unsupported: automatic recovery/retry is
     * forbidden in this task. Use [classifyPending] (read-only) and human-directed handling.
     */
    internal fun resolvePending(): Nothing = throw ReferenceStoreException(
        ReferenceStoreCodes.AUTO_RECOVERY_UNSUPPORTED,
        "resolvePending() is unsupported: no auto-recovery / no retry / no receipt completion. Use classifyPending().",
    )

    // ------------------------------------------------------------------ internals: plans

    private fun buildPlan(
        op: String,
        eventId: String,
        ts: String,
        payload: List<Pair<String, TimeJson.Value>>,
    ): ReferenceStorePlan {
        if (state.eventIds.size + 1 > ReferenceStoreCodec.MAX_EVENTS) {
            throw ReferenceStoreException(ReferenceStoreCodes.CAPACITY, "too many events")
        }
        val line = ReferenceStoreCodec.eventLine(op, eventId, ts, state.head, payload)
        val appendBytes = ReferenceStoreCodec.lineAppendBytes(line)
        if (appendBytes.size > ReferenceStoreCodec.MAX_LINE_BYTES) {
            throw ReferenceStoreException(ReferenceStoreCodes.CAPACITY, "line too long")
        }
        if (state.size + appendBytes.size > ReferenceStoreCodec.MAX_FILE_BYTES) {
            throw ReferenceStoreException(ReferenceStoreCodes.CAPACITY, "event file too large")
        }
        return ReferenceStorePlan(
            line = line,
            rootFingerprint = rootFingerprint,
            eventId = eventId,
            op = op,
            ts = ts,
            beforeHash = state.head,
            beforeSize = state.size,
            beforeSha256 = state.sha256,
            suffixHash = ReferenceStoreCodec.suffixHashOf(line),
            afterHash = ReferenceStoreCodec.nextHead(state.head, line),
        )
    }

    private fun verifyPlanIntegrity(plan: ReferenceStorePlan) {
        if (ReferenceStoreCodec.suffixHashOf(plan.line) != plan.suffixHash ||
            ReferenceStoreCodec.nextHead(plan.beforeHash, plan.line) != plan.afterHash ||
            ReferenceStoreCodec.tokenOf(
                plan.root(), plan.eventId, plan.op, plan.ts,
                plan.beforeHash, plan.beforeSize, plan.beforeSha256, plan.suffixHash, plan.afterHash,
            ) != plan.token ||
            ReferenceStoreCodec.confirmationOf(plan.root(), plan.token, plan.beforeSha256) != plan.confirmation
        ) {
            throw ReferenceStoreException(ReferenceStoreCodes.PLAN_INVALID, "plan hash/token/root binding mismatch")
        }
    }

    /**
     * Fail-closed mutability gate. It INDEPENDENTLY re-reads the current ledger and compares
     * its exact bytes against the in-memory baseline; it also checks a pending marker on disk
     * (not only in memory). Returns the fresh bytes so confirm() can bind them to the plan.
     */
    private fun checkMutableStateOnly() {
        if (locked) {
            throw ReferenceStoreException(
                ReferenceStoreCodes.LOCKED_UNKNOWN,
                "confirm outcome unknown; classifyPending() (read-only) + human handling required",
            )
        }
        if (pendingProposal != null || Files.exists(pendingPath, LinkOption.NOFOLLOW_LINKS)) {
            throw ReferenceStoreException(
                ReferenceStoreCodes.PENDING_UNRESOLVED,
                "pending proposal blocks new plans/confirms/cancels",
            )
        }
    }

    /** Full mutability gate incl. the independent ledger-baseline check; used by plan*(). */
    private fun checkMutable(): ByteArray {
        checkMutableStateOnly()
        val bytes = readBytesChecked(eventsPath)
        val size = bytes.size.toLong()
        val sha = TimeHasher.sha256(bytes)
        if (size != state.size || sha != state.sha256) {
            throw ReferenceStoreException(
                ReferenceStoreCodes.BASELINE_DRIFT,
                "ledger drifted from in-memory baseline (size=$size sha=$sha); refusing, bytes untouched",
            )
        }
        return bytes
    }

    /** Independent current-ledger read for confirm(): identical to the plan-time gate. */
    private fun checkMutableBaseline(): ByteArray = checkMutable()

    private fun existingMemo(id: String): ReferenceEntry.Memo {
        val entry = state.entries[id]
            ?: throw ReferenceStoreException(ReferenceStoreCodes.ENTRY_NOT_FOUND, id)
        return entry as? ReferenceEntry.Memo
            ?: throw ReferenceStoreException(ReferenceStoreCodes.WRONG_POOL, "$id is not a memo")
    }

    // ------------------------------------------------------------------ internals: replay

    private class RState(
        var head: String,
        val entries: LinkedHashMap<String, ReferenceEntry>,
        var queues: ReferenceStoreQueues,
    ) {
        var size: Long = 0
        var sha256: String = ""
        val picks = mutableListOf<PickEventRecord>()
        val eventIds = LinkedHashSet<String>()
        val pickIds = LinkedHashSet<String>()
    }

    /** Independent full re-read and strict replay of the event base (never trusts memory). */
    private fun replayAll(): RState {
        val bytes = readBytesChecked(eventsPath)
        val text = try {
            TimeJson.decodeUtf8Strict(bytes)
        } catch (e: Exception) {
            throw ReferenceStoreException(ReferenceStoreCodes.FILE_CORRUPT, "utf8: ${e.message}", e)
        }
        val result = RState(
            ReferenceStoreCodec.GENESIS_HEAD,
            LinkedHashMap(),
            ReferenceStoreQueues(emptyList(), emptyList()),
        )
        result.size = bytes.size.toLong()
        result.sha256 = TimeHasher.sha256(bytes)
        if (text.isEmpty()) return result
        if (!text.endsWith("\n")) {
            throw ReferenceStoreException(ReferenceStoreCodes.FILE_CORRUPT, "REF_NO_TRAILING_LF")
        }
        val lines = text.split('\n').dropLast(1)
        if (lines.size > ReferenceStoreCodec.MAX_EVENTS) {
            throw ReferenceStoreException(ReferenceStoreCodes.CAPACITY, "too many events")
        }
        for ((index, line) in lines.withIndex()) {
            val event = try {
                ReferenceStoreCodec.parseEventLine(line, result.head)
            } catch (e: ReferenceStoreException) {
                throw e
            } catch (e: RuntimeException) {
                throw ReferenceStoreException(ReferenceStoreCodes.FILE_CORRUPT, "line ${index + 1}: ${e.message}", e)
            }
            applyEvent(result, event)
        }
        return result
    }

    private fun applyEvent(state: RState, event: ParsedEvent) {
        // event.unknownKeys is deliberately unused: original line bytes are preserved on disk
        // (append-only) and never rewritten. Known nested structures remain closed schemas.
        when (event.op) {
            ReferenceStoreCodec.OP_CREATE_MEMO -> {
                val entry = event.entry as? ReferenceEntry.Memo
                    ?: throw corrupt("REF_OP_ENTRY:${event.op}")
                if (state.entries.containsKey(entry.id)) throw corrupt("REF_DUP_ID:${entry.id}")
                state.entries[entry.id] = entry
            }
            ReferenceStoreCodec.OP_CREATE_PITFALL -> {
                val entry = event.entry as? ReferenceEntry.Pitfall
                    ?: throw corrupt("REF_OP_ENTRY:${event.op}")
                if (state.entries.containsKey(entry.id)) throw corrupt("REF_DUP_ID:${entry.id}")
                state.entries[entry.id] = entry
            }
            ReferenceStoreCodec.OP_MEMO_DONE -> {
                val before = state.entries[event.entry!!.id] as? ReferenceEntry.Memo
                    ?: throw corrupt("REF_TARGET:${event.entry.id}")
                if (before.done || before.archived) throw corrupt("REF_ILLEGAL_TRANSITION:${before.id}")
                val expected = before.copy(done = true, ts = event.ts)
                if (event.entry != expected) throw corrupt("REF_AFTER_ROW:${before.id}")
                state.entries[before.id] = expected
            }
            ReferenceStoreCodec.OP_MEMO_ARCHIVE -> {
                val before = state.entries[event.entry!!.id] as? ReferenceEntry.Memo
                    ?: throw corrupt("REF_TARGET:${event.entry.id}")
                if (before.archived) throw corrupt("REF_ILLEGAL_TRANSITION:${before.id}")
                val expected = before.copy(archived = true, ts = event.ts)
                if (event.entry != expected) throw corrupt("REF_AFTER_ROW:${before.id}")
                state.entries[before.id] = expected
            }
            ReferenceStoreCodec.OP_PITFALL_ARCHIVE -> {
                val before = state.entries[event.entry!!.id] as? ReferenceEntry.Pitfall
                    ?: throw corrupt("REF_TARGET:${event.entry.id}")
                if (before.archived) throw corrupt("REF_ILLEGAL_TRANSITION:${before.id}")
                val expected = before.copy(archived = true, ts = event.ts)
                if (event.entry != expected) throw corrupt("REF_AFTER_ROW:${before.id}")
                state.entries[before.id] = expected
            }
            ReferenceStoreCodec.OP_PICK -> {
                val record = event.pick!!
                if (state.queues.memo != record.memoRecentBefore ||
                    state.queues.pitfall != record.pitfallRecentBefore
                ) throw corrupt("REF_RECENT_BEFORE:${event.eventId}")
                val memoAfter = RecentQueue.advance(record.memoRecentBefore, record.memo?.id)
                val pitfallAfter = RecentQueue.advance(record.pitfallRecentBefore, record.pitfall?.id)
                if (event.queuesAfter!!.memo != memoAfter || event.queuesAfter.pitfall != pitfallAfter) {
                    throw corrupt("REF_RECENT_AFTER:${event.eventId}")
                }
                state.queues = ReferenceStoreQueues(memoAfter, pitfallAfter)
                state.picks += record
                state.pickIds += record.eventId
            }
        }
        if (!state.eventIds.add(event.eventId)) throw corrupt("REF_EVENT_ID_DUP:${event.eventId}")
        state.head = ReferenceStoreCodec.nextHead(state.head, event.line)
    }

    private fun corrupt(detail: String): ReferenceStoreException =
        ReferenceStoreException(ReferenceStoreCodes.FILE_CORRUPT, detail)

    // ------------------------------------------------------------------ internals: pending / receipt / cancelled io

    private fun readPendingFileOrNull(): ParsedPending? {
        if (!Files.exists(pendingPath, LinkOption.NOFOLLOW_LINKS)) return null
        return parsePendingBytes(readBytesChecked(pendingPath))
    }

    private fun parsePendingBytes(bytes: ByteArray): ParsedPending {
        val text = try {
            TimeJson.decodeUtf8Strict(bytes)
        } catch (e: Exception) {
            throw ReferenceStoreException(ReferenceStoreCodes.PENDING_CORRUPT, "utf8: ${e.message}", e)
        }
        return ReferenceStoreCodec.parsePendingLine(text)
    }

    private fun readCancelledText(): String {
        if (!Files.exists(cancelledPath, LinkOption.NOFOLLOW_LINKS)) return ""
        val bytes = readBytesChecked(cancelledPath)
        return try {
            TimeJson.decodeUtf8Strict(bytes)
        } catch (e: Exception) {
            throw ReferenceStoreException(ReferenceStoreCodes.CANCELLED_CORRUPT, "utf8: ${e.message}", e)
        }
    }

    private fun parseCancelled(text: String): Set<String> = try {
        ReferenceStoreCodec.parseCancelled(text)
    } catch (e: ReferenceStoreException) {
        throw e
    } catch (e: RuntimeException) {
        throw ReferenceStoreException(ReferenceStoreCodes.CANCELLED_CORRUPT, e.message ?: "parse", e)
    }

    /**
     * Durable replacement of ONE marker/journal/before-image/cancelled file ONLY
     * (temp + ATOMIC_MOVE + fsync + staged + final readback). This is NOT the event append:
     * the event-line append is a separate APPEND operation with no atomicity guarantee.
     * There is NO fallback to a plain move: if ATOMIC_MOVE is unavailable the write fails
     * closed (never a non-atomic replacement).
     */
    private fun writeMarkerAtomically(path: Path, bytes: ByteArray, tmpName: String, label: String) {
        val tmp = dir.resolve(tmpName)
        try {
            assertNoSymlink(path)
            Files.deleteIfExists(tmp)
            FileChannel.open(
                tmp,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            val staged = Files.readAllBytes(tmp)
            if (!staged.contentEquals(bytes)) {
                throw IOException("$label staging readback mismatch")
            }
            // No fallback: ATOMIC_MOVE or nothing.
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDir()
            val finalBytes = readBytesChecked(path)
            if (!finalBytes.contentEquals(bytes)) {
                throw IOException("$label final readback mismatch")
            }
        } catch (e: IOException) {
            throw ReferenceStoreException(ReferenceStoreCodes.IO, label, e)
        }
    }

    private fun deletePending() {
        try {
            assertNoSymlink(pendingPath)
            Files.deleteIfExists(pendingPath)
            syncDir()
        } catch (e: IOException) {
            throw ReferenceStoreException(ReferenceStoreCodes.IO, "deletePending", e)
        }
    }

    /** Best-effort removal of the committed before-image; never fails an already proven write. */
    private fun deleteBeforeImageBestEffort() {
        try {
            assertNoSymlink(beforePath)
            Files.deleteIfExists(beforePath)
            syncDir()
        } catch (ignored: Exception) {
            // The append + receipt were already verified; a stale before-image is harmless.
        }
    }

    private fun appendReceiptChecked(
        token: String,
        eventId: String,
        op: String,
        ts: String,
        beforeHash: String,
        afterHash: String,
    ) {
        assertNoSymlink(receiptPath)
        appendBytes(
            receiptPath,
            TimeJson.encodeUtf8Strict(
                ReferenceStoreCodec.receiptLine(token, eventId, op, ts, beforeHash, afterHash)
            ),
        )
        val tail = readReceiptTailOrNull()
        if (tail == null || tail.token != token || tail.afterHash != afterHash) {
            throw ReferenceStoreException(
                ReferenceStoreCodes.RECEIPT_READBACK,
                "tail=${tail?.token ?: "none"} expected=$token",
            )
        }
    }

    private fun readReceiptTailOrNull(): ParsedReceipt? {
        val text = readReceiptText()
        if (text.isEmpty()) return null
        val lines = text.split('\n')
        val tail = lines[lines.size - 2]
        return ReferenceStoreCodec.parseReceiptLine(tail + "\n")
    }

    private fun receiptHasToken(token: String): Boolean {
        val text = readReceiptText()
        if (text.isEmpty()) return false
        return text.split('\n').dropLast(1).any { line ->
            runCatching { ReferenceStoreCodec.parseReceiptLine(line + "\n").token == token }.getOrDefault(false)
        }
    }

    private fun validateReceiptFile() {
        val text = readReceiptText()
        if (text.isEmpty()) return
        if (!text.endsWith("\n")) {
            throw ReferenceStoreException(ReferenceStoreCodes.RECEIPT_CORRUPT, "no trailing LF")
        }
        val lines = text.split('\n').dropLast(1)
        if (lines.size > ReferenceStoreCodec.MAX_EVENTS + 16) {
            throw ReferenceStoreException(ReferenceStoreCodes.RECEIPT_CORRUPT, "too many receipts")
        }
        lines.forEach { ReferenceStoreCodec.parseReceiptLine(it + "\n") }
    }

    private fun readReceiptText(): String {
        val bytes = readBytesChecked(receiptPath)
        return try {
            TimeJson.decodeUtf8Strict(bytes)
        } catch (e: Exception) {
            throw ReferenceStoreException(ReferenceStoreCodes.RECEIPT_CORRUPT, "utf8: ${e.message}", e)
        }
    }

    // ------------------------------------------------------------------ internals: file io

    private fun assertNoSymlink(path: Path) {
        if (Files.isSymbolicLink(path)) {
            throw ReferenceStoreException(ReferenceStoreCodes.DIR_INVALID, "symbolic entry refused: $path")
        }
    }

    private fun readBytesChecked(path: Path): ByteArray {
        assertNoSymlink(path)
        return try {
            Files.readAllBytes(path)
        } catch (e: IOException) {
            throw ReferenceStoreException(ReferenceStoreCodes.IO, "read($path)", e)
        }
    }

    private fun createEmptyFile(path: Path) {
        try {
            FileChannel.open(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel -> channel.force(true) }
        } catch (e: IOException) {
            throw ReferenceStoreException(ReferenceStoreCodes.IO, "create($path)", e)
        }
    }

    private fun appendBytes(path: Path, bytes: ByteArray) {
        try {
            assertNoSymlink(path)
            FileChannel.open(
                path,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        } catch (e: IOException) {
            throw ReferenceStoreException(ReferenceStoreCodes.IO, "append($path)", e)
        }
    }

    private fun syncDir() {
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { channel -> channel.force(true) }
        } catch (ignored: Exception) {
            // Directory fsync is best-effort; the files themselves are always forced.
        }
    }
}

/**
 * Parent/App-facing API: plans are built here, confirmed with an EXPLICIT confirmation token,
 * cancelled durably, and unresolved outcomes are only ever CLASSIFIED (read-only).
 *
 * This is an app-private isolated validation store, not the formal multi-file reference
 * source and not a production write path. See [ReferenceStoreUnsupported].
 */
class ReferenceStoreApi private constructor(private val store: ReferenceStore) {
    companion object {
        fun open(dir: Path): ReferenceStoreApi = ReferenceStoreApi(ReferenceStore.open(dir))
    }

    fun snapshot(): ReferenceStoreSnapshot = store.snapshot()
    fun rootFingerprint(): String = store.rootFingerprint()
    fun unsupportedCapabilities(): Set<String> = store.unsupportedCapabilities()
    fun productionWritesSupported(): Boolean = store.productionWritesSupported()

    fun planCreateMemo(
        id: String,
        title: String,
        ts: String,
        due: String? = null,
        world: String? = null,
        body: String? = null,
        tags: List<String> = emptyList(),
    ): ReferenceStorePlan = store.planCreateMemo(id, title, ts, due, world, body, tags)

    fun planCreatePitfall(
        id: String,
        title: String,
        ts: String,
        count: Int,
        world: String? = null,
        body: String? = null,
        tags: List<String> = emptyList(),
    ): ReferenceStorePlan = store.planCreatePitfall(id, title, ts, count, world, body, tags)

    fun planMemoDone(id: String, ts: String): ReferenceStorePlan = store.planMemoDone(id, ts)
    fun planArchive(id: String, ts: String): ReferenceStorePlan = store.planArchive(id, ts)

    fun planPick(
        binding: TurnBinding,
        today: LocalDate,
        random: RandomSource,
        ts: String,
        scope: ReferenceScope = ReferenceScope.all(),
    ): ReferenceStorePlan? = store.planPick(binding, today, random, ts, scope)

    /** The only public write gate: an explicit [confirmation] token is mandatory. */
    fun confirm(plan: ReferenceStorePlan, confirmation: String): ReferenceStoreSnapshot =
        store.confirm(plan, confirmation)

    fun cancel(plan: ReferenceStorePlan): Unit = store.cancel(plan)

    /** Strictly read-only classification; no auto-recovery and no retry. */
    fun classifyPending(): ReferencePendingClassification = store.classifyPending()
}
