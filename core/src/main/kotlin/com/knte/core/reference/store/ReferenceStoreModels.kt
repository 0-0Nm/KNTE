package com.knte.core.reference.store

import com.knte.core.reference.PickEventRecord
import com.knte.core.reference.ReferenceEntry

/**
 * App-private isolated reference store: value types and failure codes
 * (数据契约 v2 §七 domains, aggregate single-file event version).
 *
 * Hard boundaries (do not read past this):
 * - This store is NOT the formal multi-file reference source. The formal files are
 *   db/memo.jsonl, db/pitfall.jsonl, db/memo_pick.jsonl and db/pick_log.jsonl, owned by
 *   the formal system whose multi-file schema is not fully fixed yet.
 * - Unsupported by design: export/import/projection toward the formal multi-file layout,
 *   cross-file transactional witnessing, automatic AI same-pitfall merging, automatic
 *   count merge/split (user-managed relations only). See [ReferenceStoreUnsupported].
 * - The aggregate JSONL append is NOT a formal multi-file transaction and makes NO
 *   single-file-append atomicity claim. A partial/torn append is possible; its outcome is
 *   UNKNOWN and locks the store until explicit, human-directed handling. There is NO
 *   automatic recovery and NO automatic retry of an unresolved write.
 * - PRODUCTION writes with real material are explicitly UNSUPPORTED by this validation
 *   slice: [ReferenceStoreUnsupported.PRODUCTION_WRITES_SUPPORTED] is false and confirm()
 *   fails closed past a bounded before-image ceiling.
 * - Unknown top-level event keys are preserved with their original bytes; known nested
 *   structures stay closed schemas.
 * - No credentials, no network, no real user material, no permission APIs. The directory
 *   is expected to be an already app-private directory; isolation is the caller's duty.
 */
object ReferenceStoreCodes {
    const val LOCKED_UNKNOWN = "STORE_LOCKED_UNKNOWN"
    const val PENDING_UNRESOLVED = "STORE_PENDING_UNRESOLVED"
    const val NO_PENDING = "STORE_NO_PENDING"
    const val BASELINE_DRIFT = "STORE_BASELINE_DRIFT"
    const val DUPLICATE_ID = "STORE_DUPLICATE_ID"
    const val ENTRY_NOT_FOUND = "STORE_ENTRY_NOT_FOUND"
    const val WRONG_POOL = "STORE_WRONG_POOL"
    const val ILLEGAL_TRANSITION = "STORE_ILLEGAL_TRANSITION"
    const val PICK_REPLAY = "STORE_PICK_REPLAY"
    const val PLAN_INVALID = "STORE_PLAN_INVALID"
    /** A plan built for a different store root (root fingerprint mismatch). */
    const val FOREIGN_ROOT = "STORE_FOREIGN_ROOT"
    /** The explicit confirmation token does not match the plan. */
    const val CONFIRMATION_INVALID = "STORE_CONFIRMATION_INVALID"
    const val PLAN_CANCELLED = "STORE_PLAN_CANCELLED"
    const val PLAN_ALREADY_CONFIRMED = "STORE_PLAN_ALREADY_CONFIRMED"
    const val AUTO_RECOVERY_UNSUPPORTED = "STORE_AUTO_RECOVERY_UNSUPPORTED"
    const val CAPACITY = "STORE_CAPACITY"
    const val PRODUCTION_WRITE_UNSUPPORTED = "STORE_PRODUCTION_WRITE_UNSUPPORTED"
    const val PENDING_CORRUPT = "STORE_PENDING_CORRUPT"
    const val CANCELLED_CORRUPT = "STORE_CANCELLED_CORRUPT"
    const val RECEIPT_CORRUPT = "STORE_RECEIPT_CORRUPT"
    const val FILE_CORRUPT = "STORE_FILE_CORRUPT"
    const val APPEND_READBACK = "STORE_APPEND_READBACK_MISMATCH"
    const val RECEIPT_READBACK = "STORE_RECEIPT_READBACK_MISMATCH"
    const val IO = "STORE_IO"
    const val DIR_INVALID = "STORE_DIR_INVALID"
}

/**
 * Explicit, machine-checkable list of unsupported capabilities. Nothing here is silently
 * emulated: the store never claims multi-file atomicity, automatic recovery or production
 * readiness. Parent/App code can read [PRODUCTION_WRITES_SUPPORTED] directly.
 */
object ReferenceStoreUnsupported {
    const val AI_SAME_PITFALL = "STORE_UNSUPPORTED_AI_SAME_PITFALL"
    const val COUNT_MERGE_SPLIT = "STORE_UNSUPPORTED_COUNT_MERGE_SPLIT"
    const val FORMAL_EXPORT_IMPORT = "STORE_UNSUPPORTED_FORMAL_EXPORT_IMPORT"
    const val MULTI_FILE_TRANSACTION = "STORE_UNSUPPORTED_MULTI_FILE_TRANSACTION"
    const val PRODUCTION_WRITE = "STORE_UNSUPPORTED_PRODUCTION_WRITE"
    const val AUTO_RECOVERY = "STORE_UNSUPPORTED_AUTO_RECOVERY"

    /** Production writes with real material are explicitly NOT supported by this slice. */
    const val PRODUCTION_WRITES_SUPPORTED: Boolean = false

    val ALL: Set<String> = setOf(
        AI_SAME_PITFALL, COUNT_MERGE_SPLIT, FORMAL_EXPORT_IMPORT,
        MULTI_FILE_TRANSACTION, PRODUCTION_WRITE, AUTO_RECOVERY,
    )
}

/** Store-level failure with a stable machine-checkable [code] from [ReferenceStoreCodes]. */
class ReferenceStoreException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("$code: $message", cause)

/**
 * One immutable planned event. Only hashes, identifiers and tokens are public; the proposed
 * line itself is internal, so callers can neither read nor mutate pending bytes.
 *
 * The [token] binds the store [rootFingerprint], the exact event-base prefix (beforeHash,
 * beforeSize, beforeSha256), the exact line to append (suffixHash) and the exact resulting
 * chain head (afterHash), plus identity and timestamp. [confirmation] is a separate explicit
 * token: the public `confirm(plan, confirmation)` requires it, so a plan cannot be applied by
 * possession of the plan object alone and a foreign-root plan is rejected before any write.
 */
class ReferenceStorePlan internal constructor(
    internal val line: String,
    internal val rootFingerprint: String,
    val eventId: String,
    val op: String,
    val ts: String,
    val beforeHash: String,
    val beforeSize: Long,
    val beforeSha256: String,
    val suffixHash: String,
    val afterHash: String,
) {
    val token: String = ReferenceStoreCodec.tokenOf(
        rootFingerprint, eventId, op, ts, beforeHash, beforeSize, beforeSha256, suffixHash, afterHash,
    )

    /** Explicit confirmation token required by the public confirm() surface. */
    val confirmation: String = ReferenceStoreCodec.confirmationOf(rootFingerprint, token, beforeSha256)

    internal fun root(): String = rootFingerprint

    /** Human/audit summary: root, token, ids and hashes; never the proposed bytes. */
    fun describe(): String = ReferenceStoreCodec.planDescribe(this)

    override fun equals(other: Any?): Boolean =
        other is ReferenceStorePlan &&
            other.line == line &&
            other.rootFingerprint == rootFingerprint &&
            other.eventId == eventId &&
            other.op == op &&
            other.ts == ts &&
            other.beforeHash == beforeHash &&
            other.beforeSize == beforeSize &&
            other.beforeSha256 == beforeSha256 &&
            other.suffixHash == suffixHash &&
            other.afterHash == afterHash

    override fun hashCode(): Int = token.hashCode()

    override fun toString(): String =
        "ReferenceStorePlan(op=$op, eventId=$eventId, token=$token, root=${rootFingerprint.take(8)})"
}

/** Both anti-degradation queues, one value each pool (数据契约 v2 §7.4). */
data class ReferenceStoreQueues(
    val memo: List<String>,
    val pitfall: List<String>,
)

/** Read-only description of an unresolved pending proposal; no proposal bytes exposed. */
data class ReferenceStorePending(
    val rootFingerprint: String,
    val token: String,
    val eventId: String,
    val op: String,
    val ts: String,
    val beforeHash: String,
    val beforeSize: Long,
    val beforeSha256: String,
    val suffixHash: String,
    val afterHash: String,
)

/**
 * Immutable replayed view of the store. All collections are defensive copies: mutating a
 * snapshot can never mutate the store's internal state. When [hasPending] is true the
 * values describe the last good state only and MUST be read together with [pending].
 */
data class ReferenceStoreSnapshot(
    val entries: List<ReferenceEntry>,
    val recentQueues: ReferenceStoreQueues,
    val pickHistory: List<PickEventRecord>,
    val head: String,
    val eventCount: Int,
    val pending: ReferenceStorePending?,
    val hasPending: Boolean,
)

/** Read-only classification of an unresolved pending proposal. Never implies a write. */
enum class ReferencePendingClass {
    /** Observed head already equals the pending afterHash: the proposal landed. */
    APPLIED,
    /** Observed head still equals the pending beforeHash: no proposal bytes landed. */
    NOT_APPLIED,
    /** Neither: drift, torn tail or foreign bytes. Human handling required. */
    UNKNOWN,
}

/**
 * Result of the strictly read-only [classifyPending]. The store never appends the proposal,
 * never deletes the pending marker and never completes a receipt while classifying.
 */
data class ReferencePendingClassification(
    val pending: ReferenceStorePending,
    val observedHead: String,
    val observedSize: Long,
    val observedSha256: String,
    val beforeImageMatches: Boolean,
    val state: ReferencePendingClass,
) {
    /** Contract marker: classification performs zero writes. */
    val readOnly: Boolean get() = true
}
