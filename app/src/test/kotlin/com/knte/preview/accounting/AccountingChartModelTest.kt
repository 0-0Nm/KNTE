package com.knte.preview.accounting

import com.knte.core.accounting.AccountingReports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountingChartModelTest {
    private fun report(vararg day: AccountingReports.Day, month: String = ""): AccountingReports.MonthReport =
        AccountingReports.MonthReport(
            month = month, rows = emptyList(), days = day.toList(),
            income = day.sumOf { it.income }, expense = day.sumOf { it.expense },
            net = day.sumOf { it.income } - day.sumOf { it.expense }, excludedInRange = 0,
        )

    @Test fun sortsUnorderedDaysAndDoesNotJoinSparseAllHistory() {
        val model = AccountingChartModel.from(
            report(
                AccountingReports.Day("2026-11-10", 100L, 0L),
                AccountingReports.Day("2026-10-01", 0L, 50L),
                AccountingReports.Day("2026-11-12", 0L, 25L),
            ), "rev-1", loaded = true, stale = false, busy = false, reviewRequired = false,
        )
        assertEquals(listOf("2026-10-01", "2026-11-10", "2026-11-12"), model.points.map { it.date })
        assertFalse(model.joins(1))
        assertFalse(model.joins(2))
        assertEquals("rev-1", model.sourceRevision)
    }

    @Test fun monthKeepsZeroDaysButChartOmitsZeroDotsAndSameDayTotalsStayCombined() {
        val model = AccountingChartModel.from(
            report(
                AccountingReports.Day("2026-02-01", 0L, 0L),
                AccountingReports.Day("2026-02-02", 300L, 25L),
                AccountingReports.Day("2026-02-03", 0L, 0L),
                month = "2026-02"),
            "r", true, false, false, false,
        )
        assertEquals(listOf("2026-02-01", "2026-02-02", "2026-02-03"), model.points.map { it.date })
        assertEquals(listOf(0L, 300L, 0L), model.points.map { it.income })
        assertEquals(listOf(0L, 25L, 0L), model.points.map { it.expense })
        assertEquals(300L, model.incomeTotal)
        assertEquals(25L, model.expenseTotal)
    }

    @Test fun emptySnapshotAndNullReportNeverBecomeZeroChart() {
        val model = AccountingChartModel.from(null, null, loaded = false, stale = false, busy = false, reviewRequired = false)
        assertEquals(AccountingChartStatus.UNAVAILABLE, model.status)
        assertTrue(model.points.isEmpty())
        val empty = AccountingChartModel.from(report(month = "2026-02"), "empty", true, false, false, false)
        assertEquals(AccountingChartStatus.EMPTY, empty.status)
    }

    @Test fun staleBusyReviewAreExplicitAndKeepSnapshotIdentity() {
        val r = report(AccountingReports.Day("2026-01-01", Long.MAX_VALUE, Long.MAX_VALUE), month = "")
        val stale = AccountingChartModel.from(r, "old", true, true, false, false)
        assertEquals(AccountingChartStatus.STALE, stale.status)
        assertEquals(Long.MAX_VALUE, stale.maxValue)
        assertTrue(stale.statusLabel.contains("未更新"))
        val busy = AccountingChartModel.from(r, "old", true, false, true, false)
        assertEquals(AccountingChartStatus.BUSY, busy.status)
        val review = AccountingChartModel.from(r, "old", true, false, false, true)
        assertEquals(AccountingChartStatus.REVIEW, review.status)
        assertTrue(review.statusLabel.contains("未宣称"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeReportValueIsRejectedRatherThanInvented() {
        AccountingChartModel.from(
            AccountingReports.MonthReport("", emptyList(), listOf(AccountingReports.Day("2026-01-01", -1L, 0L)), -1L, 0L, -1L, 0),
            "bad", true, false, false, false,
        )
    }
}
