package com.knte.core.accounting

/** Append entry role. MANUAL is a normal active row; REVERSAL is an opposite-amount
 * append-only line bound to one active ORIGINAL/CORRECTION target via corrected_of.
 * It is a plan classification, NOT a formal schema change: the JSONL row keeps the
 * existing isolated format (note=冲销, source=manual, corrected_of=<target ts>). */
enum class AccountingEntryKind { MANUAL, REVERSAL }

/** Frozen binding of one reversal append: exact target identity plus the WHOLE-ledger
 * baseline revision and derived totals captured when the plan was prepared. Immutable;
 * confirm() re-validates target consumption and report consistency against the
 * independent post-publish readback instead of trusting the in-memory proposal. */
class AccountingReversalBinding internal constructor(
    val originalViewKey: String,
    val originalId: String,
    val originalTs: String,
    val originalCents: Long,
    val originalDate: String,
    val originalCategory: String,
    val originalLocation: String,
    val originalRawInput: String,
    val sourceRevision: String,
    val baselineNet: Long,
    val baselineEffectiveRows: Int,
)

/** Result evidence for a confirmed reversal. Every field is derived from the independent
 * post-publish readback (AccountingSnapshot.parse(readBounded(target))) and the receipt
 * written for that append, never from the pre-write preview. */
class AccountingReversalOutcome internal constructor(
    val originalTs: String,
    val originalId: String,
    val originalCents: Long,
    val reversalTs: String,
    val reversalCents: Long,
    val originalActiveAfter: Boolean,
    val reversalActiveAfter: Boolean,
    val netBefore: Long,
    val netAfter: Long,
    val reportNetAfter: Long,
    val readbackRevision: String,
    val receipt: String,
)

/** Observable plan lifecycle. PENDING_REVIEW means a durable *.pending marker exists:
 * the last write result is UNKNOWN, replay is refused and history is never cleared. */
enum class AccountingPlanState { UNKNOWN, PENDING, CONFIRMED, CANCELLED, PENDING_REVIEW }
