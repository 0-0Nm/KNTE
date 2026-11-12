package com.knte.core.reference.formal

import com.knte.core.time.format.TimeJson
import com.knte.core.time.format.TimeJsonValues

/** Strict typed codec for the four formal JSONL line classes. No new JSON parser is added:
 *  core/time/format/TimeJson already rejects duplicate keys, bad escapes and loose surrogates. */
object FormalReferenceCodec {
    const val MAX_LINE_BYTES = 1_000_000

    private fun str(value: TimeJson.Value?, field: String): String {
        if (value == null) failMissing(field)
        return try {
            TimeJsonValues.str(value)
        } catch (e: RuntimeException) {
            throw FormalReferenceException(FormalReferenceCodes.FIELD_TYPE, field)
        }
    }

    private fun bool(value: TimeJson.Value?, field: String): Boolean = when (value) {
        is TimeJson.Value.Bool -> value.value
        null -> failMissing(field)
        else -> throw FormalReferenceException(FormalReferenceCodes.FIELD_TYPE, field)
    }

    private fun num(value: TimeJson.Value?, field: String): String = when (value) {
        is TimeJson.Value.Num -> value.literal
        null -> failMissing(field)
        else -> throw FormalReferenceException(FormalReferenceCodes.FIELD_TYPE, field)
    }

    private fun strList(value: TimeJson.Value?, field: String): List<String> {
        if (value == null) failMissing(field)
        val arr = value as? TimeJson.Value.Arr
            ?: throw FormalReferenceException(FormalReferenceCodes.FIELD_TYPE, field)
        return arr.items.map { str(it, field) }
    }

    private fun failMissing(field: String): Nothing =
        throw FormalReferenceException(FormalReferenceCodes.FIELD_MISSING, field)

    private fun optionalNull(map: Map<String, TimeJson.Value>, key: String) {
        if (map.containsKey(key) && map[key] is TimeJson.Value.Null) {
            throw FormalReferenceException(FormalReferenceCodes.OPTIONAL_NULL, key)
        }
    }

    private fun requiredOp(map: Map<String, TimeJson.Value>) {
        val op = str(map["op"], "op")
        if (op != FormalReferenceLayout.OP_UPSERT) {
            throw FormalReferenceException(FormalReferenceCodes.OP_INVALID, op)
        }
    }

    /** at_trust is canonical on write. §7 examples omit it; read-side accepts that omission
     *  but rejects any other value (including null). Canonical encode always adds it. */
    private fun trust(map: Map<String, TimeJson.Value>) {
        val raw = map["at_trust"] ?: return
        if (raw is TimeJson.Value.Null) {
            throw FormalReferenceException(FormalReferenceCodes.OPTIONAL_NULL, "at_trust")
        }
        val value = str(raw, "at_trust")
        if (value != FormalReferenceLayout.AT_TRUST) {
            throw FormalReferenceException(FormalReferenceCodes.TRUST_INVALID, value)
        }
    }

    /** UTF-8 no BOM, strict decoding, exactly one LF-terminated line, no CR / embedded LF. */
    fun decodeLine(member: String, bytes: ByteArray): FormalReferenceLine {
        if (member !in FormalReferenceLayout.MEMBERS) {
            throw FormalReferenceException(FormalReferenceCodes.MEMBER_SET, member)
        }
        if (bytes.isEmpty()) {
            throw FormalReferenceException(FormalReferenceCodes.NOT_SINGLE_LINE, "empty")
        }
        if (bytes.size > MAX_LINE_BYTES) {
            throw FormalReferenceException(FormalReferenceCodes.LINE_TOO_LONG, bytes.size.toString())
        }
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            throw FormalReferenceException(FormalReferenceCodes.BOM, "utf8-bom")
        }
        if (bytes[bytes.size - 1] != 0x0A.toByte()) {
            throw FormalReferenceException(FormalReferenceCodes.NOT_SINGLE_LINE, "missing-LF")
        }
        for (i in 0 until bytes.size - 1) {
            val b = bytes[i]
            if (b == 0x0A.toByte() || b == 0x0D.toByte()) {
                throw FormalReferenceException(FormalReferenceCodes.NOT_SINGLE_LINE, "embedded-newline")
            }
        }
        val text = try {
            TimeJson.decodeUtf8Strict(bytes.copyOfRange(0, bytes.size - 1))
        } catch (e: Exception) {
            throw FormalReferenceException(FormalReferenceCodes.JSON_SHAPE, "utf8:${e.message}")
        }
        val value = try {
            TimeJson.parse(text)
        } catch (e: RuntimeException) {
            val code = if (e.message?.contains("JSON_DUPLICATE_KEY") == true)
                FormalReferenceCodes.DUPLICATE_KEY else FormalReferenceCodes.JSON_SHAPE
            throw FormalReferenceException(code, "json:${e.message}")
        }
        val obj = value as? TimeJson.Value.Obj
            ?: throw FormalReferenceException(FormalReferenceCodes.JSON_SHAPE, "not-object")
        val map = linkedMapOf<String, TimeJson.Value>()
        obj.entries.forEach { (k, v) ->
            if (map.put(k, v) != null) {
                throw FormalReferenceException(FormalReferenceCodes.DUPLICATE_KEY, k)
            }
        }
        trust(map)
        val known = when (member) {
            FormalReferenceLayout.MEMBER_MEMO -> FormalMemoLine.MEMO_KNOWN
            FormalReferenceLayout.MEMBER_PITFALL -> FormalPitfallLine.PITFALL_KNOWN
            FormalReferenceLayout.MEMBER_MEMO_PICK -> FormalMemoPickLine.MEMO_PICK_KNOWN
            else -> FormalPickLogLine.PICK_LOG_KNOWN
        }
        // Candidate writer generates no seed. An unknown legacy seed must not be silently
        // dropped by a read/canonical roundtrip (v2's example and omission rule disagree).
        val forward = obj.entries.filter { (k, _) -> k !in known }.map { it.first to it.second }
        return when (member) {
            FormalReferenceLayout.MEMBER_MEMO -> decodeMemo(map, forward)
            FormalReferenceLayout.MEMBER_PITFALL -> decodePitfall(map, forward)
            FormalReferenceLayout.MEMBER_MEMO_PICK -> decodeMemoPick(map, forward)
            else -> decodePickLog(map, forward)
        }
    }

    private fun decodeMemo(
        map: Map<String, TimeJson.Value>,
        forward: List<Pair<String, TimeJson.Value>>,
    ): FormalMemoLine {
        requiredOp(map)
        optionalNull(map, "due")
        optionalNull(map, "world")
        val id = str(map["id"], "id")
        FormalChecks.id(id, FormalReferenceLayout.MEMBER_MEMO)
        val title = str(map["title"], "title")
        FormalChecks.text(title, com.knte.core.reference.ReferenceEntry.TITLE_MAX, "title", true)
        val due = map["due"]?.let { FormalChecks.date(str(it, "due")) }
        val world = map["world"]?.let {
            FormalChecks.text(str(it, "world"), com.knte.core.reference.ReferenceEntry.WORLD_MAX, "world")
        }
        val done = bool(map["done"], "done")
        val archived = bool(map["archived"], "archived")
        val tags = FormalChecks.tags(strList(map["tags"], "tags"))
        val createdTs = FormalChecks.stamp(str(map["created_ts"], "created_ts"))
        val ts = FormalChecks.stamp(str(map["ts"], "ts"))
        return FormalMemoLine(id, title, due, done, archived, tags, world, createdTs, ts, forward)
    }

    private fun decodePitfall(
        map: Map<String, TimeJson.Value>,
        forward: List<Pair<String, TimeJson.Value>>,
    ): FormalPitfallLine {
        requiredOp(map)
        optionalNull(map, "world")
        val id = str(map["id"], "id")
        FormalChecks.id(id, FormalReferenceLayout.MEMBER_PITFALL)
        val title = str(map["title"], "title")
        FormalChecks.text(title, com.knte.core.reference.ReferenceEntry.TITLE_MAX, "title", true)
        val count = FormalChecks.countLiteral(num(map["count"], "count"))
        val archived = bool(map["archived"], "archived")
        val tags = FormalChecks.tags(strList(map["tags"], "tags"))
        val world = map["world"]?.let {
            FormalChecks.text(str(it, "world"), com.knte.core.reference.ReferenceEntry.WORLD_MAX, "world")
        }
        val createdTs = FormalChecks.stamp(str(map["created_ts"], "created_ts"))
        val ts = FormalChecks.stamp(str(map["ts"], "ts"))
        return FormalPitfallLine(id, title, count, archived, tags, world, createdTs, ts, forward)
    }

    private fun decodeMemoPick(
        map: Map<String, TimeJson.Value>,
        forward: List<Pair<String, TimeJson.Value>>,
    ): FormalMemoPickLine {
        val pool = str(map["pool"], "pool")
        if (pool != FormalReferenceLayout.MEMBER_MEMO && pool != FormalReferenceLayout.MEMBER_PITFALL) {
            throw FormalReferenceException(FormalReferenceCodes.POOL_INVALID, pool)
        }
        val picked = strList(map["picked_ids"], "picked_ids")
        if (picked.size > FormalReferenceLayout.N_RECENT) {
            throw FormalReferenceException(FormalReferenceCodes.CAPACITY, "picked_ids")
        }
        picked.forEach { FormalChecks.poolId(it, pool) }
        val ts = FormalChecks.stamp(str(map["ts"], "ts"))
        return FormalMemoPickLine(pool, picked, ts, forward)
    }

    private fun decodePickLog(
        map: Map<String, TimeJson.Value>,
        forward: List<Pair<String, TimeJson.Value>>,
    ): FormalPickLogLine {
        listOf("memo_id", "memo_w", "pitfall_id", "pitfall_w").forEach { optionalNull(map, it) }
        val turn = FormalChecks.stamp(str(map["turn"], "turn"))
        val ts = FormalChecks.stamp(str(map["ts"], "ts"))
        val memoId = map["memo_id"]?.let { FormalChecks.id(str(it, "memo_id"), FormalReferenceLayout.MEMBER_MEMO) }
        val memoW = map["memo_w"]?.let { FormalChecks.weightLiteral(num(it, "memo_w")) }
        val pitfallId = map["pitfall_id"]?.let {
            FormalChecks.id(str(it, "pitfall_id"), FormalReferenceLayout.MEMBER_PITFALL)
        }
        val pitfallW = map["pitfall_w"]?.let { FormalChecks.weightLiteral(num(it, "pitfall_w")) }
        if ((memoId == null) != (memoW == null)) {
            throw FormalReferenceException(FormalReferenceCodes.FIELD_MISSING, "memo pair")
        }
        if ((pitfallId == null) != (pitfallW == null)) {
            throw FormalReferenceException(FormalReferenceCodes.FIELD_MISSING, "pitfall pair")
        }
        val recent = strList(map["recent_before"], "recent_before")
        if (recent.size > FormalReferenceLayout.N_RECENT) {
            throw FormalReferenceException(FormalReferenceCodes.CAPACITY, "recent_before")
        }
        recent.forEach { FormalChecks.id(it, FormalReferenceLayout.MEMBER_MEMO) }
        return FormalPickLogLine(turn, memoId, memoW, pitfallId, pitfallW, recent, ts, forward)
    }

    /** Canonical UTF-8 (no BOM), one LF-terminated line. Strict surrogate encoding is enforced. */
    fun encodeLine(line: FormalReferenceLine): ByteArray {
        val value = line.toJson() as? TimeJson.Value.Obj
            ?: throw FormalReferenceException(FormalReferenceCodes.JSON_SHAPE, "not-object")
        // Validate the entire emitted tree, including directly-constructed unknown values.
        // No shared TimeJson serializer is weakened or changed by this candidate layer.
        FormalChecks.forward(listOf("candidate_root" to value), emptySet())
        val json = TimeJson.encode(value)
        val bytes = try {
            TimeJson.encodeUtf8Strict(json + "\n")
        } catch (e: Exception) {
            throw FormalReferenceException(FormalReferenceCodes.JSON_SHAPE, "encode:${e.message}")
        }
        if (bytes.size > MAX_LINE_BYTES) {
            throw FormalReferenceException(FormalReferenceCodes.LINE_TOO_LONG, bytes.size.toString())
        }
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == 0x0A.toByte() || bytes[i] == 0x0D.toByte()) {
                throw FormalReferenceException(FormalReferenceCodes.NOT_SINGLE_LINE, "encoded-newline")
            }
        }
        // Reparse the serialized bytes and validate known-field shapes as well. This
        // catches a caller mutating public list-backed values after line construction.
        decodeLine(line.member, bytes)
        return bytes
    }

    /** decode -> encode canonical roundtrip (used by golden/unknown-forwarding tests). */
    fun canonical(member: String, bytes: ByteArray): ByteArray =
        encodeLine(decodeLine(member, bytes))
}
