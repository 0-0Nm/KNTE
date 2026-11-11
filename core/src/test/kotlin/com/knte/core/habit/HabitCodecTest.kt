package com.knte.core.habit

import com.knte.core.time.format.TimeFields
import com.knte.core.time.format.TimeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Golden-file contract tests for the habit codec: strict parsing, unknown keys, cached hints. */
class HabitCodecTest {

    private fun goldenFile(): File =
        File(javaClass.classLoader!!.getResource("habit/golden-habits.jsonl")!!.file)

    private fun golden(): HabitSnapshot = HabitSnapshot.parse(goldenFile().readBytes())

    private fun goldenLines(): List<String> = goldenFile().readText().trimEnd('\n').split('\n')

    @Test fun goldenFileParsesAndKeepsEveryLine() {
        val snapshot = golden()
        val text = File(javaClass.classLoader!!.getResource("habit/golden-habits.jsonl")!!.file).readText()
        val expected = text.trimEnd('\n').split('\n').size
        assertEquals(expected, snapshot.eventCount)
        assertEquals(expected, snapshot.events().size)
        assertEquals(HabitCodec.hash(snapshot.bytes()), snapshot.revision)
        // Same-day correction line is preserved, not merged away.
        assertEquals(1, snapshot.sameDayCorrectionCount)
    }

    @Test fun identitiesLifecycleAndUnknownKeysSurviveRoundTrip() {
        val snapshot = golden()
        val archived = snapshot.identity("h-aaaa0003")!!
        assertTrue(archived.archived)
        assertEquals("旧习惯改", archived.name)
        assertEquals(30, archived.targetDays)
        assertEquals("2026-10-03T09:00:00", archived.createdTs) // immutable across edits
        assertEquals(3, snapshot.identities().size)
        assertEquals(listOf("h-aaaa0001", "h-aaaa0002", "h-aaaa0003"), snapshot.identities().map { it.id })
        // Unknown identity/check keys are preserved verbatim in the parsed model.
        val corrected = snapshot.check("h-aaaa0001", LocalDate.of(2026, 10, 10))!!
        assertEquals("更正：补记完成", corrected.note)
        assertEquals(listOf("unknown_future_key"), corrected.unknownKeys.map { it.first })
        assertTrue(snapshot.events().first { it.eventId == "r-a0000010" }.unknownKeys.isEmpty())
        val unknown = corrected.unknownKeys[0]
        assertEquals("unknown_future_key", unknown.first)
        val nested = unknown.second as TimeJson.Value.Obj
        assertEquals("nested", nested.entries.single().first)
    }

    @Test fun canonicalEncodingOfAParsedLineMatchesTheOriginalBytes() {
        val snapshot = golden()
        val line = snapshot.events()[3]
        assertEquals(
            File(javaClass.classLoader!!.getResource("habit/golden-habits.jsonl")!!.file)
                .readText().trimEnd('\n').split('\n')[3],
            line.encode()
        )
        assertTrue(line.token().matches(Regex("[0-9a-f]{64}")))
        // Canonical encoding is stable and reproducible.
        assertEquals(line.encode(), HabitCodec.parseEvent(line.encode()).encode())
    }

    @Test fun canonicalEncodingNeverDuplicatesKeysOrPollutesUnknownKeys() {
        val events = golden().events()
        assertEquals(goldenLines().size, events.size)
        events.forEachIndexed { index, event ->
            val encoded = event.encode()
            listOf("\"event_id\":", "\"op\":", "\"ts\":", "\"at_trust\":").forEach { key ->
                assertEquals("line $index repeated $key", 1, occurrences(encoded, key))
            }
            if (event.op == "check") {
                assertTrue("line $index event-level unknownKeys polluted", event.unknownKeys.isEmpty())
                listOf("\"id\":", "\"name\":", "\"date\":", "\"done\":").forEach { key ->
                    assertEquals("line $index repeated $key", 1, occurrences(encoded, key))
                }
            }
            // A duplicate key would also make the strict parser refuse this canonical line.
            assertEquals("line $index unstable", encoded, HabitCodec.parseEvent(encoded).encode())
        }
        val corrected = events.first { it.eventId == "r-a0000010" }
        assertEquals(listOf("unknown_future_key"), corrected.check!!.unknownKeys.map { it.first })
        assertEquals(1, occurrences(corrected.encode(), "\"unknown_future_key\":"))

        val defineWithUnknown = """{"event_id":"r-feed0001","op":"define","habit":{"id":"h-feed0001","name":"x","target_days":7,"created_ts":"2026-10-01T08:00:00","archived":false},"future_event_key":{"ok":true},"ts":"2026-10-01T08:00:00","at_trust":"unverified"}"""
        val define = HabitCodec.parseEvent(defineWithUnknown)
        assertEquals(listOf("future_event_key"), define.unknownKeys.map { it.first })
        assertEquals(defineWithUnknown, define.encode())
        assertEquals(1, occurrences(define.encode(), "\"future_event_key\":"))
    }

    @Test fun missingDayIsUnknownNeverFailure() {
        val snapshot = golden()
        assertEquals(HabitMark.UNKNOWN, snapshot.mark("h-aaaa0001", LocalDate.of(2026, 10, 4)))
        assertEquals(HabitMark.UNKNOWN, snapshot.mark("h-aaaa0001", LocalDate.of(2026, 10, 12)))
        assertEquals(HabitMark.FAILED, snapshot.mark("h-aaaa0001", LocalDate.of(2026, 10, 9)))
        assertEquals(HabitMark.DONE, snapshot.mark("h-aaaa0001", LocalDate.of(2026, 10, 11)))
        // A gap in the middle is visible as UNKNOWN, and the streak stops there.
        assertEquals(HabitMark.UNKNOWN, snapshot.mark("h-aaaa0002", LocalDate.of(2026, 10, 6)))
        assertEquals(1, snapshot.streak("h-aaaa0002", LocalDate.of(2026, 10, 7)))
        assertEquals(0, snapshot.streak("h-aaaa0002", LocalDate.of(2026, 10, 6)))
        assertNull(snapshot.daily(LocalDate.of(2026, 10, 8)))
    }

    @Test fun streakAndStageAreDerivedFromTheLatestRowOnly() {
        val snapshot = golden()
        // 10-05..10-08 done, 10-09 done=false, 10-10..10-11 done (10-10 carries a same-day correction).
        assertEquals(1, snapshot.streak("h-aaaa0001", LocalDate.of(2026, 10, 5)))
        assertEquals(2, snapshot.streak("h-aaaa0001", LocalDate.of(2026, 10, 6)))
        assertEquals(3, snapshot.streak("h-aaaa0001", LocalDate.of(2026, 10, 7)))
        assertEquals(4, snapshot.streak("h-aaaa0001", LocalDate.of(2026, 10, 8)))
        assertEquals(0, snapshot.streak("h-aaaa0001", LocalDate.of(2026, 10, 9)))
        assertEquals(1, snapshot.streak("h-aaaa0001", LocalDate.of(2026, 10, 10)))
        assertEquals(2, snapshot.streak("h-aaaa0001", LocalDate.of(2026, 10, 11)))
        // Today unrecorded -> yesterday's streak is reported, today is not fabricated as a loss.
        assertEquals(2, snapshot.currentStreak("h-aaaa0001", LocalDate.of(2026, 10, 12)))
        assertEquals(2, snapshot.currentStreak("h-aaaa0001", LocalDate.of(2026, 10, 11)))
        assertEquals(1, snapshot.currentStreak("h-aaaa0001", LocalDate.of(2026, 10, 10)))
        assertEquals(3, snapshot.totalDone("h-aaaa0001", LocalDate.of(2026, 10, 7)))
        assertEquals(6, snapshot.totalDone("h-aaaa0001", LocalDate.of(2026, 10, 11)))
    }

    @Test fun growthThresholdsMatchTheContract() {
        assertEquals("尚未开始", HabitProjection.stage(0))
        assertEquals("种子", HabitProjection.stage(1))
        assertEquals("种子", HabitProjection.stage(2))
        assertEquals("萌芽", HabitProjection.stage(3))
        assertEquals("幼苗", HabitProjection.stage(7))
        assertEquals("成树", HabitProjection.stage(21))
        assertEquals("成树", HabitProjection.stage(65))
        assertEquals("结果", HabitProjection.stage(66))
        assertEquals(HabitProjection.STAGE_THRESHOLDS.map { it.second },
            HabitProjection.STAGE_THRESHOLDS.map { HabitProjection.stage(it.first) })
        for (days in 1..200) {
            assertEquals(HabitProjection.stageLevel(days) >= 1, days >= 1)
        }
    }

    @Test fun heatWindowKeepsUnknownDaysAndIsSameSource() {
        val snapshot = golden()
        val today = LocalDate.of(2026, 10, 11)
        val week = snapshot.heat("h-aaaa0001", today, 7)
        assertEquals(7, week.size)
        // 10-05..10-11: DONE DONE DONE DONE FAILED DONE DONE
        assertEquals(listOf(
            HabitMark.DONE, HabitMark.DONE, HabitMark.DONE, HabitMark.DONE,
            HabitMark.FAILED, HabitMark.DONE, HabitMark.DONE
        ), week)
        val month = snapshot.heat("h-aaaa0001", today, 28)
        assertEquals(28, month.size)
        assertEquals(HabitMark.DONE, month.last())
        // Every day before the first check stays UNKNOWN, never false.
        assertTrue(month.dropLast(7).all { it == HabitMark.UNKNOWN })
        assertTrue(month.takeLast(7).none { it == HabitMark.UNKNOWN })
    }

    @Test fun threeIncompleteReflectionOnlyCountsExplicitFailures() {
        val snapshot = golden()
        // 日新: 10-09 done, 10-10/10-11/10-12 done=false.
        assertTrue(snapshot.threeIncomplete(LocalDate.of(2026, 10, 13)))
        assertFalse(snapshot.threeIncomplete(LocalDate.of(2026, 10, 12))) // 10-09 was a success
        assertFalse(snapshot.threeIncomplete(LocalDate.of(2026, 10, 11)))
        assertFalse(snapshot.threeIncomplete(LocalDate.of(2026, 10, 9))) // missing days are not failures
        assertEquals(true, snapshot.daily(LocalDate.of(2026, 10, 9)))
        assertEquals(false, snapshot.daily(LocalDate.of(2026, 10, 12)))
    }

    @Test fun archivedHabitKeepsRowsButIsHiddenFromActiveLists() {
        val snapshot = golden()
        assertTrue(snapshot.identity("h-aaaa0003")!!.archived)
        assertEquals(2, snapshot.identities(includeArchived = false).size) // h-aaaa0003 archived
        // Nothing was physically deleted: history rows are still readable.
        assertTrue(snapshot.events().count { it.op == "define" } >= 5)
    }

    @Test fun cachedHintMismatchIsRejectedNotTrusted() {
        val bad = """{"event_id":"r-b0000001","op":"define","habit":{"id":"h-bbbb0001","name":"x","target_days":7,"created_ts":"2026-10-01T08:00:00","archived":false},"ts":"2026-10-01T08:00:00","at_trust":"unverified"}""" + "\n" +
            """{"event_id":"r-b0000002","op":"check","id":"h-bbbb0001","name":"x","date":"2026-10-02","done":true,"streak":9,"stage":"幼苗","ts":"2026-10-02T08:00:00","at_trust":"unverified"}""" + "\n"
        fails { HabitSnapshot.parse(bad.toByteArray()) }
        val wrongStage = bad.replace("\"streak\":9", "\"streak\":1").replace("\"stage\":\"幼苗\"", "\"stage\":\"成树\"")
        fails { HabitSnapshot.parse(wrongStage.toByteArray()) }
    }

    @Test fun unknownOpAndStrictFieldsFailClosed() {
        val define = """{"event_id":"r-c0000001","op":"define","habit":{"id":"h-cccc0001","name":"x","target_days":7,"created_ts":"2026-10-01T08:00:00","archived":false},"ts":"2026-10-01T08:00:00","at_trust":"unverified"}"""
        fails { HabitCodec.parseEvent(define.replace("\"op\":\"define\"", "\"op\":\"delete\"")) }
        fails { HabitCodec.parseEvent(define.replace("\"op\":\"define\"", "\"op\":\"check\"")) }
        fails { HabitCodec.parseEvent(define.replace("\"at_trust\":\"unverified\"", "\"at_trust\":\"verified\"")) }
        fails { HabitCodec.parseEvent(define.replace("\"event_id\":\"r-c0000001\"", "\"event_id\":\"x-1\"")) }
        fails { HabitCodec.parseEvent(define.replace("\"habit\":", "")) }
        // Strict date / timestamp: no calendar guessing, no future checks.
        val check = """{"event_id":"r-c0000002","op":"check","id":"h-cccc0001","name":"x","date":"2026-10-02","done":true,"ts":"2026-10-02T08:00:00","at_trust":"unverified"}"""
        fails { HabitCodec.parseEvent(check.replace("\"date\":\"2026-10-02\"", "\"date\":\"2026-02-30\"")) }
        fails { HabitCodec.parseEvent(check.replace("\"date\":\"2026-10-02\"", "\"date\":\"2026-10-02T08:00:00\"")) }
        fails { HabitCodec.parseEvent(check.replace("\"ts\":\"2026-10-02T08:00:00\"", "\"ts\":\"2026-10-02T08:00\"")) }
        fails { HabitCodec.parseEvent(check.replace("\"done\":true", "\"done\":\"yes\"")) }
        // Future check against the write timestamp is refused.
        fails { HabitCodec.parseEvent(check.replace("\"date\":\"2026-10-02\"", "\"date\":\"2026-10-03\"")) }
    }

    @Test fun emptyAndMalformedFilesFailClosed() {
        assertTrue(HabitSnapshot.parse(ByteArray(0)).events().isEmpty())
        fails { HabitSnapshot.parse("""{"event_id":"r-d0000001","op":"define","habit":{"id":"h-dddd0001","name":"x","target_days":7,"created_ts":"2026-10-01T08:00:00","archived":false},"ts":"2026-10-01T08:00:00","at_trust":"unverified"}""".toByteArray()) }
        fails { HabitSnapshot.parse(byteArrayOf(1, 2, 3)) }
        fails { HabitSnapshot.parse("x\n".toByteArray()) }
        // A check for a habit that was never defined is refused.
        val orphan = """{"event_id":"r-d0000002","op":"check","id":"h-dddd0002","name":"x","date":"2026-10-02","done":true,"ts":"2026-10-02T08:00:00","at_trust":"unverified"}""" + "\n"
        fails { HabitSnapshot.parse(orphan.toByteArray()) }
        // A check of an archived habit is refused.
        val archived = """{"event_id":"r-d0000003","op":"define","habit":{"id":"h-dddd0003","name":"x","target_days":7,"created_ts":"2026-10-01T08:00:00","archived":true},"ts":"2026-10-01T08:00:00","at_trust":"unverified"}""" + "\n" +
            """{"event_id":"r-d0000004","op":"check","id":"h-dddd0003","name":"x","date":"2026-10-02","done":true,"ts":"2026-10-02T08:00:00","at_trust":"unverified"}""" + "\n"
        fails { HabitSnapshot.parse(archived.toByteArray()) }
        // Duplicate event ids are refused.
        val duplicate = """{"event_id":"r-d0000005","op":"define","habit":{"id":"h-dddd0004","name":"x","target_days":7,"created_ts":"2026-10-01T08:00:00","archived":false},"ts":"2026-10-01T08:00:00","at_trust":"unverified"}""" + "\n" +
            """{"event_id":"r-d0000005","op":"define","habit":{"id":"h-dddd0004","name":"x","target_days":7,"created_ts":"2026-10-01T08:00:00","archived":false},"ts":"2026-10-01T08:00:00","at_trust":"unverified"}""" + "\n"
        fails { HabitSnapshot.parse(duplicate.toByteArray()) }
        // Identity recreated with a different created_ts is refused.
        val recreated = """{"event_id":"r-d0000006","op":"define","habit":{"id":"h-dddd0005","name":"x","target_days":7,"created_ts":"2026-10-01T08:00:00","archived":false},"ts":"2026-10-01T08:00:00","at_trust":"unverified"}""" + "\n" +
            """{"event_id":"r-d0000007","op":"define","habit":{"id":"h-dddd0005","name":"x","target_days":7,"created_ts":"2026-10-02T08:00:00","archived":false},"ts":"2026-10-02T08:00:00","at_trust":"unverified"}""" + "\n"
        fails { HabitSnapshot.parse(recreated.toByteArray()) }
    }

    @Test fun dailyReflectionNeedsNoDefineAndRejectsWrongName() {
        val daily = """{"event_id":"r-e0000001","op":"check","id":"h-00000000","name":"日新","date":"2026-10-02","done":true,"streak":1,"stage":"种子","ts":"2026-10-02T20:10:00","at_trust":"unverified"}""" + "\n"
        val snapshot = HabitSnapshot.parse(daily.toByteArray())
        assertEquals(true, snapshot.daily(LocalDate.of(2026, 10, 2)))
        fails { HabitSnapshot.parse(daily.replace("\"name\":\"日新\"", "\"name\":\"别的\"").toByteArray()) }
        // 日新 is a reserved identity id and cannot be defined.
        val define = """{"event_id":"r-e0000002","op":"define","habit":{"id":"h-00000000","name":"日新","target_days":7,"created_ts":"2026-10-01T08:00:00","archived":false},"ts":"2026-10-01T08:00:00","at_trust":"unverified"}"""
        fails { HabitCodec.parseEvent(define) }
    }

    @Test fun timezoneBoundaryDatesAreExactStrings() {
        // A check day is the literal date string: 23:59 and 00:00 are different days, never merged.
        val line = """{"event_id":"r-f0000001","op":"check","id":"h-00000000","name":"日新","date":"2026-10-10","done":true,"streak":1,"stage":"种子","ts":"2026-10-10T23:59:59","at_trust":"unverified"}"""
        val snapshot = HabitSnapshot.parse((line + "\n").toByteArray())
        assertEquals(HabitMark.DONE, snapshot.mark(HabitProjection.DAILY_ID, LocalDate.of(2026, 10, 10)))
        assertEquals(HabitMark.UNKNOWN, snapshot.mark(HabitProjection.DAILY_ID, LocalDate.of(2026, 10, 11)))
        // The write timestamp may be on a later day than a backfilled check day.
        val backfill = """{"event_id":"r-f0000002","op":"check","id":"h-00000000","name":"日新","date":"2026-10-09","done":false,"streak":0,"stage":"尚未开始","ts":"2026-10-10T00:00:01","at_trust":"unverified"}"""
        val both = HabitSnapshot.parse((line + "\n" + backfill + "\n").toByteArray())
        assertEquals(HabitMark.FAILED, both.mark(HabitProjection.DAILY_ID, LocalDate.of(2026, 10, 9)))
        assertEquals(TimeFields.date("2026-10-09"), "2026-10-09")
    }

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    private fun fails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected refusal")
        } catch (_: Exception) {
            // required fail-closed path
        }
    }
}
