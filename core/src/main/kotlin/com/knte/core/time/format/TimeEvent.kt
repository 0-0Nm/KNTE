package com.knte.core.time.format

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One materialized line of `timetrack.jsonl`. Append-only: nothing here ever rewrites history. */
data class TimeEvent(
    val op: String,
    val eventId: String,
    val ts: String,
    val fields: Map<String, TimeJson.Value>,
    val unknownKeys: List<Pair<String, TimeJson.Value>>,
    val atTrust: String? = null
) {
    /** Field lookup that does not guess: null means "key absent". */
    fun field(name: String): TimeJson.Value? = fields[name]
    fun text(name: String): String? = fields[name]?.let { TimeJsonValues.str(it) }
    fun textList(name: String): List<String>? = fields[name]?.let { TimeJsonValues.strList(it) }

    /** Canonical single-line JSON. Unknown keys keep their original relative position and shape. */
    fun encode(): String = TimeJson.encode(canonical())

    /** Hash over the canonical bytes; used for approval tokens and event identity. */
    fun token(): String = TimeHasher.sha256(TimeJson.encodeUtf8Strict(encode()))

    internal fun canonical(): TimeJson.Value {
        val entries = mutableListOf<Pair<String, TimeJson.Value>>()
        entries += "op" to TimeJson.Value.Str(op)
        if (eventId.isNotEmpty()) entries += "event_id" to TimeJson.Value.Str(eventId)
        fields.forEach { (k, v) -> entries += k to v }
        unknownKeys.forEach { (k, v) -> entries += k to v }
        entries += "ts" to TimeJson.Value.Str(ts)
        atTrust?.let { entries += "at_trust" to TimeJson.Value.Str(it) }
        return TimeJson.Value.Obj(entries)
    }
}

/** Event codec: strict on known keys, transparent on unknown keys, closed on unsupported values. */
object TimeEventCodec {
    private val OPS = setOf("start", "stop", "amend", "rename_project")
    private val START_KEYS = setOf("id", "date", "start", "project", "tags", "note", "repeat_id")
    private val STOP_KEYS = setOf("id", "end", "end_date")
    private val AMEND_KEYS = setOf("target", "field", "value", "reason")
    private val RENAME_KEYS = setOf("from", "to")
    private val DERIVED_KEYS = setOf("status", "duration_min", "duration_sec", "duration")

    /** Validates and canonicalizes one raw line. Never invents missing keys. */
    fun parse(line: String): TimeEvent {
        require(line.length <= MAX_LINE_CHARS) { "TIME_LINE_TOO_LONG" }
        require(line.isNotEmpty()) { "TIME_LINE_EMPTY" }
        require('\n' !in line && '\r' !in line) { "TIME_LINE_NOT_SINGLE" }
        val value = TimeJson.parse(line)
        val obj = value as? TimeJson.Value.Obj ?: error("TIME_EVENT_NOT_OBJECT")
        val map = linkedMapOf<String, TimeJson.Value>()
        obj.entries.forEach { (k, v) ->
            require(!map.containsKey(k)) { "TIME_EVENT_DUPLICATE_KEY" }
            map[k] = v
        }
        val op = map["op"]?.let { TimeJsonValues.str(it) } ?: error("TIME_OP_MISSING")
        require(op in OPS) { "TIME_OP_UNSUPPORTED" }
        map.keys.forEach { k -> require(k !in DERIVED_KEYS) { "TIME_DERIVED_FIELD_PERSISTED" } }
        val eventId = map["event_id"]?.let {
            val id = TimeJsonValues.str(it)
            require(id.isNotEmpty()) { "TIME_EVENT_ID_EMPTY" }
            id
        } ?: ""
        val ts = map["ts"]?.let { TimeFields.stamp(TimeJsonValues.str(it)) } ?: error("TIME_TS_MISSING")
        if (map.containsKey("at_trust")) {
            val trust = TimeJsonValues.str(map.getValue("at_trust"))
            require(trust == "unverified") { "TIME_TRUST_UNSUPPORTED" }
        }
        val known = when (op) {
            "start" -> START_KEYS
            "stop" -> STOP_KEYS
            "amend" -> AMEND_KEYS
            else -> RENAME_KEYS
        }
        val fields = linkedMapOf<String, TimeJson.Value>()
        val unknown = mutableListOf<Pair<String, TimeJson.Value>>()
        map.forEach { (k, v) ->
            if (k in RESERVED) return@forEach
            if (k in known) fields[k] = v else unknown += k to v
        }
        when (op) {
            "start" -> {
                fields["id"] = TimeJson.Value.Str(TimeFields.id(requireStr(fields, "id")))
                fields["date"] = TimeJson.Value.Str(TimeFields.date(requireStr(fields, "date")))
                fields["start"] = TimeJson.Value.Str(TimeFields.hm(requireStr(fields, "start")))
                fields["project"] = TimeJson.Value.Str(TimeFields.limited(requireStr(fields, "project"), 100, true))
                fields["tags"]?.let { TimeJson.Value.Arr(TimeFields.tags(TimeJsonValues.strList(it)).map { v -> TimeJson.Value.Str(v) }) }
                    ?.also { fields["tags"] = it }
                fields["note"]?.let {
                    fields["note"] = if (it is TimeJson.Value.Null) it else
                        TimeJson.Value.Str(TimeFields.limited(TimeJsonValues.str(it), 40, false))
                }
                fields["repeat_id"]?.let {
                    fields["repeat_id"] = if (it is TimeJson.Value.Null) it else
                        TimeJson.Value.Str(TimeFields.repeatId(TimeJsonValues.str(it)))
                }
            }
            "stop" -> {
                fields["id"] = TimeJson.Value.Str(TimeFields.id(requireStr(fields, "id")))
                fields["end"] = TimeJson.Value.Str(TimeFields.hm(requireStr(fields, "end")))
                fields["end_date"]?.let {
                    fields["end_date"] = if (it is TimeJson.Value.Null) it else
                        TimeJson.Value.Str(TimeFields.date(TimeJsonValues.str(it)))
                }
            }
            "amend" -> {
                fields["target"] = TimeJson.Value.Str(TimeFields.id(requireStr(fields, "target")))
                val field = requireStr(fields, "field")
                require(field in TimeFields.AMEND_FIELDS) { "TIME_AMEND_FIELD_UNSUPPORTED" }
                val value = map["value"] ?: error("TIME_AMEND_VALUE_MISSING")
                fields["value"] = amendValue(field, value)
                fields["reason"]?.let {
                    fields["reason"] = if (it is TimeJson.Value.Null) it else
                        TimeJson.Value.Str(TimeFields.limited(TimeJsonValues.str(it), 200, false))
                }
            }
            "rename_project" -> {
                fields["from"] = TimeJson.Value.Str(TimeFields.limited(requireStr(fields, "from"), 100, true))
                fields["to"] = TimeJson.Value.Str(TimeFields.limited(requireStr(fields, "to"), 100, true))
            }
        }
        return TimeEvent(op, eventId, ts, fields, unknown.toList(), map["at_trust"]?.let { TimeJsonValues.str(it) })
    }

    private fun amendValue(field: String, value: TimeJson.Value): TimeJson.Value = when (field) {
        "date" -> TimeJson.Value.Str(TimeFields.date(TimeJsonValues.str(value)))
        "start", "end" -> TimeJson.Value.Str(TimeFields.hm(TimeJsonValues.str(value)))
        "end_date" -> nullable(value) { TimeJson.Value.Str(TimeFields.date(TimeJsonValues.str(it))) }
        "project" -> TimeJson.Value.Str(TimeFields.limited(TimeJsonValues.str(value), 100, true))
        "note" -> nullable(value) { TimeJson.Value.Str(TimeFields.limited(TimeJsonValues.str(it), 40, false)) }
        "tags" -> nullable(value) {
            val tags = TimeFields.tags(TimeJsonValues.strList(it))
            TimeJson.Value.Arr(tags.map { t -> TimeJson.Value.Str(t) })
        }
        else -> error("TIME_AMEND_FIELD_UNSUPPORTED")
    }

    private inline fun nullable(value: TimeJson.Value, transform: (TimeJson.Value) -> TimeJson.Value): TimeJson.Value =
        if (value is TimeJson.Value.Null) value else transform(value)

    private fun requireStr(fields: Map<String, TimeJson.Value>, key: String): String {
        val v = fields[key] ?: error("TIME_FIELD_MISSING_$key")
        return TimeJsonValues.str(v)
    }

    /** Serializes a new event for append. `ts`/`at_trust` are written by the caller clock, never inferred. */
    fun encodeNew(
        op: String,
        eventId: String,
        ts: LocalDateTime,
        fields: Map<String, TimeJson.Value>,
        unknownKeys: List<Pair<String, TimeJson.Value>> = emptyList()
    ): String = TimeEvent(op, eventId, stamp(ts), fields, unknownKeys, "unverified").encode()

    fun stamp(time: LocalDateTime): String =
        time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT))

    const val MAX_LINE_CHARS = 32768
    private val RESERVED = setOf("op", "event_id", "ts", "at_trust")
}

/** SHA-256 helper shared by the repository and the tests. */
object TimeHasher {
    fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}
