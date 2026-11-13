package com.knte.core.accounting

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Two-phase append plan. Binds destination dataset, pre-state hash (null = file absent),
 * post-state hash, and the exact appended line; `confirmation` is the single-use key. */
class AccountingAppendPlan internal constructor(
    internal val rootId: String,
    val dataset: String,
    val id: String,
    val ts: String,
    val beforeRevision: String?,
    val afterRevision: String,
    val entryLine: String,
    val rowCountBefore: Int,
    internal val draftToken: String,
    /** MANUAL = ordinary active row; REVERSAL = opposite-amount append bound to one target. */
    val entryKind: AccountingEntryKind = AccountingEntryKind.MANUAL,
    /** Frozen original-target + whole-ledger baseline binding; null for MANUAL plans. */
    val reversal: AccountingReversalBinding? = null,
) {
    internal fun lineBytes(): ByteArray = AccountingCodec.encode(entryLine + "\n")
    val confirmation: String = AccountingCodec.hash(AccountingCodec.encode(
        "$rootId\n$dataset\n${beforeRevision ?: "CREATE"}\n$afterRevision\n$draftToken\n" +
            "$entryKind\n${reversal?.originalTs ?: "-"}\n${reversal?.sourceRevision ?: "-"}"
    ))
}

/** Frozen READ-ONLY reversal basis. It never writes and never grants write permission by
 * itself; render() produces the exact line in the existing isolated format. The legacy
 * R-void "rewrite original voided_by" interpretation stays closed (see appendVoidReversal);
 * the new capability appends this line through the standard AccountingAppendPlan chain. */
class AccountingVoidProposal internal constructor(
    val rootId: String,
    val dataset: String,
    val sourceRevision: String,
    val originalViewKey: String,
    val originalId: String,
    val originalTs: String,
    val originalCents: Long,
    val originalDate: String,
    val originalCategory: String,
    internal val reversal: AccountingDraft,
) {
    val proposedNote: String = reversal.note
    val proposedCents: Long = reversal.cents

    /** Exact line a FUTURE authorized writer would append, rendered with a write-time ts. */
    fun render(ts: String): String = reversal.encodeLine(ts, correctedOf = originalTs)
}

sealed class AccountingConfirmResult {
    class Confirmed(
        val snapshot: AccountingSnapshot,
        val appended: AccountingRow,
        /** Present only for an AccountingEntryKind.REVERSAL append; derived from independent readback. */
        val reversal: AccountingReversalOutcome? = null,
    ) : AccountingConfirmResult()
    class AlreadyWritten(val snapshot: AccountingSnapshot, val existing: AccountingRow) : AccountingConfirmResult()
}

/** LOCAL ISOLATED accounting store under <root>/db/accounting.jsonl (数据契约_v2 §二).
 * Append-only: no API rewrites or deletes history; corrections are read-side projections and
 * reversals APPEND an opposite-amount line resolved by that same existing projection.
 * Same-process callers cooperate on a per-path lock; BUSY/concurrent-writer
 * arbitration beyond hash-conflict detection is the parent's job. A durable pending marker
 * precedes every target write; before-images are retained in a journal; the written file is
 * always read back; failures never auto-retry. No external-CAS or power-loss guarantee. */
class IsolatedAccountingLedger internal constructor(
    directory: File,
    private val phaseProbe: (String) -> Unit,
) {
    constructor(directory: File) : this(directory, {})

    private val root: Path = directory.toPath().toAbsolutePath().normalize()
    private val dbDir: Path = root.resolve("db")
    private val target: Path = dbDir.resolve("accounting.jsonl")
    private val journalDir: Path = dbDir.resolve(".journal")
    private val pending: Path = dbDir.resolve("accounting.jsonl.pending")
    private val mutex = locks.computeIfAbsent(root.toString()) { Any() }
    private val states = HashMap<String, PlanState>()

    private enum class PlanState { PENDING, CONFIRMED, CANCELLED }

    init {
        synchronized(mutex) {
            safeRoot(allowMissing = true)
            Files.createDirectories(root)
            safeRoot()
        }
    }

    fun datasetPath(): Path = target
    fun journalPath(): Path = journalDir
    /** Legacy R-void (rewrite original voided_by) stays closed; not a formal schema ruling. */
    fun voidWriteSupported(): Boolean = false
    /** New append-only reversal capability (prepareReversal -> confirm). */
    fun reversalWriteSupported(): Boolean = true
    /** True while a durable *.pending marker makes the last write result UNKNOWN. */
    fun pendingReview(): Boolean = synchronized(mutex) { pendingMarkerExists() }
    /** Observable single-use plan lifecycle. */
    fun planState(plan: AccountingAppendPlan): AccountingPlanState = synchronized(mutex) {
        when {
            plan.rootId != root.toString() -> AccountingPlanState.UNKNOWN
            states[plan.confirmation] == null -> AccountingPlanState.UNKNOWN
            states[plan.confirmation] == PlanState.CONFIRMED -> AccountingPlanState.CONFIRMED
            states[plan.confirmation] == PlanState.CANCELLED -> AccountingPlanState.CANCELLED
            pendingMarkerExists() -> AccountingPlanState.PENDING_REVIEW
            else -> AccountingPlanState.PENDING
        }
    }

    fun load(): AccountingSnapshot = synchronized(mutex) { loadInternal() }

    private fun loadInternal(): AccountingSnapshot {
        safeRoot()
        if (!Files.exists(dbDir, LinkOption.NOFOLLOW_LINKS)) return AccountingSnapshot.parse(ByteArray(0))
        inventoryDb()
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return AccountingSnapshot.parse(ByteArray(0))
        return AccountingSnapshot.parse(readBounded(target))
    }

    /** THE single plan builder: every append (manual OR reversal) goes through this exact
     * pre-state hash / after-state hash / entry line binding and is registered PENDING. */
    private fun buildAppendPlan(
        draft: AccountingDraft,
        ts: String,
        correctedOf: String?,
        entryKind: AccountingEntryKind,
        reversalBinding: AccountingReversalBinding?,
    ): AccountingAppendPlan = synchronized(mutex) {
        require(AccountingCodec.validTimestamp(ts)) { "TS_INVALID" }
        safeRoot()
        if (Files.exists(dbDir, LinkOption.NOFOLLOW_LINKS)) inventoryDb()
        val snapshot = loadInternal()
        check(!snapshot.unterminatedTail) { "WRITE_UNTERMINATED" }
        check(snapshot.rows.size < AccountingCodec.MAX_ROWS) { "CAPACITY" }
        check(snapshot.rows.none { it.ts == ts }) { "TIMESTAMP_BUSY" }
        val line = draft.encodeLine(ts, correctedOf)
        val next = snapshot.rawBytes() + AccountingCodec.encode(line + "\n")
        check(next.size <= AccountingCodec.MAX_FILE_BYTES) { "SIZE_LIMIT" }
        val plan = AccountingAppendPlan(
            rootId = root.toString(),
            dataset = AccountingCodec.DATASET,
            id = draft.id,
            ts = ts,
            beforeRevision = if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) snapshot.revision else null,
            afterRevision = AccountingCodec.hash(next),
            entryLine = line,
            rowCountBefore = snapshot.rows.size,
            draftToken = draft.token,
            entryKind = entryKind,
            reversal = reversalBinding,
        )
        states[plan.confirmation] = PlanState.PENDING
        plan
    }

    /** Builds a single-use append plan for one manual confirm-card entry. */
    fun prepareManual(
        draft: AccountingDraft,
        ts: String = AccountingCodec.nowTimestamp(),
    ): AccountingAppendPlan = buildAppendPlan(
        draft, ts, null, AccountingEntryKind.MANUAL, null,
    )

    /** App-private isolated reversal: freeze one active ORIGINAL/CORRECTION target and the
     * WHOLE-ledger baseline, then return a SINGLE-USE AccountingAppendPlan. There is NO
     * bypass channel: the only write is confirm(). Fails closed before creating any plan. */
    fun prepareReversal(
        targetKey: String,
        reversalTs: String = AccountingCodec.nowTimestamp(),
        reversalId: String = AccountingCodec.newRecordId(),
        sourceRevision: String? = null,
    ): AccountingAppendPlan = synchronized(mutex) {
        require(AccountingCodec.validTimestamp(reversalTs)) { "TS_INVALID" }
        safeRoot()
        if (Files.exists(dbDir, LinkOption.NOFOLLOW_LINKS)) inventoryDb()
        val snapshot = loadInternal()
        check(!snapshot.unterminatedTail) { "WRITE_UNTERMINATED" }
        check(snapshot.rows.size < AccountingCodec.MAX_ROWS) { "CAPACITY" }
        check(snapshot.rows.none { it.ts == reversalTs }) { "TIMESTAMP_BUSY" }
        val candidate = snapshot.find(targetKey)
        if (candidate != null && candidate.kind == AccountingKind.REVERSAL)
            throw IllegalArgumentException("REVERSAL_TARGET_IS_REVERSAL")
        val proposal = frozenReversal(snapshot, targetKey, sourceRevision, reversalId)
        val target = checkNotNull(snapshot.find(proposal.originalViewKey)) { "VOID_TARGET_MISSING" }
        // Abnormal date / category / amount / identity gates (fail-closed).
        check(AccountingCodec.validDate(proposal.originalDate)) { "REVERSAL_DATE_INVALID" }
        check(proposal.originalCategory in AccountingCodec.CATEGORIES) { "REVERSAL_CATEGORY_INVALID" }
        check(proposal.proposedCents == Math.negateExact(proposal.originalCents)) { "REVERSAL_AMOUNT_MISMATCH" }
        check(proposal.proposedNote == "冲销") { "REVERSAL_NOTE_INVALID" }
        check(target.location == proposal.reversal.location && target.rawInput == proposal.reversal.rawInput) {
            "REVERSAL_IDENTITY_MISMATCH"
        }
        // Pre-validate the projection with the EXACT line the plan will publish.
        val line = proposal.render(reversalTs)
        val next = snapshot.rawBytes() + AccountingCodec.encode(line + "\n")
        check(next.size <= AccountingCodec.MAX_FILE_BYTES) { "SIZE_LIMIT" }
        val projected = AccountingSnapshot.parse(next)
        val origAfter = projected.byTimestamp(proposal.originalTs)
        val revAfter = projected.byTimestamp(reversalTs)
        check(origAfter != null && !origAfter.active && origAfter.voidedBy == reversalTs) {
            "REVERSAL_PROJECTION_NOT_CONSUMED"
        }
        check(revAfter != null && revAfter.valid && revAfter.kind == AccountingKind.REVERSAL &&
            revAfter.cents == proposal.proposedCents) { "REVERSAL_PROJECTION_INVALID" }
        val binding = AccountingReversalBinding(
            originalViewKey = proposal.originalViewKey,
            originalId = proposal.originalId,
            originalTs = proposal.originalTs,
            originalCents = proposal.originalCents,
            originalDate = proposal.originalDate,
            originalCategory = proposal.originalCategory,
            originalLocation = target.location,
            originalRawInput = target.rawInput,
            sourceRevision = snapshot.revision,
            baselineNet = snapshot.net,
            baselineEffectiveRows = snapshot.effectiveRows.size,
        )
        buildAppendPlan(
            proposal.reversal, reversalTs, proposal.originalTs,
            AccountingEntryKind.REVERSAL, binding,
        )
    }

    /** Shared frozen validation for voidProposal() and prepareReversal(). Never writes. */
    private fun frozenReversal(
        snapshot: AccountingSnapshot,
        targetKey: String,
        sourceRevision: String?,
        reversalId: String,
    ): AccountingVoidProposal {
        if (sourceRevision != null) check(snapshot.revision == sourceRevision) { "VOID_SOURCE_STALE" }
        val row = snapshot.find(targetKey) ?: throw IllegalArgumentException("VOID_TARGET_MISSING")
        check(row.kind != AccountingKind.REVERSAL) { "REVERSAL_TARGET_IS_REVERSAL" }
        check(row.valid && row.active) { "VOID_TARGET_NOT_ACTIVE" }
        check(row.rawInput.isNotEmpty()) { "VOID_RAW_INPUT_MISSING" }
        val opposite = try {
            Math.negateExact(row.cents)
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("REVERSAL_AMOUNT_INVALID")
        }
        check(opposite != 0L && Math.abs(opposite) <= AccountingCodec.MAX_CENTS) { "REVERSAL_AMOUNT_INVALID" }
        val reversal = AccountingDraft.fromCents(
            id = reversalId, date = row.date, cents = opposite, category = row.category,
            location = row.location, note = "冲销", rawInput = row.rawInput,
        )
        return AccountingVoidProposal(
            rootId = root.toString(), dataset = AccountingCodec.DATASET,
            sourceRevision = snapshot.revision, originalViewKey = row.viewKey,
            originalId = row.id, originalTs = row.ts, originalCents = row.cents,
            originalDate = row.date, originalCategory = row.category, reversal = reversal,
        )
    }

    /** Cancels a pending plan; a cancelled plan can never be confirmed. */
    fun cancel(plan: AccountingAppendPlan): Unit = synchronized(mutex) {
        require(plan.rootId == root.toString()) { "ROOT_MISMATCH" }
        check(!pendingMarkerExists()) { "PENDING_REVIEW_REQUIRED" }
        when (states[plan.confirmation]) {
            null -> throw IllegalStateException("PLAN_UNKNOWN")
            PlanState.CONFIRMED -> throw IllegalStateException("PLAN_ALREADY_CONFIRMED")
            PlanState.CANCELLED -> {}
            PlanState.PENDING -> states[plan.confirmation] = PlanState.CANCELLED
        }
    }

    fun confirm(plan: AccountingAppendPlan, confirmation: String): AccountingConfirmResult = synchronized(mutex) {
        require(plan.rootId == root.toString()) { "ROOT_MISMATCH" }
        require(confirmation == plan.confirmation) { "CONFIRMATION_MISMATCH" }
        when (states[plan.confirmation]) {
            null -> throw IllegalStateException("PLAN_UNKNOWN")
            PlanState.CONFIRMED -> throw IllegalStateException("PLAN_ALREADY_CONFIRMED")
            PlanState.CANCELLED -> throw IllegalStateException("PLAN_CANCELLED")
            PlanState.PENDING -> {}
        }
        check(!pendingMarkerExists()) { "PENDING_REVIEW_REQUIRED" }
        safeRoot()
        val dbExisted = Files.exists(dbDir, LinkOption.NOFOLLOW_LINKS)
        val beforeBytes: ByteArray
        if (plan.beforeRevision == null) {
            if (dbExisted) inventoryDb()
            check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "CREATE_CONFLICT" }
            beforeBytes = ByteArray(0)
        } else {
            check(dbExisted && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "REVISION_CONFLICT" }
            inventoryDb()
            beforeBytes = readBounded(target)
            check(AccountingCodec.hash(beforeBytes) == plan.beforeRevision) { "REVISION_CONFLICT" }
        }
        val next = beforeBytes + plan.lineBytes()
        check(AccountingCodec.hash(next) == plan.afterRevision) { "PLAN_CONTENT_MISMATCH" }
        val current = AccountingSnapshot.parse(beforeBytes)
        val existing = current.byId(plan.id)
        if (plan.entryKind == AccountingEntryKind.REVERSAL) {
            check(existing == null) { "REVERSAL_ID_CONFLICT" }
            val b = checkNotNull(plan.reversal) { "REVERSAL_BINDING_MISSING" }
            check(plan.beforeRevision != null) { "REVERSAL_BASELINE_MISSING" }
            check(AccountingCodec.hash(beforeBytes) == b.sourceRevision) { "REVERSAL_BASELINE_DRIFT" }
            val orig = current.byTimestamp(b.originalTs)
            check(orig != null && orig.valid && orig.active && orig.kind != AccountingKind.REVERSAL) {
                "REVERSAL_TARGET_STALE"
            }
            check(orig.id == b.originalId && orig.cents == b.originalCents &&
                orig.date == b.originalDate && orig.category == b.originalCategory &&
                orig.location == b.originalLocation && orig.rawInput == b.originalRawInput) {
                "REVERSAL_IDENTITY_DRIFT"
            }
            check(current.rows.none { it.valid && it.correctedOf == b.originalTs }) {
                "REVERSAL_TARGET_ALREADY_REVERSED"
            }
        }
        if (existing != null) {
            val rebuilt = runCatching {
                AccountingDraft.fromCents(
                    existing.id, existing.date, existing.cents, existing.category,
                    existing.location, existing.note, existing.rawInput
                ).token
            }.getOrNull()
            if (existing.valid && existing.source == AccountingCodec.SOURCE_MANUAL &&
                existing.correctedOf.isEmpty() && rebuilt == plan.draftToken
            ) {
                states[plan.confirmation] = PlanState.CONFIRMED
                return AccountingConfirmResult.AlreadyWritten(current, existing)
            }
            throw IllegalStateException("ID_CONFLICT")
        }
        val pre = AccountingSnapshot.parse(next)
        check(pre.rows.size <= AccountingCodec.MAX_ROWS) { "CAPACITY" }
        val appended = pre.byTimestamp(plan.ts)
        if (plan.entryKind == AccountingEntryKind.REVERSAL) {
            val b = checkNotNull(plan.reversal) { "REVERSAL_BINDING_MISSING" }
            check(appended != null && appended.id == plan.id && appended.valid &&
                appended.kind == AccountingKind.REVERSAL && appended.correctedOf == b.originalTs &&
                !appended.active) { "PRE_WRITE_VALIDATION_FAILED" }
            val consumed = pre.byTimestamp(b.originalTs)
            check(consumed != null && !consumed.active && consumed.voidedBy == plan.ts) {
                "REVERSAL_PRE_WRITE_NOT_CONSUMED"
            }
        } else {
            check(appended != null && appended.id == plan.id && appended.active) { "PRE_WRITE_VALIDATION_FAILED" }
        }
        if (!dbExisted) Files.createDirectories(dbDir)
        check(!Files.isSymbolicLink(journalDir) &&
            (!Files.exists(journalDir, LinkOption.NOFOLLOW_LINKS) ||
                Files.isDirectory(journalDir, LinkOption.NOFOLLOW_LINKS))) { "JOURNAL_CHANGED" }
        if (!Files.exists(journalDir, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(journalDir)
        val attempt = journalDir.resolve(UUID.randomUUID().toString())
        Files.createDirectory(attempt)
        writeNew(pending, AccountingCodec.encode(attempt.fileName.toString()))
        forceDir(dbDir)
        phaseProbe("pending-durable")
        if (plan.beforeRevision != null) {
            writeNew(attempt.resolve("before.jsonl"), beforeBytes)
            check(readBounded(attempt.resolve("before.jsonl")).contentEquals(beforeBytes)) { "BEFORE_IMAGE_UNCONFIRMED" }
        }
        writeNew(attempt.resolve("proposed.jsonl"), next)
        check(readBounded(attempt.resolve("proposed.jsonl")).contentEquals(next)) { "PROPOSAL_UNCONFIRMED" }
        forceDir(attempt)
        forceDir(journalDir)
        if (plan.beforeRevision == null) {
            check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "CREATE_CONFLICT" }
        } else {
            check(AccountingCodec.hash(readBounded(target)) == plan.beforeRevision) { "REVISION_CONFLICT" }
        }
        if (plan.beforeRevision == null) {
            writeNew(target, next)
        } else {
            val staged = attempt.resolve("publish.jsonl")
            writeNew(staged, next)
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        forceDir(dbDir)
        phaseProbe("target-published")
        val actual = readBounded(target)
        check(AccountingCodec.hash(actual) == plan.afterRevision && actual.contentEquals(next)) {
            "WRITE_RESULT_UNKNOWN"
        }
        // Independent readback: re-parse the exact on-disk bytes, never the in-memory preview.
        val readback = AccountingSnapshot.parse(actual)
        val receipt = receiptText(plan, readback)
        val outcome = if (plan.entryKind == AccountingEntryKind.REVERSAL)
            validateReversalReadback(plan, readback, receipt) else null
        writeNew(attempt.resolve("receipt.txt"), AccountingCodec.encode(receipt))
        forceDir(attempt)
        phaseProbe("receipt-durable")
        Files.delete(pending)
        forceDir(dbDir)
        states[plan.confirmation] = PlanState.CONFIRMED
        val row = readback.byTimestamp(plan.ts)
        check(row != null && row.id == plan.id) { "READBACK_ROW_MISSING" }
        AccountingConfirmResult.Confirmed(readback, row, outcome)
    }

    /** Durable receipt text; the first three lines keep the original verified format. */
    private fun receiptText(plan: AccountingAppendPlan, readback: AccountingSnapshot): String {
        val b = StringBuilder("verified\n").append(plan.dataset).append('\n')
            .append(plan.afterRevision).append('\n')
        if (plan.entryKind == AccountingEntryKind.REVERSAL) {
            val rev = plan.reversal!!
            b.append("entry=reversal\n")
                .append("original=").append(rev.originalTs).append('\n')
                .append("reversal=").append(plan.ts).append('\n')
                .append("net_before=").append(rev.baselineNet).append('\n')
                .append("net_after=").append(readback.net).append('\n')
                .append("report_net=").append(AccountingReports.month(readback, null).net).append('\n')
                .append("readback_revision=").append(readback.revision).append('\n')
        }
        return b.toString()
    }

    /** Verifies target consumption and report consistency from the independent readback. */
    private fun validateReversalReadback(
        plan: AccountingAppendPlan,
        readback: AccountingSnapshot,
        receipt: String,
    ): AccountingReversalOutcome {
        val b = checkNotNull(plan.reversal) { "REVERSAL_BINDING_MISSING" }
        val orig = readback.byTimestamp(b.originalTs)
        check(orig != null && orig.valid && !orig.active && orig.voidedBy == plan.ts) {
            "REVERSAL_READBACK_NOT_CONSUMED"
        }
        check(orig.id == b.originalId && orig.cents == b.originalCents &&
            orig.date == b.originalDate && orig.category == b.originalCategory &&
            orig.location == b.originalLocation && orig.rawInput == b.originalRawInput) {
            "REVERSAL_READBACK_IDENTITY"
        }
        val rev = readback.byTimestamp(plan.ts)
        check(rev != null && rev.valid && rev.kind == AccountingKind.REVERSAL &&
            rev.id == plan.id && rev.cents == Math.negateExact(b.originalCents) &&
            rev.correctedOf == b.originalTs && !rev.active) { "REVERSAL_READBACK_ROW" }
        val expectedNet = Math.subtractExact(b.baselineNet, b.originalCents)
        check(readback.net == expectedNet) { "REVERSAL_NET_MISMATCH" }
        check(readback.effectiveRows.size == b.baselineEffectiveRows - 1) { "REVERSAL_EFFECTIVE_COUNT" }
        val month = AccountingReports.month(readback, null)
        val monthlyNet = AccountingReports.monthlyTotals(readback).sumOf { it.net }
        val categoryNet = AccountingReports.categoryTotals(readback).sumOf { it.net }
        check(month.income == readback.income && month.expense == readback.expense) { "REVERSAL_REPORT_TOTALS" }
        check(month.net == expectedNet && monthlyNet == expectedNet && categoryNet == expectedNet) {
            "REVERSAL_REPORT_CONSUMPTION"
        }
        return AccountingReversalOutcome(
            originalTs = b.originalTs,
            originalId = b.originalId,
            originalCents = b.originalCents,
            reversalTs = plan.ts,
            reversalCents = rev.cents,
            originalActiveAfter = orig.active,
            reversalActiveAfter = rev.active,
            netBefore = b.baselineNet,
            netAfter = readback.net,
            reportNetAfter = month.net,
            readbackRevision = readback.revision,
            receipt = receipt,
        )
    }

    /** Read-only proposal (public semantics unchanged): validates one active target and
     * renders a FUTURE line, but never grants write permission by itself. */
    fun voidProposal(
        targetKey: String,
        sourceRevision: String? = null,
        reversalId: String = AccountingCodec.newRecordId(),
    ): AccountingVoidProposal = synchronized(mutex) {
        frozenReversal(loadInternal(), targetKey, sourceRevision, reversalId)
    }

    /** LEGACY R-void interpretation (rewrite the original row's voided_by) remains explicitly
     * closed per the earlier no-authoritative-ruling stance. This is NOT a formal schema
     * ruling: the NEW append-only reversal is a separate capability, see prepareReversal(). */
    fun appendVoidReversal(proposal: AccountingVoidProposal): Nothing = throw UnsupportedOperationException(
        "VOID_WRITE_UNSUPPORTED: contract R-void requires filling voided_by on the original row, " +
            "conflicting with append-only; no authoritative decision yet - use voidProposal() for " +
            "read-only validation instead"
    )

    private fun pendingMarkerExists(): Boolean {
        if (!Files.exists(dbDir, LinkOption.NOFOLLOW_LINKS)) return false
        Files.newDirectoryStream(dbDir).use { stream ->
            for (p in stream) if (p.fileName.toString().endsWith(".pending")) return true
        }
        return false
    }

    private fun inventoryDb() {
        check(!Files.isSymbolicLink(dbDir) && Files.isDirectory(dbDir, LinkOption.NOFOLLOW_LINKS)) { "DB_CHANGED" }
        Files.newDirectoryStream(dbDir).use { stream ->
            for (p in stream) {
                val n = p.fileName.toString()
                check(!n.endsWith(".pending")) { "PENDING_REVIEW_REQUIRED" }
                when (n) {
                    "accounting.jsonl" -> check(!Files.isSymbolicLink(p) &&
                        Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) { "TARGET_NOT_REGULAR" }
                    ".journal" -> check(!Files.isSymbolicLink(p) &&
                        Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) { "JOURNAL_CHANGED" }
                    else -> throw IllegalStateException("DB_ENTRY_UNEXPECTED")
                }
            }
        }
    }

    private fun safeRoot(allowMissing: Boolean = false) {
        var p: Path? = root
        while (p != null) {
            check(!Files.isSymbolicLink(p)) { "ROOT_LINK_REJECTED" }
            p = p.parent
        }
        if (!allowMissing || Files.exists(root, LinkOption.NOFOLLOW_LINKS))
            check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) &&
                root.toFile().canonicalFile.toPath() == root) { "ROOT_CHANGED" }
    }

    private fun readBounded(p: Path): ByteArray {
        check(!Files.isSymbolicLink(p) && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) { "DB_SPECIAL_FILE" }
        check(Files.size(p) <= AccountingCodec.MAX_FILE_BYTES) { "SIZE_LIMIT" }
        return Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS).use { input ->
            val out = ByteArrayOutputStream()
            val block = ByteArray(8192)
            while (true) {
                val n = input.read(block)
                if (n < 0) break
                check(out.size() + n <= AccountingCodec.MAX_FILE_BYTES) { "SIZE_LIMIT" }
                out.write(block, 0, n)
            }
            out.toByteArray()
        }
    }

    private fun writeNew(p: Path, bytes: ByteArray) {
        FileChannel.open(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun forceDir(p: Path) {
        FileChannel.open(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
    }
}
