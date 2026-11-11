package com.knte.core.reference

import com.knte.core.time.format.TimeJson
import com.knte.core.time.format.TimeJsonValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferencePickTest {
    private val all = ReferenceScope.all()
    private val ts = "2026-11-11T21:03:11"

    private fun pick(
        entries: Collection<ReferenceEntry>,
        vararg tickets: Long,
        memoRecent: List<String> = emptyList(),
        eventId: String? = "r-1a2b3c4d",
    ): TurnPick =
        ReferencePick.pickTurn(
            entries, all, TEST_TODAY, ts, ScriptedRandom(tickets),
            memoRecent = memoRecent, eventId = eventId,
        )!!

    @Test
    fun turnPickDrawsBothPoolsAndAdvancesQueues() {
        val pick = pick(fourMemos() + fourPitfalls(), 0, 0)
        assertEquals("r-1a2b3c4d", pick.eventId)
        assertEquals("m-00000001", pick.memo!!.entry.id)
        assertEquals("p-00000001", pick.pitfall!!.entry.id)
        assertEquals(emptyList<String>(), pick.memoRecentBefore)
        assertEquals(emptyList<String>(), pick.pitfallRecentBefore)
        assertEquals(listOf("m-00000001"), pick.memoRecentAfter)
        assertEquals(listOf("p-00000001"), pick.pitfallRecentAfter)
        assertEquals(ts, pick.ts)
    }

    @Test
    fun eventRecordCarriesCompleteMetadata() {
        val pick = pick(fourMemos() + fourPitfalls(), 0, 0)
        val record = PickEventRecord.of(pick)
        val line = record.encodeLine()
        assertTrue(line.endsWith("\n"))
        val event = TimeJson.parse(line) as TimeJson.Value.Obj
        assertEquals(
            listOf(
                "id", "op", "ts", "at_trust", "today", "scope",
                "memo_recent_before", "pitfall_recent_before", "memo", "pitfall", "seed",
            ),
            event.entries.map { it.first },
        )
        fun field(name: String): TimeJson.Value = event.entries.first { it.first == name }.second
        assertEquals("r-1a2b3c4d", TimeJsonValues.str(field("id")))
        assertEquals("pick", TimeJsonValues.str(field("op")))
        assertEquals("unverified", TimeJsonValues.str(field("at_trust")))
        assertEquals("2026-11-11", TimeJsonValues.str(field("today")))
        assertTrue(field("seed") is TimeJson.Value.Null)
        val memo = field("memo") as TimeJson.Value.Obj
        fun mfield(name: String): TimeJson.Value = memo.entries.first { it.first == name }.second
        assertEquals(
            listOf(
                "pool", "id", "item", "base_units", "base_weight", "effective_units",
                "effective_weight", "reason", "recent", "pool_size", "ticket", "total_units", "mode",
            ),
            memo.entries.map { it.first },
        )
        assertEquals("memo", TimeJsonValues.str(mfield("pool")))
        assertEquals("m-00000001", TimeJsonValues.str(mfield("id")))
        assertEquals("42", (mfield("base_units") as TimeJson.Value.Num).literal)
        assertEquals("2.1", TimeJsonValues.str(mfield("base_weight")))
        assertEquals("42", (mfield("effective_units") as TimeJson.Value.Num).literal)
        assertEquals("memo_due_0_3", TimeJsonValues.str(mfield("reason")))
        assertFalse((mfield("recent") as TimeJson.Value.Bool).value)
        assertEquals("4", (mfield("pool_size") as TimeJson.Value.Num).literal)
        assertEquals("0", (mfield("ticket") as TimeJson.Value.Num).literal)
        assertEquals("106", (mfield("total_units") as TimeJson.Value.Num).literal)
        assertEquals("weighted", TimeJsonValues.str(mfield("mode")))
        val item = mfield("item") as TimeJson.Value.Obj
        assertEquals(
            listOf("op", "id", "title", "due", "done", "archived", "tags", "created_ts", "ts"),
            item.entries.map { it.first },
        )
        assertEquals("2026-11-11", TimeJsonValues.str(item.entries.first { it.first == "due" }.second))
        // 同域内解析回读与原记录一致（可互验）
        assertEquals(record, PickEventRecord.parse(event))
    }

    @Test
    fun bothPoolsEmptyProduceNoFakePick() {
        assertNull(ReferencePick.pickTurn(emptyList(), all, TEST_TODAY, ts, ScriptedRandom(longArrayOf(0)), eventId = "r-1a2b3c4d"))
        assertNull(
            ReferencePick.pickTurn(
                fourMemos(), ReferenceScope.world("归墟", listOf("归墟")),
                TEST_TODAY, ts, ScriptedRandom(longArrayOf(0)), eventId = "r-1a2b3c4d",
            ),
        )
    }

    @Test
    fun oneEmptyPoolKeepsItsQueueAndRecordHonest() {
        val pick = ReferencePick.pickTurn(
            fourPitfalls(), all, TEST_TODAY, ts,
            ScriptedRandom(longArrayOf(0)),
            memoRecent = listOf("m-00000009"),
            eventId = "r-1a2b3c4d",
        )!!
        assertNull(pick.memo)
        assertEquals(listOf("m-00000009"), pick.memoRecentBefore)
        assertEquals(listOf("m-00000009"), pick.memoRecentAfter) // 空池不动队列（不补样例）
        assertEquals("p-00000001", pick.pitfall!!.entry.id)
        val record = PickEventRecord.of(pick)
        val event = TimeJson.parse(record.encodeLine()) as TimeJson.Value.Obj
        assertTrue(event.entries.first { it.first == "memo" }.second is TimeJson.Value.Null)
        val outcome = ReferencePick.verifyTurn(fourPitfalls(), record)
        assertTrue(outcome.ok)
        assertEquals(ReplayStatus.OK, outcome.memo)
        assertEquals(ReplayStatus.OK, outcome.pitfall)
    }

    @Test
    fun queuesHoldOnlyTheLastThreePicks() {
        var memoRecent = emptyList<String>()
        val tickets = listOf(0L, 30, 40, 50) // Valid tickets under each progressively halved pool.
        for (turn in tickets.indices) {
            val pick = ReferencePick.pickTurn(
                fourMemos(), all, TEST_TODAY, "2026-11-11T21:0${turn}:11",
                ScriptedRandom(longArrayOf(tickets[turn])),
                memoRecent = memoRecent,
                eventId = "r-0000000${turn + 1}",
            )!!
            memoRecent = pick.memoRecentAfter
        }
        assertEquals(listOf("m-00000002", "m-00000003", "m-00000004"), memoRecent)
    }

    @Test
    fun replayVerifiesFreshRecord() {
        val entries = fourMemos() + fourPitfalls()
        val pick = pick(entries, 0, 0)
        val record = PickEventRecord.of(pick)
        val parsed = PickEventRecord.parse(TimeJson.parse(record.encodeLine()))
        assertEquals(record, parsed)
        val outcome = ReferencePick.verifyTurn(entries, parsed)
        assertTrue(outcome.ok)
        assertEquals(ReplayStatus.OK, outcome.memo)
        assertEquals(ReplayStatus.OK, outcome.pitfall)
        assertNull(outcome.binding)
    }

    @Test
    fun replayDetectsEntryDrift() {
        val pick = pick(fourMemos() + fourPitfalls(), 0, 0)
        val record = PickEventRecord.of(pick)
        // m-00000001 的 due 从当天改为 +30 天：权重 42 → 30
        val drifted = fourMemos().map { entry ->
            if (entry.id == "m-00000001") {
                memoEntry("m-00000001", title = "今天到期", due = TEST_TODAY.plusDays(30).toString())
            } else entry
        } + fourPitfalls()
        val outcome = ReferencePick.verifyTurn(drifted, record)
        assertFalse(outcome.ok)
        assertEquals(ReplayStatus.SNAPSHOT_MISMATCH, outcome.memo)
        assertEquals(ReplayStatus.OK, outcome.pitfall)
    }

    @Test
    fun replayStatusesForPoolShrinkAndForgedRecords() {
        val pick = pick(fourMemos() + fourPitfalls(), 0, 0)
        val record = PickEventRecord.of(pick)
        // 被抽中条目被移走：票仍落池但快照不同
        val shrunk = fourMemos().filter { it.id != "m-00000001" } + fourPitfalls()
        val outcome = ReferencePick.verifyTurn(shrunk, record)
        assertNotEquals(ReplayStatus.OK, outcome.memo)
        assertEquals(ReplayStatus.SNAPSHOT_MISMATCH, outcome.memo)
        assertEquals(ReplayStatus.OK, outcome.pitfall)
        // 记录非空而池空 → 伪造
        assertEquals(
            ReplayStatus.EMPTY_POOL_WITH_RECORD,
            ReferencePick.replayChoice(fourPitfalls(), ReferencePool.MEMO, record.memo!!, all, TEST_TODAY, emptyList()),
        )
        // 池非空而记录缺失 → 伪造
        assertEquals(
            ReplayStatus.RECORD_MISSING,
            ReferencePick.replayChoice(fourMemos(), ReferencePool.MEMO, null, all, TEST_TODAY, emptyList()),
        )
        // 原样重放通过
        assertEquals(
            ReplayStatus.OK,
            ReferencePick.replayChoice(fourMemos(), ReferencePool.MEMO, record.memo!!, all, TEST_TODAY, emptyList()),
        )
    }

    @Test
    fun ticketOutOfRangeWhenPoolShrinksBelowTicket() {
        val pick = pick(fourMemos() + fourPitfalls(), 105, 0) // memo ticket 105 → m-00000004
        assertEquals("m-00000004", pick.memo!!.entry.id)
        val record = PickEventRecord.of(pick)
        val shrunk = fourMemos().filter { it.id != "m-00000004" } + fourPitfalls()
        val outcome = ReferencePick.verifyTurn(shrunk, record)
        assertEquals(ReplayStatus.TICKET_OUT_OF_RANGE, outcome.memo)
    }

    @Test
    fun parseRejectsForgedOrTamperedEvents() {
        val bound = validBinding()
        val pick = ReferencePick.pickTurn(fourMemos(), all, TEST_TODAY, ts, ScriptedRandom(longArrayOf(0)), binding = bound)!!
        val line = PickEventRecord.of(pick).encodeLine()
        PickEventRecord.parse(TimeJson.parse(line)) // 正常可解析
        // 篡改事件 id：与绑定派生 id 不一致
        fails { PickEventRecord.parse(TimeJson.parse(line.replace("\"id\":\"" + bound.eventId + "\"", "\"id\":\"r-ffffffff\""))) }
        // op 非法
        fails {
            PickEventRecord.parse(
                TimeJson.parse(
                    """{"id":"r-1a2b3c4d","op":"upsert","ts":"2026-11-11T21:03:11","at_trust":"unverified","today":"2026-11-11","scope":{"kind":"all","label":"全部世界","names":[]},"memo_recent_before":[],"pitfall_recent_before":[],"memo":null,"pitfall":null,"seed":null}""",
                ),
            )
        }
        // at_trust 非法
        fails {
            PickEventRecord.parse(
                TimeJson.parse(
                    """{"id":"r-1a2b3c4d","op":"pick","ts":"2026-11-11T21:03:11","at_trust":"verified","today":"2026-11-11","scope":{"kind":"all","label":"全部世界","names":[]},"memo_recent_before":[],"pitfall_recent_before":[],"memo":null,"pitfall":null,"seed":null}""",
                ),
            )
        }
        // seed 非空
        fails {
            PickEventRecord.parse(
                TimeJson.parse(
                    """{"id":"r-1a2b3c4d","op":"pick","ts":"2026-11-11T21:03:11","at_trust":"unverified","today":"2026-11-11","scope":{"kind":"all","label":"全部世界","names":[]},"memo_recent_before":[],"pitfall_recent_before":[],"memo":null,"pitfall":null,"seed":123}""",
                ),
            )
        }
    }

    @Test
    fun seedStaysHonestNullBecauseReplayUsesTicket() {
        val pick = pick(fourMemos(), 0)
        val line = PickEventRecord.of(pick).encodeLine()
        assertTrue(line.contains("\"seed\":null"))
        assertTrue(line.contains("\"ticket\":0"))
    }
}
