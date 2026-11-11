package com.knte.core.reference

import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceWeightTest {

    private fun memoAt(d: Long?, done: Boolean = false, archived: Boolean = false): ReferenceEntry.Memo =
        memoEntry("m-0000000a", due = d?.let { TEST_TODAY.plusDays(it).toString() }, done = done, archived = archived)

    @Test
    fun boundaryDaysFallIntoShorterRemainingBucket() {
        // 契约 7.2 边界验证用例：取大不取小
        val expected = mapOf(
            3L to (MemoTier.DUE_0_3 to 42),
            7L to (MemoTier.DUE_3_7 to 38),
            14L to (MemoTier.DUE_7_14 to 34),
            30L to (MemoTier.DUE_14_30 to 30),
            60L to (MemoTier.DUE_30_60 to 26),
            90L to (MemoTier.DUE_60_90 to 20),
        )
        expected.forEach { (d, tier) ->
            val entry = memoAt(d)
            assertEquals(tier.first, ReferenceWeight.memoTier(entry, TEST_TODAY))
            assertEquals(tier.second, ReferenceWeight.units(entry, TEST_TODAY))
        }
        assertEquals("2.1", ReferenceWeight.decimal(ReferenceWeight.units(memoAt(3), TEST_TODAY)))
        assertEquals("1.9", ReferenceWeight.decimal(ReferenceWeight.units(memoAt(7), TEST_TODAY)))
        assertEquals("1.7", ReferenceWeight.decimal(ReferenceWeight.units(memoAt(14), TEST_TODAY)))
        assertEquals("1.5", ReferenceWeight.decimal(ReferenceWeight.units(memoAt(30), TEST_TODAY)))
        assertEquals("1.3", ReferenceWeight.decimal(ReferenceWeight.units(memoAt(60), TEST_TODAY)))
        assertEquals("1", ReferenceWeight.decimal(ReferenceWeight.units(memoAt(90), TEST_TODAY)))
    }

    @Test
    fun adjacentDaysFallIntoLongerRemainingBucket() {
        val expected = mapOf(
            2L to MemoTier.DUE_0_3, 4L to MemoTier.DUE_3_7, 6L to MemoTier.DUE_3_7,
            8L to MemoTier.DUE_7_14, 13L to MemoTier.DUE_7_14, 15L to MemoTier.DUE_14_30,
            29L to MemoTier.DUE_14_30, 31L to MemoTier.DUE_30_60, 59L to MemoTier.DUE_30_60,
            61L to MemoTier.DUE_60_90, 89L to MemoTier.DUE_60_90, 91L to MemoTier.DUE_90_PLUS,
        )
        expected.forEach { (d, tier) ->
            assertEquals(tier, ReferenceWeight.memoTier(memoAt(d), TEST_TODAY))
        }
    }

    @Test
    fun expiredAndTodayShareTopWeight() {
        assertEquals(MemoTier.EXPIRED, ReferenceWeight.memoTier(memoAt(-1), TEST_TODAY))
        assertEquals(42, ReferenceWeight.units(memoAt(-1), TEST_TODAY))
        assertEquals("2.1", ReferenceWeight.decimal(ReferenceWeight.units(memoAt(-1), TEST_TODAY)))
        assertEquals(MemoTier.DUE_0_3, ReferenceWeight.memoTier(memoAt(0), TEST_TODAY))
        assertEquals(42, ReferenceWeight.units(memoAt(0), TEST_TODAY))
        assertEquals(MemoTier.EXPIRED, ReferenceWeight.memoTier(memoAt(-365), TEST_TODAY))
    }

    @Test
    fun noDueUsesFlatTier() {
        val entry = memoAt(null)
        assertEquals(MemoTier.NO_DUE, ReferenceWeight.memoTier(entry, TEST_TODAY))
        assertEquals(20, ReferenceWeight.units(entry, TEST_TODAY))
        assertEquals("1", ReferenceWeight.decimal(20))
    }

    @Test
    fun doneOrArchivedIsJudgedFirst() {
        for (d in listOf(0L, 30L, 3650L)) {
            assertEquals(MemoTier.ARCHIVED, ReferenceWeight.memoTier(memoAt(d, done = true), TEST_TODAY))
            assertEquals(MemoTier.ARCHIVED, ReferenceWeight.memoTier(memoAt(d, archived = true), TEST_TODAY))
            assertEquals(4, ReferenceWeight.units(memoAt(d, done = true), TEST_TODAY))
        }
        assertEquals("0.2", ReferenceWeight.decimal(ReferenceWeight.units(memoAt(0, archived = true), TEST_TODAY)))
        assertEquals("memo_archived", ReferenceWeight.reason(memoAt(0, done = true), TEST_TODAY))
    }

    @Test
    fun pitfallIndependentPoolWeight() {
        assertEquals(20, ReferenceWeight.pitfallUnits(pitfallEntry("p-0000000a", count = 1)))
        assertEquals(30, ReferenceWeight.pitfallUnits(pitfallEntry("p-0000000a", count = 2)))
        assertEquals(40, ReferenceWeight.pitfallUnits(pitfallEntry("p-0000000a", count = 3)))
        assertEquals(110, ReferenceWeight.pitfallUnits(pitfallEntry("p-0000000a", count = 10)))
        assertEquals("2", ReferenceWeight.decimal(40))
        assertEquals("1.5", ReferenceWeight.decimal(30))
        assertEquals("pitfall_occasions", ReferenceWeight.reason(pitfallEntry("p-0000000a", count = 3), TEST_TODAY))
    }

    @Test
    fun pitfallArchivedStaysAtPointTwo() {
        val entry = pitfallEntry("p-0000000a", count = 7, archived = true)
        assertEquals(4, ReferenceWeight.pitfallUnits(entry))
        assertEquals("0.2", ReferenceWeight.decimal(ReferenceWeight.pitfallUnits(entry)))
        assertEquals("pitfall_archived", ReferenceWeight.reason(entry, TEST_TODAY))
    }

    @Test
    fun largeCountReachesCapWithoutOverflow() {
        assertEquals(1000010, ReferenceWeight.pitfallUnits(pitfallEntry("p-0000000a", count = 100000)))
        assertEquals("50000.5", ReferenceWeight.decimal(1000010))
        fails { pitfallEntry("p-0000000a", count = 100001) }
        fails { pitfallEntry("p-0000000a", count = 0) }
        fails { pitfallEntry("p-0000000a", count = -3) }
    }

    @Test
    fun farDistancesClampToOuterTiers() {
        assertEquals(MemoTier.DUE_90_PLUS, ReferenceWeight.memoTier(memoAt(3650), TEST_TODAY))
        assertEquals("0.7", ReferenceWeight.decimal(ReferenceWeight.units(memoAt(3650), TEST_TODAY)))
        assertEquals(MemoTier.DUE_90_PLUS, ReferenceWeight.memoTier(memoEntry("m-0000000a", due = "9999-12-31"), TEST_TODAY))
        assertEquals(MemoTier.EXPIRED, ReferenceWeight.memoTier(memoEntry("m-0000000a", due = "0001-01-01"), TEST_TODAY))
    }

    @Test
    fun decimalStripsTrailingZerosLikeLegacy() {
        assertEquals("1", ReferenceWeight.decimal(20))
        assertEquals("2.1", ReferenceWeight.decimal(42))
        assertEquals("1.9", ReferenceWeight.decimal(38))
        assertEquals("1.7", ReferenceWeight.decimal(34))
        assertEquals("1.5", ReferenceWeight.decimal(30))
        assertEquals("1.3", ReferenceWeight.decimal(26))
        assertEquals("0.7", ReferenceWeight.decimal(14))
        assertEquals("0.2", ReferenceWeight.decimal(4))
        assertEquals("0.05", ReferenceWeight.decimal(1))
    }

    @Test
    fun entryValidationFailsClosed() {
        fails { memoEntry("x-00000001") }
        fails { memoEntry("m-1234567") }
        fails { memoEntry("m-0000000A") }
        fails { memoEntry("m-00000001", title = "") }
        fails { memoEntry("m-00000001", title = "a\u0000b") }
        fails { memoEntry("m-00000001", due = "2026-02-30") }
        fails { memoEntry("m-00000001", due = "2026-11-1") }
        fails { memoEntry("m-00000001", createdTs = "2026-10-28 09:00:00") }
        fails { memoEntry("m-00000001", ts = "2026-10-28T24:00:00") }
        fails {
            ReferenceEntry.Memo("m-00000001", "t", "2026-10-28T09:00:00", "2026-10-28T09:00:00", tags = (1..21).map { "tag$it" })
        }
        fails {
            ReferenceEntry.Memo("m-00000001", "t", "2026-10-28T09:00:00", "2026-10-28T09:00:00", tags = listOf("x".repeat(41)))
        }
        fails { pitfallEntry("m-00000001", count = 1) }
        fails {
            ReferenceEntry.Pitfall("p-00000001", "t", 1, "2026-10-28T09:00:00", "2026-10-28T09:00:00", world = "x".repeat(81))
        }
    }

    @Test
    fun entryBoundariesAccepted() {
        memoEntry("m-00000001", world = "x".repeat(80))
        ReferenceEntry.Memo("m-00000001", "t", "2026-10-28T09:00:00", "2026-10-28T09:00:00", tags = (1..20).map { "tag$it" })
        ReferenceEntry.Memo("m-00000001", "t", "2026-10-28T09:00:00", "2026-10-28T09:00:00", body = "x".repeat(6000))
        pitfallEntry("p-00000001", count = 100000)
        fails { ReferenceEntry.Memo("m-00000001", "t", "2026-10-28T09:00:00", "2026-10-28T09:00:00", body = "x".repeat(6001)) }
        fails { memoEntry("m-00000001", world = "x".repeat(81)) }
        fails { ReferenceEntry.Memo("m-00000001", "x".repeat(201), "2026-10-28T09:00:00", "2026-10-28T09:00:00") }
    }
}
