package com.knte.core.accounting

import java.time.YearMonth
import java.util.regex.Pattern

/** Derived read-only projections over a snapshot. Original rows are never re-serialized;
 * invalid rows never enter totals (they are surfaced through snapshot.findings instead). */
object AccountingReports {
    class Day(val date: String, val income: Long, val expense: Long)
    class MonthTotal(val month: String, val income: Long, val expense: Long, val net: Long, val count: Int)
    class CategoryTotal(
        val category: String, val inEnum: Boolean,
        val income: Long, val expense: Long, val net: Long, val count: Int,
    )

    class MonthReport(
        val month: String,
        val rows: List<AccountingRow>,
        val days: List<Day>,
        val income: Long,
        val expense: Long,
        val net: Long,
        /** Invalid rows whose date fell inside this scope; inspect snapshot.findings for codes. */
        val excludedInRange: Int,
    )

    private val monthPattern = Pattern.compile("[0-9]{4}-(0[1-9]|1[0-2])")

    /** Accepts "" / null (all months) or a strict YYYY-MM. */
    fun requireInputMonth(month: String?) {
        if (month == null || month.isEmpty()) return
        if (!monthPattern.matcher(month).matches()) throw IllegalArgumentException("MONTH_INVALID")
    }

    private fun inScope(date: String, month: String?): Boolean =
        month.isNullOrEmpty() || date.startsWith("$month-")

    fun month(snapshot: AccountingSnapshot, month: String?): MonthReport {
        requireInputMonth(month)
        val scope = month ?: ""
        val rows = mutableListOf<AccountingRow>()
        val byDate = LinkedHashMap<String, LongArray>()
        var income = 0L
        var expense = 0L
        for (row in snapshot.effectiveRows) {
            if (!inScope(row.date, scope)) continue
            rows.add(row)
            val bucket = byDate.getOrPut(row.date) { LongArray(2) }
            if (row.cents > 0) {
                income = Math.addExact(income, row.cents)
                bucket[0] = Math.addExact(bucket[0], row.cents)
            } else {
                expense = Math.addExact(expense, -row.cents)
                bucket[1] = Math.addExact(bucket[1], -row.cents)
            }
        }
        val days = if (scope.isEmpty()) {
            byDate.entries.map { Day(it.key, it.value[0], it.value[1]) }
        } else {
            val ym = YearMonth.parse(scope)
            (1..ym.lengthOfMonth()).map { d ->
                val day = ym.atDay(d).toString()
                val a = byDate[day]
                Day(day, a?.get(0) ?: 0L, a?.get(1) ?: 0L)
            }
        }
        val excluded = snapshot.rows.count { !it.valid && it.date.isNotEmpty() && inScope(it.date, scope) }
        return MonthReport(scope, rows.toList(), days, income, expense, Math.subtractExact(income, expense), excluded)
    }

    /** One aggregate row per month that has effective entries, sorted ascending. */
    fun monthlyTotals(snapshot: AccountingSnapshot): List<MonthTotal> {
        val byMonth = LinkedHashMap<String, LongArray>()
        for (row in snapshot.effectiveRows) {
            if (row.date.length < 7) continue
            val m = row.date.substring(0, 7)
            val a = byMonth.getOrPut(m) { LongArray(3) }
            if (row.cents > 0) a[0] = Math.addExact(a[0], row.cents)
            else a[1] = Math.addExact(a[1], -row.cents)
            a[2] += 1
        }
        return byMonth.entries.sortedBy { it.key }.map {
            MonthTotal(it.key, it.value[0], it.value[1], Math.subtractExact(it.value[0], it.value[1]), it.value[2].toInt())
        }
    }

    /** Category totals. All 10 contract categories are always listed (zero-filled, contract
     * order); out-of-enum categories that actually occur are appended, flagged inEnum=false. */
    fun categoryTotals(snapshot: AccountingSnapshot, month: String? = null): List<CategoryTotal> {
        requireInputMonth(month)
        val scope = month ?: ""
        val totals = LinkedHashMap<String, LongArray>()
        for (row in snapshot.effectiveRows) {
            if (!inScope(row.date, scope)) continue
            val a = totals.getOrPut(row.category) { LongArray(3) }
            if (row.cents > 0) a[0] = Math.addExact(a[0], row.cents)
            else a[1] = Math.addExact(a[1], -row.cents)
            a[2] += 1
        }
        val result = mutableListOf<CategoryTotal>()
        for (category in AccountingCodec.CATEGORIES) {
            val a = totals[category]
            val income = a?.get(0) ?: 0L
            val expense = a?.get(1) ?: 0L
            result.add(CategoryTotal(category, true, income, expense,
                Math.subtractExact(income, expense), a?.get(2)?.toInt() ?: 0))
        }
        for ((category, a) in totals.entries.sortedBy { it.key }) {
            if (category in AccountingCodec.CATEGORIES) continue
            result.add(CategoryTotal(category, false, a[0], a[1], Math.subtractExact(a[0], a[1]), a[2].toInt()))
        }
        return result.toList()
    }
}
