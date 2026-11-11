package com.knte.core.habit

import com.knte.core.time.format.TimeJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Immutable append plan for one habit event. Binds route identity, op, event id, event token,
 *  baseline hash (null = file absent), suffix hash and after hash; `confirmation` is the single key
 *  the caller must echo back. Nothing is written when a plan is built. */
class HabitAppendPlan internal constructor(
    private val routeId: String,
    private val before: ByteArray,
    private val suffix: ByteArray,
    private val after: ByteArray,
    val op: String,
    val eventId: String,
    val token: String
) {
    val baselineRevision: String? = if (before.isEmpty()) null else HabitCodec.hash(before)
    val afterRevision: String = HabitCodec.hash(after)
    val suffixRevision: String = HabitCodec.hash(suffix)
    val replay: Boolean = suffix.isEmpty()

    val confirmation: String = HabitCodec.hash(
        TimeJson.encodeUtf8Strict(
            TimeJson.encode(
                TimeJson.Value.Obj(
                    listOf(
                        "route" to TimeJson.Value.Str(routeId),
                        "op" to TimeJson.Value.Str(op),
                        "eventId" to TimeJson.Value.Str(eventId),
                        "token" to TimeJson.Value.Str(token),
                        "baseline" to (baselineRevision?.let { TimeJson.Value.Str(it) } ?: TimeJson.Value.Null),
                        "suffix" to TimeJson.Value.Str(suffixRevision)
                    )
                )
            )
        )
    )

    internal fun route(): String = routeId
    internal fun beforeBytes() = before.clone()
    internal fun suffixBytes() = suffix.clone()
}

/** Thrown when a commit cannot be proven complete. The caller must re-read, never auto-retry. */
class HabitWriteUnknownStateException(message: String) : IOException(message)

/** LOCAL ISOLATED habit store under `<root>/db/habits.jsonl` (schema knte-habit-v1).
 *
 *  This is NOT the production source, NOT the formal §9.1 check-only file and NOT a dual-root
 *  target: it is a private, app-local isolated store the parent may wire later. No real data, no
 *  SAF, no permissions, no network, no notifications.
 *
 *  Durability contract (same discipline as the isolated time/accounting stores):
 *   - append-only: an append only grows the file at the tail, a prefix byte is never touched;
 *   - a durable `*.pending` marker is written BEFORE the target is touched; a crash keeps it and
 *     blocks every read until a human clears it;
 *   - before-image + suffix are journaled and read back before publication;
 *   - the written file is always read back; a mismatch raises
 *     [HabitWriteUnknownStateException] and is never retried, never "repaired";
 *   - same-process callers cooperate on a per-root lock; this is NOT an external compare-and-swap;
 *   - no business row is ever physically deleted and no pending state is auto-recovered.
 */
class HabitLedgerStore private constructor(
    private val root: Path,
    private val phaseProbe: (String) -> Unit,
    private val readbackFault: () -> Boolean
) {
    companion object {
        private val locks = ConcurrentHashMap<String, Any>()

        fun open(directory: File): HabitLedgerStore {
            val path = directory.toPath().toAbsolutePath().normalize()
            require(path.toString() == directory.absoluteFile.toPath().normalize().toString()) {
                "HABIT_STORE_PATH_INVALID"
            }
            var ancestor: Path? = path
            while (ancestor != null) {
                require(!Files.isSymbolicLink(ancestor)) { "HABIT_STORE_LINK_REJECTED" }
                ancestor = ancestor.parent
            }
            Files.createDirectories(path)
            require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "HABIT_STORE_NOT_DIRECTORY" }
            require(path.toFile().canonicalFile.toPath() == path) { "HABIT_STORE_ROOT_CHANGED" }
            return HabitLedgerStore(path, {}, { false })
        }

        /** Test-only factory: simulates a readback that does not match the plan. */
        internal fun openForFaultInjection(directory: File, fault: () -> Boolean): HabitLedgerStore {
            val path = directory.toPath().toAbsolutePath().normalize()
            Files.createDirectories(path)
            return HabitLedgerStore(path, {}, fault)
        }

        /** Test-only factory: throws at the named commit phase, simulating a crash at that point. */
        internal fun openForFault(directory: File, faultAt: (String) -> Boolean): HabitLedgerStore {
            val path = directory.toPath().toAbsolutePath().normalize()
            Files.createDirectories(path)
            return HabitLedgerStore(path, { phase ->
                if (faultAt(phase)) throw IOException("HABIT_INJECTED_FAULT:$phase")
            }, { false })
        }

        /** Test-only factory: records the commit phases in order. */
        internal fun openForPhases(directory: File, phases: MutableList<String>): HabitLedgerStore {
            val path = directory.toPath().toAbsolutePath().normalize()
            Files.createDirectories(path)
            return HabitLedgerStore(path, { phase -> phases += phase }, { false })
        }
    }

    private val mutex = locks.computeIfAbsent(root.toString()) { Any() }
    private val dbDir = root.resolve("db")
    private val ledger = dbDir.resolve(HabitCodec.FILE_NAME)
    private val journalDir = dbDir.resolve(".journal")
    private val pending = dbDir.resolve("${HabitCodec.FILE_NAME}.pending")
    private val states = HashMap<String, PlanState>()

    private enum class PlanState { PENDING, CONFIRMED, CANCELLED }

    /** Reads the whole log. A pending marker or a foreign entry blocks the read (fail closed). */
    fun load(): HabitSnapshot = synchronized(mutex) {
        inventoryDb()
        parse(readLedgerOrEmpty())
    }

    /** Builds a `define` plan. `ts` is caller-supplied; nothing here reads the device clock. */
    fun planDefine(
        id: String,
        create: Boolean,
        name: String?,
        targetDays: Int?,
        archived: Boolean?,
        eventId: String = HabitCodec.newEventId(),
        ts: String = HabitCodec.stamp(LocalDateTime.now())
    ): HabitAppendPlan = plan(eventId, ts) { snapshot ->
        HabitCodec.buildDefine(snapshot, eventId, ts, id, create, name, targetDays, archived)
    }

    /** Builds a per-day `check` plan. A repeated day is a same-day correction: it appends a new
     *  line and never rewrites the earlier one. */
    fun planCheck(
        id: String,
        date: String,
        done: Boolean,
        note: String? = null,
        eventId: String = HabitCodec.newEventId(),
        ts: String = HabitCodec.stamp(LocalDateTime.now())
    ): HabitAppendPlan = plan(eventId, ts) { snapshot ->
        HabitCodec.buildCheck(snapshot, eventId, ts, id, date, done, note)
    }

    /** Cancels a pending plan; a cancelled plan can never be confirmed. */
    fun cancel(plan: HabitAppendPlan): Unit = synchronized(mutex) {
        require(plan.route() == root.toString()) { "HABIT_ROOT_MISMATCH" }
        when (states[plan.confirmation]) {
            null -> throw IllegalStateException("PLAN_UNKNOWN")
            PlanState.CONFIRMED -> throw IllegalStateException("PLAN_ALREADY_CONFIRMED")
            PlanState.CANCELLED -> throw IllegalStateException("PLAN_CANCELLED")
            PlanState.PENDING -> states[plan.confirmation] = PlanState.CANCELLED
        }
    }

    /** Applies a confirmed plan. An unknown outcome is an error, never a retry. */
    fun commit(plan: HabitAppendPlan, confirmation: String): HabitSnapshot = synchronized(mutex) {
        require(plan.route() == root.toString()) { "HABIT_ROOT_MISMATCH" }
        // A pending marker must block even an apparently idempotent replay: no receipt, no success.
        inventoryDb()
        val state = states[plan.confirmation]
        // Cancellation is terminal: refuse before parsing a possibly drifted ledger or replaying.
        if (state == PlanState.CANCELLED) throw IllegalStateException("PLAN_CANCELLED")
        require(confirmation == plan.confirmation) { "HABIT_CONFIRMATION_MISMATCH" }
        val current = readLedgerOrEmpty()
        val parsed = parse(current)
        // Idempotent replay: the event id is already on disk, so the write is a no-op.
        val already = parsed.events().firstOrNull { it.eventId == plan.eventId }
        if (already != null) {
            require(plan.replay || already.token() == plan.token) { "HABIT_EVENT_ID_CONFLICT" }
            states[plan.confirmation] = PlanState.CONFIRMED
            return parsed
        }
        when (state) {
            null -> throw IllegalStateException("PLAN_UNKNOWN")
            PlanState.CONFIRMED -> throw IllegalStateException("PLAN_ALREADY_CONFIRMED")
            PlanState.CANCELLED -> throw IllegalStateException("PLAN_CANCELLED")
            PlanState.PENDING -> {}
        }
        inventoryDb()
        require((if (current.isEmpty()) null else HabitCodec.hash(current)) == plan.baselineRevision) { "HABIT_BASELINE_CHANGED" }
        require(current.size + plan.suffixBytes().size <= HabitCodec.MAX_FILE_BYTES) { "HABIT_FILE_CAPACITY" }
        require(parsed.eventCount + 1 <= HabitCodec.MAX_EVENTS) { "HABIT_EVENT_CAPACITY" }

        val attemptId = UUID.randomUUID().toString()
        val attempt = journalDir.resolve(attemptId)
        Files.createDirectories(journalDir)
        require(Files.isDirectory(journalDir, LinkOption.NOFOLLOW_LINKS)) { "HABIT_JOURNAL_INVALID" }
        Files.createDirectory(attempt)
        writeNew(attempt.resolve("before.bin"), plan.beforeBytes())
        check(plan.beforeBytes().contentEquals(readBounded(attempt.resolve("before.bin")))) {
            "HABIT_BEFORE_IMAGE_UNCONFIRMED"
        }
        writeNew(attempt.resolve("suffix.bin"), plan.suffixBytes())
        check(plan.suffixBytes().contentEquals(readBounded(attempt.resolve("suffix.bin")))) {
            "HABIT_SUFFIX_UNCONFIRMED"
        }
        writeNew(attempt.resolve("event.txt"), TimeJson.encodeUtf8Strict(plan.token + "\n"))
        forceDir(attempt); forceDir(journalDir)
        writeNew(pending, TimeJson.encodeUtf8Strict(attemptId + "\n"))
        forceDir(dbDir)
        phaseProbe("pending-durable")
        // Last cooperative check, not an external compare-and-swap.
        require(readLedgerOrEmpty().let { if (it.isEmpty()) null else HabitCodec.hash(it) } == plan.baselineRevision) { "HABIT_BASELINE_CHANGED" }
        appendSuffix(plan.suffixBytes())
        forceDir(dbDir)
        phaseProbe("target-published")
        val actual = readLedgerOrEmpty()
        val afterHash = if (readbackFault()) HabitCodec.hash(actual + byteArrayOf(0)) else HabitCodec.hash(actual)
        if (afterHash != plan.afterRevision) {
            // Result unknown: keep the pending marker, do not delete, do not retry, do not repair.
            throw HabitWriteUnknownStateException("HABIT_WRITE_RESULT_UNKNOWN")
        }
        val verified = parse(actual)
        writeNew(attempt.resolve("receipt.txt"),
            TimeJson.encodeUtf8Strict("verified\n${plan.afterRevision}\n"))
        forceDir(attempt)
        phaseProbe("receipt-durable")
        Files.delete(pending)
        forceDir(dbDir)
        states[plan.confirmation] = PlanState.CONFIRMED
        verified
    }

    fun journalPath(): Path = journalDir
    fun ledgerPath(): Path = ledger

    private fun plan(eventId: String, ts: String, build: (HabitSnapshot) -> HabitEvent): HabitAppendPlan {
        val before = load()
        require(before.events().none { it.eventId == eventId }) { "HABIT_EVENT_ID_CONFLICT" }
        val event = build(before)
        val line = event.encode()
        require(line.indexOf('\n') < 0 && line.indexOf('\r') < 0) { "HABIT_EVENT_MULTILINE" }
        val suffix = TimeJson.encodeUtf8Strict(line + "\n")
        require(before.bytes().size + suffix.size <= HabitCodec.MAX_FILE_BYTES) { "HABIT_FILE_CAPACITY" }
        require(before.eventCount + 1 <= HabitCodec.MAX_EVENTS) { "HABIT_EVENT_CAPACITY" }
        val after = before.bytes() + suffix
        // Round-trip the projected result before a plan is offered: a plan never hides a bad row.
        parse(after)
        val plan = HabitAppendPlan(root.toString(), before.bytes(), suffix, after, event.op, event.eventId,
            event.token())
        states[plan.confirmation] = PlanState.PENDING
        return plan
    }

    private fun parse(bytes: ByteArray): HabitSnapshot = HabitSnapshot.parse(bytes)

    private fun inventoryDb() {
        require(!Files.isSymbolicLink(root) && root.toFile().canonicalFile.toPath() == root) {
            "HABIT_STORE_ROOT_CHANGED"
        }
        require(!Files.exists(dbDir, LinkOption.NOFOLLOW_LINKS) ||
            Files.isDirectory(dbDir, LinkOption.NOFOLLOW_LINKS)) { "HABIT_DB_CHANGED" }
        require(!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) { "HABIT_PENDING_REVIEW_REQUIRED" }
        if (!Files.exists(dbDir, LinkOption.NOFOLLOW_LINKS)) return
        Files.newDirectoryStream(dbDir).use { stream ->
            for (entry in stream) {
                val name = entry.fileName.toString()
                require(!Files.isSymbolicLink(entry)) { "HABIT_STORE_LINK_REJECTED" }
                when (name) {
                    HabitCodec.FILE_NAME -> {
                        require(Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                            "HABIT_STORE_SPECIAL_FILE"
                        }
                        require(Files.size(entry) <= HabitCodec.MAX_FILE_BYTES) { "HABIT_FILE_CAPACITY" }
                    }
                    ".journal" -> {
                        require(Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) { "HABIT_JOURNAL_INVALID" }
                        Files.newDirectoryStream(entry).use { attempts ->
                            for (attempt in attempts) {
                                require(!Files.isSymbolicLink(attempt)) { "HABIT_STORE_LINK_REJECTED" }
                            }
                        }
                    }
                    else -> throw IllegalStateException("HABIT_DB_ENTRY_UNEXPECTED:$name")
                }
            }
        }
        require(!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) { "HABIT_PENDING_REVIEW_REQUIRED" }
    }

    private fun readBounded(path: Path): ByteArray {
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "HABIT_STORE_SPECIAL_FILE"
        }
        require(Files.size(path) <= HabitCodec.MAX_FILE_BYTES) { "HABIT_FILE_CAPACITY" }
        return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val out = ByteArrayOutputStream()
            val block = ByteArray(8192)
            while (true) {
                val n = input.read(block)
                if (n < 0) break
                require(n > 0 && out.size() + n <= HabitCodec.MAX_FILE_BYTES) { "HABIT_READ_BOUND" }
                out.write(block, 0, n)
            }
            out.toByteArray()
        }
    }

    /** An absent file is a valid empty store; only an existing file is read. */
    private fun readLedgerOrEmpty(): ByteArray =
        if (Files.exists(ledger, LinkOption.NOFOLLOW_LINKS)) readBounded(ledger) else ByteArray(0)

    private fun appendSuffix(suffix: ByteArray) {
        FileChannel.open(ledger, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            require(!Files.isSymbolicLink(ledger)) { "HABIT_STORE_LINK_REJECTED" }
            channel.position(channel.size())
            val buffer = ByteBuffer.wrap(suffix)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun writeNew(path: Path, bytes: ByteArray) {
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun forceDir(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    }
}

/** Parent-facing API: plans are built here, confirmed by the caller, then applied.
 *  Values are validated by [HabitCodec] at plan time, never silently coerced. */
class HabitLedgerApi(private val store: HabitLedgerStore) {

    fun snapshot(): HabitSnapshot = store.load()

    fun ledgerPath(): Path = store.ledgerPath()

    fun journalPath(): Path = store.journalPath()

    // ---- identity lifecycle: create / edit / archive / restore (append-only `define`) ----

    fun planCreate(id: String, name: String, targetDays: Int, eventId: String = HabitCodec.newEventId(),
                   ts: String = HabitCodec.stamp(LocalDateTime.now())): HabitAppendPlan =
        store.planDefine(id, create = true, name = name, targetDays = targetDays, archived = null,
            eventId = eventId, ts = ts)

    fun planEdit(id: String, name: String? = null, targetDays: Int? = null,
                 eventId: String = HabitCodec.newEventId(),
                 ts: String = HabitCodec.stamp(LocalDateTime.now())): HabitAppendPlan =
        store.planDefine(id, create = false, name = name, targetDays = targetDays, archived = null,
            eventId = eventId, ts = ts)

    /** archive = true archives, false restores. Archived habits keep every row; nothing is deleted. */
    fun planArchive(id: String, archive: Boolean, eventId: String = HabitCodec.newEventId(),
                    ts: String = HabitCodec.stamp(LocalDateTime.now())): HabitAppendPlan =
        store.planDefine(id, create = false, name = null, targetDays = null, archived = archive,
            eventId = eventId, ts = ts)

    // ---- per-day checks ----

    fun planCheck(id: String, date: String, done: Boolean, note: String? = null,
                  eventId: String = HabitCodec.newEventId(),
                  ts: String = HabitCodec.stamp(LocalDateTime.now())): HabitAppendPlan =
        store.planCheck(id, date, done, note, eventId, ts)

    fun commit(plan: HabitAppendPlan, confirmation: String): HabitSnapshot =
        store.commit(plan, confirmation)

    fun cancel(plan: HabitAppendPlan): Unit = store.cancel(plan)

    // ---- pure projections (never persisted, never a source) ----

    fun mark(id: String, day: LocalDate): HabitMark = store.load().mark(id, day)

    fun streak(id: String, day: LocalDate): Int = store.load().streak(id, day)

    fun currentStreak(id: String, today: LocalDate): Int = store.load().currentStreak(id, today)

    fun stage(id: String, today: LocalDate): String =
        HabitProjection.stage(store.load().currentStreak(id, today))

    fun heat(id: String, today: LocalDate, days: Int): List<HabitMark> = store.load().heat(id, today, days)

    fun threeIncomplete(today: LocalDate): Boolean = store.load().threeIncomplete(today)
}
