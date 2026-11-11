package com.knte.core.accounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountingReportsTest {
    private fun row(
        id: String,
        ts: String,
        amount: String,
        date: String = "2026-10-28",
        category: String = "餐饮",
        note: String? = null,
        rawInput: String = "账单原句",
        correctedOf: String? = null,
    ): String {
        val pairs = mutableListOf<Pair<String, Any?>>(
            "id" to id, "date" to date, "amount" to raw(amount), "category" to category,
        )
        if (note != null) pairs.add("note" to note)
        pairs.add("source" to "manual")
        pairs.add("raw_input" to rawInput)
        pairs.add("ts" to ts)
        pairs.add("at_trust" to "unverified")
        if (correctedOf != null) pairs.add("corrected_of" to correctedOf)
        return jsonLine(*pairs.toTypedArray())
    }

    @Test fun monthlyTotalsAcrossMonthsExactCents() {
        val snapshot = AccountingSnapshot.parse(
            fixture(
                row("r-00000001", "2026-10-28T12:31:01", "-25.00"),
                row("r-00000002", "2026-10-29T08:00:00", "-4.50", category = "交通", date = "2026-10-29"),
                row("r-00000003", "2026-10-28T09:00:00", "3000.00", date = "2026-10-28"),
                row("r-00000004", "2026-11-01T09:00:00", "-30.00", date = "2026-11-01"),
            )
        )
        val totals = AccountingReports.monthlyTotals(snapshot)
        assertEquals(2, totals.size)
        assertEquals("2026-10", totals[0].month)
        assertEquals(300000L, totals[0].income)
        assertEquals(2950L, totals[0].expense)
        assertEquals(297050L, totals[0].net)
        assertEquals(3, totals[0].count)
        assertEquals("2026-11", totals[1].month)
        assertEquals(0L, totals[1].income)
        assertEquals(3000L, totals[1].expense)
        assertEquals(1, totals[1].count)
    }

    @Test fun emptyMonthZeroFilledDaysAndInputValidation() {
        val snapshot = AccountingSnapshot.parse(fixture(row("r-00000001", "2026-10-28T12:31:01", "-25.00")))
        val report = AccountingReports.month(snapshot, "2026-02")
        assertEquals(28, report.days.size)
        assertTrue(report.days.all { it.income == 0L && it.expense == 0L })
        assertEquals(0L, report.income)
        assertEquals(0L, report.expense)
        assertEquals(0, report.rows.size)
        assertTrue(err { AccountingReports.month(snapshot, "2026-13") }.contains("MONTH_INVALID"))
        assertTrue(err { AccountingReports.month(snapshot, "2026-1") }.contains("MONTH_INVALID"))
    }

    @Test fun monthScopeDayBreakdown() {
        val snapshot = AccountingSnapshot.parse(
            fixture(
                row("r-00000001", "2026-10-28T12:31:01", "-25.00"),
                row("r-00000002", "2026-10-28T13:00:00", "3000.00"),
                row("r-00000003", "2026-11-01T09:00:00", "-30.00", date = "2026-11-01"),
            )
        )
        val report = AccountingReports.month(snapshot, "2026-10")
        assertEquals(31, report.days.size)
        val day = report.days.first { it.date == "2026-10-28" }
        assertEquals(300000L, day.income)
        assertEquals(2500L, day.expense)
        assertEquals(2, report.rows.size)
        assertEquals(0, report.excludedInRange)
        val all = AccountingReports.month(snapshot, null)
        assertEquals(3, all.rows.size)
        assertTrue(all.days.any { it.date == "2026-11-01" })
    }

    @Test fun categoryTotalsEnumZeroFilledPlusOutOfEnumBucket() {
        val snapshot = AccountingSnapshot.parse(
            fixture(
                row("r-00000001", "2026-10-28T12:31:01", "-25.00", category = "餐饮"),
                row("r-00000002", "2026-10-28T12:31:02", "-4.50", category = "交通"),
                row("r-00000003", "2026-10-28T12:31:03", "3000.00", category = "餐饮"),
                row("r-00000004", "2026-10-28T12:31:04", "-10.00", category = "打赏"),
            )
        )
        val totals = AccountingReports.categoryTotals(snapshot)
        assertEquals(11, totals.size)
        assertEquals(AccountingCodec.CATEGORIES, totals.take(10).map { it.category })
        assertTrue(totals.take(10).all { it.inEnum })
        val dining = totals[0]
        assertEquals(300000L, dining.income)
        assertEquals(2500L, dining.expense)
        assertEquals(297500L, dining.net)
        assertEquals(2, dining.count)
        val transport = totals[1]
        assertEquals(450L, transport.expense)
        assertTrue(totals.subList(2, 10).all { it.count == 0 && it.income == 0L && it.expense == 0L })
        val extra = totals[10]
        assertEquals("打赏", extra.category)
        assertTrue(!extra.inEnum)
        assertEquals(1000L, extra.expense)
        val octoberOnly = AccountingReports.categoryTotals(snapshot, "2026-10")
        assertEquals(11, octoberOnly.size)
        val november = AccountingReports.categoryTotals(snapshot, "2026-11")
        assertTrue(november.all { it.count == 0 })
    }

    @Test fun reversalPairNetsOutButStaysVisibleInAudit() {
        val snapshot = AccountingSnapshot.parse(
            fixture(
                row("r-00000001", "2026-10-28T12:31:01", "-100.00", rawInput = "打车100"),
                row("r-00000002", "2026-10-28T13:00:00", "100.00", note = "冲销",
                    rawInput = "打车100", correctedOf = "2026-10-28T12:31:01"),
            )
        )
        assertTrue(AccountingReports.monthlyTotals(snapshot).isEmpty())
        val report = AccountingReports.month(snapshot, "2026-10")
        assertEquals(0L, report.income)
        assertEquals(0L, report.expense)
        assertEquals(0, report.rows.size)
        assertEquals(2, snapshot.rows.size)
        assertEquals(1, snapshot.rows.count { it.kind == AccountingKind.REVERSAL })
    }

    @Test fun invalidRowsReportedNotSilentlySkipped() {
        val snapshot = AccountingSnapshot.parse(
            fixture(
                row("r-00000001", "2026-10-28T12:31:01", "-25.00"),
                "{\"id\":\"r-00000002\",\"date\":\"2026-10-28\",\"amount\":\"x\",\"category\":\"餐饮\",\"source\":\"manual\",\"raw_input\":\"坏行\",\"ts\":\"2026-10-28T12:31:02\",\"at_trust\":\"unverified\"}",
                row("r-00000003", "2026-10-28T12:31:03", "-10.00"),
                row("r-00000004", "2026-11-01T12:31:04", "-99.00", date = "2026-11-01"),
            )
        )
        val report = AccountingReports.month(snapshot, "2026-10")
        assertEquals(2, report.rows.size)
        assertEquals(3500L, report.expense)
        assertEquals(1, report.excludedInRange)
        assertTrue(snapshot.findings.any { it.lineNumber == 2 && it.code == "AMOUNT_INVALID" })
        assertEquals(3, AccountingReports.monthlyTotals(snapshot).map { it.count }.sum())
        assertEquals(1, AccountingReports.month(snapshot, null).excludedInRange)
    }
}
