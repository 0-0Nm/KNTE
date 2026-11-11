package com.knte.core.accounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AccountingSnapshotTest {
    private fun row(
        id: String,
        ts: String,
        amount: String,
        date: String = "2026-10-28",
        category: String = "餐饮",
        note: String? = null,
        location: String? = null,
        rawInput: String = "午饭25块",
        source: String = "manual",
        correctedOf: String? = null,
        voidedBy: String? = null,
        atTrust: String? = "unverified",
        extras: List<Pair<String, Any?>> = emptyList(),
    ): String {
        val pairs = mutableListOf<Pair<String, Any?>>(
            "id" to id, "date" to date, "amount" to raw(amount), "category" to category,
        )
        if (location != null) pairs.add("location" to location)
        if (note != null) pairs.add("note" to note)
        pairs.add("source" to source)
        pairs.add("raw_input" to rawInput)
        pairs.add("ts" to ts)
        if (atTrust != null) pairs.add("at_trust" to atTrust)
        if (correctedOf != null) pairs.add("corrected_of" to correctedOf)
        if (voidedBy != null) pairs.add("voided_by" to voidedBy)
        pairs.addAll(extras)
        return jsonLine(*pairs.toTypedArray())
    }

    @Test fun legacyRowDecodedUnknownKeysPassedThrough() {
        val line = row(
            "r-00000001", "2026-10-28T12:31:04", "-25.00",
            note = "午饭", location = "学校食堂",
            extras = listOf("ai_proposal_token" to "p1", "foo" to raw("{\"nested\":[1,2]}")),
        )
        val bytes = fixture(line)
        val snapshot = AccountingSnapshot.parse(bytes)
        assertEquals(1, snapshot.rows.size)
        val first = snapshot.rows[0]
        assertTrue(first.valid)
        assertTrue(first.active)
        assertEquals(AccountingKind.ORIGINAL, first.kind)
        assertEquals("r-00000001", first.id)
        assertEquals(-2500L, first.cents)
        assertEquals("ts:2026-10-28T12:31:04", first.viewKey)
        assertEquals("unverified", first.atTrust)
        assertFalse(first.legacyClockMetadataAbsent)
        val fields = first.fields()
        assertEquals("p1", fields["ai_proposal_token"])
        val foo = fields["foo"]
        assertTrue(foo is LinkedHashMap<*, *> && foo.size == 1)
        assertEquals(line + "\n", String(first.rawBytes(), Charsets.UTF_8))
        assertEquals(AccountingCodec.hash(bytes), snapshot.revision)
    }

    @Test fun missingAtTrustFlaggedButStillCounted() {
        val snapshot = AccountingSnapshot.parse(
            fixture(row("r-00000001", "2026-10-28T12:31:04", "-25.00", atTrust = null))
        )
        val first = snapshot.rows[0]
        assertTrue(first.active)
        assertTrue(first.legacyClockMetadataAbsent)
        assertTrue(snapshot.findings.any { it.code == "AT_TRUST_ABSENT" && it.lineNumber == 1 })
        assertEquals(-2500L, snapshot.net)
    }

    @Test fun illegalAmountsExcludedWithExplicitFindings() {
        val lines = listOf(
            row("r-00000001", "2026-10-28T12:31:01", "-25.00"),
            "{\"id\":\"r-00000002\",\"date\":\"2026-10-28\",\"amount\":\"25\",\"category\":\"餐饮\",\"source\":\"manual\",\"raw_input\":\"x\",\"ts\":\"2026-10-28T12:31:02\",\"at_trust\":\"unverified\"}",
            row("r-00000003", "2026-10-28T12:31:03", "0"),
            row("r-00000004", "2026-10-28T12:31:04", "25.005"),
            "{\"id\":\"r-00000005\",\"date\":\"2026-10-28\",\"category\":\"餐饮\",\"source\":\"manual\",\"raw_input\":\"x\",\"ts\":\"2026-10-28T12:31:05\",\"at_trust\":\"unverified\"}",
        )
        val snapshot = AccountingSnapshot.parse(fixture(*lines.toTypedArray()))
        assertEquals(5, snapshot.rows.size)
        assertEquals(1, snapshot.effectiveRows.size)
        assertTrue(snapshot.rows[1].issues.any { it.code == "AMOUNT_INVALID" })
        assertTrue(snapshot.rows[2].issues.any { it.code == "AMOUNT_INVALID" })
        assertTrue(snapshot.rows[3].issues.any { it.code == "AMOUNT_INVALID" })
        assertTrue(snapshot.rows[4].issues.any { it.code == "AMOUNT_MISSING" })
        assertTrue(snapshot.findings.any { it.lineNumber == 2 && it.code == "AMOUNT_INVALID" })
        assertEquals(-2500L, snapshot.net)
    }

    @Test fun outOfEnumCategoryAndMissingRawInputOnlyWarn() {
        val snapshot = AccountingSnapshot.parse(
            fixture(
                row("r-00000001", "2026-10-28T12:31:01", "-25.00", category = "打赏", rawInput = "打赏"),
                "{\"id\":\"r-00000002\",\"date\":\"2026-10-28\",\"amount\":-10.00,\"category\":\"餐饮\",\"source\":\"manual\",\"ts\":\"2026-10-28T12:31:02\",\"at_trust\":\"unverified\"}",
            )
        )
        assertEquals(2, snapshot.effectiveRows.size)
        assertTrue(snapshot.findings.any { it.code == "CATEGORY_OUT_OF_ENUM" && it.lineNumber == 1 })
        assertTrue(snapshot.findings.any { it.code == "RAW_INPUT_MISSING" && it.lineNumber == 2 })
        assertEquals(-3500L, snapshot.net)
    }

    @Test fun legacyStateMarkersRefusedForAggregation() {
        val parsed = AccountingSnapshot.parse(
            fixture(
                row("r-00000001", "2026-10-28T12:31:01", "-25.00", extras = listOf("archived" to true)),
                row("r-00000002", "2026-10-28T12:31:02", "-10.00", extras = listOf("deleted" to false)),
            )
        )
        assertEquals(2, parsed.rows.size)
        assertFalse(parsed.rows[0].valid)
        assertTrue(parsed.rows[0].issues.any { it.code == "LEGACY_STATE_MARKER" })
        assertTrue(parsed.rows[1].issues.any { it.code == "LEGACY_STATE_MARKER" })
        assertEquals(0, parsed.effectiveRows.size)
        assertEquals(0L, parsed.net)
    }

    @Test fun duplicateIdAndTimestampBothInvalid() {
        val snapshot = AccountingSnapshot.parse(
            fixture(
                row("r-00000001", "2026-10-28T12:31:01", "-25.00"),
                row("r-00000001", "2026-10-28T12:31:02", "-10.00"),
                row("r-00000002", "2026-10-28T12:31:02", "-5.00"),
                row("r-00000003", "2026-10-28T12:31:03", "-1.00"),
            )
        )
        assertEquals(4, snapshot.rows.size)
        assertTrue(snapshot.rows[0].issues.any { it.code == "DUPLICATE_ID" })
        assertTrue(snapshot.rows[1].issues.any { it.code == "DUPLICATE_ID" })
        assertTrue(snapshot.rows[1].issues.any { it.code == "DUPLICATE_TIMESTAMP" })
        assertTrue(snapshot.rows[2].issues.any { it.code == "DUPLICATE_TIMESTAMP" })
        assertEquals(1, snapshot.effectiveRows.size)
        assertEquals(-100L, snapshot.net)
        assertNull(snapshot.byTimestamp("2026-10-28T12:31:02"))
        assertNull(snapshot.byId("r-00000001"))
        assertEquals("r-00000003", snapshot.byId("r-00000003")?.id)
    }

    @Test fun correctionReplacesPredecessorInEffectiveView() {
        val snapshot = AccountingSnapshot.parse(
            fixture(
                row("r-00000001", "2026-10-28T12:31:01", "-100.00", rawInput = "打车100"),
                row("r-00000002", "2026-10-28T12:32:00", "-35.00", note = "改金额",
                    correctedOf = "2026-10-28T12:31:01", rawInput = "打车100"),
            )
        )
        val original = snapshot.rows[0]
        val correction = snapshot.rows[1]
        assertEquals(AccountingKind.ORIGINAL, original.kind)
        assertEquals(AccountingKind.CORRECTION, correction.kind)
        assertFalse(original.active)
        assertTrue(correction.active)
        assertEquals("2026-10-28T12:32:00", original.voidedBy)
        assertEquals(1, snapshot.effectiveRows.size)
        assertEquals(-3500L, snapshot.net)
        assertNotNull(snapshot.byTimestamp("2026-10-28T12:31:01"))
    }

    @Test fun reversalCancelsBothSides() {
        val snapshot = AccountingSnapshot.parse(
            fixture(
                row("r-00000001", "2026-10-28T12:31:01", "-100.00", note = "打车",
                    location = "出租车", rawInput = "打车花了100"),
                row("r-00000002", "2026-10-28T13:00:00", "100.00", note = "冲销",
                    location = "出租车", rawInput = "打车花了100",
                    correctedOf = "2026-10-28T12:31:01"),
            )
        )
        val original = snapshot.rows[0]
        val reversal = snapshot.rows[1]
        assertEquals(AccountingKind.REVERSAL, reversal.kind)
        assertFalse(original.active)
        assertFalse(reversal.active)
        assertEquals("2026-10-28T13:00:00", original.voidedBy)
        assertEquals(0, snapshot.effectiveRows.size)
        assertEquals(0L, snapshot.net)
        assertEquals(0L, snapshot.income)
        assertEquals(0L, snapshot.expense)
    }

    @Test fun mismatchedReversalInvalidAndOriginalStaysActive() {
        val snapshot = AccountingSnapshot.parse(
            fixture(
                row("r-00000001", "2026-10-28T12:31:01", "-100.00", rawInput = "打车100"),
                row("r-00000002", "2026-10-28T13:00:00", "100.00", note = "冲销",
                    category = "交通", rawInput = "打车100",
                    correctedOf = "2026-10-28T12:31:01"),
            )
        )
        val reversal = snapshot.rows[1]
        assertFalse(reversal.valid)
        assertTrue(reversal.issues.any { it.code == "REVERSAL_MISMATCH" })
        assertTrue(snapshot.rows[0].active)
        assertEquals(1, snapshot.effectiveRows.size)
        assertEquals(-10000L, snapshot.net)
    }

    @Test fun branchedAndForwardChainsFailClosed() {
        val forward = AccountingSnapshot.parse(
            fixture(
                row("r-00000002", "2026-10-28T12:32:00", "-35.00", note = "改",
                    correctedOf = "2026-10-28T12:31:01", rawInput = "打车100"),
                row("r-00000001", "2026-10-28T12:31:01", "-100.00", rawInput = "打车100"),
            )
        )
        assertTrue(forward.rows[0].issues.any { it.code == "CHAIN_FORWARD_REFERENCE" })
        assertTrue(forward.rows[1].active)
        val branched = AccountingSnapshot.parse(
            fixture(
                row("r-00000001", "2026-10-28T12:31:01", "-100.00", rawInput = "打车100"),
                row("r-00000002", "2026-10-28T12:32:00", "-35.00", note = "改一",
                    correctedOf = "2026-10-28T12:31:01", rawInput = "打车100"),
                row("r-00000003", "2026-10-28T12:33:00", "-40.00", note = "改二",
                    correctedOf = "2026-10-28T12:31:01", rawInput = "打车100"),
            )
        )
        assertTrue(branched.rows[1].valid)
        assertTrue(branched.rows[2].issues.any { it.code == "CHAIN_BRANCHED" })
        assertEquals(-3500L, branched.net)
        val dangling = AccountingSnapshot.parse(
            fixture(
                row("r-00000001", "2026-10-28T12:31:01", "-100.00", rawInput = "打车100",
                    voidedBy = "2026-10-28T23:59:59"),
            )
        )
        assertTrue(dangling.rows[0].issues.any { it.code == "VOIDED_BY_MISMATCH" })
        assertEquals(0, dangling.effectiveRows.size)
        val missing = AccountingSnapshot.parse(
            fixture(row("r-00000002", "2026-10-28T12:32:00", "-35.00", note = "改",
                correctedOf = "2026-10-28T12:31:01", rawInput = "打车100"))
        )
        assertTrue(missing.rows[0].issues.any { it.code == "CHAIN_TARGET_MISSING" })
    }

    @Test fun unterminatedTailBlankAndOverlongLines() {
        val line1 = row("r-00000001", "2026-10-28T12:31:01", "-25.00")
        val line2 = row("r-00000002", "2026-10-28T12:31:02", "-10.00")
        val snapshot = AccountingSnapshot.parse(AccountingCodec.encode(line1 + "\n" + line2))
        assertTrue(snapshot.unterminatedTail)
        assertEquals(2, snapshot.rows.size)
        assertTrue(snapshot.rows[1].issues.any { it.code == "UNTERMINATED_LAST_LINE" })
        assertFalse(snapshot.rows[1].valid)
        assertEquals(1, snapshot.effectiveRows.size)
        val blank = AccountingSnapshot.parse(fixture(line1, "", line2))
        assertEquals(2, blank.rows.size)
        assertTrue(blank.findings.any { it.code == "BLANK_LINE" && it.lineNumber == 2 })
        val overlong = "{\"pad\":\"" + "x".repeat(33000) + "\",\"id\":\"r-00000003\"}"
        val over = AccountingSnapshot.parse(fixture(line1, overlong))
        assertEquals(1, over.rows.size)
        assertTrue(over.findings.any { it.code == "LINE_OVERLONG" && it.lineNumber == 2 })
    }

    @Test fun emptyFileValidMalformedUtf8Refused() {
        val empty = AccountingSnapshot.parse(ByteArray(0))
        assertEquals(0, empty.rows.size)
        assertEquals(AccountingCodec.EMPTY_REVISION, empty.revision)
        assertEquals(0L, empty.net)
        fails { AccountingSnapshot.parse(byteArrayOf(0xC3.toByte(), 0x28)) }
    }

    @Test fun capacityFlaggedPastLegacyRowLimit() {
        val lines = (1..5001).map { i ->
            val id = "r-" + i.toString(16).padStart(8, '0')
            val ts = String.format(
                Locale.ROOT, "2026-10-28T%02d:%02d:%02d",
                i / 3600 % 24, i / 60 % 60, i % 60,
            )
            "{\"id\":\"$id\",\"date\":\"2026-10-28\",\"amount\":-1.00,\"category\":\"餐饮\"," +
                "\"source\":\"manual\",\"raw_input\":\"x\",\"ts\":\"$ts\",\"at_trust\":\"unverified\"}"
        }
        val snapshot = AccountingSnapshot.parse(fixture(*lines.toTypedArray()))
        assertEquals(5001, snapshot.rows.size)
        assertTrue(snapshot.overCapacity)
    }
}
