package com.knte.core.accounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Regression tests for the 49ca66ae plan-authorization blockers. Each case mirrors the
 * IndependentProbe reproduction semantics against the real production APIs only. */
class AccountingPlanIdentityRegressionTest {
    private fun newRoot(): Path = Files.createTempDirectory("knte-planfix-")
    private fun ledger(root: Path) = IsolatedAccountingLedger(root.toFile())
    private fun file(root: Path): Path = root.resolve("db/accounting.jsonl")
    private fun bytes(root: Path): ByteArray =
        if (Files.exists(file(root))) Files.readAllBytes(file(root)) else ByteArray(0)

    private fun manual(l: IsolatedAccountingLedger, ts: String = "2026-10-28T12:31:04"): AccountingAppendPlan =
        l.prepareManual(
            AccountingDraft.fromCents("r-00000001", "2026-10-28", -10000L, "交通", "出租车", "打车", "打车100"),
            ts,
        )

    private fun reversal(l: IsolatedAccountingLedger, ts: String = "2026-11-13T13:00:00"): AccountingAppendPlan =
        l.prepareReversal("r-00000001", ts, "r-00000002")

    private fun error(block: () -> Unit): String =
        try { block(); "NO_EXCEPTION" } catch (e: Exception) { e.message ?: e.javaClass.name }

    /** Blocker 1: cancel(old); prepare the SAME target/ts/id again; the old cancelled object
     * must stay CANCELLED and confirm(old) must be refused before any write. The fresh plan
     * itself is still usable, and a later cancel of the fresh plan cannot revive the old one. */
    @Test fun cancelledOldObjectStaysCancelledAfterIdenticalPrepare() {
        val root = newRoot()
        val l = ledger(root)
        val mp = manual(l)
        assertTrue(l.confirm(mp, mp.confirmation) is AccountingConfirmResult.Confirmed)
        val old = reversal(l)
        l.cancel(old)
        assertEquals(AccountingPlanState.CANCELLED, l.planState(old))
        val before = bytes(root)
        val fresh = reversal(l)
        // The fresh plan is independently usable and independently cancellable.
        assertEquals(AccountingPlanState.PENDING, l.planState(fresh))
        // The old cancelled object is NOT reactivated by the fresh registration.
        assertEquals(AccountingPlanState.CANCELLED, l.planState(old))
        val result = error { l.confirm(old, old.confirmation) }
        assertEquals("PLAN_CANCELLED", result)
        assertTrue(bytes(root).contentEquals(before))
        l.cancel(fresh)
        assertEquals(AccountingPlanState.CANCELLED, l.planState(fresh))
        assertEquals(AccountingPlanState.CANCELLED, l.planState(old))
        assertEquals("PLAN_CANCELLED", error { l.confirm(old, old.confirmation) })
        assertTrue(bytes(root).contentEquals(before))
    }

    /** Manual variant of blocker 1 with identical prepare inputs (same draft/id/ts). */
    @Test fun cancelledManualObjectStaysCancelledAfterIdenticalManualPrepare() {
        val root = newRoot()
        val l = ledger(root)
        val old = manual(l)
        l.cancel(old)
        assertEquals(AccountingPlanState.CANCELLED, l.planState(old))
        val before = bytes(root)
        val fresh = manual(l)
        assertEquals(AccountingPlanState.PENDING, l.planState(fresh))
        assertEquals(AccountingPlanState.CANCELLED, l.planState(old))
        assertEquals("PLAN_CANCELLED", error { l.confirm(old, old.confirmation) })
        // Cancelling an already-CANCELLED object is an idempotent no-op.
        assertEquals("NO_EXCEPTION", error { l.cancel(old) })
        assertTrue(bytes(root).contentEquals(before))
    }

    /** Blocker 2: instance B's identical-argument prepare must not make ledger A's plan
     * PENDING/CONFIRMABLE in B; the foreign object stays UNKNOWN in B. */
    @Test fun foreignInstancePlanStaysUnknownAfterIdenticalLocalPrepare() {
        val root = newRoot()
        val a = ledger(root)
        val mp = manual(a)
        assertTrue(a.confirm(mp, mp.confirmation) is AccountingConfirmResult.Confirmed)
        val pa = reversal(a)
        assertEquals(AccountingPlanState.PENDING, a.planState(pa))
        val b = ledger(root)
        assertEquals(AccountingPlanState.UNKNOWN, b.planState(pa))
        assertEquals("PLAN_UNKNOWN", error { b.cancel(pa) })
        val before = bytes(root)
        val pb = reversal(b)
        assertEquals(AccountingPlanState.PENDING, b.planState(pb))
        assertEquals(AccountingPlanState.UNKNOWN, b.planState(pa))
        assertEquals("PLAN_UNKNOWN", error { b.confirm(pa, pa.confirmation) })
        assertTrue(bytes(root).contentEquals(before))
        // The local instance still owns and can use its own plan object.
        assertEquals(AccountingPlanState.PENDING, a.planState(pa))
        assertTrue(a.confirm(pa, pa.confirmation) is AccountingConfirmResult.Confirmed)
    }

    /** Manual cross-instance variant: A's prepared manual plan is UNKNOWN in B even after
     * B prepares a byte-identical plan, and cannot be confirmed or cancelled there. */
    @Test fun manualPlanFromOtherInstanceStaysUnknownInSecondInstance() {
        val root = newRoot()
        val a = ledger(root)
        val pa = manual(a)
        val b = ledger(root)
        val pb = manual(b)
        assertEquals(AccountingPlanState.UNKNOWN, b.planState(pa))
        assertEquals("PLAN_UNKNOWN", error { b.confirm(pa, pa.confirmation) })
        assertEquals("PLAN_UNKNOWN", error { b.cancel(pa) })
        assertEquals(AccountingPlanState.PENDING, b.planState(pb))
        assertTrue(b.confirm(pb, pb.confirmation) is AccountingConfirmResult.Confirmed)
        assertEquals(AccountingPlanState.PENDING, a.planState(pa))
    }

    /** Blocker 3: a same-module copy of an issued plan with an altered reversal binding
     * (baselineNet + 1, same confirmation) is rejected before any business write; no data
     * bytes, no journal entry, no pending marker. */
    @Test fun forgedBindingCopyRejectedBeforeAnyWrite() {
        val root = newRoot()
        val l = ledger(root)
        val mp = manual(l)
        assertTrue(l.confirm(mp, mp.confirmation) is AccountingConfirmResult.Confirmed)
        val p = reversal(l)
        val b = p.reversal!!
        val fake = AccountingAppendPlan(
            p.rootId, p.dataset, p.id, p.ts, p.beforeRevision, p.afterRevision,
            p.entryLine, p.rowCountBefore, p.draftToken, p.entryKind,
            AccountingReversalBinding(
                b.originalViewKey, b.originalId, b.originalTs, b.originalCents, b.originalDate,
                b.originalCategory, b.originalLocation, b.originalRawInput, b.sourceRevision,
                b.baselineNet + 1, b.baselineEffectiveRows,
            ),
        )
        assertEquals(p.confirmation, fake.confirmation)
        assertEquals(AccountingPlanState.UNKNOWN, l.planState(fake))
        val before = bytes(root)
        val journalBefore = Files.list(root.resolve("db/.journal")).use { it.count() }
        assertEquals("PLAN_UNKNOWN", error { l.confirm(fake, fake.confirmation) })
        assertTrue(bytes(root).contentEquals(before))
        assertFalse(l.pendingReview())
        // No NEW journal attempt: same count as before the refused confirm.
        assertEquals(journalBefore, Files.list(root.resolve("db/.journal")).use { it.count() })
        // The genuine issued plan still confirms normally afterwards.
        assertTrue(l.confirm(p, p.confirmation) is AccountingConfirmResult.Confirmed)
    }

    /** Same-module copy with altered binding but registered earlier must not lose its own
     * state: cancel cannot unlock a later-registered copy's state either (object binding). */
    @Test fun genuinePlanStateUnaffectedByForgedSibling() {
        val root = newRoot()
        val l = ledger(root)
        val mp = manual(l)
        assertTrue(l.confirm(mp, mp.confirmation) is AccountingConfirmResult.Confirmed)
        val p = reversal(l)
        val b = p.reversal!!
        val fake = AccountingAppendPlan(
            p.rootId, p.dataset, p.id, p.ts, p.beforeRevision, p.afterRevision,
            p.entryLine, p.rowCountBefore, p.draftToken, p.entryKind,
            AccountingReversalBinding(
                b.originalViewKey, b.originalId, b.originalTs, b.originalCents, b.originalDate,
                b.originalCategory, b.originalLocation, b.originalRawInput, b.sourceRevision,
                b.baselineNet - 1, b.baselineEffectiveRows,
            ),
        )
        // A forged sibling is UNKNOWN: cancel targets nothing and cannot unlock the genuine plan.
        assertEquals("PLAN_UNKNOWN", error { l.cancel(fake) })
        assertEquals(AccountingPlanState.PENDING, l.planState(p))
        assertTrue(l.confirm(p, p.confirmation) is AccountingConfirmResult.Confirmed)
        assertEquals(AccountingPlanState.UNKNOWN, l.planState(fake))
    }
}
