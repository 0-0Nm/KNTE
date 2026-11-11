package com.knte.core.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceListingTest {
    private val all = ReferenceScope.all()

    @Test
    fun listsSortByCreatedThenIdAndFilter() {
        val entries = listOf(
            memoEntry("m-00000002", createdTs = "2026-10-29T09:00:00"),
            memoEntry("m-00000001", createdTs = "2026-10-29T09:00:00"),
            memoEntry("m-00000003", createdTs = "2026-10-28T09:00:00", done = true),
            pitfallEntry("p-00000009", count = 3),
        )
        val rows = ReferenceList.list(entries, all, TEST_TODAY)
        assertEquals(listOf("m-00000003", "p-00000009", "m-00000001", "m-00000002"), rows.map { it.entry.id })
        assertEquals(
            listOf("m-00000001", "m-00000002"),
            ReferenceList.list(entries, all, TEST_TODAY, pool = ReferencePool.MEMO, archived = false).map { it.entry.id },
        )
        assertEquals(
            listOf("m-00000003"),
            ReferenceList.list(entries, all, TEST_TODAY, archived = true).map { it.entry.id },
        )
        assertEquals(
            listOf("p-00000009"),
            ReferenceList.list(entries, all, TEST_TODAY, pool = ReferencePool.PITFALL).map { it.entry.id },
        )
    }

    @Test
    fun rowsCarryWeightReasonRecentAndUsage() {
        val entries = listOf(
            memoEntry("m-00000001", due = TEST_TODAY.toString()),
            memoEntry("m-00000002", archived = true),
            pitfallEntry("p-00000001", count = 3),
        )
        val rows = ReferenceList.list(entries, all, TEST_TODAY, recent = mapOf(ReferencePool.MEMO to listOf("m-00000001")))
        val row1 = rows.first { it.entry.id == "m-00000001" }
        assertEquals(42, row1.baseUnits)
        assertEquals("2.1", row1.weight)
        assertEquals("memo_due_0_3", row1.reason)
        assertTrue(row1.inRecent)
        val row2 = rows.first { it.entry.id == "m-00000002" }
        assertEquals("0.2", row2.weight)
        assertEquals("memo_archived", row2.reason)
        assertFalse(row2.inRecent)
        val row3 = rows.first { it.entry.id == "p-00000001" }
        assertEquals(40, row3.baseUnits)
        assertEquals("2", row3.weight)
        assertEquals("pitfall_occasions", row3.reason)
        assertEquals(ReferencePool.PITFALL, row3.pool)
    }

    @Test
    fun usageUnknownWithoutHistoryNeverZero() {
        val rows = ReferenceList.list(fourMemos(), all, TEST_TODAY, usage = null)
        rows.forEach { assertNull(it.usage) } // 无历史 → 未知，不伪造 0
    }

    @Test
    fun usageZeroOnlyFromCompleteHistory() {
        val usage = ReferenceUsage.of(
            listOf(KnownPick(ReferencePool.MEMO, "m-00000002"), KnownPick(ReferencePool.MEMO, "m-00000002")),
            complete = true,
        )
        val rows = ReferenceList.list(fourMemos(), all, TEST_TODAY, pool = ReferencePool.MEMO, usage = usage)
        assertEquals(2, rows.first { it.entry.id == "m-00000002" }.usage)
        assertEquals(0, rows.first { it.entry.id == "m-00000001" }.usage) // 完整历史下从未中选才是真 0
    }

    @Test
    fun partialHistoryKeepsUnpickedUnknown() {
        val usage = ReferenceUsage.of(listOf(KnownPick(ReferencePool.MEMO, "m-00000002")))
        assertEquals(1, usage.usage(ReferencePool.MEMO, "m-00000002"))
        assertNull(usage.usage(ReferencePool.MEMO, "m-00000001"))
        assertNull(usage.usage(ReferencePool.PITFALL, "p-00000001"))
        val rows = ReferenceList.list(fourMemos(), all, TEST_TODAY, pool = ReferencePool.MEMO, usage = usage)
        assertEquals(1, rows.first { it.entry.id == "m-00000002" }.usage)
        assertNull(rows.first { it.entry.id == "m-00000001" }.usage)
    }

    @Test
    fun absentHistoryObjectReportsUnknown() {
        assertNull(ReferenceUsage.of(null).usage(ReferencePool.MEMO, "m-00000001"))
        assertFalse(ReferenceUsage.of(null).hasHistory)
        assertFalse(ReferenceUsage.of(null).complete)
    }
}
