package com.knte.core.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceSelectTest {
    private val all = ReferenceScope.all()

    private fun pickMemo(entries: List<ReferenceEntry>, ticket: Long, recent: List<String> = emptyList()): Choice =
        ReferenceSelect.draw(entries, ReferencePool.MEMO, all, TEST_TODAY, recent, ScriptedRandom(longArrayOf(ticket)))!!

    @Test
    fun weightedDrawMapsTicketRangesInIdOrder() {
        val choice = pickMemo(fourMemos(), 0)
        assertEquals("m-00000001", choice.entry.id)
        assertEquals("m-00000001", pickMemo(fourMemos(), 41).entry.id)
        assertEquals("m-00000002", pickMemo(fourMemos(), 42).entry.id)
        assertEquals("m-00000003", pickMemo(fourMemos(), 91).entry.id)
        assertEquals("m-00000004", pickMemo(fourMemos(), 105).entry.id)
        assertFalse(choice.snapshot.uniform)
        assertEquals(4, choice.snapshot.poolSize)
        assertEquals(106L, choice.snapshot.totalUnits)
        assertEquals(42, choice.candidate.baseUnits)
        assertEquals(42, choice.candidate.effectiveUnits)
        assertEquals("memo_due_0_3", choice.candidate.reason)
        assertEquals(0L, choice.ticket)
    }

    @Test
    fun recentQueueHalvesWeightButNeverExcludes() {
        val recent = listOf("m-00000001", "m-00000002", "m-00000003")
        // effective: id1 21 [0,21), id2 15 [21,36), id3 10 [36,46), id4 14 [46,60)
        val stillPicksRecent = pickMemo(fourMemos(), 20, recent)
        assertEquals("m-00000001", stillPicksRecent.entry.id)
        assertEquals(21, stillPicksRecent.candidate.effectiveUnits)
        assertEquals(42, stillPicksRecent.candidate.baseUnits)
        assertTrue(stillPicksRecent.candidate.recent)
        assertEquals("m-00000002", pickMemo(fourMemos(), 35, recent).entry.id)
        assertEquals("m-00000004", pickMemo(fourMemos(), 59, recent).entry.id)
        assertFalse(pickMemo(fourMemos(), 59, recent).candidate.recent)
        assertEquals(60L, pickMemo(fourMemos(), 0, recent).snapshot.totalUnits)
    }

    @Test
    fun smallPoolDegradesToUniformWithoutPenalty() {
        val small = listOf(
            memoEntry("m-00000001", due = TEST_TODAY.toString()),
            memoEntry("m-00000002", archived = true),
            memoEntry("m-00000003", due = TEST_TODAY.plusDays(3650).toString()),
        )
        val recent = listOf("m-00000001", "m-00000002", "m-00000003") // 全员在队也不降权
        for (ticket in 0..2) {
            val choice = pickMemo(small, ticket.toLong(), recent)
            assertTrue(choice.snapshot.uniform)
            assertEquals(3, choice.snapshot.poolSize)
            assertEquals(3L, choice.snapshot.totalUnits)
            assertEquals(1, choice.candidate.effectiveUnits)
            assertEquals(small[ticket].id, choice.entry.id)
        }
        fails { pickMemo(small, 3, recent) } // ticket >= total
        assertEquals("m-00000002", pickMemo(small, 1, recent).entry.id)
    }

    @Test
    fun poolOfExactlyFourStaysWeighted() {
        assertFalse(pickMemo(fourMemos(), 0).snapshot.uniform)
    }

    @Test
    fun emptyPoolAndEmptyScopeDrawNull() {
        assertNull(ReferenceSelect.draw(emptyList(), ReferencePool.MEMO, all, TEST_TODAY, emptyList(), ScriptedRandom(longArrayOf(0))))
        assertNull(
            ReferenceSelect.draw(
                fourMemos(), ReferencePool.MEMO, ReferenceScope.world("归墟", listOf("归墟")),
                TEST_TODAY, emptyList(), ScriptedRandom(longArrayOf(0)),
            ),
        )
        assertNull(ReferenceSelect.draw(fourPitfalls(), ReferencePool.MEMO, all, TEST_TODAY, emptyList(), ScriptedRandom(longArrayOf(0))))
    }

    @Test
    fun queueAdvancesAndDropsOldestBeyondThree() {
        assertEquals(listOf("a", "b", "c"), RecentQueue.advance(listOf("a", "b"), "c"))
        assertEquals(listOf("b", "c", "d"), RecentQueue.advance(listOf("a", "b", "c"), "d"))
        assertEquals(listOf("a", "b"), RecentQueue.advance(listOf("a", "b"), null))
        assertEquals(listOf("a", "b", "a"), RecentQueue.advance(listOf("a", "b"), "a"))
        val original = mutableListOf("x", "y")
        val advanced = RecentQueue.advance(original, "z")
        assertEquals(listOf("x", "y"), original)
        assertEquals(listOf("x", "y", "z"), advanced)
        fails { (advanced as MutableList<String>).add("w") }
    }

    @Test
    fun uniformDistributionOverHundredDraws() {
        // 契约 V5：池 ≤ N 纯随机，抽 100 次分布均匀（确定性票序）
        val small = listOf(
            memoEntry("m-00000001", due = TEST_TODAY.toString()),
            memoEntry("m-00000002"),
            memoEntry("m-00000003", due = TEST_TODAY.plusDays(3650).toString()),
        )
        val counts = mutableMapOf<String, Int>()
        val random = ScriptedRandom(longArrayOf(0, 1, 2))
        repeat(100) {
            val choice = ReferenceSelect.draw(small, ReferencePool.MEMO, all, TEST_TODAY, emptyList(), random)!!
            counts[choice.entry.id] = (counts[choice.entry.id] ?: 0) + 1
        }
        assertEquals(34, counts["m-00000001"])
        assertEquals(33, counts["m-00000002"])
        assertEquals(33, counts["m-00000003"])
    }

    @Test
    fun weightedSweepMatchesWeightProportions() {
        // 契约 V5：池 > N 加权轮盘，全覆盖票面命中数与权重成比例
        val tickets = (0 until 106).map { it.toLong() }.toLongArray()
        val random = ScriptedRandom(tickets)
        val counts = mutableMapOf<String, Int>()
        repeat(106) {
            val choice = ReferenceSelect.draw(fourMemos(), ReferencePool.MEMO, all, TEST_TODAY, emptyList(), random)!!
            counts[choice.entry.id] = (counts[choice.entry.id] ?: 0) + 1
        }
        assertEquals(42, counts["m-00000001"])
        assertEquals(30, counts["m-00000002"])
        assertEquals(20, counts["m-00000003"])
        assertEquals(14, counts["m-00000004"])
    }

    @Test
    fun inputsNeverMutatedAndSnapshotFrozen() {
        val source = mutableListOf(
            memoEntry("m-00000004", due = TEST_TODAY.plusDays(3650).toString()),
            memoEntry("m-00000001", due = TEST_TODAY.toString()),
            memoEntry("m-00000003", due = TEST_TODAY.plusDays(90).toString()),
        )
        val originalIds = source.map { it.id }
        val choice = pickMemo(source, 1)
        assertEquals(originalIds, source.map { it.id })
        assertEquals(3, choice.snapshot.poolSize)
        source.removeAt(0)
        // 快照冻结：池随后变化不影响已取快照（weight_snapshot 不可变）
        assertEquals(3, choice.snapshot.poolSize)
        assertEquals(3, choice.snapshot.candidates.size)
        fails { (choice.snapshot.candidates as MutableList<WeightCandidate>).add(WeightCandidate("m-00000009", 1, 1, false, "x")) }
        assertEquals(2, source.size)
    }

    @Test
    fun deterministicAcrossInputOrder() {
        val a = listOf(
            memoEntry("m-00000001", due = TEST_TODAY.toString()),
            memoEntry("m-00000002", due = TEST_TODAY.plusDays(30).toString()),
            memoEntry("m-00000003", due = TEST_TODAY.plusDays(90).toString()),
            memoEntry("m-00000004", due = TEST_TODAY.plusDays(3650).toString()),
        )
        val b = listOf(a[3], a[1], a[0], a[2])
        assertEquals(pickMemo(a, 60).entry.id, pickMemo(b, 60).entry.id)
        assertEquals("m-00000002", pickMemo(a, 60).entry.id)
    }
}
