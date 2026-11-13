package com.knte.preview.accounting

import com.knte.core.accounting.AccountingReports
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Pure presentation projection. It never invents dates or totals and retains report identity. */
data class AccountingChartPoint(val date: String, val income: Long, val expense: Long)

enum class AccountingChartStatus { READY, EMPTY, STALE, BUSY, REVIEW, UNAVAILABLE }

data class AccountingChartModel(
    val scope: String,
    val sourceRevision: String?,
    val points: List<AccountingChartPoint>,
    val maxValue: Long,
    val status: AccountingChartStatus,
    val statusLabel: String,
    val incomeTotal: Long,
    val expenseTotal: Long,
) {
    val hasData: Boolean get() = maxValue > 0L
    /** Only adjacent calendar days are drawable as a line. Sparse all-history points stay dots. */
    fun joins(i: Int): Boolean {
        if (i <= 0 || i >= points.size) return false
        return runCatching {
            ChronoUnit.DAYS.between(LocalDate.parse(points[i - 1].date), LocalDate.parse(points[i].date)) == 1L
        }.getOrDefault(false)
    }

    companion object {
        fun from(
            report: AccountingReports.MonthReport?,
            sourceRevision: String?,
            loaded: Boolean,
            stale: Boolean,
            busy: Boolean,
            reviewRequired: Boolean,
        ): AccountingChartModel {
            val scope = report?.month ?: ""
            val status = when {
                reviewRequired -> AccountingChartStatus.REVIEW
                busy -> AccountingChartStatus.BUSY
                !loaded || report == null -> AccountingChartStatus.UNAVAILABLE
                stale -> AccountingChartStatus.STALE
                else -> AccountingChartStatus.READY
            }
            val statusLabel = when (status) {
                AccountingChartStatus.READY -> "已从当前报表快照更新"
                AccountingChartStatus.EMPTY -> "当前范围没有有效流水"
                AccountingChartStatus.STALE -> "上次成功快照（本次读取失败，未更新）"
                AccountingChartStatus.BUSY -> "正在重新读取；保留上次图形"
                AccountingChartStatus.REVIEW -> "等待人工核对；未宣称已更新"
                AccountingChartStatus.UNAVAILABLE -> "尚无成功报表快照，不生成图形"
            }
            if (report == null || !loaded) {
                return AccountingChartModel(scope, sourceRevision, emptyList(), 0L, status, statusLabel, 0L, 0L)
            }
            require(report.income >= 0L && report.expense >= 0L) { "REPORT_NEGATIVE_TOTAL" }
            val allPoints = report.days
                .sortedBy { it.date }
                .map { day ->
                    require(day.income >= 0L && day.expense >= 0L) { "REPORT_NEGATIVE_DAY" }
                    AccountingChartPoint(day.date, day.income, day.expense)
                }
            // A selected month is explicitly zero-filled by the report API: retain every day so
            // the line can visibly return to zero. All-month scope is sparse: retain only observed
            // non-zero days and never imply continuity across missing dates.
            val points = if (scope.isEmpty()) allPoints.filter { it.income != 0L || it.expense != 0L } else allPoints
            val maxValue = points.fold(0L) { current, point ->
                maxOf(current, point.income, point.expense)
            }
            val finalStatus = if (maxValue == 0L && status == AccountingChartStatus.READY) {
                AccountingChartStatus.EMPTY
            } else status
            val finalLabel = if (finalStatus == AccountingChartStatus.EMPTY) "当前范围没有有效流水" else statusLabel
            return AccountingChartModel(scope, sourceRevision, points, maxValue, finalStatus, finalLabel, report.income, report.expense)
        }
    }
}
