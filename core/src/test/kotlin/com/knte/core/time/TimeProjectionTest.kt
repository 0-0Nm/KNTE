package com.knte.core.time

import com.knte.core.time.format.TimeEventCodec
import com.knte.core.time.projection.TimeProjectionBuilder
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** Projection tests against the synthetic golden event stream. */
class TimeProjectionTest {

    private fun events(): List<com.knte.core.time.format.TimeEvent> {
        val text = File(javaClass.classLoader!!.getResource("time/golden-events.jsonl")!!.file).readText()
        return text.trimEnd('\n').split('\n').map { TimeEventCodec.parse(it) }
    }

    private fun projection() = TimeProjectionBuilder.build(events())

    @Test fun runningAndDoneAreComputedNotStored() {
        val p = projection()
        val running = p.records.filter { it.running() }
        // t-orphan001 has a stop but no start, so it is an orphan stop, not a running record.
        assertEquals(listOf("t-aaaa0003", "t-aaaa0004"), running.map { it.id })
        val done = p.records.first { it.id == "t-aaaa0001" }
        assertFalse(done.running())
        assertEquals(85L, done.minutes()) // 09:05 -> 10:30 after amend
        assertEquals(1, done.amendCount)
        assertEquals("晨间阅读", done.project) // rename applied at merge time
        assertEquals(2, p.runningCount)
    }

    @Test fun crossDaySplitsByOverlapAndKeepsStartDate() {
        val p = projection()
        val night = p.records.first { it.id == "t-aaaa0002" }
        assertEquals("2026-10-23", night.date)
        assertEquals("2026-10-24", night.endDate)
        assertEquals(75L, night.minutes())
        assertEquals(30L, p.dayMinutes(LocalDate.of(2026, 10, 23)).let { it - 85L - 0L })
        // Full day attribution: 10-23 has 30 (night) + 85 (morning) = 115; 10-24 has 45.
        assertEquals(115L, p.dayMinutes(LocalDate.of(2026, 10, 23)))
        assertEquals(45L, p.dayMinutes(LocalDate.of(2026, 10, 24)))
    }

    @Test fun orphanNeedsTwelveHoursAndNeverGetsAnAutomaticStop() {
        val p = projection()
        val running = p.records.first { it.id == "t-aaaa0003" } // 2026-10-24 08:00
        assertTrue(running.running())
        assertNull(running.finish)
        assertFalse(running.orphan(LocalDateTime.parse("2026-10-24T18:00:00")))
        assertTrue(running.orphan(LocalDateTime.parse("2026-10-24T21:00:00")))
        // No synthesized stop even far in the future.
        val later = TimeProjectionBuilder.build(events())
        assertTrue(later.records.first { it.id == "t-aaaa0003" }.running())
        assertEquals(0L, later.records.first { it.id == "t-aaaa0003" }.minutes())
    }

    @Test fun overlapUnionCountsSharedTimeOnce() {
        val p = projection()
        // 10:00 physical (no stop) and orphan stop 10:00 are running; the two finished records overlap 09:05-10:30 vs 23:30-00:45 -> no overlap.
        assertEquals(85L + 75L, p.sumMinutes())
        assertEquals(160L, p.unionMinutes())
    }

    @Test fun overlapPairIsListedNotRemoved() {
        val events = listOf(
            TimeEventCodec.parse("""{"op":"start","event_id":"a","id":"t-x1","date":"2026-10-23","start":"09:00","project":"甲","ts":"2026-10-23T09:00:00"}"""),
            TimeEventCodec.parse("""{"op":"stop","event_id":"b","id":"t-x1","end":"11:00","ts":"2026-10-23T11:00:00"}"""),
            TimeEventCodec.parse("""{"op":"start","event_id":"c","id":"t-x2","date":"2026-10-23","start":"10:00","project":"乙","ts":"2026-10-23T10:00:00"}"""),
            TimeEventCodec.parse("""{"op":"stop","event_id":"d","id":"t-x2","end":"12:00","ts":"2026-10-23T12:00:00"}""")
        )
        val p = TimeProjectionBuilder.build(events)
        assertEquals(listOf("t-x1" to "t-x2"), p.overlapPairs())
        assertEquals(240L, p.sumMinutes()) // two records, 120 minutes each
        assertEquals(180L, p.unionMinutes()) // 09:00-12:00 counted once
        assertEquals(2, p.records.size)
    }

    @Test fun duplicateSuspectsAreListedOnly() {
        val events = listOf(
            TimeEventCodec.parse("""{"op":"start","event_id":"a","id":"t-x1","date":"2026-10-23","start":"09:00","project":"甲","ts":"2026-10-23T09:00:00"}"""),
            TimeEventCodec.parse("""{"op":"stop","event_id":"b","id":"t-x1","end":"10:00","ts":"2026-10-23T10:00:00"}"""),
            TimeEventCodec.parse("""{"op":"start","event_id":"c","id":"t-x2","date":"2026-10-23","start":"09:00","project":"甲","ts":"2026-10-23T09:00:00"}"""),
            TimeEventCodec.parse("""{"op":"stop","event_id":"d","id":"t-x2","end":"10:00","ts":"2026-10-23T10:00:00"}""")
        )
        val p = TimeProjectionBuilder.build(events)
        assertEquals(1, p.duplicateSuspects().size)
        assertEquals(2, p.duplicateSuspectCount)
        assertEquals(2, p.records.size) // nothing deleted
    }

    @Test fun orphanAmendAndOrphanStopAreFlaggedNotDropped() {
        val p = projection()
        assertEquals(1, p.orphanAmendCount)
        assertEquals(1, p.orphanStopCount)
    }

    @Test fun invalidRangeIsFlaggedAndExcludedFromTotals() {
        val events = listOf(
            TimeEventCodec.parse("""{"op":"start","event_id":"a","id":"t-x1","date":"2026-10-23","start":"10:00","project":"甲","ts":"2026-10-23T10:00:00"}"""),
            TimeEventCodec.parse("""{"op":"stop","event_id":"b","id":"t-x1","end":"09:00","ts":"2026-10-23T09:30:00"}""")
        )
        val p = TimeProjectionBuilder.build(events)
        assertEquals(1, p.invalidCount)
        assertEquals(0L, p.records.first().minutes())
        assertEquals(0L, p.unionMinutes())
    }

    @Test fun recordsAreSortedByBeginThenId() {
        val p = projection()
        val begins = p.records.map { it.begin }
        assertEquals(begins.sorted(), begins)
    }

    @Test fun duplicateStartIdsFailClosed() {
        val events = listOf(
            TimeEventCodec.parse("""{"op":"start","event_id":"a","id":"t-x1","date":"2026-10-23","start":"09:00","project":"甲","ts":"2026-10-23T09:00:00"}"""),
            TimeEventCodec.parse("""{"op":"start","event_id":"b","id":"t-x1","date":"2026-10-23","start":"09:30","project":"甲","ts":"2026-10-23T09:30:00"}""")
        )
        try { TimeProjectionBuilder.build(events); fail("duplicate start accepted") } catch (e: Exception) { }
    }

    @Test fun amendToTagsAndNoteAndEndIsAppliedInOrder() {
        val events = listOf(
            TimeEventCodec.parse("""{"op":"start","event_id":"a","id":"t-x1","date":"2026-10-23","start":"09:00","project":"甲","tags":["旧"],"note":"旧","ts":"2026-10-23T09:00:00"}"""),
            TimeEventCodec.parse("""{"op":"amend","event_id":"b","target":"t-x1","field":"tags","value":["新"],"ts":"2026-10-23T09:10:00"}"""),
            TimeEventCodec.parse("""{"op":"amend","event_id":"c","target":"t-x1","field":"note","value":"新","ts":"2026-10-23T09:11:00"}"""),
            TimeEventCodec.parse("""{"op":"stop","event_id":"d","id":"t-x1","end":"10:00","ts":"2026-10-23T10:00:00"}"""),
            TimeEventCodec.parse("""{"op":"amend","event_id":"e","target":"t-x1","field":"end","value":"10:30","ts":"2026-10-23T10:31:00"}"""),
            TimeEventCodec.parse("""{"op":"amend","event_id":"f","target":"t-x1","field":"tags","value":null,"ts":"2026-10-23T10:32:00"}""")
        )
        val r = TimeProjectionBuilder.build(events).records.first()
        assertEquals(90L, r.minutes())
        assertEquals(emptyList<String>(), r.tags)
        assertEquals("新", r.note)
        assertEquals(4, r.amendCount)
    }

    @Test fun renameAfterAmendStillApplies() {
        val events = listOf(
            TimeEventCodec.parse("""{"op":"start","event_id":"a","id":"t-x1","date":"2026-10-23","start":"09:00","project":"旧名","ts":"2026-10-23T09:00:00"}"""),
            TimeEventCodec.parse("""{"op":"amend","event_id":"b","target":"t-x1","field":"project","value":"中间名","ts":"2026-10-23T09:10:00"}"""),
            TimeEventCodec.parse("""{"op":"rename_project","event_id":"c","from":"中间名","to":"新名","ts":"2026-10-23T09:20:00"}""")
        )
        val r = TimeProjectionBuilder.build(events).records.first()
        assertEquals("新名", r.project)
    }

    @Test fun emptyEventListYieldsEmptyProjection() {
        val p = TimeProjectionBuilder.build(emptyList())
        assertTrue(p.records.isEmpty())
        assertEquals(0L, p.unionMinutes())
        assertEquals(0, p.runningCount)
    }

    @Test fun timezoneIsExplicitLocalForBothDateAndProjection() {
        val r = projection().records.first { it.id == "t-aaaa0001" }
        // Local-date based: the record belongs to its local start day regardless of device zone.
        assertEquals(LocalDate.of(2026, 10, 23), LocalDate.parse(r.date))
        assertEquals(LocalDateTime.parse("2026-10-23T09:05:00"), r.begin)
        assertEquals(LocalDateTime.parse("2026-10-23T10:30:00"), r.finish)
    }
}

/** Running state must not depend on any UI timer persistence: the projection is a pure function
 *  of the event log, and the display start only borrows the recorded ts when it agrees with the
 *  event's own date+start. */
class TimeProjectionPurityTest {

    @Test fun runningStateComesFromEventsNotFromUiState() {
        val events = listOf(
            TimeEventCodec.parse("""{"op":"start","event_id":"a","id":"t-x1","date":"2026-10-23","start":"09:00","project":"甲","ts":"2026-10-23T09:00:01"}""")
        )
        val first = TimeProjectionBuilder.build(events)
        val second = TimeProjectionBuilder.build(events)
        assertEquals(first, second)
        assertTrue(first.find("t-x1")!!.running())
        // No stop event anywhere in the log: nothing to persist, nothing to restore.
        assertTrue(events.none { it.op == "stop" })
    }

    @Test fun displayBeginUsesRecordedTsOnlyWhenItAgreesWithEventTime() {
        val agree = TimeEventCodec.parse("""{"op":"start","event_id":"a","id":"t-x1","date":"2026-10-23","start":"09:00","project":"甲","ts":"2026-10-23T09:00:01"}""")
        val r1 = TimeProjectionBuilder.build(listOf(agree)).find("t-x1")!!
        assertEquals(LocalDateTime.parse("2026-10-23T09:00:01"), r1.displayBegin())
        val disagree = TimeEventCodec.parse("""{"op":"start","event_id":"a","id":"t-x1","date":"2026-10-23","start":"09:00","project":"甲","ts":"2026-10-23T08:59:59"}""")
        val r2 = TimeProjectionBuilder.build(listOf(disagree)).find("t-x1")!!
        assertEquals(LocalDateTime.parse("2026-10-23T09:00:00"), r2.displayBegin())
        // A backfilled/amended start does not resurrect an old UI timestamp.
        val amended = listOf(
            agree,
            TimeEventCodec.parse("""{"op":"amend","event_id":"b","target":"t-x1","field":"start","value":"08:30","ts":"2026-10-23T12:00:00"}""")
        )
        val r3 = TimeProjectionBuilder.build(amended).find("t-x1")!!
        assertEquals("08:30", r3.start)
        assertEquals(LocalDateTime.parse("2026-10-23T08:30:00"), r3.displayBegin())
    }

    @Test fun reopenOfTheSameEventsIsDeterministic() {
        val text = File(javaClass.classLoader!!.getResource("time/golden-events.jsonl")!!.file).readText()
        val events = text.trimEnd('\n').split('\n').map { TimeEventCodec.parse(it) }
        assertEquals(TimeProjectionBuilder.build(events), TimeProjectionBuilder.build(events))
    }
}
