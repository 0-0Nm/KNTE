package com.knte.core.reference

import java.time.LocalDate

/**
 * Reference listing and usage statistics. Usage never fabricates a zero: without a pick
 * history (or with a partial one) the count is unknown (null), never 0 (不伪造 0 未知用量).
 */

data class KnownPick(val pool: ReferencePool, val id: String) {
    init {
        require(id.isNotBlank()) { "KNOWN_PICK_ID_BLANK" }
    }
}

class ReferenceUsage private constructor(
    val hasHistory: Boolean,
    val complete: Boolean,
    private val counts: Map<ReferencePool, Map<String, Int>>,
) {
    /** null = unknown (history absent or partial). 0 is only ever returned for a row that
     *  was never picked under a COMPLETE history. */
    fun usage(pool: ReferencePool, id: String): Int? {
        if (!hasHistory) return null
        val observed = counts[pool]?.get(id) ?: 0
        return when {
            complete -> observed
            observed > 0 -> observed
            else -> null
        }
    }

    companion object {
        fun of(picks: List<KnownPick>?, complete: Boolean = false): ReferenceUsage {
            if (picks == null) return ReferenceUsage(false, false, emptyMap())
            val byPool = picks.groupBy({ it.pool }, { it.id })
                .mapValues { (_, ids) -> ids.groupingBy { it }.eachCount() }
            return ReferenceUsage(true, complete, byPool)
        }
    }
}

data class ReferenceListRow(
    val entry: ReferenceEntry,
    val pool: ReferencePool,
    val baseUnits: Int,
    val weight: String,
    val reason: String,
    val inRecent: Boolean,
    val usage: Int?,
)

object ReferenceList {
    /** Deterministic listing (created_ts then id, legacy order) with complete weight/reason
     *  metadata. `archived`: null = all rows, true = archived-or-done only, false = active
     *  only (完成即归档). */
    fun list(
        entries: Collection<ReferenceEntry>,
        scope: ReferenceScope,
        today: LocalDate,
        pool: ReferencePool? = null,
        archived: Boolean? = null,
        recent: Map<ReferencePool, List<String>> = emptyMap(),
        usage: ReferenceUsage? = null,
    ): List<ReferenceListRow> =
        entries.asSequence()
            .filter { pool == null || it.pool == pool }
            .filter { entry -> scope.includes(entry) }
            .filter { entry ->
                archived == null || ((entry.archived || (entry is ReferenceEntry.Memo && entry.done)) == archived)
            }
            .map { entry ->
                val base = ReferenceWeight.units(entry, today)
                ReferenceListRow(
                    entry = entry,
                    pool = entry.pool,
                    baseUnits = base,
                    weight = ReferenceWeight.decimal(base),
                    reason = ReferenceWeight.reason(entry, today),
                    inRecent = recent[entry.pool]?.contains(entry.id) == true,
                    usage = usage?.usage(entry.pool, entry.id),
                )
            }
            .sortedWith(compareBy({ it.entry.createdTs }, { it.entry.id }))
            .toList()
}
