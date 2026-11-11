package com.knte.core.habit

import java.time.LocalDate

/** Read-only view over the append-only habit log.
 *
 *  Parsing is total and strict: a malformed line, an unknown op, a duplicate event id, an
 *  inconsistent identity (re-created created_ts, archived habit, name mismatch) or a cached
 *  streak/stage hint that disagrees with the recomputed truth is an error. Nothing is repaired,
 *  nothing is dropped, no history is rewritten. `bytes()` returns the exact file content so
 *  callers can prove that an append never touched a previous byte.
 */
class HabitSnapshot private constructor(
    private val raw: ByteArray,
    val revision: String,
    val events: List<HabitEvent>,
    /** Number of (habit, day) pairs that received more than one check line: same-day correction. */
    val sameDayCorrectionCount: Int
) {
    private val identityMap = LinkedHashMap<String, HabitIdentity>()
    private val latestMap = LinkedHashMap<String, HabitCheck>()
    private val eventIds = LinkedHashSet<String>()

    init {
        events.forEach { event ->
            require(eventIds.add(event.eventId)) { "HABIT_DUPLICATE_EVENT_ID" }
            when (event.op) {
                "define" -> {
                    val identity = event.identity!!
                    require(identity.id != HabitProjection.DAILY_ID) { "HABIT_DAILY_RESERVED" }
                    val old = identityMap[identity.id]
                    if (old != null) require(old.createdTs == identity.createdTs) { "HABIT_CREATED_TS_CHANGED" }
                    identityMap[identity.id] = identity
                    require(identityMap.size <= HabitCodec.MAX_HABITS) { "HABIT_CAPACITY" }
                }
                "check" -> {
                    val check = event.check!!
                    if (check.habitId == HabitProjection.DAILY_ID) {
                        require(check.name == HabitProjection.DAILY_NAME) { "HABIT_DAILY_NAME_INVALID" }
                    } else {
                        val identity = identityMap[check.habitId] ?: error("HABIT_NOT_FOUND")
                        require(!identity.archived) { "HABIT_ARCHIVED" }
                        require(identity.name == check.name) { "HABIT_NAME_MISMATCH" }
                    }
                    require(!check.day().isAfter(LocalDate.parse(event.ts.substring(0, 10)))) {
                        "HABIT_FUTURE_CHECK"
                    }
                    latestMap[HabitProjection.key(check.habitId, check.day())] = check
                    // Cached hints are validated against the recomputed truth, never trusted.
                    val streak = HabitProjection.streak(latestMap, check.habitId, check.day())
                    if (check.streak != null) require(check.streak == streak) { "HABIT_STREAK_HINT_MISMATCH" }
                    if (check.stage != null) require(check.stage == HabitProjection.stage(streak)) {
                        "HABIT_STAGE_HINT_MISMATCH"
                    }
                }
                else -> error("HABIT_OP_UNSUPPORTED:${event.op}")
            }
        }
    }

    /** Exact file bytes; the caller must not mutate the returned array. */
    fun bytes(): ByteArray = raw.clone()

    fun events(): List<HabitEvent> = events.toList()

    val eventCount: Int get() = events.size

    fun identity(id: String): HabitIdentity? = identityMap[id]

    /** All habits; `includeArchived = false` hides archived ones without deleting them. */
    fun identities(includeArchived: Boolean = true): List<HabitIdentity> =
        identityMap.values.filter { includeArchived || !it.archived }

    /** Latest check row per (habit, day): every earlier line of a same-day correction stays on disk. */
    fun latestMap(): Map<String, HabitCheck> = latestMap.toMap()

    fun check(id: String, day: LocalDate): HabitCheck? = latestMap[HabitProjection.key(id, day)]

    fun mark(id: String, day: LocalDate): HabitMark = HabitProjection.mark(latestMap, id, day)

    fun daily(day: LocalDate): Boolean? = check(HabitProjection.DAILY_ID, day)?.done

    fun streak(id: String, day: LocalDate): Int = HabitProjection.streak(latestMap, id, day)

    fun currentStreak(id: String, today: LocalDate): Int =
        HabitProjection.currentStreak(latestMap, id, today)

    fun totalDone(id: String, until: LocalDate): Int =
        HabitProjection.totalDone(latestMap, id, until)

    /** 28 or 91 day heat window (caller chooses); a missing day stays UNKNOWN, never false. */
    fun heat(id: String, today: LocalDate, days: Int): List<HabitMark> =
        HabitProjection.heat(latestMap, id, today, days)

    fun history(id: String): List<HabitCheck> = latestMap.values.filter { it.habitId == id }

    /** 日新: three consecutive done=false days; missing days are not failures. */
    fun threeIncomplete(today: LocalDate): Boolean = HabitProjection.threeIncomplete(latestMap, today)

    companion object {
        /** Parses the whole file. Empty bytes are a valid empty store. */
        fun parse(bytes: ByteArray): HabitSnapshot {
            require(bytes.size <= HabitCodec.MAX_FILE_BYTES) { "HABIT_FILE_CAPACITY" }
            require(bytes.isEmpty() || bytes.last() == '\n'.code.toByte()) {
                "HABIT_FILE_UNTERMINATED_LAST_LINE"
            }
            require(bytes.none { it == 0.toByte() }) { "HABIT_FILE_NUL" }
            val text = com.knte.core.time.format.TimeJson.decodeUtf8Strict(bytes)
            val events = mutableListOf<HabitEvent>()
            val seenDays = LinkedHashSet<String>()
            var corrections = 0
            if (text.isNotEmpty()) {
                for (line in text.substring(0, text.length - 1).split('\n')) {
                    require(line.isNotEmpty() && line.length <= HabitCodec.MAX_LINE_CHARS) { "HABIT_LINE_INVALID" }
                    val event = HabitCodec.parseEvent(line)
                    if (event.op == "check") {
                        val key = HabitProjection.key(event.check!!.habitId, event.check!!.day())
                        if (!seenDays.add(key)) corrections++
                    }
                    events += event
                    require(events.size <= HabitCodec.MAX_EVENTS) { "HABIT_EVENT_CAPACITY" }
                }
            }
            return HabitSnapshot(bytes.clone(), HabitCodec.hash(bytes), events.toList(), corrections)
        }
    }
}
