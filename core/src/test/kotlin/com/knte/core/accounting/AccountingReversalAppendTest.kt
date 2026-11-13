package com.knte.core.accounting

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Production-API tests for the app-private append-only reversal vertical slice.
 * Every case drives IsolatedAccountingLedger / AccountingSnapshot / AccountingReports;
 * no substitute business model is introduced. */
class AccountingReversalAppendTest {
    private fun newRoot(): Path = Files.createTempDirectory("knte-reversal-")
    private fun file(root: Path): Path = root.resolve("db/accounting.jsonl")
    private fun ledger(root: Path) = IsolatedAccountingLedger(root.toFile())
    private fun pending(root: Path): Path = root.resolve("db/accounting.jsonl.pending")
    private fun journal(root: Path): Path = root.resolve("db/.journal")

    private fun draft(
        id: String = "r-00000001",
        amountText: String = "100",
        income: Boolean = false,
        date: String = "2026-10-28",
        category: String = "交通",
        location: String = "出租车",
        note: String = "打车",
        rawInput: String = "打车花了100",
    ): AccountingDraft = AccountingDraft.prepare(id, date, amountText, income, category, location, note, rawInput)

    private fun writeManual(l: IsolatedAccountingLedger, d: AccountingDraft, ts: String): AccountingRow {
        val p = l.prepareManual(d, ts)
        return (l.confirm(p, p.confirmation) as AccountingConfirmResult.Confirmed).appended
    }

    private fun bytes(root: Path): ByteArray = Files.readAllBytes(file(root))

    /** Only reversal receipts; manual confirmations also write verified receipts. */
    private fun reversalReceipts(root: Path): List<String> =
        receipts(root).filter { it.contains("entry=reversal") }

    private fun receipts(root: Path): List<String> {
        if (!Files.exists(journal(root))) return emptyList()
        val out = mutableListOf<String>()
        Files.walk(journal(root)).use { s ->
            s.filter { it.fileName.toString() == "receipt.txt" }
                .forEach { out += String(Files.readAllBytes(it), StandardCharsets.UTF_8) }
        }
        return out
    }

    @Test fun normalReversalAppendsFrozenLineAndIndependentReadbackReportsConsistent() {
        val root = newRoot()
        val l = ledger(root)
        writeManual(l, draft(id = "r-00000001"), "2026-10-28T12:31:04")       // -10000 交通
        writeManual(l, draft(id = "r-00000002", amountText = "25", category = "餐饮",
            location = "学校食堂", note = "午饭", rawInput = "午饭25"), "2026-10-28T12:31:05") // -2500
        writeManual(l, draft(id = "r-00000003", amountText = "3000", income = true,
            category = "其他", location = "", note = "工资", rawInput = "工资3000"),
            "2026-10-28T12:31:06")                                             // +300000
        val baseline = l.load()
        assertEquals(287500L, baseline.net) // -10000 -2500 +300000
        val beforeBytes = bytes(root)
        val revTs = "2026-10-28T13:00:00"

        val plan = l.prepareReversal("r-00000001", revTs, "r-0abcdef0")
        assertEquals(AccountingEntryKind.REVERSAL, plan.entryKind)
        assertEquals(AccountingPlanState.PENDING, l.planState(plan))
        assertTrue(l.reversalWriteSupported())
        assertFalse(l.voidWriteSupported())
        assertNotNull(plan.reversal)
        val b = plan.reversal!!
        assertEquals("ts:2026-10-28T12:31:04", b.originalViewKey)
        assertEquals("r-00000001", b.originalId)
        assertEquals("2026-10-28T12:31:04", b.originalTs)
        assertEquals(-10000L, b.originalCents)
        assertEquals("2026-10-28", b.originalDate)
        assertEquals("交通", b.originalCategory)
        assertEquals("出租车", b.originalLocation)
        assertEquals("打车花了100", b.originalRawInput)
        assertEquals(baseline.revision, b.sourceRevision)
        assertEquals(287500L, b.baselineNet)
        assertEquals(3, b.baselineEffectiveRows)
        assertEquals(baseline.revision, plan.beforeRevision)
        assertTrue(plan.entryLine.contains("\"corrected_of\":\"2026-10-28T12:31:04\""))
        assertTrue(plan.entryLine.contains("\"amount\":100.00"))
        assertTrue(plan.entryLine.contains("\"note\":\"冲销\""))
        assertTrue(plan.entryLine.contains("\"source\":\"manual\""))
        assertTrue(plan.entryLine.endsWith("\"at_trust\":\"unverified\",\"corrected_of\":\"2026-10-28T12:31:04\"}"))
        assertFalse(Files.exists(file(root).resolveSibling("accounting.jsonl.pending")))

        val confirmed = l.confirm(plan, plan.confirmation) as AccountingConfirmResult.Confirmed
        assertNotNull(confirmed.reversal)
        val outcome = confirmed.reversal!!
        assertEquals("2026-10-28T12:31:04", outcome.originalTs)
        assertEquals("r-00000001", outcome.originalId)
        assertEquals(-10000L, outcome.originalCents)
        assertEquals(revTs, outcome.reversalTs)
        assertEquals(10000L, outcome.reversalCents)
        assertFalse(outcome.originalActiveAfter)
        assertFalse(outcome.reversalActiveAfter)
        assertEquals(287500L, outcome.netBefore)
        assertEquals(297500L, outcome.netAfter)   // 287500 - (-10000)
        assertEquals(297500L, outcome.reportNetAfter)
        assertEquals(plan.afterRevision, outcome.readbackRevision)
        assertTrue(outcome.receipt.contains("entry=reversal"))
        assertTrue(outcome.receipt.contains("net_before=287500"))
        assertTrue(outcome.receipt.contains("report_net=297500"))

        // append-only: exact prefix preserved, frozen line is the last line
        val after = bytes(root)
        assertArrayEquals(beforeBytes, after.copyOfRange(0, beforeBytes.size))
        assertEquals(plan.entryLine + "\n", String(after.copyOfRange(beforeBytes.size, after.size), StandardCharsets.UTF_8))

        // independent readback from a FRESH ledger instance
        val reopened = ledger(root).load()
        assertEquals(4, reopened.rows.size)
        assertEquals(2, reopened.effectiveRows.size)
        assertEquals(297500L, reopened.net)
        assertFalse(reopened.byTimestamp("2026-10-28T12:31:04")!!.active)
        assertEquals(revTs, reopened.byTimestamp("2026-10-28T12:31:04")!!.voidedBy)
        val rev = reopened.byTimestamp(revTs)!!
        assertEquals(AccountingKind.REVERSAL, rev.kind)
        assertFalse(rev.active)
        assertEquals("2026-10-28T12:31:04", rev.correctedOf)

        // reports consume the pair: excluded from rows / monthly / category
        val month = AccountingReports.month(reopened, "2026-10")
        assertEquals(2, month.rows.size)
        assertTrue(month.rows.none { it.ts == "2026-10-28T12:31:04" || it.ts == revTs })
        assertEquals(297500L, month.net)
        assertEquals(0, month.excludedInRange)
        assertEquals(1, AccountingReports.monthlyTotals(reopened).size)
        assertEquals(297500L, AccountingReports.monthlyTotals(reopened)[0].net)
        assertEquals(297500L, AccountingReports.categoryTotals(reopened).sumOf { it.net })

        val rs = reversalReceipts(root)
        assertEquals(1, rs.size)
        assertTrue(rs[0].startsWith("verified\ndb/accounting.jsonl\n${plan.afterRevision}\n"))
        assertTrue(rs[0].contains("original=2026-10-28T12:31:04"))
        assertTrue(rs[0].contains("reversal=$revTs"))
        assertTrue(rs[0].contains("readback_revision=${plan.afterRevision}"))
        assertEquals(AccountingPlanState.CONFIRMED, l.planState(plan))
        assertFalse(l.pendingReview())
        assertFalse(Files.exists(pending(root)))
    }

    @Test fun reversalOfOnlyEntryProducesZeroTotalsAndEmptyReports() {
        val root = newRoot()
        val l = ledger(root)
        writeManual(l, draft(), "2026-10-28T12:31:04")
        val plan = l.prepareReversal("r-00000001", "2026-10-28T13:00:00", "r-0abcdef1")
        val c = l.confirm(plan, plan.confirmation) as AccountingConfirmResult.Confirmed
        assertNotNull(c.reversal)
        val o = c.reversal!!
        assertEquals(0L, o.netAfter)
        assertEquals(0L, o.reportNetAfter)
        assertEquals(-10000L, o.netBefore)
        val rb = ledger(root).load()
        assertEquals(2, rb.rows.size)
        assertEquals(0, rb.effectiveRows.size)
        assertEquals(0L, rb.net)
        assertEquals(0L, rb.income)
        assertEquals(0L, rb.expense)
        assertTrue(AccountingReports.month(rb, "2026-10").rows.isEmpty())
        assertEquals(0L, AccountingReports.month(rb, "2026-10").net)
        assertTrue(AccountingReports.monthlyTotals(rb).isEmpty())
        assertTrue(AccountingReports.categoryTotals(rb).all { it.income == 0L && it.expense == 0L && it.net == 0L })
    }

    @Test fun correctionTargetCanBeReversedAndChainStaysAppendOnly() {
        val root = newRoot()
        val originalLine = jsonLine(
            "id" to "r-00000001", "date" to "2026-10-28", "amount" to raw("-100.00"),
            "category" to "交通", "location" to "出租车", "note" to "打车",
            "source" to "manual", "raw_input" to "打车花了100",
            "ts" to "2026-10-28T12:31:04", "at_trust" to "unverified",
        )
        val correctionLine = jsonLine(
            "id" to "r-00000002", "date" to "2026-10-28", "amount" to raw("-35.00"),
            "category" to "交通", "location" to "出租车", "note" to "改金额",
            "source" to "manual", "raw_input" to "打车花了100",
            "ts" to "2026-10-28T12:32:00", "at_trust" to "unverified",
            "corrected_of" to "2026-10-28T12:31:04",
        )
        Files.createDirectories(root.resolve("db"))
        Files.write(file(root), fixture(originalLine, correctionLine))
        val l = ledger(root)
        val baseline = l.load()
        assertEquals(1, baseline.effectiveRows.size)
        assertEquals(-3500L, baseline.net)
        val before = bytes(root)
        val revTs = "2026-10-28T13:00:00"

        val plan = l.prepareReversal("ts:2026-10-28T12:32:00", revTs, "r-0abcdef2")
        assertEquals(-3500L, plan.reversal!!.originalCents)
        assertEquals("2026-10-28T12:32:00", plan.reversal!!.originalTs)
        assertTrue(plan.entryLine.contains("\"corrected_of\":\"2026-10-28T12:32:00\""))
        assertTrue(plan.entryLine.contains("\"amount\":35.00"))
        val c = l.confirm(plan, plan.confirmation) as AccountingConfirmResult.Confirmed
        assertNotNull(c.reversal)
        assertEquals(0L, c.reversal!!.netAfter)
        val rb = ledger(root).load()
        assertEquals(3, rb.rows.size)
        assertEquals(0, rb.effectiveRows.size)
        assertEquals(0L, rb.net)
        assertFalse(rb.byTimestamp("2026-10-28T12:32:00")!!.active)
        assertEquals(revTs, rb.byTimestamp("2026-10-28T12:32:00")!!.voidedBy)
        val after = bytes(root)
        assertArrayEquals(before, after.copyOfRange(0, before.size))
        assertEquals(1, receipts(root).size)
    }

    @Test fun cancelDoesNotCommitAndTargetRemainsReversible() {
        val root = newRoot()
        val l = ledger(root)
        writeManual(l, draft(), "2026-10-28T12:31:04")
        val before = bytes(root)
        val plan = l.prepareReversal("r-00000001", "2026-10-28T13:00:00", "r-0abcdef3")
        assertEquals(AccountingPlanState.PENDING, l.planState(plan))
        l.cancel(plan)
        assertEquals(AccountingPlanState.CANCELLED, l.planState(plan))
        assertArrayEquals(before, bytes(root))
        assertTrue(err { l.confirm(plan, plan.confirmation) }.contains("PLAN_CANCELLED"))
        assertEquals(1, l.load().effectiveRows.size)
        assertEquals(-10000L, l.load().net)
        assertTrue(reversalReceipts(root).isEmpty())
        assertFalse(Files.exists(pending(root)))

        // fresh plan on the same still-active target succeeds
        val again = l.prepareReversal("r-00000001", "2026-10-28T13:00:01", "r-0abcdef4")
        val c = l.confirm(again, again.confirmation) as AccountingConfirmResult.Confirmed
        assertNotNull(c.reversal)
        assertEquals(0L, c.reversal!!.netAfter)
        assertEquals(2, ledger(root).load().rows.size)
    }

    @Test fun staleSourceRevisionAndBaselineDriftFailClosed() {
        val root = newRoot()
        val l = ledger(root)
        writeManual(l, draft(), "2026-10-28T12:31:04")
        assertTrue(err { l.prepareReversal("r-00000001", "2026-10-28T13:00:00",
            "r-0abcdef5", "deadbeef") }.contains("VOID_SOURCE_STALE"))

        val plan = l.prepareReversal("r-00000001", "2026-10-28T13:00:00", "r-0abcdef6")
        // concurrent baseline change: a normal manual append through the same public chain
        writeManual(l, draft(id = "r-00000009", amountText = "9", category = "餐饮",
            rawInput = "加一笔9"), "2026-10-28T12:40:00")
        val drifted = bytes(root)
        assertTrue(err { l.confirm(plan, plan.confirmation) }.contains("REVISION_CONFLICT"))
        assertArrayEquals(drifted, bytes(root)) // no clobber
        assertEquals(2, ledger(root).load().rows.size)
        assertTrue(reversalReceipts(root).isEmpty())

        val fresh = l.prepareReversal("r-00000001", "2026-10-28T13:00:01", "r-0abcdef7")
        val c = l.confirm(fresh, fresh.confirmation) as AccountingConfirmResult.Confirmed
        // baseline now includes the extra -900 row: net -10900, after reversal -900
        assertNotNull(c.reversal)
        assertEquals(-900L, c.reversal!!.netAfter)
        assertEquals(3, ledger(root).load().rows.size)
    }

    @Test fun doubleConfirmReversedTargetAndReversalTargetRefused() {
        val root = newRoot()
        val l = ledger(root)
        writeManual(l, draft(), "2026-10-28T12:31:04")
        val revTs = "2026-10-28T13:00:00"
        val legacyProposal = l.voidProposal("r-00000001") // valid while target still active
        val plan = l.prepareReversal("r-00000001", revTs, "r-0abcdef8")
        l.confirm(plan, plan.confirmation)
        val committed = bytes(root)
        assertTrue(err { l.confirm(plan, plan.confirmation) }.contains("PLAN_ALREADY_CONFIRMED"))
        assertArrayEquals(committed, bytes(root))
        assertTrue(err { l.prepareReversal("r-00000001", "2026-10-28T13:05:00") }
            .contains("VOID_TARGET_NOT_ACTIVE"))          // already reversed
        assertTrue(err { l.prepareReversal("ts:$revTs", "2026-10-28T13:05:00") }
            .contains("REVERSAL_TARGET_IS_REVERSAL"))      // reversal pointing at reversal
        assertTrue(err { l.voidProposal("r-00000001") }.contains("VOID_TARGET_NOT_ACTIVE"))
        assertTrue(err { l.appendVoidReversal(legacyProposal) }
            .contains("VOID_WRITE_UNSUPPORTED"))
        assertArrayEquals(committed, bytes(root))
        assertEquals(2, ledger(root).load().rows.size)
        assertEquals(1, reversalReceipts(root).size)
    }

    @Test fun tamperedReadbackStaysUnknownPendingWithNoReceiptAndNoReplay() {
        val root = newRoot()
        val setup = ledger(root)
        writeManual(setup, draft(), "2026-10-28T12:31:04")
        val before = bytes(root)
        val tampered = AccountingCodec.encode(
            "{\"id\":\"r-00000009\",\"date\":\"2026-10-28\",\"amount\":-1.00,\"category\":\"交通\"," +
                "\"source\":\"manual\",\"raw_input\":\"x\",\"ts\":\"2026-10-28T12:31:09\"," +
                "\"at_trust\":\"unverified\"}\n"
        )
        val l = IsolatedAccountingLedger(root.toFile()) { phase ->
            if (phase == "target-published") Files.write(file(root), tampered)
        }
        val plan = l.prepareReversal("r-00000001", "2026-10-28T13:00:00", "r-0abcdef9")
        assertTrue(err { l.confirm(plan, plan.confirmation) }.contains("WRITE_RESULT_UNKNOWN"))
        assertArrayEquals(tampered, bytes(root))  // history NOT cleared / rolled back
        assertTrue(Files.exists(pending(root)))
        assertTrue(l.pendingReview())
        assertEquals(AccountingPlanState.PENDING_REVIEW, l.planState(plan))
        assertTrue(reversalReceipts(root).isEmpty())     // no readback => no reversal receipt
        assertTrue(err { l.confirm(plan, plan.confirmation) }.contains("PENDING_REVIEW_REQUIRED"))
        assertTrue(err { l.cancel(plan) }.contains("PENDING_REVIEW_REQUIRED"))
        assertTrue(err { ledger(root).load() }.contains("PENDING_REVIEW_REQUIRED"))
        assertTrue(err { ledger(root).prepareReversal("r-00000009", "2026-10-28T13:01:00") }
            .contains("PENDING_REVIEW_REQUIRED"))
        assertArrayEquals(tampered, bytes(root))
        assertTrue(before.isNotEmpty())
    }

    @Test fun receiptPhaseFailureStaysPendingAndRefusesReplay() {
        val root = newRoot()
        val setup = ledger(root)
        writeManual(setup, draft(), "2026-10-28T12:31:04")
        val l = IsolatedAccountingLedger(root.toFile()) { phase ->
            if (phase == "receipt-durable") throw IOException("injected-receipt-failure")
        }
        val plan = l.prepareReversal("r-00000001", "2026-10-28T13:00:00", "r-0abcdeff")
        val thrown = err { l.confirm(plan, plan.confirmation) }
        assertTrue(thrown.contains("injected-receipt-failure") || thrown.contains("IOException"))
        assertTrue(Files.exists(pending(root)))
        assertTrue(l.pendingReview())
        assertEquals(AccountingPlanState.PENDING_REVIEW, l.planState(plan))
        // the reversal line is physically present, but its result is UNKNOWN: no auto-retry
        assertEquals(2, AccountingSnapshot.parse(bytes(root)).rows.size)
        assertTrue(err { l.confirm(plan, plan.confirmation) }.contains("PENDING_REVIEW_REQUIRED"))
        assertTrue(err { l.cancel(plan) }.contains("PENDING_REVIEW_REQUIRED"))
        assertEquals(1, reversalReceipts(root).size) // reversal receipt durable; pending marker still wins
    }

    @Test fun invalidAmountDateIdentityAndMissingTargetsFailClosed() {
        val root = newRoot()
        Files.createDirectories(root.resolve("db"))
        val zeroAmount = jsonLine(
            "id" to "r-00000001", "date" to "2026-10-28", "amount" to raw("0.00"),
            "category" to "交通", "source" to "manual", "raw_input" to "零金额",
            "ts" to "2026-10-28T12:31:04", "at_trust" to "unverified",
        )
        val badDate = jsonLine(
            "id" to "r-00000002", "date" to "2026-02-30", "amount" to raw("-10.00"),
            "category" to "交通", "source" to "manual", "raw_input" to "坏日期",
            "ts" to "2026-10-28T12:31:05", "at_trust" to "unverified",
        )
        val voidedByMismatch = jsonLine(
            "id" to "r-00000003", "date" to "2026-10-28", "amount" to raw("-10.00"),
            "category" to "交通", "source" to "manual", "raw_input" to "身份不符",
            "ts" to "2026-10-28T12:31:06", "at_trust" to "unverified",
            "voided_by" to "2026-10-28T23:59:59",
        )
        Files.write(file(root), fixture(zeroAmount, badDate, voidedByMismatch))
        val l = ledger(root)
        assertTrue(l.load().findings.any { it.code == "AMOUNT_INVALID" })
        assertTrue(l.load().findings.any { it.code == "DATE_INVALID" })
        assertTrue(l.load().findings.any { it.code == "VOIDED_BY_MISMATCH" })
        assertTrue(err { l.prepareReversal("r-00000001") }.contains("VOID_TARGET"))
        assertTrue(err { l.prepareReversal("r-00000002") }.contains("VOID_TARGET"))
        assertTrue(err { l.prepareReversal("r-00000003") }.contains("VOID_TARGET"))
        assertTrue(err { l.prepareReversal("r-99999999") }.contains("VOID_TARGET_MISSING"))
        assertTrue(err { l.prepareReversal("r-00000001", "bad-ts") }.contains("TS_INVALID"))
        assertEquals(3, l.load().rows.size)
        assertTrue(receipts(root).isEmpty())
    }

    @Test fun manualRegressionBeforeAndAfterReversalAndPlanInstanceScoped() {
        val root = newRoot()
        val a = ledger(root)
        writeManual(a, draft(id = "r-00000001"), "2026-10-28T12:31:04")
        val plan = a.prepareReversal("r-00000001", "2026-10-28T13:00:00", "r-0000000a")
        val b = ledger(root)
        assertEquals(AccountingPlanState.UNKNOWN, b.planState(plan))
        assertTrue(err { b.confirm(plan, plan.confirmation) }.contains("PLAN_UNKNOWN"))
        assertTrue(err { b.cancel(plan) }.contains("PLAN_UNKNOWN"))
        a.confirm(plan, plan.confirmation)
        // normal manual append still works after reversal; history prefix immutable
        val before = bytes(root)
        writeManual(a, draft(id = "r-0000000b", amountText = "7", category = "餐饮",
            rawInput = "晚饭7"), "2026-10-28T14:00:00")
        val after = bytes(root)
        assertArrayEquals(before, after.copyOfRange(0, before.size))
        val rb = ledger(root).load()
        assertEquals(3, rb.rows.size)
        assertEquals(1, rb.effectiveRows.size) // reversed pair excluded; only the new manual row
        assertEquals(-700L, rb.net)
        val month = AccountingReports.month(rb, "2026-10")
        assertEquals(1, month.rows.size)
        assertEquals(-700L, month.net)
    }
}
