package com.knte.core.reference

import java.security.SecureRandom
import java.time.LocalDate
import java.util.Collections

/**
 * Selection core: weighted roulette with the N=3 anti-degradation queue
 * (数据契约 v2 §7.4 / 07_规则收口 §三). Pure: draws never mutate the inputs, and every
 * taken snapshot is a frozen copy.
 *
 * Design points, all verifiable: recent ids are halved, never excluded (要点 1);
 * pools of size <= N degrade to pure random WITHOUT downweighting (要点 2);
 * each pool keeps its own queue (要点 3); archived rows (0.2) stay in the pool (要点 4);
 * an empty pool yields null - no sample is fabricated (不填假引用).
 */

interface RandomSource {
    /** Uniformly distributed long in [0, bound); bound > 0. */
    fun next(bound: Long): Long
}

/** Production randomness: rejection sampling over 63 bits, identical to the legacy audited
 *  SecureRoller. No seed is ever persisted; recorded picks are replay-verified by ticket. */
class SecureReferenceRandom : RandomSource {
    private val random = SecureRandom()

    override fun next(bound: Long): Long {
        require(bound > 0) { "RANDOM_BOUND_INVALID" }
        var bits: Long
        var value: Long
        do {
            bits = random.nextLong() ushr 1
            value = bits % bound
        } while (bits - value + (bound - 1) < 0)
        return value
    }
}

object RecentQueue {
    /** Enqueue the picked id and evict the oldest beyond N; a null pick leaves the queue
     *  untouched. Duplicate ids are allowed (short-term repeats), like the legacy line. */
    fun advance(queue: List<String>, pickedId: String?): List<String> {
        if (pickedId == null) return queue.toList()
        val next = ArrayList<String>(queue.size + 1)
        next.addAll(queue)
        next.add(pickedId)
        while (next.size > ReferenceWeight.RECENT_LIMIT) next.removeAt(0)
        return Collections.unmodifiableList(next)
    }
}

data class WeightCandidate(
    val id: String,
    val baseUnits: Int,
    val effectiveUnits: Int,
    val recent: Boolean,
    val reason: String,
)

/** Frozen per-pool weight snapshot (契约 §7.5 weight_snapshot): a defensive copy taken at
 *  pick time; later mutations of the source rows or lists never change a taken snapshot. */
class WeightSnapshot(
    val pool: ReferencePool,
    val uniform: Boolean,
    val poolSize: Int,
    val totalUnits: Long,
    candidates: List<WeightCandidate>,
) {
    val candidates: List<WeightCandidate>

    init {
        this.candidates = Collections.unmodifiableList(candidates.toList())
    }
}

data class Choice(
    val entry: ReferenceEntry,
    val candidate: WeightCandidate,
    val snapshot: WeightSnapshot,
    val ticket: Long,
)

object ReferenceSelect {
    /** Scope/pool-filtered candidates in canonical id order; the input is never mutated. */
    fun filtered(
        entries: Collection<ReferenceEntry>,
        pool: ReferencePool,
        scope: ReferenceScope,
    ): List<ReferenceEntry> =
        entries.filter { it.pool == pool && scope.includes(it) }.sortedBy { it.id }

    /** One weighted draw over one pool. Returns null on an empty pool. */
    fun draw(
        entries: Collection<ReferenceEntry>,
        pool: ReferencePool,
        scope: ReferenceScope,
        today: LocalDate,
        recent: List<String>,
        random: RandomSource,
    ): Choice? {
        val poolEntries = filtered(entries, pool, scope)
        if (poolEntries.isEmpty()) return null
        val uniform = poolEntries.size <= ReferenceWeight.RECENT_LIMIT
        val candidates = poolEntries.map { entry ->
            val base = ReferenceWeight.units(entry, today)
            val halved = !uniform && recent.contains(entry.id)
            WeightCandidate(
                id = entry.id,
                baseUnits = base,
                effectiveUnits = if (uniform) 1 else if (halved) base / 2 else base,
                recent = recent.contains(entry.id),
                reason = ReferenceWeight.reason(entry, today),
            )
        }
        val total = candidates.fold(0L) { acc, candidate -> acc + candidate.effectiveUnits }
        val ticket = random.next(total)
        require(ticket in 0 until total) { "RANDOM_TICKET_OUT_OF_RANGE" }
        val snapshot = WeightSnapshot(pool, uniform, poolEntries.size, total, candidates)
        var cursor = 0L
        for (candidate in candidates) {
            cursor += candidate.effectiveUnits
            if (ticket < cursor) {
                val winner = poolEntries.first { it.id == candidate.id }
                return Choice(winner, candidate, snapshot, ticket)
            }
        }
        throw AssertionError("ROULETTE_FELL_THROUGH")
    }
}
