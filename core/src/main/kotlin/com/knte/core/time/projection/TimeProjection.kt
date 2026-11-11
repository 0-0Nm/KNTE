package com.knte.core.time.projection

import com.knte.core.time.format.TimeEvent
import com.knte.core.time.format.TimeFields
import com.knte.core.time.format.TimeJson
import com.knte.core.time.format.TimeJsonValues
import java.time.LocalDate
import java.time.LocalDateTime

/** Derived record: computed from events, never persisted. No status/duration column exists on disk. */
data class TimeRecord(
    val id: String,
    val date: String,
    val start: String,
    val end: String?,
    val endDate: String?,
    val project: String,
    val tags: List<String>,
    val note: String?,
    val repeatId: String?,
    val amendCount: Int,
    val startEventTs: String,
    val invalid: Boolean
) {
    val begin: LocalDateTime = LocalDateTime.parse("$date" + "T" + "$start:00")
    val finish: LocalDateTime? = end?.let { e ->
        LocalDateTime.parse("${endDate ?: date}T$e:00")
    }

    /** start without stop. No automatic stop is ever synthesised. */
    fun running(): Boolean = finish == null
    fun minutes(): Long = if (running() || invalid) 0
    else java.time.Duration.between(begin, finish).toMinutes()

    /** <12h running = in progress; >=12h running = orphan hint. Both stay running. */
    fun orphan(now: LocalDateTime): Boolean =
        running() && java.time.Duration.between(begin, now).toMinutes() >= ORPHAN_MINUTES

    /** Display start used by callers that only have a wall clock (never persisted, never authoritative).
     *  Returns the recorded ts only when it agrees with date+start to the minute; otherwise the event time. */
    fun displayBegin(): LocalDateTime {
        val recorded = runCatching { LocalDateTime.parse(TimeFields.stamp(startEventTs)) }.getOrNull()
        return if (recorded != null && recorded.withSecond(0).withNano(0) == begin) recorded else begin
    }

    companion object {
        const val ORPHAN_MINUTES = 720L
    }
}

/** Read model of the whole event file. Pure function of the event list: no IO, no clock. */
data class TimeProjection(
    val records: List<TimeRecord>,
    val orphanAmendCount: Int,
    val orphanStopCount: Int,
    val runningCount: Int,
    val invalidCount: Int,
    val duplicateSuspectCount: Int
) {
    fun find(id: String): TimeRecord? = records.firstOrNull { it.id == id }
    fun sumMinutes(): Long = records.sumOf { it.minutes() }

    /** Union of all finished intervals: overlaps are counted once (R-2). */
    fun unionMinutes(): Long = union(null, null)

    /** Per-day attribution for cross-day records: split by the overlap with the day (contract §6.6). */
    fun dayMinutes(day: LocalDate): Long {
        val from = day.atStartOfDay()
        val to = day.plusDays(1).atStartOfDay()
        return union(from, to)
    }

    /** Overlapping finished pairs, for `--audit-overlap` style listing. Never auto-removed. */
    fun overlapPairs(): List<Pair<String, String>> {
        val finished = records.filter { !it.running() && !it.invalid }
        val pairs = mutableListOf<Pair<String, String>>()
        for (i in finished.indices) {
            for (j in i + 1 until finished.size) {
                val a = finished[i]; val b = finished[j]
                if (a.begin.isBefore(b.finish) && b.begin.isBefore(a.finish)) pairs += a.id to b.id
            }
        }
        return pairs
    }

    /** Same project + same interval + different id: listed only, never deleted (R-4). */
    fun duplicateSuspects(): List<List<TimeRecord>> {
        val groups = records.filter { !it.running() && !it.invalid }
            .groupBy { it.project + "\u0000" + it.begin + "\u0000" + it.finish }
        return groups.values.filter { it.size > 1 }
    }

    private fun union(from: LocalDateTime?, to: LocalDateTime?): Long {
        val spans = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        for (r in records) {
            if (r.running() || r.invalid) continue
            var a = r.begin
            var b = r.finish!!
            if (from != null) {
                if (a.isBefore(from)) a = from
                if (b.isAfter(to!!)) b = to!!
            }
            if (b.isAfter(a)) spans += a to b
        }
        spans.sortBy { it.first }
        var sum = 0L
        var curA: LocalDateTime? = null
        var curB: LocalDateTime? = null
        for ((a, b) in spans) {
            if (curA == null || curB == null) { curA = a; curB = b; continue }
            if (!a.isAfter(curB)) { if (b.isAfter(curB)) curB = b }
            else {
                sum += java.time.Duration.between(curA, curB).toMinutes()
                curA = a; curB = b
            }
        }
        if (curA != null && curB != null) sum += java.time.Duration.between(curA, curB).toMinutes()
        return sum
    }
}

/** Replay: events -> projection. Duplicate ids fail closed instead of guessing which one wins. */
object TimeProjectionBuilder {
    fun build(events: List<TimeEvent>): TimeProjection {
        val starts = linkedMapOf<String, TimeEvent>()
        val stops = linkedMapOf<String, TimeEvent>()
        val amends = linkedMapOf<String, MutableList<TimeEvent>>()
        val renames = mutableListOf<TimeEvent>()
        for (e in events) {
            when (e.op) {
                "start" -> {
                    require(!starts.containsKey(e.text("id"))) { "TIME_DUPLICATE_START" }
                    starts[e.text("id")!!] = e
                }
                "stop" -> {
                    require(!stops.containsKey(e.text("id"))) { "TIME_DUPLICATE_STOP" }
                    stops[e.text("id")!!] = e
                }
                "amend" -> amends.getOrPut(e.text("target")!!) { mutableListOf() } += e
                "rename_project" -> renames += e
            }
        }
        var orphanAmend = 0
        for ((target, list) in amends) if (!starts.containsKey(target)) orphanAmend += list.size
        var orphanStop = 0
        for (id in stops.keys) if (!starts.containsKey(id)) orphanStop++
        val records = mutableListOf<TimeRecord>()
        for ((id, startEvent) in starts) {
            var date = startEvent.text("date")!!
            var startHm = startEvent.text("start")!!
            var project = startEvent.text("project")!!
            var tags = startEvent.textList("tags") ?: emptyList()
            var note: String? = startEvent.field("note")?.let {
                if (it is TimeJson.Value.Null) null else TimeJsonValues.str(it)
            }
            var repeatId: String? = startEvent.field("repeat_id")?.let {
                if (it is TimeJson.Value.Null) null else TimeJsonValues.str(it)
            }
            var end: String? = null
            var endDate: String? = null
            val stop = stops[id]
            if (stop != null) {
                end = stop.text("end")
                endDate = stop.field("end_date")?.let {
                    if (it is TimeJson.Value.Null) null else TimeJsonValues.str(it)
                }
            }
            var amendCount = 0
            for (a in amends[id] ?: emptyList()) {
                val field = a.text("field")!!
                val value = a.field("value")!!
                when (field) {
                    "date" -> date = TimeJsonValues.str(value)
                    "start" -> startHm = TimeJsonValues.str(value)
                    "end" -> end = if (value is TimeJson.Value.Null) null
                        else TimeJsonValues.str(value)
                    "end_date" -> endDate = if (value is TimeJson.Value.Null) null
                        else TimeJsonValues.str(value)
                    "project" -> project = TimeJsonValues.str(value)
                    "tags" -> tags = if (value is TimeJson.Value.Null) emptyList()
                        else TimeJsonValues.strList(value)
                    "note" -> note = if (value is TimeJson.Value.Null) null
                        else TimeJsonValues.str(value)
                }
                amendCount++
            }
            // rename_project applies after amends, so a later rename of an amended name still wins.
            for (r in renames) if (r.text("from") == project) project = r.text("to")!!
            val invalid = try {
                end != null && LocalDateTime.parse("${endDate ?: date}T$end:00")
                    .isBefore(LocalDateTime.parse("${date}T$startHm:00"))
            } catch (_: Exception) { true }
            records += TimeRecord(
                id = id, date = date, start = startHm, end = end, endDate = endDate,
                project = project, tags = tags, note = note, repeatId = repeatId,
                amendCount = amendCount, startEventTs = startEvent.ts, invalid = invalid
            )
        }
        records.sortWith(compareBy({ it.begin }, { it.id }))
        return TimeProjection(
            records = records,
            orphanAmendCount = orphanAmend,
            orphanStopCount = orphanStop,
            runningCount = records.count { it.running() },
            invalidCount = records.count { it.invalid },
            duplicateSuspectCount = records.count { !it.running() && !it.invalid &&
                records.count { o -> !o.running() && !o.invalid && o.project == it.project &&
                    o.begin == it.begin && o.finish == it.finish } > 1 }
        )
    }
}
