package com.knte.core.reference.formal

import com.knte.core.reference.RandomSource
import com.knte.core.reference.ReferenceEntry
import com.knte.core.reference.ReferencePool
import com.knte.core.reference.ReferenceScope
import com.knte.core.reference.TurnBinding
import com.knte.core.time.format.TimeFields
import com.knte.core.time.format.TimeJson
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Collections

/**
 * Pure-domain layout for the FOUR formal reference files of 数据契约 v2 §7.1–7.5.
 * These four members, and ONLY these four, are in scope. 珠玑 / V9 (§7.6, V9) are NOT covered.
 *
 * No file IO, no SAF, no formal root, no migration, no network, no Android, no store bridge.
 * The input is an INDEPENDENT typed event model ([INPUT_MODEL]): the isolated Store's private
 * aggregate event schema is deliberately NOT reused and is NOT implicitly migrated here.
 * Reused domain values: ReferenceEntry rows, ReferenceWeight/ReferenceSelect/ReferencePick,
 * ReferenceScope, and core/time/format/TimeJson (the existing strict JSON implementation).
 */
object FormalReferenceLayout {
    const val MEMO_PATH = "db/memo.jsonl"
    const val PITFALL_PATH = "db/pitfall.jsonl"
    const val MEMO_PICK_PATH = "db/memo_pick.jsonl"
    const val PICK_LOG_PATH = "db/pick_log.jsonl"

    const val MEMBER_MEMO = "memo"
    const val MEMBER_PITFALL = "pitfall"
    const val MEMBER_MEMO_PICK = "memo_pick"
    const val MEMBER_PICK_LOG = "pick_log"

    /** Fixed member order: any missing / duplicate / reordered / extra member fails closed. */
    val MEMBERS: List<String> = Collections.unmodifiableList(
        listOf(MEMBER_MEMO, MEMBER_PITFALL, MEMBER_MEMO_PICK, MEMBER_PICK_LOG)
    )

    val PATHS: Map<String, String> = Collections.unmodifiableMap(
        linkedMapOf(
            MEMBER_MEMO to MEMO_PATH,
            MEMBER_PITFALL to PITFALL_PATH,
            MEMBER_MEMO_PICK to MEMO_PICK_PATH,
            MEMBER_PICK_LOG to PICK_LOG_PATH,
        )
    )

    const val INPUT_MODEL = "knte.reference.formal.typed-events/1"
    const val NOT_A_MIGRATION =
        "independent typed events; no implicit migration from the isolated Store aggregate"
    const val AT_TRUST = "unverified"
    const val OP_UPSERT = "upsert"

    /** N = 3, two independent recent queues (契约 §7.4). */
    const val N_RECENT = 3
    const val MAX_EVENTS = 10_000
    const val MAX_LINES_PER_MEMBER = 10_000
    const val MAX_FORWARD_KEYS = 64
    const val MAX_FORWARD_NODES = 512
    const val MAX_FORWARD_TEXT = 6_000
}

/** Machine-checkable failure codes. Every rejection is fail-closed, never a silent default. */
object FormalReferenceCodes {
    const val UNSUPPORTED = "FORMAL_UNSUPPORTED"
    const val UNSUPPORTED_EVENT = "FORMAL_UNSUPPORTED_EVENT"
    const val UNSUPPORTED_SPLIT = "FORMAL_UNSUPPORTED_SPLIT"
    const val UNSUPPORTED_FIELD = "FORMAL_UNSUPPORTED_FIELD"
    const val EMPTY_POOL = "FORMAL_EMPTY_POOL"
    const val BOM = "FORMAL_BOM"
    const val NOT_SINGLE_LINE = "FORMAL_NOT_SINGLE_LINE"
    const val LINE_TOO_LONG = "FORMAL_LINE_TOO_LONG"
    const val JSON_SHAPE = "FORMAL_JSON_SHAPE"
    const val DUPLICATE_KEY = "FORMAL_DUPLICATE_KEY"
    const val OPTIONAL_NULL = "FORMAL_OPTIONAL_NULL"
    const val SEED_MUST_BE_OMITTED = "FORMAL_SEED_MUST_BE_OMITTED"
    const val FIELD_MISSING = "FORMAL_FIELD_MISSING"
    const val FIELD_TYPE = "FORMAL_FIELD_TYPE"
    const val OP_INVALID = "FORMAL_OP_INVALID"
    const val TRUST_INVALID = "FORMAL_TRUST_INVALID"
    const val ID_INVALID = "FORMAL_ID_INVALID"
    const val POOL_INVALID = "FORMAL_POOL_INVALID"
    const val POOL_PREFIX = "FORMAL_POOL_PREFIX"
    const val COUNT_INVALID = "FORMAL_COUNT_INVALID"
    const val DATE_INVALID = "FORMAL_DATE_INVALID"
    const val TS_INVALID = "FORMAL_TS_INVALID"
    const val TEXT_INVALID = "FORMAL_TEXT_INVALID"
    const val CONTROL_CHAR = "FORMAL_CONTROL_CHAR"
    const val SURROGATE = "FORMAL_SURROGATE"
    const val WEIGHT_NOT_FINITE = "FORMAL_WEIGHT_NOT_FINITE"
    const val WEIGHT_RANGE = "FORMAL_WEIGHT_RANGE"
    const val FORWARD_KNOWN_KEY = "FORMAL_FORWARD_KNOWN_KEY"
    const val FORWARD_TOO_DEEP = "FORMAL_FORWARD_TOO_DEEP"
    const val CAPACITY = "FORMAL_CAPACITY"
    const val MEMBER_SET = "FORMAL_MEMBER_SET"
    const val MEMBER_REPLACED = "FORMAL_MEMBER_REPLACED"
    const val MEMBER_ORDER = "FORMAL_MEMBER_ORDER"
    const val BASELINE_DRIFT = "FORMAL_BASELINE_DRIFT"
    const val STALE_CONFIRMATION = "FORMAL_STALE_CONFIRMATION"
    const val CONFIRMATION_INVALID = "FORMAL_CONFIRMATION_INVALID"
    const val PLAN_CONSUMED = "FORMAL_PLAN_CONSUMED"
    const val REPLAY = "FORMAL_REPLAY"
}

class FormalReferenceException(val code: String, detail: String) :
    RuntimeException("$code: $detail")

internal fun fail(code: String, detail: String = ""): Nothing =
    throw FormalReferenceException(code, detail)

/**
 * Legacy declarations are preserved verbatim (they live unchanged in the isolated Store).
 * This object mirrors them as literals so the pure formal domain has ZERO link to the store
 * package. New formal-only gaps are listed separately. Nothing here is silently emulated.
 */
object FormalReferenceUnsupported {
    const val PRODUCTION_WRITES_SUPPORTED: Boolean = false

    const val AI_SAME_PITFALL = "STORE_UNSUPPORTED_AI_SAME_PITFALL"
    const val COUNT_MERGE_SPLIT = "STORE_UNSUPPORTED_COUNT_MERGE_SPLIT"
    const val FORMAL_EXPORT_IMPORT = "STORE_UNSUPPORTED_FORMAL_EXPORT_IMPORT"
    const val MULTI_FILE_TRANSACTION = "STORE_UNSUPPORTED_MULTI_FILE_TRANSACTION"
    const val PRODUCTION_WRITE = "STORE_UNSUPPORTED_PRODUCTION_WRITE"
    const val AUTO_RECOVERY = "STORE_UNSUPPORTED_AUTO_RECOVERY"

    const val FILE_IO = "FORMAL_UNSUPPORTED_FILE_IO"
    const val SAF = "FORMAL_UNSUPPORTED_SAF"
    const val FORMAL_ROOT = "FORMAL_UNSUPPORTED_FORMAL_ROOT"
    const val MIGRATION = "FORMAL_UNSUPPORTED_MIGRATION"
    const val NETWORK = "FORMAL_UNSUPPORTED_NETWORK"
    const val ANDROID = "FORMAL_UNSUPPORTED_ANDROID"
    const val STORE_BRIDGE = "FORMAL_UNSUPPORTED_STORE_BRIDGE"
    const val SPLIT_TWO_UPSERTS = "FORMAL_UNSUPPORTED_SPLIT_TWO_UPSERTS"
    const val PRIVATE_HISTORY_EVENTS = "FORMAL_UNSUPPORTED_PRIVATE_HISTORY_EVENTS"
    const val WEIGHT_SNAPSHOT_SCHEMA = "FORMAL_UNSUPPORTED_WEIGHT_SNAPSHOT_SCHEMA"
    const val DISK_ATOMICITY = "FORMAL_UNSUPPORTED_DISK_ATOMICITY"
    const val PERSISTENT_TRANSACTION = "FORMAL_UNSUPPORTED_PERSISTENT_TRANSACTION"
    const val PITFALL_RECENT_BEFORE_FIELD = "FORMAL_UNSUPPORTED_PITFALL_RECENT_BEFORE_FIELD"
    const val OPTIONAL_NULL_CONFLICT = "FORMAL_UNSUPPORTED_OPTIONAL_NULL_CONFLICT"
    const val BODY_FIELD = "FORMAL_UNSUPPORTED_BODY_FIELD"
    const val SEED_EXAMPLE_CONFLICT = "FORMAL_UNSUPPORTED_SEED_EXAMPLE_CONFLICT"

    val ALL: Set<String> = Collections.unmodifiableSet(
        linkedSetOf(
            AI_SAME_PITFALL, COUNT_MERGE_SPLIT, FORMAL_EXPORT_IMPORT,
            MULTI_FILE_TRANSACTION, PRODUCTION_WRITE, AUTO_RECOVERY,
            FILE_IO, SAF, FORMAL_ROOT, MIGRATION, NETWORK, ANDROID, STORE_BRIDGE,
            SPLIT_TWO_UPSERTS, PRIVATE_HISTORY_EVENTS, WEIGHT_SNAPSHOT_SCHEMA,
            DISK_ATOMICITY, PERSISTENT_TRANSACTION, PITFALL_RECENT_BEFORE_FIELD,
            OPTIONAL_NULL_CONFLICT, BODY_FIELD, SEED_EXAMPLE_CONFLICT,
        )
    )
}

/** Bounded structural validation shared by decode and encode. Never a loose String replace. */
internal object FormalChecks {
    private val MEMO_ID = Regex("m-[0-9a-f]{8}")
    private val PITFALL_ID = Regex("p-[0-9a-f]{8}")
    private val EVENT_ID = Regex("r-[0-9a-f]{8}")
    private val COUNT_LITERAL = Regex("[1-9][0-9]{0,5}")
    private val WEIGHT_LITERAL = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,2})?")

    fun id(value: String, pool: String): String {
        val pattern = when (pool) {
            FormalReferenceLayout.MEMBER_MEMO -> MEMO_ID
            FormalReferenceLayout.MEMBER_PITFALL -> PITFALL_ID
            "event" -> EVENT_ID
            else -> fail(FormalReferenceCodes.POOL_INVALID, pool)
        }
        if (!pattern.matches(value)) fail(FormalReferenceCodes.ID_INVALID, "$pool:$value")
        return value
    }

    fun poolId(value: String, pool: String): String = when (pool) {
        FormalReferenceLayout.MEMBER_MEMO, FormalReferenceLayout.MEMBER_PITFALL -> id(value, pool)
        else -> fail(FormalReferenceCodes.POOL_INVALID, pool)
    }

    fun text(value: String, max: Int, field: String, nonEmpty: Boolean = false): String {
        if (value.codePointCount(0, value.length) > max) {
            fail(FormalReferenceCodes.TEXT_INVALID, "too-long:$field")
        }
        if (nonEmpty && value.isBlank()) fail(FormalReferenceCodes.TEXT_INVALID, "blank:$field")
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c < ' ' || c == '\u007F') fail(FormalReferenceCodes.CONTROL_CHAR, field)
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) {
                    fail(FormalReferenceCodes.SURROGATE, field)
                }
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) fail(FormalReferenceCodes.SURROGATE, field)
            i++
        }
        return value
    }

    fun stamp(value: String): String = try {
        TimeFields.stamp(value)
    } catch (e: RuntimeException) {
        fail(FormalReferenceCodes.TS_INVALID, value)
    }

    fun date(value: String): String = try {
        TimeFields.date(value)
    } catch (e: RuntimeException) {
        fail(FormalReferenceCodes.DATE_INVALID, value)
    }

    fun tags(values: List<String>): List<String> {
        if (values.size > 20) fail(FormalReferenceCodes.TEXT_INVALID, "tags-count")
        values.forEach { text(it, 40, "tag", true) }
        return values.toList()
    }

    fun count(value: Int): Int {
        if (value !in 1..ReferenceEntry.MAX_COUNT) {
            fail(FormalReferenceCodes.COUNT_INVALID, value.toString())
        }
        return value
    }

    fun countLiteral(literal: String): Int {
        if (!COUNT_LITERAL.matches(literal)) {
            fail(FormalReferenceCodes.COUNT_INVALID, literal)
        }
        return count(literal.toInt())
    }

    /** Rejects exponent forms, NaN, Infinity, negative and out-of-range weights. */
    fun weightLiteral(literal: String): String {
        if (!WEIGHT_LITERAL.matches(literal)) {
            fail(FormalReferenceCodes.WEIGHT_NOT_FINITE, literal)
        }
        val decimal = BigDecimal(literal)
        if (decimal < BigDecimal("0.2") || decimal > BigDecimal("50000.5")) {
            fail(FormalReferenceCodes.WEIGHT_RANGE, literal)
        }
        return literal
    }

    fun forward(values: List<Pair<String, TimeJson.Value>>, known: Set<String>) {
        if (values.size > FormalReferenceLayout.MAX_FORWARD_KEYS) {
            fail(FormalReferenceCodes.FORWARD_TOO_DEEP, "keys")
        }
        val names = values.map { it.first }
        if (names.toSet().size != names.size) {
            fail(FormalReferenceCodes.DUPLICATE_KEY, "forward")
        }
        var nodes = 0
        fun walk(v: TimeJson.Value, depth: Int = 2) {
            if (depth > 16) fail(FormalReferenceCodes.FORWARD_TOO_DEEP, "depth")
            nodes++
            if (nodes > FormalReferenceLayout.MAX_FORWARD_NODES) {
                fail(FormalReferenceCodes.FORWARD_TOO_DEEP, "nodes")
            }
            when (v) {
                is TimeJson.Value.Str -> forwardValueText(v.text)
                is TimeJson.Value.Num -> {
                    if (v.literal.length > 64 || !v.literal.matches(
                            Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                        fail(FormalReferenceCodes.JSON_SHAPE, "number literal")
                    // Finite domain bound for candidate numeric extensions; never emit NaN/Infinity.
                    if (!v.literal.toDouble().isFinite())
                        fail(FormalReferenceCodes.JSON_SHAPE, "nonfinite number")
                }
                is TimeJson.Value.Arr -> v.items.forEach { walk(it, depth + 1) }
                is TimeJson.Value.Obj -> {
                    val keys = v.entries.map { it.first }
                    if (keys.toSet().size != keys.size)
                        fail(FormalReferenceCodes.DUPLICATE_KEY, "nested forward")
                    v.entries.forEach { (k, child) ->
                        text(k, 120, "forward.key", true)
                        walk(child, depth + 1)
                    }
                }
                else -> Unit
            }
        }
        values.forEach { (name, value) ->
            text(name, 120, "forward.key", true)
            if (name in known || name == "at_trust" || name == "ts") {
                fail(FormalReferenceCodes.FORWARD_KNOWN_KEY, name)
            }
            walk(value)
        }
    }

    /** Unknown VALUES may contain JSON-escaped control characters: TimeJson re-escapes them
     *  on encode, so no raw newline can be injected. Raw newline BYTES are rejected earlier
     *  by the codec. Unpaired surrogates and oversized values still fail closed. */
    private fun forwardValueText(value: String) {
        if (value.codePointCount(0, value.length) > FormalReferenceLayout.MAX_FORWARD_TEXT) {
            fail(FormalReferenceCodes.FORWARD_TOO_DEEP, "forward.value-length")
        }
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) {
                    fail(FormalReferenceCodes.SURROGATE, "forward.value")
                }
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) fail(FormalReferenceCodes.SURROGATE, "forward.value")
            i++
        }
    }
}

/** Caller-supplied context only. NO default clock read, no LocalDate.now(), no System time.
 *  A deterministic seed (or an exact ticket list) is REQUIRED: no hidden SecureRandom, no
 *  default. The seed is an RNG spec, NEVER a formal line field: §7.5 shows `seed:null` while
 *  §三 mandates optional-key omission; we adopt OMISSION and never write `seed`.
 *  `tickets` optionally pins an exact draw sequence for exact-weight assertions. */
class FormalReferenceContext private constructor(
    val today: LocalDate,
    val seed: Long?,
    tickets: List<Long>?,
) {
    val tickets: List<Long>? = tickets?.let { Collections.unmodifiableList(it.toList()) }
    init {
        if (seed == null && tickets == null) {
            fail(FormalReferenceCodes.UNSUPPORTED, "context requires caller seed or exact tickets")
        }
        tickets?.let { require(it.isNotEmpty()) { "FORMAL_EMPTY_TICKETS" } }
    }

    /** Fresh RNG for each projection; the caller cannot mutate a shared stateful object. */
    fun newRandom(): RandomSource = when {
        tickets != null -> TicketRandom(tickets)
        seed != null -> SeededFormalRandom(seed)
        else -> fail(FormalReferenceCodes.UNSUPPORTED, "no rng spec")
    }

    fun fingerprint(): TimeJson.Value {
        val p = mutableListOf<Pair<String, TimeJson.Value>>()
        p += "today" to TimeJson.Value.Str(today.toString())
        seed?.let { p += "seed" to TimeJson.Value.Num(it.toString()) }
        tickets?.let {
            p += "tickets" to TimeJson.Value.Arr(it.map { t -> TimeJson.Value.Num(t.toString()) })
        }
        return TimeJson.Value.Obj(p)
    }

    companion object {
        fun seeded(today: LocalDate, seed: Long): FormalReferenceContext =
            FormalReferenceContext(today, seed, null)

        /** Exact ticket sequence: no Monte-Carlo threshold, precise weight assertions. */
        fun exact(today: LocalDate, tickets: List<Long>): FormalReferenceContext =
            FormalReferenceContext(today, null, tickets.toList())

        fun seededTickets(today: LocalDate, seed: Long, tickets: List<Long>): FormalReferenceContext =
            FormalReferenceContext(today, seed, tickets.toList())
    }
}

/** Deterministic xorshift64* RNG. Pure memory; the seed is never persisted into a formal line. */
class SeededFormalRandom(seed: Long) : RandomSource {
    private var state: Long = seed xor -7046029254386353131L

    override fun next(bound: Long): Long {
        if (bound <= 0) fail(FormalReferenceCodes.WEIGHT_RANGE, "random-bound")
        state = state xor (state shl 13)
        state = state xor (state ushr 7)
        state = state xor (state shl 17)
        return (state ushr 1) % bound
    }
}

/** Exact caller-pinned tickets; failing fast if the script is exhausted. */
class TicketRandom(private val tickets: List<Long>) : RandomSource {
    private var index = 0

    override fun next(bound: Long): Long {
        if (bound <= 0) fail(FormalReferenceCodes.WEIGHT_RANGE, "random-bound")
        if (index >= tickets.size) {
            fail(FormalReferenceCodes.UNSUPPORTED, "ticket script exhausted: no auto retry")
        }
        val ticket = tickets[index++]
        if (ticket !in 0 until bound) {
            fail(FormalReferenceCodes.WEIGHT_RANGE, "ticket=$ticket bound=$bound")
        }
        return ticket
    }
}

/** Independent typed events. Unsupported private history events are explicit, not migrated. */
sealed class FormalReferenceEvent {
    abstract val ts: String

    data class MemoUpsert(
        val entry: ReferenceEntry.Memo,
        val forward: List<Pair<String, TimeJson.Value>> = emptyList(),
    ) : FormalReferenceEvent() {
        override val ts: String get() = entry.ts
    }

    data class PitfallUpsert(
        val entry: ReferenceEntry.Pitfall,
        val forward: List<Pair<String, TimeJson.Value>> = emptyList(),
    ) : FormalReferenceEvent() {
        override val ts: String get() = entry.ts
    }

    /** One round = pick_log 1 line + memo_pick 2 lines (one per independent pool). */
    data class PickRound(
        val entries: List<ReferenceEntry>,
        val scope: ReferenceScope,
        override val ts: String,
        val memoRecentBefore: List<String>,
        val pitfallRecentBefore: List<String>,
        val eventId: String,
        val binding: TurnBinding? = null,
        /** Caller may explicitly supply weight_snapshot; the domain never invents its schema. */
        val forward: List<Pair<String, TimeJson.Value>> = emptyList(),
    ) : FormalReferenceEvent()

    /** §7.3 split = two upserts; contract range unclear -> explicit UNSUPPORTED, no invented fields. */
    data class SplitUnsupported(override val ts: String, val reason: String) : FormalReferenceEvent()

    /** Any other (including private Store history) event -> fail-closed UNSUPPORTED. */
    data class Unsupported(override val ts: String, val kind: String, val detail: String = "") :
        FormalReferenceEvent()
}

/** Candidate in-memory line only, NOT a finalized production schema.
 * `forward` preserves unknown JSON keys; no file IO or formal writer is connected. */
sealed class FormalReferenceLine {
    abstract val member: String
    abstract val ts: String
    abstract fun toJson(): TimeJson.Value

    protected fun tail(pairs: MutableList<Pair<String, TimeJson.Value>>, forward: List<Pair<String, TimeJson.Value>>) {
        pairs += "ts" to TimeJson.Value.Str(ts)
        pairs += "at_trust" to TimeJson.Value.Str(FormalReferenceLayout.AT_TRUST)
        forward.forEach { pairs += it }
    }
}

class FormalMemoLine(
    val id: String,
    val title: String,
    val due: String?,
    val done: Boolean,
    val archived: Boolean,
    val tags: List<String>,
    val world: String?,
    val createdTs: String,
    override val ts: String,
    val forward: List<Pair<String, TimeJson.Value>> = emptyList(),
) : FormalReferenceLine() {
    override val member: String = FormalReferenceLayout.MEMBER_MEMO

    init {
        FormalChecks.id(id, FormalReferenceLayout.MEMBER_MEMO)
        FormalChecks.text(title, ReferenceEntry.TITLE_MAX, "title", true)
        due?.let { FormalChecks.date(it) }
        FormalChecks.tags(tags)
        world?.let { FormalChecks.text(it, ReferenceEntry.WORLD_MAX, "world") }
        FormalChecks.stamp(createdTs)
        FormalChecks.stamp(ts)
        FormalChecks.forward(forward, MEMO_KNOWN)
    }

    override fun toJson(): TimeJson.Value {
        val p = mutableListOf<Pair<String, TimeJson.Value>>()
        p += "op" to TimeJson.Value.Str(FormalReferenceLayout.OP_UPSERT)
        p += "id" to TimeJson.Value.Str(id)
        p += "title" to TimeJson.Value.Str(title)
        due?.let { p += "due" to TimeJson.Value.Str(it) }
        p += "done" to TimeJson.Value.Bool(done)
        p += "archived" to TimeJson.Value.Bool(archived)
        p += "tags" to TimeJson.Value.Arr(tags.map { TimeJson.Value.Str(it) })
        world?.let { p += "world" to TimeJson.Value.Str(it) }
        p += "created_ts" to TimeJson.Value.Str(createdTs)
        tail(p, forward)
        return TimeJson.Value.Obj(p)
    }

    companion object {
        val MEMO_KNOWN = setOf(
            "op", "id", "title", "due", "done", "archived", "tags",
            "world", "created_ts", "ts", "at_trust",
        )
    }
}

class FormalPitfallLine(
    val id: String,
    val title: String,
    val count: Int,
    val archived: Boolean,
    val tags: List<String>,
    val world: String?,
    val createdTs: String,
    override val ts: String,
    val forward: List<Pair<String, TimeJson.Value>> = emptyList(),
) : FormalReferenceLine() {
    override val member: String = FormalReferenceLayout.MEMBER_PITFALL

    init {
        FormalChecks.id(id, FormalReferenceLayout.MEMBER_PITFALL)
        FormalChecks.text(title, ReferenceEntry.TITLE_MAX, "title", true)
        FormalChecks.count(count)
        FormalChecks.tags(tags)
        world?.let { FormalChecks.text(it, ReferenceEntry.WORLD_MAX, "world") }
        FormalChecks.stamp(createdTs)
        FormalChecks.stamp(ts)
        FormalChecks.forward(forward, PITFALL_KNOWN)
    }

    override fun toJson(): TimeJson.Value {
        val p = mutableListOf<Pair<String, TimeJson.Value>>()
        p += "op" to TimeJson.Value.Str(FormalReferenceLayout.OP_UPSERT)
        p += "id" to TimeJson.Value.Str(id)
        p += "title" to TimeJson.Value.Str(title)
        p += "count" to TimeJson.Value.Num(count.toString())
        p += "archived" to TimeJson.Value.Bool(archived)
        p += "tags" to TimeJson.Value.Arr(tags.map { TimeJson.Value.Str(it) })
        world?.let { p += "world" to TimeJson.Value.Str(it) }
        p += "created_ts" to TimeJson.Value.Str(createdTs)
        tail(p, forward)
        return TimeJson.Value.Obj(p)
    }

    companion object {
        val PITFALL_KNOWN = setOf(
            "op", "id", "title", "count", "archived", "tags",
            "world", "created_ts", "ts", "at_trust",
        )
    }
}

class FormalMemoPickLine(
    val pool: String,
    val pickedIds: List<String>,
    override val ts: String,
    val forward: List<Pair<String, TimeJson.Value>> = emptyList(),
) : FormalReferenceLine() {
    override val member: String = FormalReferenceLayout.MEMBER_MEMO_PICK

    init {
        if (pool != FormalReferenceLayout.MEMBER_MEMO && pool != FormalReferenceLayout.MEMBER_PITFALL) {
            fail(FormalReferenceCodes.POOL_INVALID, pool)
        }
        if (pickedIds.size > FormalReferenceLayout.N_RECENT) {
            fail(FormalReferenceCodes.CAPACITY, "picked_ids>${FormalReferenceLayout.N_RECENT}")
        }
        pickedIds.forEach { FormalChecks.poolId(it, pool) }
        FormalChecks.stamp(ts)
        FormalChecks.forward(forward, MEMO_PICK_KNOWN)
    }

    override fun toJson(): TimeJson.Value {
        val p = mutableListOf<Pair<String, TimeJson.Value>>()
        p += "pool" to TimeJson.Value.Str(pool)
        p += "picked_ids" to TimeJson.Value.Arr(pickedIds.map { TimeJson.Value.Str(it) })
        tail(p, forward)
        return TimeJson.Value.Obj(p)
    }

    companion object {
        val MEMO_PICK_KNOWN = setOf("pool", "picked_ids", "ts", "at_trust")
    }
}

class FormalPickLogLine(
    val turn: String,
    val memoId: String?,
    val memoW: String?,
    val pitfallId: String?,
    val pitfallW: String?,
    val recentBefore: List<String>,
    override val ts: String,
    val forward: List<Pair<String, TimeJson.Value>> = emptyList(),
) : FormalReferenceLine() {
    override val member: String = FormalReferenceLayout.MEMBER_PICK_LOG

    init {
        FormalChecks.stamp(turn)
        FormalChecks.stamp(ts)
        if ((memoId == null) != (memoW == null)) {
            fail(FormalReferenceCodes.FIELD_MISSING, "memo_id/memo_w pair")
        }
        if ((pitfallId == null) != (pitfallW == null)) {
            fail(FormalReferenceCodes.FIELD_MISSING, "pitfall_id/pitfall_w pair")
        }
        memoId?.let { FormalChecks.id(it, FormalReferenceLayout.MEMBER_MEMO) }
        memoW?.let { FormalChecks.weightLiteral(it) }
        pitfallId?.let { FormalChecks.id(it, FormalReferenceLayout.MEMBER_PITFALL) }
        pitfallW?.let { FormalChecks.weightLiteral(it) }
        if (recentBefore.size > FormalReferenceLayout.N_RECENT) {
            fail(FormalReferenceCodes.CAPACITY, "recent_before>${FormalReferenceLayout.N_RECENT}")
        }
        recentBefore.forEach { FormalChecks.id(it, FormalReferenceLayout.MEMBER_MEMO) }
        FormalChecks.forward(forward, PICK_LOG_KNOWN)
    }

    override fun toJson(): TimeJson.Value {
        val p = mutableListOf<Pair<String, TimeJson.Value>>()
        p += "turn" to TimeJson.Value.Str(turn)
        memoId?.let { p += "memo_id" to TimeJson.Value.Str(it) }
        memoW?.let { p += "memo_w" to TimeJson.Value.Num(it) }
        pitfallId?.let { p += "pitfall_id" to TimeJson.Value.Str(it) }
        pitfallW?.let { p += "pitfall_w" to TimeJson.Value.Num(it) }
        p += "recent_before" to TimeJson.Value.Arr(recentBefore.map { TimeJson.Value.Str(it) })
        // seed is OPTIONAL-OMITTED (契约 §三) and never written; weight_snapshot schema is
        // contract-unspecified, so it is only preserved when the caller explicitly forwards it.
        tail(p, forward)
        return TimeJson.Value.Obj(p)
    }

    companion object {
        val PICK_LOG_KNOWN = setOf(
            "turn", "memo_id", "memo_w", "pitfall_id", "pitfall_w",
            "recent_before", "ts", "at_trust",
        )
    }
}
