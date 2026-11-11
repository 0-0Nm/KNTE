package com.knte.core.habit

import java.time.LocalDate

/** One day mark of a habit. UNKNOWN is a first-class state: a missing day is NEVER a failure.
 *  The projection never fabricates a done=false for a day that has no check row. */
enum class HabitMark { DONE, FAILED, UNKNOWN }

/** Pure projection over the per-day check rows (数据契约_v2 §9.1).
 *
 *  Truth is the逐日 `check` line; `streak` and `stage` are derived here and are NEVER a source:
 *  a persisted hint is validated against this recomputation and rejected when it disagrees.
 *  No IO, no clock, no persistence of derived state: every date comes from the caller, so
 *  cross-day / timezone / clock drift stays caller-controlled (no LocalDate.now() inside).
 */
object HabitProjection {
    const val DAILY_ID = "h-00000000"
    const val DAILY_NAME = "日新"

    /** §9.1 five growth stages: consecutive 1 / 3 / 7 / 21 / 66 days (defaults, not configurable here). */
    val STAGE_THRESHOLDS: List<Pair<Int, String>> =
        listOf(1 to "种子", 3 to "萌芽", 7 to "幼苗", 21 to "成树", 66 to "结果")

    fun key(id: String, day: LocalDate): String = id + "|" + day.toString()

    fun mark(latest: Map<String, HabitCheck>, id: String, day: LocalDate): HabitMark {
        val check = latest[key(id, day)] ?: return HabitMark.UNKNOWN
        return if (check.done) HabitMark.DONE else HabitMark.FAILED
    }

    /** Consecutive done days ending at `day` (inclusive). A missing day or done=false breaks it. */
    fun streak(latest: Map<String, HabitCheck>, id: String, day: LocalDate,
               maxSteps: Int = HabitCodec.MAX_EVENTS): Int {
        var count = 0
        var cursor = day
        while (count <= maxSteps) {
            val check = latest[key(id, cursor)] ?: break
            if (!check.done) break
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    /** Today not yet checked -> streak of yesterday, so an unrecorded today is not shown as a loss. */
    fun currentStreak(latest: Map<String, HabitCheck>, id: String, today: LocalDate): Int =
        if (latest[key(id, today)] == null) streak(latest, id, today.minusDays(1))
        else streak(latest, id, today)

    fun totalDone(latest: Map<String, HabitCheck>, id: String, until: LocalDate): Int =
        latest.values.count { it.habitId == id && it.done && !it.day().isAfter(until) }

    /** §9.1 growth stage name for a derived streak length. */
    fun stage(days: Int): String = when {
        days <= 0 -> "尚未开始"
        days >= 66 -> "结果"
        days >= 21 -> "成树"
        days >= 7 -> "幼苗"
        days >= 3 -> "萌芽"
        else -> "种子"
    }

    /** 0..5 level of the same thresholds, for view sizing only. */
    fun stageLevel(days: Int): Int = when {
        days >= 66 -> 5
        days >= 21 -> 4
        days >= 7 -> 3
        days >= 3 -> 2
        days >= 1 -> 1
        else -> 0
    }

    /** Same-source heat window (28 or 91 days by caller choice). Missing stays UNKNOWN. */
    fun heat(latest: Map<String, HabitCheck>, id: String, today: LocalDate, days: Int): List<HabitMark> {
        require(days in 1..3650) { "HABIT_HEAT_DAYS_RANGE" }
        return (days - 1 downTo 0).map { mark(latest, id, today.minusDays(it.toLong())) }
    }

    /** 日新 (daily reflection): three consecutive done=false days, missing days are not failures. */
    fun threeIncomplete(latest: Map<String, HabitCheck>, today: LocalDate): Boolean {
        for (i in 1..3) {
            if (mark(latest, DAILY_ID, today.minusDays(i.toLong())) != HabitMark.FAILED) return false
        }
        return true
    }
}
