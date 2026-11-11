package com.knte.core.time.repository

import com.knte.core.time.format.TimeEvent
import com.knte.core.time.format.TimeEventCodec
import com.knte.core.time.format.TimeHasher
import com.knte.core.time.format.TimeJson
import com.knte.core.time.projection.TimeProjectionBuilder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Snapshot of the isolated JSONL ledger: exact bytes, their hash, events and derived projection. */
class TimeSnapshot internal constructor(
    internal val bytes: ByteArray,
    val revision: String,
    val events: List<TimeEvent>,
    val projection: com.knte.core.time.projection.TimeProjection
) {
    fun findEvent(eventId: String): TimeEvent? =
        if (eventId.isEmpty()) null else events.firstOrNull { it.eventId == eventId }
}

/** Immutable append plan. Confirmation binds route identity, baseline hash, event id and event token. */
class TimeAppendPlan internal constructor(
    private val routeId: String,
    private val before: ByteArray,
    private val suffix: ByteArray,
    private val after: ByteArray,
    val eventId: String,
    val token: String,
    val op: String
) {
    val baselineRevision: String = TimeHasher.sha256(before)
    val afterRevision: String = TimeHasher.sha256(after)
    val suffixRevision: String = TimeHasher.sha256(suffix)
    val confirmation: String = TimeHasher.sha256(
        TimeJson.encodeUtf8Strict(
            TimeJson.encode(
                TimeJson.Value.Obj(
                    listOf(
                        "route" to TimeJson.Value.Str(routeId),
                        "op" to TimeJson.Value.Str(op),
                        "eventId" to TimeJson.Value.Str(eventId),
                        "token" to TimeJson.Value.Str(token),
                        "baseline" to TimeJson.Value.Str(baselineRevision),
                        "suffix" to TimeJson.Value.Str(suffixRevision)
                    )
                )
            )
        )
    )
    val replay: Boolean = suffix.isEmpty()
    internal fun routeId(): String = routeId
    internal fun eventId(): String = eventId
    internal fun tokenHash(): String = token
    internal fun beforeBytes() = before.clone()
    internal fun suffixBytes() = suffix.clone()
}

/** Thrown when a commit cannot be proven complete. The ledger state must be re-read, never auto-retried. */
class TimeWriteUnknownStateException(message: String) : IOException(message)

/** LOCAL ISOLATED time repository: caller supplies an app-private directory.
 * Same append-only JSONL format as the production `db/timetrack.jsonl`, but this is NOT the production
 * source: no real data, no SAF, no permissions, no network, no formal database connection.
 * Cooperative same-process lock only (not external CAS). Pending markers block reads: fail closed,
 * no automatic deletion, no automatic recovery, no replay of unfinished writes. */
class IsolatedTimeRepository private constructor(
    private val root: Path,
    /** Test-only fault injection: simulates a readback that does not match the plan. Never used in production paths. */
    private val readbackFault: () -> Boolean = { false }
) {

    companion object {
        const val FILE_NAME = "timetrack.jsonl"
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val MAX_EVENTS = 5000
        private val locks = ConcurrentHashMap<String, Any>()

        fun open(directory: File): IsolatedTimeRepository {
            val path = directory.toPath().toAbsolutePath().normalize()
            require(path.toString() == directory.absoluteFile.toPath().normalize().toString()) {
                "TIME_STORE_PATH_INVALID"
            }
            var ancestor: Path? = path
            while (ancestor != null) {
                require(!Files.isSymbolicLink(ancestor)) { "TIME_STORE_LINK_REJECTED" }
                ancestor = ancestor.parent
            }
            Files.createDirectories(path)
            require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "TIME_STORE_NOT_DIRECTORY" }
            require(path.toFile().canonicalFile.toPath() == path) { "TIME_STORE_ROOT_CHANGED" }
            return IsolatedTimeRepository(path)
        }

        /** Test-only factory enabling the readback fault. Not part of the parent-facing API. */
        internal fun openForFaultInjection(directory: File, fault: () -> Boolean): IsolatedTimeRepository {
            val path = directory.toPath().toAbsolutePath().normalize()
            Files.createDirectories(path)
            return IsolatedTimeRepository(path, fault)
        }
    }

    private val mutex = locks.computeIfAbsent(root.toString()) { Any() }
    private val ledger = root.resolve(FILE_NAME)
    private val journalDir = root.resolve(".journal")

    /** Reads the whole file. Any pending marker or foreign entry blocks the read (fail closed). */
    fun load(): TimeSnapshot = synchronized(mutex) {
        inventory()
        val bytes = if (Files.exists(ledger, LinkOption.NOFOLLOW_LINKS)) readBounded(ledger) else ByteArray(0)
        parse(bytes)
    }

    fun list(): TimeSnapshot = load()

    /** Builds an append plan. Nothing is written; the caller must confirm before commit. */
    fun planAppend(event: TimeEvent): TimeAppendPlan = synchronized(mutex) {
        planAppend(event, TimeEventCodec.stamp(LocalDateTime.now()))
    }

    internal fun planAppend(event: TimeEvent, now: String): TimeAppendPlan {
        require(event.eventId.isNotEmpty()) { "TIME_EVENT_ID_REQUIRED" }
        val before = load()
        require(before.findEvent(event.eventId) == null) { "TIME_EVENT_ID_CONFLICT" }
        require(before.events.size < MAX_EVENTS) { "TIME_EVENT_CAPACITY" }
        require(before.bytes.size + 1 <= MAX_FILE_BYTES) { "TIME_FILE_CAPACITY" }
        // Round-trip through the strict codec before validating projection/writing.
        val stamped = TimeEventCodec.parse(event.copy(ts = now).encode())
        validate(stamped, before, now)
        val line = stamped.encode()
        require(line.indexOf('\n') < 0 && line.indexOf('\r') < 0) { "TIME_EVENT_MULTILINE" }
        val suffix = TimeJson.encodeUtf8Strict(line + "\n")
        require(before.bytes.size + suffix.size <= MAX_FILE_BYTES) { "TIME_FILE_CAPACITY" }
        val after = before.bytes + suffix
        return TimeAppendPlan(root.toString(), before.bytes, suffix, after, event.eventId, stamped.token(), event.op)
    }

    /** Applies a confirmed plan. Unknown outcome after the target write is an error, never a retry. */
    fun commit(plan: TimeAppendPlan, confirmation: String): TimeSnapshot = synchronized(mutex) {
        require(plan.routeId() == root.toString()) { "TIME_PLAN_FOREIGN_ROOT" }
        require(confirmation == plan.confirmation) { "TIME_CONFIRMATION_MISMATCH" }
        // Pending state must block even an apparently idempotent replay: no receipt, no success.
        inventory()
        // Idempotent replay: the event id is already on disk, so the write is a no-op regardless of
        // any newer appends. This is checked before the baseline comparison.
        val current = readLedgerOrEmpty()
        val parsed = parse(current)
        if (parsed.findEvent(plan.eventId()) != null) {
            require(plan.replay || plan.tokenHash() == parsed.findEvent(plan.eventId())!!.token()) {
                "TIME_EVENT_ID_CONFLICT"
            }
            return parsed
        }
        inventory()
        require(TimeHasher.sha256(current) == plan.baselineRevision) { "TIME_BASELINE_CHANGED" }
        // A durable pending receipt exists BEFORE the target is touched; a crash leaves it and blocks reads.
        val attemptId = UUID.randomUUID().toString()
        val attempt = journalDir.resolve(attemptId)
        Files.createDirectories(journalDir)
        require(Files.isDirectory(journalDir, LinkOption.NOFOLLOW_LINKS)) { "TIME_JOURNAL_INVALID" }
        Files.createDirectory(attempt)
        writeNew(attempt.resolve("before.bin"), plan.beforeBytes())
        check(plan.beforeBytes().contentEquals(readBounded(attempt.resolve("before.bin")))) {
            "TIME_BEFORE_IMAGE_UNCONFIRMED"
        }
        writeNew(attempt.resolve("suffix.bin"), plan.suffixBytes())
        check(plan.suffixBytes().contentEquals(readBounded(attempt.resolve("suffix.bin")))) {
            "TIME_SUFFIX_UNCONFIRMED"
        }
        forceDir(attempt); forceDir(journalDir)
        val pending = root.resolve("$FILE_NAME.pending")
        writeNew(pending, TimeJson.encodeUtf8Strict(attemptId + "\n"))
        forceDir(root)
        // Last cooperative check, not an external compare-and-swap.
        require(TimeHasher.sha256(readLedgerOrEmpty()) == plan.baselineRevision) { "TIME_BASELINE_CHANGED" }
        appendSuffix(plan.suffixBytes())
        forceDir(root)
        val actual = readLedgerOrEmpty()
        val expected = plan.afterRevision
        val afterHash = if (readbackFault()) TimeHasher.sha256(actual + byteArrayOf(0)) else TimeHasher.sha256(actual)
        if (afterHash != expected) {
            // Result unknown: keep pending, do not delete, do not retry, do not "repair".
            throw TimeWriteUnknownStateException("TIME_WRITE_RESULT_UNKNOWN")
        }
        writeNew(attempt.resolve("receipt.txt"), TimeJson.encodeUtf8Strict("verified\n$expected\n"))
        forceDir(attempt)
        Files.delete(pending)
        forceDir(root)
        return parse(actual)
    }

    /** Derived view for callers that only need a projection (no UI timer state involved). */
    fun projections(): com.knte.core.time.projection.TimeProjection = load().projection

    private fun validate(event: TimeEvent, before: TimeSnapshot, now: String) {
        val nowMinute = runCatching { LocalDateTime.parse(now) }.getOrNull()
            ?.withSecond(0)?.withNano(0) ?: error("TIME_CLOCK_INVALID")
        when (event.op) {
            "start" -> {
                require(before.projection.find(event.text("id")!!) == null) { "TIME_ID_ALREADY_EXISTS" }
                val begin = LocalDateTime.parse(event.text("date") + "T" + event.text("start") + ":00")
                require(!begin.isAfter(nowMinute)) { "TIME_START_IN_FUTURE" }
            }
            "stop" -> {
                val record = before.projection.find(event.text("id")!!)
                require(record != null && record.running()) { "TIME_TARGET_NOT_RUNNING" }
                val day = event.text("end_date") ?: record.date
                val end = LocalDateTime.parse("${day}T${event.text("end")}:00")
                require(!end.isBefore(record.begin)) { "TIME_END_BEFORE_START" }
                require(!end.isAfter(nowMinute)) { "TIME_END_IN_FUTURE" }
            }
            "amend" -> {
                // Unknown targets are still written and flagged by the projection (R-5), so no existence check here.
                val target = event.text("target")!!
                val changed = before.projection.find(target)
                if (changed != null) {
                    val after = TimeProjectionBuilder.build(before.events + event)
                    val updated = after.find(target)
                    if (updated != null) {
                        require(!updated.begin.isAfter(nowMinute)) { "TIME_AMEND_FUTURE_BEGIN" }
                        require(updated.finish == null || !updated.finish.isAfter(nowMinute)) {
                            "TIME_AMEND_FUTURE_END"
                        }
                        require(after.invalidCount <= before.projection.invalidCount) {
                            "TIME_AMEND_CREATES_INVALID_RANGE"
                        }
                    }
                }
            }
            "rename_project" -> {
                val from = event.text("from")!!
                val to = event.text("to")!!
                require(from != to) { "TIME_RENAME_SAME_NAME" }
                require(before.projection.records.any { it.project == from }) { "TIME_RENAME_SOURCE_MISSING" }
            }
        }
    }

    private fun parse(bytes: ByteArray): TimeSnapshot {
        require(bytes.size <= MAX_FILE_BYTES) { "TIME_FILE_CAPACITY" }
        require(bytes.isEmpty() || bytes.last() == '\n'.code.toByte()) { "TIME_FILE_UNTERMINATED_LAST_LINE" }
        val events = mutableListOf<TimeEvent>()
        val ids = mutableSetOf<String>()
        if (bytes.isNotEmpty()) {
            val text = TimeJson.decodeUtf8Strict(bytes)
            require('\u0000' !in text) { "TIME_FILE_NUL" }
            val lines = text.substring(0, text.length - 1).split('\n')
            for (line in lines) {
                require(line.isNotEmpty() && line.length <= TimeEventCodec.MAX_LINE_CHARS) { "TIME_LINE_INVALID" }
                val event = TimeEventCodec.parse(line)
                if (event.eventId.isNotEmpty()) require(ids.add(event.eventId)) { "TIME_DUPLICATE_EVENT_ID" }
                events += event
                require(events.size <= MAX_EVENTS) { "TIME_EVENT_CAPACITY" }
            }
        }
        val projection = TimeProjectionBuilder.build(events)
        return TimeSnapshot(bytes, TimeHasher.sha256(bytes), events, projection)
    }

    private fun inventory() {
        require(!Files.isSymbolicLink(root) && root.toFile().canonicalFile.toPath() == root) {
            "TIME_STORE_ROOT_CHANGED"
        }
        require(!Files.exists(root.resolve("$FILE_NAME.pending"), LinkOption.NOFOLLOW_LINKS)) {
            "TIME_PENDING_REVIEW_REQUIRED"
        }
        Files.newDirectoryStream(root).use { stream ->
            for (entry in stream) {
                val name = entry.fileName.toString()
                require(!Files.isSymbolicLink(entry)) { "TIME_STORE_LINK_REJECTED" }
                if (name == FILE_NAME) {
                    require(Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) { "TIME_STORE_SPECIAL_FILE" }
                    require(Files.size(entry) <= MAX_FILE_BYTES) { "TIME_FILE_CAPACITY" }
                    continue
                }
                require(name == ".journal") { "TIME_STORE_UNKNOWN_ENTRY" }
                require(Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) { "TIME_JOURNAL_INVALID" }
                Files.newDirectoryStream(entry).use { attempts ->
                    for (attempt in attempts) require(!Files.isSymbolicLink(attempt)) { "TIME_STORE_LINK_REJECTED" }
                }
            }
        }
        require(!Files.exists(root.resolve("$FILE_NAME.pending"), LinkOption.NOFOLLOW_LINKS)) {
            "TIME_PENDING_REVIEW_REQUIRED"
        }
    }

    private fun readBounded(path: Path): ByteArray {
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "TIME_STORE_SPECIAL_FILE"
        }
        require(Files.size(path) <= MAX_FILE_BYTES) { "TIME_FILE_CAPACITY" }
        return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val out = ByteArrayOutputStream()
            val block = ByteArray(8192)
            while (true) {
                val n = input.read(block)
                if (n < 0) break
                require(n > 0 && out.size() + n <= MAX_FILE_BYTES) { "TIME_READ_BOUND" }
                out.write(block, 0, n)
            }
            out.toByteArray()
        }
    }

    /** Empty file is a valid state (nothing appended yet); only an existing file is read. */
    private fun readLedgerOrEmpty(): ByteArray =
        if (Files.exists(ledger, LinkOption.NOFOLLOW_LINKS)) readBounded(ledger) else ByteArray(0)

    private fun appendSuffix(suffix: ByteArray) {
        FileChannel.open(ledger, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            require(!Files.isSymbolicLink(ledger)) { "TIME_STORE_LINK_REJECTED" }
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
 *  Values are validated by [com.knte.core.time.format.TimeEventCodec] at plan time, never silently coerced. */
class TimeLedgerApi(private val repository: IsolatedTimeRepository) {

    fun snapshot() = repository.load()

    /** start plan. date/start/project required; tags/note/repeat_id optional. */
    fun planStart(
        eventId: String,
        id: String,
        date: String,
        start: String,
        project: String,
        tags: List<String> = emptyList(),
        note: String? = null,
        repeatId: String? = null
    ): TimeAppendPlan {
        val fields = linkedMapOf<String, TimeJson.Value>(
            "id" to TimeJson.Value.Str(id),
            "date" to TimeJson.Value.Str(date),
            "start" to TimeJson.Value.Str(start),
            "project" to TimeJson.Value.Str(project)
        )
        if (tags.isNotEmpty()) fields["tags"] = TimeJson.Value.Arr(tags.map { TimeJson.Value.Str(it) })
        if (note != null) fields["note"] = TimeJson.Value.Str(note)
        if (repeatId != null) fields["repeat_id"] = TimeJson.Value.Str(repeatId)
        return repository.planAppend(TimeEvent("start", eventId, "", fields, emptyList()))
    }

    /** stop plan. Cross-day stop carries end_date; same-day omits it. */
    fun planStop(eventId: String, id: String, end: String, endDate: String? = null): TimeAppendPlan {
        val fields = linkedMapOf<String, TimeJson.Value>(
            "id" to TimeJson.Value.Str(id),
            "end" to TimeJson.Value.Str(end)
        )
        if (endDate != null) fields["end_date"] = TimeJson.Value.Str(endDate)
        return repository.planAppend(TimeEvent("stop", eventId, "", fields, emptyList()))
    }

    fun planAmend(
        eventId: String,
        target: String,
        field: String,
        value: TimeJson.Value,
        reason: String? = null
    ): TimeAppendPlan {
        val fields = linkedMapOf<String, TimeJson.Value>(
            "target" to TimeJson.Value.Str(target),
            "field" to TimeJson.Value.Str(field),
            "value" to value
        )
        if (reason != null) fields["reason"] = TimeJson.Value.Str(reason)
        return repository.planAppend(TimeEvent("amend", eventId, "", fields, emptyList()))
    }

    fun planRenameProject(eventId: String, from: String, to: String): TimeAppendPlan {
        val fields = linkedMapOf<String, TimeJson.Value>(
            "from" to TimeJson.Value.Str(from),
            "to" to TimeJson.Value.Str(to)
        )
        return repository.planAppend(TimeEvent("rename_project", eventId, "", fields, emptyList()))
    }

    /** list projections: pure derived state, nothing persisted, no UI timer persistence involved. */
    fun listProjections() = repository.projections()

    fun commit(plan: TimeAppendPlan, confirmation: String) = repository.commit(plan, confirmation)
}
