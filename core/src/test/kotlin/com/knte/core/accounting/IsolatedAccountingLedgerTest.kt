package com.knte.core.accounting

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class IsolatedAccountingLedgerTest {
    private fun tempRoot(): Path = Files.createTempDirectory("knte-accounting-")

    private fun draft(
        id: String = "r-0a1b2c3d",
        amountText: String = "25",
        income: Boolean = false,
        date: String = "2026-10-28",
        category: String = "餐饮",
        location: String = "学校食堂",
        note: String = "午饭",
        rawInput: String = "午饭25块在学校食堂",
    ): AccountingDraft = AccountingDraft.prepare(id, date, amountText, income, category, location, note, rawInput)

    private fun file(root: Path): Path = root.resolve("db/accounting.jsonl")

    private fun write(ledger: IsolatedAccountingLedger, d: AccountingDraft, ts: String): AccountingConfirmResult {
        val plan = ledger.prepareManual(d, ts)
        return ledger.confirm(plan, plan.confirmation)
    }

    @Test fun manualConfirmCreatesUnderDbAndIndependentReadBack() {
        val root = tempRoot()
        val ledger = IsolatedAccountingLedger(root.toFile())
        val plan = ledger.prepareManual(draft(), "2026-10-28T12:31:04")
        assertEquals(AccountingCodec.DATASET, plan.dataset)
        assertEquals(null, plan.beforeRevision)
        assertEquals(0, plan.rowCountBefore)
        assertFalse(Files.exists(file(root)))
        val confirmed = ledger.confirm(plan, plan.confirmation) as AccountingConfirmResult.Confirmed
        assertEquals("r-0a1b2c3d", confirmed.appended.id)
        assertEquals(-2500L, confirmed.appended.cents)
        assertEquals("manual", confirmed.appended.source)
        assertEquals("午饭25块在学校食堂", confirmed.appended.rawInput)
        val bytes = Files.readAllBytes(file(root))
        assertEquals(plan.entryLine + "\n", String(bytes, StandardCharsets.UTF_8))
        assertEquals(plan.afterRevision, AccountingCodec.hash(bytes))
        assertTrue(plan.entryLine.startsWith("{\"id\":\"r-0a1b2c3d\",\"date\":\"2026-10-28\",\"amount\":-25.00"))
        assertTrue(plan.entryLine.endsWith("\"ts\":\"2026-10-28T12:31:04\",\"at_trust\":\"unverified\"}"))
        val reopened = IsolatedAccountingLedger(root.toFile()).load()
        assertEquals(1, reopened.rows.size)
        assertEquals(-2500L, reopened.rows[0].cents)
        assertEquals(-2500L, reopened.net)
    }

    @Test fun planBindsHashesConfirmationCancelAndPending() {
        val root = tempRoot()
        val ledger = IsolatedAccountingLedger(root.toFile())
        val plan = ledger.prepareManual(draft(), "2026-10-28T12:31:04")
        val other = ledger.prepareManual(draft(note = "晚饭"), "2026-10-28T12:31:04")
        assertFalse(plan.confirmation == other.confirmation)
        assertFalse(plan.afterRevision == other.afterRevision)
        assertTrue(err { ledger.confirm(plan, "not-confirmed") }.contains("CONFIRMATION_MISMATCH"))
        assertFalse(Files.exists(file(root)))
        ledger.cancel(plan)
        assertTrue(err { ledger.confirm(plan, plan.confirmation) }.contains("PLAN_CANCELLED"))
        assertFalse(Files.exists(file(root)))
        val written = ledger.confirm(other, other.confirmation) as AccountingConfirmResult.Confirmed
        assertEquals("晚饭", written.appended.note)
        assertTrue(err { ledger.confirm(other, other.confirmation) }.contains("PLAN_ALREADY_CONFIRMED"))
        assertTrue(err { ledger.cancel(other) }.contains("PLAN_ALREADY_CONFIRMED"))
    }

    @Test fun externalDriftConflictsNeverClobberForeignBytes() {
        val root = tempRoot()
        val ledger = IsolatedAccountingLedger(root.toFile())
        write(ledger, draft(), "2026-10-28T12:31:04")
        assertEquals(1, ledger.load().rows.size)
        val stale = ledger.prepareManual(draft(id = "r-00000002"), "2026-10-28T12:31:05")
        val intruder = AccountingCodec.encode(
            "{\"id\":\"r-00000009\",\"date\":\"2026-10-28\",\"amount\":-1.00,\"category\":\"交通\"," +
                "\"source\":\"manual\",\"raw_input\":\"地铁\",\"ts\":\"2026-10-28T12:31:09\"," +
                "\"at_trust\":\"unverified\"}\n"
        )
        val before = Files.readAllBytes(file(root))
        Files.write(file(root), before + intruder)
        assertTrue(err { ledger.confirm(stale, stale.confirmation) }.contains("REVISION_CONFLICT"))
        assertArrayEquals(before + intruder, Files.readAllBytes(file(root)))
        assertEquals(2, IsolatedAccountingLedger(root.toFile()).load().rows.size)

        val root2 = tempRoot()
        val ledger2 = IsolatedAccountingLedger(root2.toFile())
        val create = ledger2.prepareManual(draft(), "2026-10-28T12:31:04")
        Files.createDirectories(root2.resolve("db"))
        Files.write(file(root2), intruder)
        assertTrue(err { ledger2.confirm(create, create.confirmation) }.contains("CREATE_CONFLICT"))
        assertArrayEquals(intruder, Files.readAllBytes(file(root2)))
    }

    @Test fun duplicateIdIsIdempotentOnlyForIdenticalRow() {
        val root = tempRoot()
        val ledger = IsolatedAccountingLedger(root.toFile())
        write(ledger, draft(), "2026-10-28T12:31:04")
        val afterFirst = Files.readAllBytes(file(root))
        val again = write(ledger, draft(), "2026-10-28T12:31:05")
        assertTrue(again is AccountingConfirmResult.AlreadyWritten)
        assertEquals(1, (again as AccountingConfirmResult.AlreadyWritten).snapshot.rows.size)
        assertArrayEquals(afterFirst, Files.readAllBytes(file(root)))
        val conflicting = ledger.prepareManual(draft(amountText = "35"), "2026-10-28T12:31:06")
        assertTrue(err { ledger.confirm(conflicting, conflicting.confirmation) }.contains("ID_CONFLICT"))
        assertArrayEquals(afterFirst, Files.readAllBytes(file(root)))
    }

    @Test fun duplicateWriteTimestampRefused() {
        val root = tempRoot()
        val ledger = IsolatedAccountingLedger(root.toFile())
        write(ledger, draft(), "2026-10-28T12:31:04")
        assertTrue(err { ledger.prepareManual(draft(id = "r-00000002"), "2026-10-28T12:31:04") }
            .contains("TIMESTAMP_BUSY"))
    }

    @Test fun appendOnlyPrefixBytesNeverChange() {
        val root = tempRoot()
        val ledger = IsolatedAccountingLedger(root.toFile())
        write(ledger, draft(), "2026-10-28T12:31:04")
        val bytesA = Files.readAllBytes(file(root))
        write(ledger, draft(id = "r-00000002"), "2026-10-28T12:31:05")
        val bytesB = Files.readAllBytes(file(root))
        assertArrayEquals(bytesA, bytesB.copyOfRange(0, bytesA.size))
        assertEquals(2, IsolatedAccountingLedger(root.toFile()).load().rows.size)
    }

    @Test fun failureBeforeTargetWriteRetainsPendingAndFailsClosed() {
        val root = tempRoot()
        val ledger = IsolatedAccountingLedger(root.toFile()) { phase ->
            if (phase == "pending-durable") throw java.io.IOException("injected-before-write")
        }
        val plan = ledger.prepareManual(draft(), "2026-10-28T12:31:04")
        fails { ledger.confirm(plan, plan.confirmation) }
        assertFalse(Files.exists(file(root)))
        assertTrue(Files.exists(root.resolve("db/accounting.jsonl.pending")))
        assertTrue(err { ledger.load() }.contains("PENDING_REVIEW_REQUIRED"))
        assertTrue(err { ledger.prepareManual(draft(id = "r-00000003"), "2026-10-28T12:31:06") }
            .contains("PENDING_REVIEW_REQUIRED"))
    }

    @Test fun failureAfterPublishKeepsBeforeImageAndRefusesReplay() {
        val root = tempRoot()
        val setup = IsolatedAccountingLedger(root.toFile())
        write(setup, draft(), "2026-10-28T12:31:04")
        val bytesA = Files.readAllBytes(file(root))
        val ledger = IsolatedAccountingLedger(root.toFile()) { phase ->
            if (phase == "target-published") throw java.io.IOException("injected-after-write")
        }
        val planB = ledger.prepareManual(draft(id = "r-00000002"), "2026-10-28T12:31:05")
        fails { ledger.confirm(planB, planB.confirmation) }
        val onDisk = AccountingSnapshot.parse(Files.readAllBytes(file(root)))
        assertEquals(2, onDisk.rows.size)
        assertTrue(Files.exists(root.resolve("db/accounting.jsonl.pending")))
        val beforeImages = mutableListOf<ByteArray>()
        Files.walk(root.resolve("db/.journal")).use { stream ->
            stream.filter { it.fileName.toString() == "before.jsonl" }
                .forEach { beforeImages.add(Files.readAllBytes(it)) }
        }
        assertEquals(1, beforeImages.size)
        assertArrayEquals(bytesA, beforeImages[0])
        assertTrue(err { ledger.confirm(planB, planB.confirmation) }.contains("PENDING_REVIEW_REQUIRED"))
    }

    @Test fun tamperedReadbackIsReportedUnknownAndLocked() {
        val root = tempRoot()
        val tampered = AccountingCodec.encode(
            "{\"id\":\"r-00000009\",\"date\":\"2026-10-28\",\"amount\":-1.00,\"category\":\"交通\"," +
                "\"source\":\"manual\",\"raw_input\":\"x\",\"ts\":\"2026-10-28T12:31:09\"," +
                "\"at_trust\":\"unverified\"}\n"
        )
        val ledger = IsolatedAccountingLedger(root.toFile()) { phase ->
            if (phase == "target-published") Files.write(file(root), tampered)
        }
        val plan = ledger.prepareManual(draft(), "2026-10-28T12:31:04")
        assertTrue(err { ledger.confirm(plan, plan.confirmation) }.contains("WRITE_RESULT_UNKNOWN"))
        assertArrayEquals(tampered, Files.readAllBytes(file(root)))
        assertTrue(Files.exists(root.resolve("db/accounting.jsonl.pending")))
        var receipts = 0
        Files.walk(root.resolve("db/.journal")).use { stream ->
            receipts = stream.filter { it.fileName.toString() == "receipt.txt" }.count().toInt()
        }
        assertEquals(0, receipts)
    }

    @Test fun voidProposalValidatedButWriteExplicitlyUnsupported() {
        val root = tempRoot()
        val ledger = IsolatedAccountingLedger(root.toFile())
        write(
            ledger,
            draft(id = "r-00000001", amountText = "100", category = "交通",
                location = "出租车", note = "打车", rawInput = "打车花了100"),
            "2026-10-28T12:31:04",
        )
        val proposal = ledger.voidProposal("r-00000001")
        assertEquals("ts:2026-10-28T12:31:04", proposal.originalViewKey)
        assertEquals(-10000L, proposal.originalCents)
        assertEquals(10000L, proposal.proposedCents)
        assertEquals("冲销", proposal.proposedNote)
        assertEquals("交通", proposal.originalCategory)
        val byKey = ledger.voidProposal("ts:2026-10-28T12:31:04")
        assertEquals(proposal.originalTs, byKey.originalTs)
        val rendered = proposal.render("2026-10-28T13:00:00")
        assertTrue(rendered.contains("\"corrected_of\":\"2026-10-28T12:31:04\""))
        assertTrue(rendered.contains("\"amount\":100.00"))
        val originalLine = String(Files.readAllBytes(file(root)), StandardCharsets.UTF_8).trimEnd('\n')
        val pair = AccountingSnapshot.parse(fixture(originalLine, rendered))
        assertEquals(2, pair.rows.size)
        assertEquals(AccountingKind.REVERSAL, pair.rows[1].kind)
        assertEquals(0L, pair.net)
        assertFalse(pair.rows[0].active)
        val thrown = err { ledger.appendVoidReversal(proposal) }
        assertTrue(thrown.contains("VOID_WRITE_UNSUPPORTED"))
        assertFalse(ledger.voidWriteSupported())
        assertEquals(1, ledger.load().effectiveRows.size)
        assertTrue(err { ledger.voidProposal("r-99999999") }.contains("VOID_TARGET_MISSING"))

        val root2 = tempRoot()
        Files.createDirectories(root2.resolve("db"))
        Files.write(file(root2), fixture(originalLine, rendered))
        val ledger2 = IsolatedAccountingLedger(root2.toFile())
        assertTrue(err { ledger2.voidProposal("2026-10-28T12:31:04") }.contains("VOID_TARGET_NOT_ACTIVE"))
    }

    @Test fun unterminatedLedgerBlocksWritesAndLoadsFlagged() {
        val root = tempRoot()
        Files.createDirectories(root.resolve("db"))
        val line = "{\"id\":\"r-00000001\",\"date\":\"2026-10-28\",\"amount\":-25.00,\"category\":\"餐饮\"," +
            "\"source\":\"manual\",\"raw_input\":\"x\",\"ts\":\"2026-10-28T12:31:04\",\"at_trust\":\"unverified\"}"
        Files.write(file(root), AccountingCodec.encode(line))
        val ledger = IsolatedAccountingLedger(root.toFile())
        assertTrue(ledger.load().unterminatedTail)
        assertTrue(err { ledger.prepareManual(draft(), "2026-10-28T12:31:05") }.contains("WRITE_UNTERMINATED"))
    }

    @Test fun rootShapeAndDbShapeFailClosed() {
        val asFile = Files.createTempFile("knte-accounting-root", "").toFile()
        fails { IsolatedAccountingLedger(asFile) }
        val root = tempRoot()
        Files.createFile(root.resolve("db"))
        val ledger = IsolatedAccountingLedger(root.toFile())
        assertTrue(err { ledger.load() }.contains("DB_CHANGED"))
    }

    @Test fun plansAreInstanceScopedAndUnknownElsewhere() {
        val root = tempRoot()
        val a = IsolatedAccountingLedger(root.toFile())
        val b = IsolatedAccountingLedger(root.toFile())
        val plan = a.prepareManual(draft(), "2026-10-28T12:31:04")
        assertTrue(err { b.confirm(plan, plan.confirmation) }.contains("PLAN_UNKNOWN"))
        assertTrue(err { b.cancel(plan) }.contains("PLAN_UNKNOWN"))
        val ok = a.confirm(plan, plan.confirmation) as AccountingConfirmResult.Confirmed
        assertEquals(1, ok.snapshot.rows.size)
    }

    @Test fun capacityAndSizeGuards() {
        val root = tempRoot()
        val lines = (1..AccountingCodec.MAX_ROWS + 1).map { i ->
            val id = "r-" + i.toString(16).padStart(8, '0')
            val ts = String.format(
                java.util.Locale.ROOT, "2026-10-28T%02d:%02d:%02d",
                i / 3600 % 24, i / 60 % 60, i % 60,
            )
            "{\"id\":\"$id\",\"date\":\"2026-10-28\",\"amount\":-1.00,\"category\":\"餐饮\"," +
                "\"source\":\"manual\",\"raw_input\":\"x\",\"ts\":\"$ts\",\"at_trust\":\"unverified\"}"
        }
        Files.createDirectories(root.resolve("db"))
        Files.write(file(root), fixture(*lines.toTypedArray()))
        val ledger = IsolatedAccountingLedger(root.toFile())
        assertTrue(ledger.load().overCapacity)
        assertTrue(err { ledger.prepareManual(draft(id = "r-0f0f0f0f"), "2026-11-11T11:11:11") }
            .contains("CAPACITY"))

        val root2 = tempRoot()
        Files.createDirectories(root2.resolve("db"))
        Files.write(
            file(root2),
            AccountingCodec.encode("{\"pad\":\"" + "x".repeat(AccountingCodec.MAX_FILE_BYTES) + "\"}"),
        )
        assertTrue(err { IsolatedAccountingLedger(root2.toFile()).load() }.contains("SIZE_LIMIT"))
    }
}
