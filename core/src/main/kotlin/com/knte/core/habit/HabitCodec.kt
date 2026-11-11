package com.knte.core.habit

import com.knte.core.time.format.TimeFields
import com.knte.core.time.format.TimeHasher
import com.knte.core.time.format.TimeJson
import com.knte.core.time.format.TimeJsonValues
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Identity row carried by a `define` event. `created_ts` is immutable once written. */
data class HabitIdentity(
    val id: String,
    val name: String,
    val targetDays: Int,
    val createdTs: String,
    val archived: Boolean,
    val unknownKeys: List<Pair<String, TimeJson.Value>> = emptyList()
) {
    internal fun encodeValue(): TimeJson.Value {
        val entries = mutableListOf<Pair<String, TimeJson.Value>>()
        entries += "id" to TimeJson.Value.Str(id)
        entries += "name" to TimeJson.Value.Str(name)
        entries += "target_days" to TimeJson.Value.Num(targetDays.toString())
        entries += "created_ts" to TimeJson.Value.Str(createdTs)
        entries += "archived" to TimeJson.Value.Bool(archived)
        unknownKeys.forEach { entries += it }
        return TimeJson.Value.Obj(entries)
    }
}

/** One materialized check row. `streak`/`stage` are cached hints validated against the projection. */
data class HabitCheck(
    val habitId: String,
    val name: String,
    val date: String,
    val done: Boolean,
    val note: String?,
    val streak: Int?,
    val stage: String?,
    val unknownKeys: List<Pair<String, TimeJson.Value>> = emptyList()
) {
    fun day(): LocalDate = LocalDate.parse(date)
}

/** One line of the append-only habit log. Unknown keys are preserved verbatim, never rebuilt.
 *  Check-level unknown keys live only in [HabitCheck.unknownKeys]; define/event-level unknown keys
 *  live only in [HabitEvent.unknownKeys], so canonical encoding never emits a key twice. */
data class HabitEvent(
    val eventId: String,
    val op: String,
    val ts: String,
    val identity: HabitIdentity?,
    val check: HabitCheck?,
    val unknownKeys: List<Pair<String, TimeJson.Value>>,
    val atTrust: String
) {
    /** Canonical single-line JSON used for NEW appends only; historical bytes are never rewritten. */
    fun encode(): String = TimeJson.encode(canonical())

    /** Content hash of the canonical line: binds a plan to the exact event bytes. */
    fun token(): String = TimeHasher.sha256(TimeJson.encodeUtf8Strict(encode()))

    internal fun canonical(): TimeJson.Value {
        val entries = mutableListOf<Pair<String, TimeJson.Value>>()
        entries += "event_id" to TimeJson.Value.Str(eventId)
        entries += "op" to TimeJson.Value.Str(op)
        if (op == "define") {
            entries += "habit" to identity!!.encodeValue()
        } else {
            val c = check!!
            entries += "id" to TimeJson.Value.Str(c.habitId)
            entries += "name" to TimeJson.Value.Str(c.name)
            entries += "date" to TimeJson.Value.Str(c.date)
            entries += "done" to TimeJson.Value.Bool(c.done)
            if (c.note != null) entries += "note" to TimeJson.Value.Str(c.note)
            if (c.streak != null) entries += "streak" to TimeJson.Value.Num(c.streak.toString())
            if (c.stage != null) entries += "stage" to TimeJson.Value.Str(c.stage)
            c.unknownKeys.forEach { entries += it }
        }
        unknownKeys.forEach { entries += it }
        entries += "ts" to TimeJson.Value.Str(ts)
        entries += "at_trust" to TimeJson.Value.Str(atTrust)
        return TimeJson.Value.Obj(entries)
    }
}

/** Strict codec for the private isolated habit log (`<root>/db/habits.jsonl`, schema knte-habit-v1).
 *
 *  Vocabulary mirrors the production PRIVATE bucket habit event log (HabitCore), i.e. the
 *  bucket-private `define` + `check` format. It is NOT the formal §9.1 check-only
 *  `db/habits.jsonl`, which has no event_id / at_trust / define and is therefore
 *  incompatible with this store. The two formats are never mixed here.
 *
 *   - `define` carries the identity object: create / edit / archive / restore;
 *   - `check`  carries one per-day done=true|false row.
 *  Unknown op values fail explicitly. Ids, dates and timestamps are strict. `at_trust` is always
 *  "unverified" because a device clock is never trusted. This object never reads the device clock.
 */
object HabitCodec {
    const val MAX_FILE_BYTES = 2 * 1024 * 1024
    const val MAX_EVENTS = 10000
    const val MAX_HABITS = 200
    const val MAX_LINE_CHARS = 32768
    const val FILE_NAME = "habits.jsonl"
    const val SCHEMA = "knte-habit-v1"

    val OPS: Set<String> = setOf("define", "check")
    val HABIT_ID: Regex = Regex("h-[0-9a-f]{8}")
    val EVENT_ID: Regex = Regex("r-[0-9a-f]{8}")

    private val IDENTITY_KEYS = setOf("id", "name", "target_days", "created_ts", "archived")
    private val CHECK_KEYS = setOf("id", "name", "date", "done", "note", "streak", "stage")
    private val RESERVED = setOf("op", "event_id", "ts", "at_trust")

    fun hash(bytes: ByteArray): String = TimeHasher.sha256(bytes)

    /** Caller clock: the caller supplies `now`, this object never reads the system clock. */
    fun stamp(now: LocalDateTime): String =
        now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT))

    fun newEventId(): String = "r-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)

    fun habitId(value: String): String {
        require(value.matches(HABIT_ID)) { "HABIT_ID_INVALID" }
        return value
    }

    fun eventId(value: String): String {
        require(value.matches(EVENT_ID)) { "HABIT_EVENT_ID_INVALID" }
        return value
    }

    fun name(value: String): String {
        val clean = TimeFields.limited(value, 80, true)
        require(clean.none { it.isISOControl() }) { "HABIT_NAME_CONTROL_CHAR" }
        return clean
    }

    fun note(value: String): String = TimeFields.limited(value, 2000, false)

    fun targetDays(value: Long): Int {
        require(value in 1..3650) { "HABIT_TARGET_DAYS_RANGE" }
        return value.toInt()
    }

    /** Strict calendar date with an explicit habit-domain error code (no ambient clock). */
    private fun date(value: String): String {
        require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) { "HABIT_DATE_FORMAT_INVALID" }
        require(runCatching { LocalDate.parse(value).toString() == value }.getOrDefault(false)) {
            "HABIT_DATE_NOT_REAL"
        }
        return value
    }

    private fun longValue(value: TimeJson.Value): Long {
        val literal = (value as? TimeJson.Value.Num)?.literal ?: error("HABIT_NUMBER_INVALID")
        return BigDecimal(literal).longValueExact()
    }

    /** Parses one raw line. Unknown keys are kept; unknown op / bad shape fail closed. */
    fun parseEvent(line: String): HabitEvent {
        require(line.length <= MAX_LINE_CHARS) { "HABIT_LINE_TOO_LONG" }
        require(line.isNotEmpty()) { "HABIT_LINE_EMPTY" }
        require('\n' !in line && '\r' !in line) { "HABIT_LINE_NOT_SINGLE" }
        val value = TimeJson.parse(line)
        val obj = value as? TimeJson.Value.Obj ?: error("HABIT_EVENT_NOT_OBJECT")
        val map = LinkedHashMap<String, TimeJson.Value>()
        obj.entries.forEach { (k, v) ->
            require(!map.containsKey(k)) { "HABIT_EVENT_DUPLICATE_KEY" }
            map[k] = v
        }
        val op = map["op"]?.let { TimeJsonValues.str(it) } ?: error("HABIT_OP_MISSING")
        require(op in OPS) { "HABIT_OP_UNSUPPORTED:$op" }
        val eventId = map["event_id"]?.let { eventId(TimeJsonValues.str(it)) } ?: error("HABIT_EVENT_ID_MISSING")
        val ts = map["ts"]?.let { TimeFields.stamp(TimeJsonValues.str(it)) } ?: error("HABIT_TS_MISSING")
        val atTrust = map["at_trust"]?.let { TimeJsonValues.str(it) } ?: error("HABIT_AT_TRUST_MISSING")
        require(atTrust == "unverified") { "HABIT_AT_TRUST_UNSUPPORTED" }
        val known = if (op == "define") setOf("habit") else CHECK_KEYS
        val unknownKeys = map.entries
            .filter { it.key !in RESERVED && it.key !in known }
            .map { it.key to it.value }
        val event = if (op == "define") {
            val raw = map["habit"] ?: error("HABIT_IDENTITY_MISSING")
            HabitEvent(eventId, op, ts, parseIdentity(raw), null, unknownKeys, atTrust)
        } else {
            // Check-level unknown keys belong to HabitCheck only. Keeping them in the event
            // as well would duplicate every unknown key in canonical().
            HabitEvent(eventId, op, ts, null, parseCheck(map, unknownKeys), emptyList(), atTrust)
        }
        // A check day may never be later than its own write timestamp (no future check-ins).
        if (op == "check") {
            require(!event.check!!.day().isAfter(LocalDate.parse(ts.substring(0, 10)))) { "HABIT_FUTURE_CHECK" }
        }
        return event
    }

    private fun parseIdentity(raw: TimeJson.Value): HabitIdentity {
        val obj = raw as? TimeJson.Value.Obj ?: error("HABIT_IDENTITY_NOT_OBJECT")
        val entries = obj.entries.toMap()
        val id = habitId(TimeJsonValues.str(entries["id"] ?: error("HABIT_ID_MISSING")))
        require(id != HabitProjection.DAILY_ID) { "HABIT_DAILY_RESERVED" }
        val habitName = name(TimeJsonValues.str(entries["name"] ?: error("HABIT_NAME_MISSING")))
        val target = targetDays(longValue(entries["target_days"] ?: error("HABIT_TARGET_DAYS_MISSING")))
        val created = TimeFields.stamp(TimeJsonValues.str(entries["created_ts"] ?: error("HABIT_CREATED_TS_MISSING")))
        val archived = TimeJsonValues.bool(entries["archived"] ?: error("HABIT_ARCHIVED_MISSING"))
        val unknown = obj.entries.filter { (k, _) -> k !in IDENTITY_KEYS }
        return HabitIdentity(id, habitName, target, created, archived, unknown)
    }

    private fun parseCheck(
        map: Map<String, TimeJson.Value>,
        unknownKeys: List<Pair<String, TimeJson.Value>>
    ): HabitCheck {
        val id = habitId(TimeJsonValues.str(map["id"] ?: error("HABIT_ID_MISSING")))
        val habitName = name(TimeJsonValues.str(map["name"] ?: error("HABIT_NAME_MISSING")))
        val day = date(TimeJsonValues.str(map["date"] ?: error("HABIT_DATE_MISSING")))
        val done = TimeJsonValues.bool(map["done"] ?: error("HABIT_DONE_MISSING"))
        val note = map["note"]?.let { if (it is TimeJson.Value.Null) null else note(TimeJsonValues.str(it)) }
        val streak = map["streak"]?.let { v ->
            val n = longValue(v); require(n in 0..MAX_EVENTS.toLong()) { "HABIT_STREAK_RANGE" }; n.toInt()
        }
        val stage = map["stage"]?.let {
            if (it is TimeJson.Value.Null) null else TimeFields.limited(TimeJsonValues.str(it), 20, false)
        }
        return HabitCheck(id, habitName, day, done, note, streak, stage, unknownKeys)
    }

    /** Builds a `define` event.
     *
     *  create = true -> the habit must NOT exist yet and name + target are required (a second
     *  create of the same id is refused, never silently turned into an edit).
     *  create = false -> the habit must exist (edit / archive / restore); `created_ts` is carried
     *  over unchanged, so identity is never recreated. */
    fun buildDefine(snapshot: HabitSnapshot, eventId: String, ts: String, id: String,
                    create: Boolean, name: String?, targetDays: Int?, archived: Boolean?): HabitEvent {
        val habitId = habitId(id)
        require(habitId != HabitProjection.DAILY_ID) { "HABIT_DAILY_RESERVED" }
        require(TimeFields.stamp(ts) == ts) { "HABIT_TS_INVALID" }
        val existing = snapshot.identity(habitId)
        if (create) {
            require(existing == null) { "HABIT_CREATE_CONFLICT" }
            require(name != null && targetDays != null && archived == null) {
                "HABIT_CREATE_FIELDS_REQUIRED"
            }
            return HabitEvent(eventId(eventId), "define", ts,
                HabitIdentity(habitId, name(name), targetDays(targetDays.toLong()), ts, false),
                null, emptyList(), "unverified")
        }
        require(existing != null) { "HABIT_NOT_FOUND" }
        val identity = HabitIdentity(
            id = habitId,
            name = if (name != null) name(name) else existing.name,
            targetDays = if (targetDays != null) targetDays(targetDays.toLong()) else existing.targetDays,
            createdTs = existing.createdTs,
            archived = archived ?: existing.archived,
            unknownKeys = existing.unknownKeys
        )
        return HabitEvent(eventId(eventId), "define", ts, identity, null, emptyList(), "unverified")
    }

    /** Builds a `check` event. The habit must exist and not be archived (日新 is identity-free).
     *  streak/stage are recomputed from the projected history and stored as cached hints only. */
    fun buildCheck(snapshot: HabitSnapshot, eventId: String, ts: String, id: String,
                   date: String, done: Boolean, note: String?): HabitEvent {
        val habitId = habitId(id)
        val day = date(date)
        require(TimeFields.stamp(ts) == ts) { "HABIT_TS_INVALID" }
        val habitName = if (habitId == HabitProjection.DAILY_ID) {
            HabitProjection.DAILY_NAME
        } else {
            val existing = snapshot.identity(habitId) ?: error("HABIT_NOT_FOUND")
            require(!existing.archived) { "HABIT_ARCHIVED" }
            existing.name
        }
        require(!LocalDate.parse(day).isAfter(LocalDate.parse(ts.substring(0, 10)))) { "HABIT_FUTURE_CHECK" }
        val cleanNote = note?.let { note(it) }
        val projected = LinkedHashMap(snapshot.latestMap())
        projected[HabitProjection.key(habitId, LocalDate.parse(day))] =
            HabitCheck(habitId, habitName, day, done, cleanNote, null, null)
        val streak = HabitProjection.streak(projected, habitId, LocalDate.parse(day))
        return HabitEvent(
            eventId = eventId(eventId),
            op = "check",
            ts = ts,
            identity = null,
            check = HabitCheck(habitId, habitName, day, done, cleanNote, streak,
                HabitProjection.stage(streak)),
            unknownKeys = emptyList(),
            atTrust = "unverified"
        )
    }
}
