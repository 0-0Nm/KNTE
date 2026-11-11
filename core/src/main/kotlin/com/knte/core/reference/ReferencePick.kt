package com.knte.core.reference

import com.knte.core.time.format.TimeFields
import com.knte.core.time.format.TimeJson
import com.knte.core.time.format.TimeJsonValues
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Turn-level pick, frozen audit records, ticket replay verification and input-hash binding.
 *
 * The JSONL append/write gate and cross-file witnessing are NOT implemented here (契约 §十
 * belongs to the repository layer); this domain only produces verifiable values and one
 * canonical line per pick. A single-file success here does NOT constitute the complete
 * reference system.
 */

internal object ReferenceHash {
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/** Binding of one pick to the completed input it was drawn for, using the legacy audited
 *  fields (chat_session s-32hex / chat_turn t-32hex / response_sha256 64hex). If the input
 *  changes, verifyBinding reports the drift and the old pick must not be reused. */
data class TurnBinding(
    val sessionId: String,
    val turnId: String,
    val responseSha256: String,
) {
    val eventId: String

    init {
        require(sessionId.matches(Regex("s-[0-9a-f]{32}"))) { "BINDING_SESSION_INVALID" }
        require(turnId.matches(Regex("t-[0-9a-f]{32}"))) { "BINDING_TURN_INVALID" }
        require(responseSha256.matches(Regex("[0-9a-f]{64}"))) { "BINDING_HASH_INVALID" }
        eventId = "r-" + ReferenceHash.sha256((sessionId + "\u0000" + turnId)
            .toByteArray(Charsets.UTF_8)).substring(0, 8)
    }

    fun matches(other: TurnBinding): Boolean =
        sessionId == other.sessionId && turnId == other.turnId && responseSha256 == other.responseSha256
}

/** Frozen per-pool pick record: the row state at draw time, its weights and the winning
 *  ticket. Canonical-JSON equality against a replayed draw is the drift check (legacy
 *  audited semantics). Added over the legacy record: pool/base_weight/effective_weight/
 *  reason/recent, so the selection reason source is fully in metadata. */
data class ChoiceRecord(
    val pool: String,
    val id: String,
    val row: TimeJson.Value,
    val baseUnits: Int,
    val baseWeight: String,
    val effectiveUnits: Int,
    val effectiveWeight: String,
    val reason: String,
    val recent: Boolean,
    val poolSize: Int,
    val ticket: Long,
    val totalUnits: Long,
    val mode: String,
) {
    fun toJson(): TimeJson.Value = TimeJson.Value.Obj(
        listOf(
            "pool" to TimeJson.Value.Str(pool),
            "id" to TimeJson.Value.Str(id),
            "item" to row,
            "base_units" to TimeJson.Value.Num(baseUnits.toString()),
            "base_weight" to TimeJson.Value.Str(baseWeight),
            "effective_units" to TimeJson.Value.Num(effectiveUnits.toString()),
            "effective_weight" to TimeJson.Value.Str(effectiveWeight),
            "reason" to TimeJson.Value.Str(reason),
            "recent" to TimeJson.Value.Bool(recent),
            "pool_size" to TimeJson.Value.Num(poolSize.toString()),
            "ticket" to TimeJson.Value.Num(ticket.toString()),
            "total_units" to TimeJson.Value.Num(totalUnits.toString()),
            "mode" to TimeJson.Value.Str(mode),
        )
    )

    companion object {
        fun of(choice: Choice): ChoiceRecord {
            val entry = choice.entry
            val candidate = choice.candidate
            return ChoiceRecord(
                pool = entry.pool.label,
                id = entry.id,
                row = rowJson(entry),
                baseUnits = candidate.baseUnits,
                baseWeight = ReferenceWeight.decimal(candidate.baseUnits),
                effectiveUnits = candidate.effectiveUnits,
                effectiveWeight = ReferenceWeight.decimal(candidate.effectiveUnits),
                reason = candidate.reason,
                recent = candidate.recent,
                poolSize = choice.snapshot.poolSize,
                ticket = choice.ticket,
                totalUnits = choice.snapshot.totalUnits,
                mode = if (choice.snapshot.uniform) "uniform" else "weighted",
            )
        }

        /** Row snapshot with contract key order; absent optional keys are omitted (契约 §三). */
        internal fun rowJson(entry: ReferenceEntry): TimeJson.Value {
            val pairs = mutableListOf<Pair<String, TimeJson.Value>>()
            pairs += "op" to TimeJson.Value.Str("upsert")
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

        fun parse(value: TimeJson.Value): ChoiceRecord {
            val obj = value as? TimeJson.Value.Obj
                ?: throw IllegalArgumentException("CHOICE_JSON_SHAPE")
            fun field(name: String): TimeJson.Value =
                requireNotNull(obj.entries.firstOrNull { it.first == name }?.second) {
                    "CHOICE_FIELD_MISSING"
                }
            val mode = TimeJsonValues.str(field("mode"))
            require(mode == "uniform" || mode == "weighted") { "CHOICE_MODE_INVALID" }
            return ChoiceRecord(
                pool = TimeJsonValues.str(field("pool")),
                id = TimeJsonValues.str(field("id")),
                row = field("item"),
                baseUnits = intOf(field("base_units")),
                baseWeight = TimeJsonValues.str(field("base_weight")),
                effectiveUnits = intOf(field("effective_units")),
                effectiveWeight = TimeJsonValues.str(field("effective_weight")),
                reason = TimeJsonValues.str(field("reason")),
                recent = TimeJsonValues.bool(field("recent")),
                poolSize = intOf(field("pool_size")),
                ticket = longOf(field("ticket")),
                totalUnits = longOf(field("total_units")),
                mode = mode,
            )
        }

        private fun intOf(value: TimeJson.Value): Int {
            val literal = (value as? TimeJson.Value.Num)?.literal
                ?: throw IllegalArgumentException("CHOICE_NOT_NUMBER")
            return literal.toInt()
        }

        private fun longOf(value: TimeJson.Value): Long {
            val literal = (value as? TimeJson.Value.Num)?.literal
                ?: throw IllegalArgumentException("CHOICE_NOT_NUMBER")
            return literal.toLong()
        }
    }
}

data class TurnPick(
    val eventId: String,
    val ts: String,
    val today: LocalDate,
    val scope: ReferenceScope,
    val memo: Choice?,
    val pitfall: Choice?,
    val memoRecentBefore: List<String>,
    val pitfallRecentBefore: List<String>,
    val memoRecentAfter: List<String>,
    val pitfallRecentAfter: List<String>,
    val binding: TurnBinding?,
)

enum class BindingStatus { OK, NONE_RECORDED, TURN_CHANGED, INPUT_CHANGED }

enum class ReplayStatus { OK, RECORD_MISSING, EMPTY_POOL_WITH_RECORD, TICKET_OUT_OF_RANGE, SNAPSHOT_MISMATCH }

data class TurnVerify(
    val binding: BindingStatus?,
    val memo: ReplayStatus,
    val pitfall: ReplayStatus,
) {
    val ok: Boolean
        get() = (binding == null || binding == BindingStatus.OK) &&
            memo == ReplayStatus.OK && pitfall == ReplayStatus.OK
}

object ReferencePick {
    private val EVENT_ID = Regex("r-[0-9a-f]{8}")

    /** One turn draw: exactly 1 memo + 1 pitfall from two independent pools (契约 §7.5).
     *  Both pools empty under the scope -> null (不填假引用); a single empty side yields a
     *  null choice and keeps its recent queue untouched (空池不补样例). */
    fun pickTurn(
        entries: Collection<ReferenceEntry>,
        scope: ReferenceScope,
        today: LocalDate,
        ts: String,
        random: RandomSource,
        memoRecent: List<String> = emptyList(),
        pitfallRecent: List<String> = emptyList(),
        binding: TurnBinding? = null,
        eventId: String? = null,
    ): TurnPick? {
        val stamp = TimeFields.stamp(ts)
        if (binding != null && eventId != null) {
            require(eventId == binding.eventId) { "PICK_EVENT_ID_CONFLICT" }
        }
        val resolvedId = when {
            binding != null -> binding.eventId
            eventId != null -> {
                require(eventId.matches(EVENT_ID)) { "PICK_EVENT_ID_INVALID" }
                eventId
            }
            else -> throw IllegalArgumentException("PICK_EVENT_ID_REQUIRED")
        }
        val memo = ReferenceSelect.draw(entries, ReferencePool.MEMO, scope, today, memoRecent, random)
        val pitfall = ReferenceSelect.draw(entries, ReferencePool.PITFALL, scope, today, pitfallRecent, random)
        if (memo == null && pitfall == null) return null
        return TurnPick(
            eventId = resolvedId,
            ts = stamp,
            today = today,
            scope = scope,
            memo = memo,
            pitfall = pitfall,
            memoRecentBefore = memoRecent.toList(),
            pitfallRecentBefore = pitfallRecent.toList(),
            memoRecentAfter = RecentQueue.advance(memoRecent, memo?.candidate?.id),
            pitfallRecentAfter = RecentQueue.advance(pitfallRecent, pitfall?.candidate?.id),
            binding = binding,
        )
    }

    /** Binding check: input hash changed or turn changed -> the old pick cannot be reused. */
    fun verifyBinding(record: PickEventRecord, current: TurnBinding): BindingStatus {
        val recorded = record.binding ?: return BindingStatus.NONE_RECORDED
        if (recorded.sessionId != current.sessionId || recorded.turnId != current.turnId) {
            return BindingStatus.TURN_CHANGED
        }
        if (recorded.responseSha256 != current.responseSha256) return BindingStatus.INPUT_CHANGED
        return BindingStatus.OK
    }

    /** Replay one recorded choice against the current pool: the recorded ticket is fed back
     *  into the same deterministic draw; the canonical record must match, otherwise the pool
     *  or weights drifted since the pick. */
    fun replayChoice(
        entries: Collection<ReferenceEntry>,
        pool: ReferencePool,
        record: ChoiceRecord?,
        scope: ReferenceScope,
        today: LocalDate,
        recent: List<String>,
    ): ReplayStatus {
        val poolEntries = ReferenceSelect.filtered(entries, pool, scope)
        if (record == null) {
            return if (poolEntries.isEmpty()) ReplayStatus.OK else ReplayStatus.RECORD_MISSING
        }
        if (poolEntries.isEmpty()) return ReplayStatus.EMPTY_POOL_WITH_RECORD
        return try {
            val replayed = ReferenceSelect.draw(entries, pool, scope, today, recent, object : RandomSource {
                override fun next(bound: Long): Long = record.ticket
            })
            when {
                replayed == null -> ReplayStatus.EMPTY_POOL_WITH_RECORD
                ChoiceRecord.of(replayed).toJson() == record.toJson() -> ReplayStatus.OK
                else -> ReplayStatus.SNAPSHOT_MISMATCH
            }
        } catch (drift: IllegalArgumentException) {
            ReplayStatus.TICKET_OUT_OF_RANGE
        }
    }

    /** Full audit verification of one pick event: input binding + both pool replays. */
    fun verifyTurn(
        entries: Collection<ReferenceEntry>,
        record: PickEventRecord,
        currentBinding: TurnBinding? = null,
    ): TurnVerify = TurnVerify(
        binding = currentBinding?.let { verifyBinding(record, it) },
        memo = replayChoice(entries, ReferencePool.MEMO, record.memo, record.scope, record.today, record.memoRecentBefore),
        pitfall = replayChoice(entries, ReferencePool.PITFALL, record.pitfall, record.scope, record.today, record.pitfallRecentBefore),
    )
}

/** The pick_log.jsonl line plan (契约 §7.4/§7.5): audit only, never input to calculation.
 *  `seed` is always null in production (no RNG state persisted); reproducibility comes from
 *  ticket replay. Writing the line (append, cross-file witness, confirmation card) is the
 *  repository layer's job and is out of scope here. */
data class PickEventRecord(
    val eventId: String,
    val ts: String,
    val today: LocalDate,
    val scope: ReferenceScope,
    val memoRecentBefore: List<String>,
    val pitfallRecentBefore: List<String>,
    val memo: ChoiceRecord?,
    val pitfall: ChoiceRecord?,
    val binding: TurnBinding?,
) {
    fun toJson(): TimeJson.Value {
        val pairs = mutableListOf<Pair<String, TimeJson.Value>>()
        pairs += "id" to TimeJson.Value.Str(eventId)
        pairs += "op" to TimeJson.Value.Str("pick")
        pairs += "ts" to TimeJson.Value.Str(ts)
        pairs += "at_trust" to TimeJson.Value.Str("unverified")
        pairs += "today" to TimeJson.Value.Str(today.toString())
        pairs += "scope" to scope.toJson()
        pairs += "memo_recent_before" to idArray(memoRecentBefore)
        pairs += "pitfall_recent_before" to idArray(pitfallRecentBefore)
        pairs += "memo" to (memo?.toJson() ?: TimeJson.Value.Null)
        pairs += "pitfall" to (pitfall?.toJson() ?: TimeJson.Value.Null)
        pairs += "seed" to TimeJson.Value.Null
        binding?.let {
            pairs += "chat_session" to TimeJson.Value.Str(it.sessionId)
            pairs += "chat_turn" to TimeJson.Value.Str(it.turnId)
            pairs += "response_sha256" to TimeJson.Value.Str(it.responseSha256)
        }
        return TimeJson.Value.Obj(pairs)
    }

    /** One canonical JSONL line (LF-terminated). Writing it to a file is NOT done here. */
    fun encodeLine(): String = TimeJson.encode(toJson()) + "\n"

    companion object {
        private fun idArray(ids: List<String>) = TimeJson.Value.Arr(ids.map { TimeJson.Value.Str(it) })

        fun of(pick: TurnPick): PickEventRecord = PickEventRecord(
            eventId = pick.eventId,
            ts = pick.ts,
            today = pick.today,
            scope = pick.scope,
            memoRecentBefore = pick.memoRecentBefore,
            pitfallRecentBefore = pick.pitfallRecentBefore,
            memo = pick.memo?.let { ChoiceRecord.of(it) },
            pitfall = pick.pitfall?.let { ChoiceRecord.of(it) },
            binding = pick.binding,
        )

        fun parse(value: TimeJson.Value): PickEventRecord {
            val obj = value as? TimeJson.Value.Obj
                ?: throw IllegalArgumentException("PICK_JSON_SHAPE")
            fun field(name: String): TimeJson.Value? =
                obj.entries.firstOrNull { it.first == name }?.second
            fun required(name: String): TimeJson.Value =
                requireNotNull(field(name)) { "PICK_FIELD_MISSING" }
            require(TimeJsonValues.str(required("op")) == "pick") { "PICK_OP_INVALID" }
            require(TimeJsonValues.str(required("at_trust")) == "unverified") { "PICK_TRUST_INVALID" }
            val seed = field("seed")
            require(seed == null || seed is TimeJson.Value.Null) { "PICK_SEED_MUST_BE_NULL" }
            val id = TimeJsonValues.str(required("id"))
            require(id.matches(Regex("r-[0-9a-f]{8}"))) { "PICK_EVENT_ID_INVALID" }
            val binding = field("chat_session")?.let { session ->
                val bound = TurnBinding(
                    sessionId = TimeJsonValues.str(session),
                    turnId = TimeJsonValues.str(required("chat_turn")),
                    responseSha256 = TimeJsonValues.str(required("response_sha256")),
                )
                require(bound.eventId == id) { "PICK_EVENT_ID_BINDING_MISMATCH" }
                bound
            }
            return PickEventRecord(
                eventId = id,
                ts = TimeFields.stamp(TimeJsonValues.str(required("ts"))),
                today = LocalDate.parse(TimeFields.date(TimeJsonValues.str(required("today")))),
                scope = ReferenceScope.parse(required("scope")),
                memoRecentBefore = TimeJsonValues.strList(required("memo_recent_before")),
                pitfallRecentBefore = TimeJsonValues.strList(required("pitfall_recent_before")),
                memo = field("memo")?.takeIf { it !is TimeJson.Value.Null }?.let { ChoiceRecord.parse(it) },
                pitfall = field("pitfall")?.takeIf { it !is TimeJson.Value.Null }?.let { ChoiceRecord.parse(it) },
                binding = binding,
            )
        }
    }
}
