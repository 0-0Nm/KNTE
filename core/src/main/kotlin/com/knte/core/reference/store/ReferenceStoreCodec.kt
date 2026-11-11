package com.knte.core.reference.store

import com.knte.core.reference.PickEventRecord
import com.knte.core.reference.ReferenceEntry
import com.knte.core.reference.ReferencePool
import com.knte.core.time.format.TimeFields
import com.knte.core.time.format.TimeHasher
import com.knte.core.time.format.TimeJson
import com.knte.core.time.format.TimeJsonValues
import java.nio.file.Path

/**
 * Strict codec of the isolated reference event base (schema v2: root-bound plans).
 *
 * Parsing rules, verifiable: the schema version must match exactly (unknown version fails
 * closed, never silently upgraded); the top-level event object keeps unknown keys for the
 * future while every known structure (entry / recent queues / pending / receipt) is a
 * closed schema where an extra key fails closed; the original line bytes are never
 * rewritten by this store; ts is a strict timestamp and at_trust is pinned to "unverified".
 *
 * This aggregate append is NOT a formal multi-file transaction and makes NO single-file
 * atomicity guarantee: a torn append can leave an UNKNOWN head. The pending record therefore
 * binds a real before-image (head + exact byte size + sha256) so a read-only classifier can
 * tell APPLIED / NOT_APPLIED / UNKNOWN without ever writing.
 */
internal object ReferenceStoreCodec {
    const val SCHEMA_EVENT = "knte.reference_store.event/1"
    const val SCHEMA_PENDING = "knte.reference_store.pending/2"
    const val SCHEMA_RECEIPT = "knte.reference_store.receipt/1"

    /** Deliberately NOT the formal file names (db/memo.jsonl, db/pitfall.jsonl, db/memo_pick.jsonl, db/pick_log.jsonl). */
    const val EVENTS_FILE = "reference_store_events.jsonl"
    const val PENDING_FILE = "reference_store_pending.json"
    const val PENDING_TMP = "reference_store_pending.tmp"
    const val BEFORE_FILE = "reference_store_before.bin"
    const val BEFORE_TMP = "reference_store_before.tmp"
    const val CANCELLED_FILE = "reference_store_cancelled.txt"
    const val CANCELLED_TMP = "reference_store_cancelled.tmp"
    const val RECEIPT_FILE = "reference_store_receipt.jsonl"

    val GENESIS_HEAD: String =
        TimeHasher.sha256("knte.core.reference.store/genesis/1".toByteArray(Charsets.UTF_8))

    /** Capacity bounds; opening past them fails closed instead of degrading. */
    const val MAX_EVENTS = 100_000
    const val MAX_LINE_BYTES = 1_000_000
    const val MAX_FILE_BYTES = 128_000_000
    const val MAX_PENDING_BYTES = 4_000_000

    /**
     * Hard ceiling on the full before-image this validation store can reliably materialize.
     * Beyond it confirm() fails closed: this is not a production multi-file transaction and
     * no unbounded journal is implemented. 8 MiB keeps memory bounded at low disk.
     */
    const val MAX_BEFORE_IMAGE_BYTES = 8 * 1024 * 1024

    const val OP_CREATE_MEMO = "create_memo"
    const val OP_CREATE_PITFALL = "create_pitfall"
    const val OP_MEMO_DONE = "memo_done"
    const val OP_MEMO_ARCHIVE = "memo_archive"
    const val OP_PITFALL_ARCHIVE = "pitfall_archive"
    const val OP_PICK = "pick"

    val OPS = setOf(
        OP_CREATE_MEMO, OP_CREATE_PITFALL,
        OP_MEMO_DONE, OP_MEMO_ARCHIVE, OP_PITFALL_ARCHIVE,
        OP_PICK,
    )

    private val KNOWN_TOP_KEYS = setOf(
        "schema", "event_id", "op", "ts", "at_trust", "prev_head",
        "entry", "pick", "recent_after",
    )
    private val KNOWN_PENDING_KEYS = setOf(
        "schema", "root_fingerprint", "token", "event_id", "op", "ts",
        "before_hash", "before_size", "before_sha256", "proposal_line",
        "suffix_hash", "after_hash",
    )
    private val KNOWN_RECEIPT_KEYS = setOf(
        "schema", "token", "event_id", "op", "ts", "before_hash", "after_hash",
    )

    private val HEX64 = Regex("[0-9a-f]{64}")

    // ---------- hashes / fingerprints ----------

    fun hexBytes(hex: String): ByteArray {
        require(hex.length == 64) { "HEX_LEN" }
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi in 0..15 && lo in 0..15) { "HEX_DIGIT" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun lineAppendBytes(line: String): ByteArray = (line + "\n").toByteArray(Charsets.UTF_8)

    fun suffixHashOf(line: String): String = TimeHasher.sha256(lineAppendBytes(line))

    fun nextHead(head: String, line: String): String =
        TimeHasher.sha256(hexBytes(head) + lineAppendBytes(line))

    /** Stable identity of a store root directory (absolute normalized path + canonical form). */
    fun rootFingerprintOf(root: Path): String = TimeHasher.sha256(
        ("knte.reference.store.root/2\u0000" + root.toAbsolutePath().normalize().toString() +
            "\u0000" + root.toFile().canonicalPath).toByteArray(Charsets.UTF_8)
    )

    /**
     * Binding root of a plan: INCLUDES the store root fingerprint AND the exact before-image
     * (head, byte size, sha256). A plan issued for one directory/baseline can never be
     * confirmed against (or silently rewritten into) another.
     */
    fun tokenOf(
        rootFingerprint: String,
        eventId: String,
        op: String,
        ts: String,
        beforeHash: String,
        beforeSize: Long,
        beforeSha256: String,
        suffixHash: String,
        afterHash: String,
    ): String = TimeHasher.sha256(
        (rootFingerprint + "\u0000" + beforeHash + "\u0000" + beforeSize + "\u0000" + beforeSha256 +
            "\u0000" + suffixHash + "\u0000" + afterHash + "\u0000" +
            eventId + "\u0000" + op + "\u0000" + ts).toByteArray(Charsets.UTF_8)
    )

    /** Separate explicit confirmation token, bound to the plan token and its root. */
    fun confirmationOf(rootFingerprint: String, token: String, beforeSha256: String): String =
        TimeHasher.sha256(
            ("confirm/2\u0000" + rootFingerprint + "\u0000" + token + "\u0000" + beforeSha256)
                .toByteArray(Charsets.UTF_8)
        )

    fun derivedEventId(id: String, op: String, ts: String): String =
        "e-" + TimeHasher.sha256((id + "\u0000" + op + "\u0000" + ts).toByteArray(Charsets.UTF_8)).substring(0, 8)

    // ---------- entry rows ----------

    fun entryJson(entry: ReferenceEntry): TimeJson.Value {
        val pairs = mutableListOf<Pair<String, TimeJson.Value>>()
        pairs += "id" to TimeJson.Value.Str(entry.id)
        pairs += "title" to TimeJson.Value.Str(entry.title)
        when (entry) {
            is ReferenceEntry.Memo -> {
                entry.due?.let { pairs += "due" to TimeJson.Value.Str(it) }
                pairs += "done" to TimeJson.Value.Bool(entry.done)
            }
            is ReferenceEntry.Pitfall -> pairs += "count" to TimeJson.Value.Num(entry.count.toString())
        }
        pairs += "archived" to TimeJson.Value.Bool(entry.archived)
        pairs += "tags" to TimeJson.Value.Arr(entry.tags.map { TimeJson.Value.Str(it) })
        entry.world?.let { pairs += "world" to TimeJson.Value.Str(it) }
        entry.body?.let { pairs += "body" to TimeJson.Value.Str(it) }
        pairs += "created_ts" to TimeJson.Value.Str(entry.createdTs)
        pairs += "ts" to TimeJson.Value.Str(entry.ts)
        return TimeJson.Value.Obj(pairs)
    }

    fun parseEntry(value: TimeJson.Value, pool: ReferencePool): ReferenceEntry {
        val obj = value as? TimeJson.Value.Obj ?: throw corrupt("REF_ENTRY_SHAPE")
        val keys = obj.entries.map { it.first }
        if (keys.toSet().size != keys.size) throw corrupt("REF_ENTRY_DUP_KEY")
        val memoKeys = setOf("id", "title", "due", "done", "archived", "tags", "world", "body", "created_ts", "ts")
        val pitfallKeys = setOf("id", "title", "count", "archived", "tags", "world", "body", "created_ts", "ts")
        val known = if (pool == ReferencePool.MEMO) memoKeys else pitfallKeys
        keys.forEach { if (it !in known) throw corrupt("REF_ENTRY_KEY:$it") }
        fun required(name: String): TimeJson.Value =
            obj.entries.firstOrNull { it.first == name }?.second ?: throw corrupt("REF_ENTRY_MISSING:$name")
        fun optional(name: String): TimeJson.Value? =
            obj.entries.firstOrNull { it.first == name }?.second?.takeIf { it !is TimeJson.Value.Null }
        try {
            return if (pool == ReferencePool.MEMO) {
                ReferenceEntry.Memo(
                    id = TimeJsonValues.str(required("id")),
                    title = TimeJsonValues.str(required("title")),
                    createdTs = TimeJsonValues.str(required("created_ts")),
                    ts = TimeJsonValues.str(required("ts")),
                    due = optional("due")?.let { TimeJsonValues.str(it) },
                    done = TimeJsonValues.bool(required("done")),
                    archived = TimeJsonValues.bool(required("archived")),
                    tags = TimeJsonValues.strList(required("tags")),
                    world = optional("world")?.let { TimeJsonValues.str(it) },
                    body = optional("body")?.let { TimeJsonValues.str(it) },
                )
            } else {
                val countLiteral = (required("count") as? TimeJson.Value.Num)?.literal
                    ?: throw corrupt("REF_ENTRY_COUNT")
                ReferenceEntry.Pitfall(
                    id = TimeJsonValues.str(required("id")),
                    title = TimeJsonValues.str(required("title")),
                    count = countLiteral.toInt(),
                    createdTs = TimeJsonValues.str(required("created_ts")),
                    ts = TimeJsonValues.str(required("ts")),
                    archived = TimeJsonValues.bool(required("archived")),
                    tags = TimeJsonValues.strList(required("tags")),
                    world = optional("world")?.let { TimeJsonValues.str(it) },
                    body = optional("body")?.let { TimeJsonValues.str(it) },
                )
            }
        } catch (e: IllegalArgumentException) {
            throw corrupt("REF_ENTRY_VALIDATION:${e.message}")
        } catch (e: IllegalStateException) {
            throw corrupt("REF_ENTRY_SHAPE:${e.message}")
        }
    }

    // ---------- event lines ----------

    fun eventLine(
        op: String,
        eventId: String,
        ts: String,
        prevHead: String,
        payload: List<Pair<String, TimeJson.Value>>,
    ): String {
        val pairs = mutableListOf<Pair<String, TimeJson.Value>>()
        pairs += "schema" to TimeJson.Value.Str(SCHEMA_EVENT)
        pairs += "event_id" to TimeJson.Value.Str(eventId)
        pairs += "op" to TimeJson.Value.Str(op)
        pairs += "ts" to TimeJson.Value.Str(ts)
        pairs += "at_trust" to TimeJson.Value.Str("unverified")
        pairs += "prev_head" to TimeJson.Value.Str(prevHead)
        pairs += payload
        return TimeJson.encode(TimeJson.Value.Obj(pairs))
    }

    fun queuesJson(queues: ReferenceStoreQueues): TimeJson.Value = TimeJson.Value.Obj(
        listOf(
            "memo" to TimeJson.Value.Arr(queues.memo.map { TimeJson.Value.Str(it) }),
            "pitfall" to TimeJson.Value.Arr(queues.pitfall.map { TimeJson.Value.Str(it) }),
        )
    )

    fun parseEventLine(line: String, expectedPrevHead: String): ParsedEvent {
        if (line.isEmpty()) throw corrupt("REF_BLANK_LINE")
        if (line.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES) throw corrupt("REF_LINE_TOO_LONG")
        if ('\r' in line) throw corrupt("REF_CR")
        val obj: TimeJson.Value.Obj = try {
            TimeJson.parse(line) as? TimeJson.Value.Obj ?: throw corrupt("REF_OBJ")
        } catch (e: IllegalArgumentException) {
            throw corrupt("REF_LINE_JSON:${e.message}")
        }
        fun required(name: String): TimeJson.Value =
            obj.entries.firstOrNull { it.first == name }?.second ?: throw corrupt("REF_FIELD_MISSING:$name")

        val schema = strStrict(required("schema"))
        if (schema != SCHEMA_EVENT) throw corrupt("REF_SCHEMA:$schema")
        val op = strStrict(required("op"))
        if (op !in OPS) throw corrupt("REF_OP:$op")
        val eventId = strStrict(required("event_id"))
        checkEventIdShape(eventId, op)
        val ts = try {
            TimeFields.stamp(strStrict(required("ts")))
        } catch (e: IllegalArgumentException) {
            throw corrupt("REF_TS:${e.message}")
        }
        if (strStrict(required("at_trust")) != "unverified") throw corrupt("REF_TRUST")
        val prevHead = strStrict(required("prev_head"))
        if (!prevHead.matches(HEX64)) throw corrupt("REF_PREV_HEAD")
        if (prevHead != expectedPrevHead) throw corrupt("REF_CHAIN:$prevHead!=$expectedPrevHead")

        val hasEntry = obj.entries.any { it.first == "entry" }
        val hasPick = obj.entries.any { it.first == "pick" }
        val hasRecent = obj.entries.any { it.first == "recent_after" }

        var entry: ReferenceEntry? = null
        var pick: PickEventRecord? = null
        var queuesAfter: ReferenceStoreQueues? = null
        when (op) {
            OP_CREATE_MEMO, OP_MEMO_DONE, OP_MEMO_ARCHIVE -> {
                if (!hasEntry || hasPick || hasRecent) throw corrupt("REF_OP_FIELDS:$op")
                // Narrow to the concrete Memo subtype BEFORE accessing Memo-only fields (done/archived).
                val memo = parseEntry(required("entry"), ReferencePool.MEMO) as? ReferenceEntry.Memo
                    ?: throw corrupt("REF_OP_ENTRY:$op")
                entry = memo
                if (op == OP_CREATE_MEMO) {
                    // Only create ops bind eventId to the row id. Derived ops use e-<hash>
                    // of (row id, op, ts), so comparing them to the row id would always fail.
                    if (memo.id != eventId) throw corrupt("REF_ID_BINDING")
                    if (memo.done || memo.archived) throw corrupt("REF_CREATE_STATE")
                    if (memo.createdTs != ts || memo.ts != ts) throw corrupt("REF_CREATE_TS")
                } else {
                    if (eventId != derivedEventId(memo.id, op, ts)) throw corrupt("REF_DERIVED_ID")
                }
            }
            OP_CREATE_PITFALL, OP_PITFALL_ARCHIVE -> {
                if (!hasEntry || hasPick || hasRecent) throw corrupt("REF_OP_FIELDS:$op")
                val pitfall = parseEntry(required("entry"), ReferencePool.PITFALL) as? ReferenceEntry.Pitfall
                    ?: throw corrupt("REF_OP_ENTRY:$op")
                entry = pitfall
                if (op == OP_CREATE_PITFALL) {
                    if (pitfall.id != eventId) throw corrupt("REF_ID_BINDING")
                    if (pitfall.archived) throw corrupt("REF_CREATE_STATE")
                    if (pitfall.createdTs != ts || pitfall.ts != ts) throw corrupt("REF_CREATE_TS")
                } else {
                    if (eventId != derivedEventId(pitfall.id, op, ts)) throw corrupt("REF_DERIVED_ID")
                }
            }
            OP_PICK -> {
                if (hasEntry || !hasPick || !hasRecent) throw corrupt("REF_OP_FIELDS:$op")
                val parsedPick = PickEventRecord.parse(required("pick"))
                pick = parsedPick
                if (parsedPick.eventId != eventId) throw corrupt("REF_ID_BINDING")
                if (parsedPick.binding == null) throw corrupt("REF_PICK_UNBOUND")
                if (parsedPick.ts != ts) throw corrupt("REF_PICK_TS")
                queuesAfter = parseQueues(required("recent_after"))
            }
        }

        // Unknown top-level keys are preserved verbatim (never rewritten, never dropped).
        val unknownKeys = obj.entries.filter { it.first !in KNOWN_TOP_KEYS }
        return ParsedEvent(line, eventId, op, ts, prevHead, entry, pick, queuesAfter, unknownKeys)
    }

    private fun parseQueues(value: TimeJson.Value): ReferenceStoreQueues {
        val obj = value as? TimeJson.Value.Obj ?: throw corrupt("REF_RECENT_SHAPE")
        if (obj.entries.map { it.first }.toSet() != setOf("memo", "pitfall")) throw corrupt("REF_RECENT_KEYS")
        fun required(name: String): TimeJson.Value =
            obj.entries.firstOrNull { it.first == name }?.second ?: throw corrupt("REF_RECENT_MISSING:$name")
        return ReferenceStoreQueues(
            memo = TimeJsonValues.strList(required("memo")),
            pitfall = TimeJsonValues.strList(required("pitfall")),
        )
    }

    private fun checkEventIdShape(eventId: String, op: String) {
        val ok = when (op) {
            OP_CREATE_MEMO -> eventId.matches(Regex("m-[0-9a-f]{8}"))
            OP_CREATE_PITFALL -> eventId.matches(Regex("p-[0-9a-f]{8}"))
            OP_PICK -> eventId.matches(Regex("r-[0-9a-f]{8}"))
            else -> eventId.matches(Regex("e-[0-9a-f]{8}"))
        }
        if (!ok) throw corrupt("REF_EVENT_ID_SHAPE:$eventId")
    }

    // ---------- pending (root-bound, with a real before-image) ----------

    fun pendingLine(plan: ReferenceStorePlan): String = TimeJson.encode(
        TimeJson.Value.Obj(
            listOf(
                "schema" to TimeJson.Value.Str(SCHEMA_PENDING),
                "root_fingerprint" to TimeJson.Value.Str(plan.root()),
                "token" to TimeJson.Value.Str(plan.token),
                "event_id" to TimeJson.Value.Str(plan.eventId),
                "op" to TimeJson.Value.Str(plan.op),
                "ts" to TimeJson.Value.Str(plan.ts),
                "before_hash" to TimeJson.Value.Str(plan.beforeHash),
                "before_size" to TimeJson.Value.Num(plan.beforeSize.toString()),
                "before_sha256" to TimeJson.Value.Str(plan.beforeSha256),
                "proposal_line" to TimeJson.Value.Str(plan.line),
                "suffix_hash" to TimeJson.Value.Str(plan.suffixHash),
                "after_hash" to TimeJson.Value.Str(plan.afterHash),
            )
        )
    ) + "\n"

    fun parsePendingLine(text: String): ParsedPending {
        if ('\r' in text) throw pendingCorrupt("PENDING_CR")
        if (!text.endsWith("\n")) throw pendingCorrupt("PENDING_NO_LF")
        val lines = text.split('\n')
        if (lines.size != 2 || lines[0].isEmpty()) throw pendingCorrupt("PENDING_SINGLE_LINE")
        val obj: TimeJson.Value.Obj = try {
            TimeJson.parse(lines[0]) as? TimeJson.Value.Obj ?: throw pendingCorrupt("PENDING_OBJ")
        } catch (e: IllegalArgumentException) {
            throw pendingCorrupt("PENDING_JSON:${e.message}")
        }
        val keys = obj.entries.map { it.first }
        if (keys.toSet().size != keys.size) throw pendingCorrupt("PENDING_DUP_KEY")
        keys.forEach { if (it !in KNOWN_PENDING_KEYS) throw pendingCorrupt("PENDING_KEY:$it") }
        fun required(name: String): TimeJson.Value =
            obj.entries.firstOrNull { it.first == name }?.second ?: throw pendingCorrupt("PENDING_MISSING:$name")
        if (strStrict(required("schema")) != SCHEMA_PENDING) throw pendingCorrupt("PENDING_SCHEMA")
        val rootFingerprint = strStrict(required("root_fingerprint"))
        val token = strStrict(required("token"))
        val eventId = strStrict(required("event_id"))
        val op = strStrict(required("op"))
        val ts = strStrict(required("ts"))
        val beforeHash = strStrict(required("before_hash"))
        val beforeSize = (required("before_size") as? TimeJson.Value.Num)?.literal?.toLongOrNull()
            ?: throw pendingCorrupt("PENDING_BEFORE_SIZE")
        if (beforeSize < 0) throw pendingCorrupt("PENDING_BEFORE_SIZE")
        val beforeSha256 = strStrict(required("before_sha256"))
        val proposalLine = strStrict(required("proposal_line"))
        val suffixHash = strStrict(required("suffix_hash"))
        val afterHash = strStrict(required("after_hash"))
        for (hash in listOf(rootFingerprint, token, beforeHash, beforeSha256, suffixHash, afterHash)) {
            if (!hash.matches(HEX64)) throw pendingCorrupt("PENDING_HASH")
        }
        if ('\n' in proposalLine || '\r' in proposalLine || proposalLine.isEmpty()) throw pendingCorrupt("PENDING_PROPOSAL")
        if (suffixHashOf(proposalLine) != suffixHash) throw pendingCorrupt("PENDING_SUFFIX")
        if (tokenOf(rootFingerprint, eventId, op, ts, beforeHash, beforeSize, beforeSha256, suffixHash, afterHash) != token) {
            throw pendingCorrupt("PENDING_TOKEN")
        }
        return ParsedPending(
            rootFingerprint, token, eventId, op, ts, beforeHash, beforeSize, beforeSha256,
            proposalLine, suffixHash, afterHash,
        )
    }

    // ---------- cancelled token markers (one LF-terminated 64-hex token per line) ----------

    fun parseCancelled(text: String): Set<String> {
        if (text.isEmpty()) return emptySet()
        if ('\r' in text || !text.endsWith("\n")) throw cancelledCorrupt("CANCELLED_LF")
        val out = LinkedHashSet<String>()
        for (line in text.split('\n').dropLast(1)) {
            if (!line.matches(HEX64)) throw cancelledCorrupt("CANCELLED_TOKEN")
            out += line
        }
        return out
    }

    fun cancelledLine(token: String): String = token + "\n"

    // ---------- receipt ----------

    fun receiptLine(
        token: String,
        eventId: String,
        op: String,
        ts: String,
        beforeHash: String,
        afterHash: String,
    ): String = TimeJson.encode(
        TimeJson.Value.Obj(
            listOf(
                "schema" to TimeJson.Value.Str(SCHEMA_RECEIPT),
                "token" to TimeJson.Value.Str(token),
                "event_id" to TimeJson.Value.Str(eventId),
                "op" to TimeJson.Value.Str(op),
                "ts" to TimeJson.Value.Str(ts),
                "before_hash" to TimeJson.Value.Str(beforeHash),
                "after_hash" to TimeJson.Value.Str(afterHash),
            )
        )
    ) + "\n"

    fun parseReceiptLine(text: String): ParsedReceipt {
        if ('\r' in text || !text.endsWith("\n")) throw receiptCorrupt("RECEIPT_LF")
        val obj: TimeJson.Value.Obj = try {
            TimeJson.parse(text.removeSuffix("\n")) as? TimeJson.Value.Obj ?: throw receiptCorrupt("RECEIPT_OBJ")
        } catch (e: IllegalArgumentException) {
            throw receiptCorrupt("RECEIPT_JSON:${e.message}")
        }
        val keys = obj.entries.map { it.first }
        if (keys.toSet().size != keys.size) throw receiptCorrupt("RECEIPT_DUP_KEY")
        keys.forEach { if (it !in KNOWN_RECEIPT_KEYS) throw receiptCorrupt("RECEIPT_KEY:$it") }
        fun required(name: String): TimeJson.Value =
            obj.entries.firstOrNull { it.first == name }?.second ?: throw receiptCorrupt("RECEIPT_MISSING:$name")
        if (strStrict(required("schema")) != SCHEMA_RECEIPT) throw receiptCorrupt("RECEIPT_SCHEMA")
        val token = strStrict(required("token"))
        val eventId = strStrict(required("event_id"))
        val op = strStrict(required("op"))
        val ts = try {
            TimeFields.stamp(strStrict(required("ts")))
        } catch (e: IllegalArgumentException) {
            throw receiptCorrupt("RECEIPT_TS:${e.message}")
        }
        val beforeHash = strStrict(required("before_hash"))
        val afterHash = strStrict(required("after_hash"))
        for (hash in listOf(token, beforeHash, afterHash)) {
            if (!hash.matches(HEX64)) throw receiptCorrupt("RECEIPT_HASH")
        }
        return ParsedReceipt(token, eventId, op, ts, beforeHash, afterHash)
    }

    // ---------- plan describe ----------

    fun planDescribe(plan: ReferenceStorePlan): String = TimeJson.encode(
        TimeJson.Value.Obj(
            listOf(
                "root_fingerprint" to TimeJson.Value.Str(plan.root()),
                "token" to TimeJson.Value.Str(plan.token),
                "confirmation" to TimeJson.Value.Str(plan.confirmation),
                "event_id" to TimeJson.Value.Str(plan.eventId),
                "op" to TimeJson.Value.Str(plan.op),
                "ts" to TimeJson.Value.Str(plan.ts),
                "before_hash" to TimeJson.Value.Str(plan.beforeHash),
                "before_size" to TimeJson.Value.Num(plan.beforeSize.toString()),
                "before_sha256" to TimeJson.Value.Str(plan.beforeSha256),
                "suffix_hash" to TimeJson.Value.Str(plan.suffixHash),
                "after_hash" to TimeJson.Value.Str(plan.afterHash),
            )
        )
    )

    // ---------- helpers ----------

    private fun strStrict(value: TimeJson.Value): String =
        (value as? TimeJson.Value.Str)?.text ?: throw corrupt("REF_NOT_STRING")

    private fun corrupt(detail: String): ReferenceStoreException =
        ReferenceStoreException(ReferenceStoreCodes.FILE_CORRUPT, detail)

    private fun pendingCorrupt(detail: String): ReferenceStoreException =
        ReferenceStoreException(ReferenceStoreCodes.PENDING_CORRUPT, detail)

    private fun cancelledCorrupt(detail: String): ReferenceStoreException =
        ReferenceStoreException(ReferenceStoreCodes.CANCELLED_CORRUPT, detail)

    private fun receiptCorrupt(detail: String): ReferenceStoreException =
        ReferenceStoreException(ReferenceStoreCodes.RECEIPT_CORRUPT, detail)
}

/** One strictly parsed event line, raw text preserved for the hash chain. */
internal class ParsedEvent(
    val line: String,
    val eventId: String,
    val op: String,
    val ts: String,
    val prevHead: String,
    val entry: ReferenceEntry?,
    val pick: PickEventRecord?,
    val queuesAfter: ReferenceStoreQueues?,
    val unknownKeys: List<Pair<String, TimeJson.Value>>,
)

/** One strictly parsed pending proposal (root-bound, real before-image). */
internal class ParsedPending(
    val rootFingerprint: String,
    val token: String,
    val eventId: String,
    val op: String,
    val ts: String,
    val beforeHash: String,
    val beforeSize: Long,
    val beforeSha256: String,
    val proposalLine: String,
    val suffixHash: String,
    val afterHash: String,
) {
    fun toPublic(): ReferenceStorePending = ReferenceStorePending(
        rootFingerprint = rootFingerprint,
        token = token,
        eventId = eventId,
        op = op,
        ts = ts,
        beforeHash = beforeHash,
        beforeSize = beforeSize,
        beforeSha256 = beforeSha256,
        suffixHash = suffixHash,
        afterHash = afterHash,
    )
}

internal class ParsedReceipt(
    val token: String,
    val eventId: String,
    val op: String,
    val ts: String,
    val beforeHash: String,
    val afterHash: String,
)
