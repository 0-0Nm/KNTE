package com.knte.core.reference

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Weight math for the two reference pools (数据契约 v2 §7.2/§7.3 + 07_规则收口 §一/§二).
 *
 * All weights are integer twentieths (1.0 = 20 units) so the anti-degradation halving stays
 * exact; the decimal string is display-only. The 9-tier table takes the HIGHER bucket on
 * boundary days (取大不取小), and done/archived is judged first (priority weight 0.2).
 */

enum class MemoTier(val units: Int, val reason: String) {
    ARCHIVED(4, "memo_archived"),
    NO_DUE(20, "memo_no_due"),
    EXPIRED(42, "memo_expired"),
    DUE_0_3(42, "memo_due_0_3"),
    DUE_3_7(38, "memo_due_3_7"),
    DUE_7_14(34, "memo_due_7_14"),
    DUE_14_30(30, "memo_due_14_30"),
    DUE_30_60(26, "memo_due_30_60"),
    DUE_60_90(20, "memo_due_60_90"),
    DUE_90_PLUS(14, "memo_due_90_plus"),
}

object ReferenceWeight {
    /** 1.0 expressed in twentieths. */
    const val TWENTIETHS = 20

    /** 07 §三: N = 3 - recent-pick queue length AND the small-pool pure-random threshold. */
    const val RECENT_LIMIT = 3

    /** 0.2: done or archived, judged before every other tier. */
    const val ARCHIVED_UNITS = 4

    /** Pitfall pool: base 1.0, +0.5 per extra occasion of the same pitfall, no time decay. */
    const val PITFALL_BASE_UNITS = 20
    const val PITFALL_STEP_UNITS = 10

    fun memoTier(entry: ReferenceEntry.Memo, today: LocalDate): MemoTier {
        if (entry.done || entry.archived) return MemoTier.ARCHIVED
        val due = entry.due ?: return MemoTier.NO_DUE
        val d = ChronoUnit.DAYS.between(today, LocalDate.parse(due))
        return when {
            d < 0 -> MemoTier.EXPIRED
            d <= 3 -> MemoTier.DUE_0_3
            d <= 7 -> MemoTier.DUE_3_7
            d <= 14 -> MemoTier.DUE_7_14
            d <= 30 -> MemoTier.DUE_14_30
            d <= 60 -> MemoTier.DUE_30_60
            d <= 90 -> MemoTier.DUE_60_90
            else -> MemoTier.DUE_90_PLUS
        }
    }

    fun pitfallUnits(entry: ReferenceEntry.Pitfall): Int =
        if (entry.archived) ARCHIVED_UNITS
        else PITFALL_BASE_UNITS + PITFALL_STEP_UNITS * (entry.count - 1)

    fun pitfallReason(entry: ReferenceEntry.Pitfall): String =
        if (entry.archived) "pitfall_archived" else "pitfall_occasions"

    fun units(entry: ReferenceEntry, today: LocalDate): Int = when (entry) {
        is ReferenceEntry.Memo -> memoTier(entry, today).units
        is ReferenceEntry.Pitfall -> pitfallUnits(entry)
    }

    /** Rule id that produced the base weight - the source of the selection reason. */
    fun reason(entry: ReferenceEntry, today: LocalDate): String = when (entry) {
        is ReferenceEntry.Memo -> memoTier(entry, today).reason
        is ReferenceEntry.Pitfall -> pitfallReason(entry)
    }

    /** Display-only decimal, trailing zeros stripped (same rendering as the legacy line). */
    fun decimal(units: Int): String =
        BigDecimal(units).divide(BigDecimal(TWENTIETHS), 2, RoundingMode.UNNECESSARY)
            .stripTrailingZeros().toPlainString()
}
