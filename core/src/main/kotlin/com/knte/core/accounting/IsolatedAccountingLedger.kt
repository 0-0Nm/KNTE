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
) {
    internal fun lineBytes(): ByteArray = AccountingCodec.encode(entryLine + "\n")
    val confirmation: String = AccountingCodec.hash(AccountingCodec.encode(
        "$rootId\n$dataset\n${beforeRevision ?: "CREATE"}\n$afterRevision\n$draftToken"
    ))
}

/** Read-only reversal proposal. NOT a write permission: reversal writes stay closed this
 * batch (数据契约_v2 R-void would mark the original row's voided_by, conflicting with the
 * append-only rule; no authoritative ruling exists yet). */
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
    class Confirmed(val snapshot: AccountingSnapshot, val appended: AccountingRow) : AccountingConfirmResult()
    class AlreadyWritten(val snapshot: AccountingSnapshot, val existing: AccountingRow) : AccountingConfirmResult()
}

/** LOCAL ISOLATED accounting store under <root>/db/accounting.jsonl (数据契约_v2 §二).
 * Append-only: no API rewrites or deletes history; corrections/reversals are read-side
 * projections only. Same-process callers cooperate on a per-path lock; BUSY/concurrent-writer
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
    fun voidWriteSupported(): Boolean = false

    fun load(): AccountingSnapshot = synchronized(mutex) { loadInternal() }

    private fun loadInternal(): AccountingSnapshot {
        safeRoot()
        if (!Files.exists(dbDir, LinkOption.NOFOLLOW_LINKS)) return AccountingSnapshot.parse(ByteArray(0))
        inventoryDb()
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return AccountingSnapshot.parse(ByteArray(0))
        return AccountingSnapshot.parse(readBounded(target))
    }

    /** Builds a single-use append plan for one manual confirm-card entry. */
    fun prepareManual(
        draft: AccountingDraft,
        ts: String = AccountingCodec.nowTimestamp(),
    ): AccountingAppendPlan = synchronized(mutex) {
        require(AccountingCodec.validTimestamp(ts)) { "TS_INVALID" }
        safeRoot()
        if (Files.exists(dbDir, LinkOption.NOFOLLOW_LINKS)) inventoryDb()
        val snapshot = loadInternal()
        check(!snapshot.unterminatedTail) { "WRITE_UNTERMINATED" }
        check(snapshot.rows.size < AccountingCodec.MAX_ROWS) { "CAPACITY" }
        check(snapshot.rows.none { it.ts == ts }) { "TIMESTAMP_BUSY" }
        val line = draft.encodeLine(ts)
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
        )
        states[plan.confirmation] = PlanState.PENDING
        plan
    }

    /** Cancels a pending plan; a cancelled plan can never be confirmed. */
    fun cancel(plan: AccountingAppendPlan): Unit = synchronized(mutex) {
        require(plan.rootId == root.toString()) { "ROOT_MISMATCH" }
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
        check(appended != null && appended.id == plan.id && appended.active) { "PRE_WRITE_VALIDATION_FAILED" }
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
        writeNew(attempt.resolve("receipt.txt"),
            AccountingCodec.encode("verified\n${plan.dataset}\n${plan.afterRevision}\n"))
        forceDir(attempt)
        phaseProbe("receipt-durable")
        Files.delete(pending)
        forceDir(dbDir)
        states[plan.confirmation] = PlanState.CONFIRMED
        val snapshot = AccountingSnapshot.parse(actual)
        val row = snapshot.byTimestamp(plan.ts)
        check(row != null && row.id == plan.id) { "READBACK_ROW_MISSING" }
        AccountingConfirmResult.Confirmed(snapshot, row)
    }

    /** Pure validation + proposal for reversing one active row (R-void). Never writes. */
    fun voidProposal(
        targetKey: String,
        sourceRevision: String? = null,
        reversalId: String = AccountingCodec.newRecordId(),
    ): AccountingVoidProposal = synchronized(mutex) {
        val snapshot = load()
        if (sourceRevision != null) check(snapshot.revision == sourceRevision) { "VOID_SOURCE_STALE" }
        val row = snapshot.find(targetKey) ?: throw IllegalArgumentException("VOID_TARGET_MISSING")
        check(row.valid && row.active && row.kind != AccountingKind.REVERSAL) { "VOID_TARGET_NOT_ACTIVE" }
        check(row.rawInput.isNotEmpty()) { "VOID_RAW_INPUT_MISSING" }
        val reversal = AccountingDraft.fromCents(
            id = reversalId, date = row.date, cents = -row.cents, category = row.category,
            location = row.location, note = "冲销", rawInput = row.rawInput,
        )
        AccountingVoidProposal(
            rootId = root.toString(), dataset = AccountingCodec.DATASET,
            sourceRevision = snapshot.revision, originalViewKey = row.viewKey,
            originalId = row.id, originalTs = row.ts, originalCents = row.cents,
            originalDate = row.date, originalCategory = row.category, reversal = reversal,
        )
    }

    /** Reversal WRITES are explicitly closed this batch: R-void would mark the original row's
     * voided_by, which contradicts the append-only rule until an authoritative decision. */
    fun appendVoidReversal(proposal: AccountingVoidProposal): Nothing = throw UnsupportedOperationException(
        "VOID_WRITE_UNSUPPORTED: contract R-void requires filling voided_by on the original row, " +
            "conflicting with append-only; no authoritative decision yet - use voidProposal() for " +
            "read-only validation instead"
    )

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
