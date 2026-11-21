package com.knte.preview.time.host

import com.knte.core.time.control.TimeControlException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/** Real controller + real durable journal over temp-isolated roots. No controller fakes,
 *  no display-phase ownership: every assertion goes through the authority's own state. */
class TimeAuthorityCoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun newCore() = TimeAuthorityCore.open(
        ledgerRoot = tmp.newFolder("ledger-${System.nanoTime()}"),
        journalRoot = tmp.newFolder("journal-${System.nanoTime()}"),
        io = kotlinx.coroutines.Dispatchers.IO,
    )

    @Test fun prepareReloadConfirmWritesRealRecordAndPublishesReady() = runBlocking {
        val core = newCore(); core.initialize()
        core.openOwner("page")
        val h = core.prepareStart("核对项目", "page")
        // reload must NOT drop the active prepare (regression: Ready overwrote Preparing)
        core.reload()
        val session = checkNotNull(core.sessionOf(h))
        assertEquals("start", session.op)
        assertEquals("核对项目", session.project)
        assertTrue(session.date.isNotBlank()); assertTrue(session.start.isNotBlank())
        assertTrue(core.state.value.phase is AuthorityPhase.Preparing)
        val result = core.confirm(h, "page")
        assertEquals("start", result.op)
        assertFalse(result.replayed)
        val view = checkNotNull(core.currentView())
        assertTrue(view.records.any { it.project == "核对项目" && it.running() })
        assertTrue(core.state.value.phase is AuthorityPhase.Ready)
        Unit
    }

    @Test fun prepareStopUsesRealSessionTimesAndCancelsCleanly() = runBlocking {
        val core = newCore(); core.initialize()
        core.openOwner("page")
        val start = core.prepareStart("停止目标", "page")
        core.confirm(start, "page")
        val running = checkNotNull(core.currentView()).records.single { it.running() }
        val stop = core.prepareStop(running.id, "page")
        val session = checkNotNull(core.sessionOf(stop))
        assertEquals("stop", session.op)
        assertEquals(running.id, session.targetId)
        assertTrue(session.end!!.isNotBlank())
        core.cancel(stop, "page")
        val view = checkNotNull(core.currentView())
        assertTrue(view.records.single { it.id == running.id }.running())
        assertNull(view.unresolvedOp)
        Unit
    }

    @Test fun crossSurfaceConfirmAndCancelAreRefusedWhileOpIsActive() = runBlocking {
        val core = newCore(); core.initialize()
        core.openOwner("page"); core.openOwner("monitor")
        val h = core.prepareStart("隔离项目", "page")
        try { core.confirm(h, "monitor"); fail("cross-surface confirm must be refused") }
        catch (e: TimeControlException) { assertEquals("TIME_OWNER_MISMATCH", e.code) }
        try { core.cancel(h, "monitor"); fail("cross-surface cancel must be refused") }
        catch (e: TimeControlException) { assertEquals("TIME_OWNER_MISMATCH", e.code) }
        // The op survives untouched and its owner can still resolve it.
        assertTrue(core.state.value.phase is AuthorityPhase.Preparing)
        core.cancel(h, "page")
        assertTrue(core.currentView()?.unresolvedOp == null)
        Unit
    }

    @Test fun competingPrepareIsRefusedAndCloseFreesTheSingleSlot() = runBlocking {
        val core = newCore(); core.initialize()
        core.openOwner("page"); core.openOwner("monitor")
        val a = core.prepareStart("页面会话", "page")
        // Real controller policy: only ONE unresolved op exists at a time. A competing
        // surface's prepare is refused, not silently queued or substituted.
        try { core.prepareStart("监控会话", "monitor"); fail("competing prepare must be refused") }
        catch (e: TimeControlException) { assertEquals("TIME_CONTROL_UNRESOLVED_OP", e.code) }
        assertEquals(a, core.ownedHandle("page"))
        assertNull(core.ownedHandle("monitor"))
        // Closing the owning surface cancels exactly its own prepare...
        core.closeOwner("page", "test-close")
        assertNull(core.ownedHandle("page"))
        val owners = core.state.value.owners
        assertTrue(owners.getValue("page").closed)
        assertEquals("test-close", owners.getValue("page").closeReason)
        // ...which frees the single unresolved slot for the other surface, explicitly.
        val b = core.prepareStart("监控会话", "monitor")
        assertEquals(b, core.ownedHandle("monitor"))
        core.cancel(b, "monitor")
        assertTrue(core.currentView()?.unresolvedOp == null)
        Unit
    }

    @Test fun closedOwnerCannotPrepareAndBusyOwnerCannotDoublePrepare() = runBlocking {
        val core = newCore(); core.initialize()
        core.openOwner("page")
        val h = core.prepareStart("第一个", "page")
        try { core.prepareStart("第二个", "page"); fail("double prepare must be refused") }
        catch (e: TimeControlException) { assertEquals("TIME_OWNER_BUSY", e.code) }
        core.closeOwner("page", "close")
        try { core.prepareStart("第三个", "page"); fail("closed owner must be refused") }
        catch (e: TimeControlException) { assertEquals("TIME_OWNER_CLOSED", e.code) }
        // closeOwner consumed the exact prepare: the old handle is no longer resolvable.
        try { core.cancel(h, "page"); fail("cancelled-by-close handle must be unknown") }
        catch (e: TimeControlException) { assertEquals("TIME_HANDLE_UNKNOWN", e.code) }
        assertTrue(core.currentView()?.unresolvedOp == null)
        Unit
    }

    @Test fun unconfirmedPrepareDoesNotPersistOrWrite() = runBlocking {
        val ledger = tmp.newFolder("prepare-ledger")
        val journal = tmp.newFolder("prepare-journal")
        val first = TimeAuthorityCore.open(ledger, journal)
        first.initialize(); first.openOwner("page")
        val h = first.prepareStart("尚未确认", "page")
        assertTrue(FileTimeIntentJournal(journal).loadAll().isEmpty())
        first.cancel(h, "page")
        val fresh = TimeAuthorityCore.open(ledger, journal)
        fresh.initialize()
        assertTrue(fresh.state.value.phase is AuthorityPhase.Ready)
        assertTrue(checkNotNull(fresh.currentView()).records.isEmpty())
        Unit
    }

    @Test fun staleExplicitSelectionNeverFallsBackToAnotherRunningTarget() {
        // Regression: explicit A removed -> no implicit stop against B.
        assertNull(MonitorSelectionPolicy.explicitActionTarget("A", listOf("B", "C")))
        assertEquals("B", MonitorSelectionPolicy.explicitActionTarget("B", listOf("B", "C")))
        assertNull(MonitorSelectionPolicy.explicitActionTarget(null, listOf("B")))
        assertNull(MonitorSelectionPolicy.explicitActionTarget("", listOf("B")))
        assertNull(MonitorSelectionPolicy.explicitActionTarget("B", emptyList()))
    }

    @Test fun observeFlowPublishesOwnerAndMonitorFlagsWithoutDroppingPrepare() = runBlocking {
        val core = newCore(); core.initialize()
        core.openOwner("monitor")
        core.setMonitor(true)
        val h = core.prepareStart("状态流", "monitor")
        core.viewSnapshot()
        val st = core.state.value
        assertTrue(st.monitor)
        assertTrue(st.owners.getValue("monitor").handle == h)
        assertTrue(st.phase is AuthorityPhase.Preparing)
        core.cancel(h, "monitor")
        assertTrue(core.state.value.phase is AuthorityPhase.Ready)
        Unit
    }
    @Test fun definiteBaselineRefusalReleasesSlotOnOwnerClose() = runBlocking {
        val ledger = tmp.newFolder("baseline-ledger")
        val first = TimeAuthorityCore.open(ledger, tmp.newFolder("baseline-intents"))
        first.initialize(); first.openOwner("page")
        val stale = first.prepareStart("旧基线", "page")
        val other = com.knte.core.time.control.TimeSessionSource.open(ledger).controller()
        other.confirm(other.prepareStart("外部变更"))
        try { first.confirm(stale, "page"); fail("must refuse stale baseline") }
        catch (e: TimeControlException) { assertEquals("TIME_BASELINE_CHANGED", e.code) }
        first.closeOwner("page", "test-close")
        first.openOwner("monitor")
        val next = first.prepareStart("新会话", "monitor")
        first.cancel(next, "monitor")
        assertEquals(listOf("外部变更"), first.currentView()?.records?.map { it.project })
    }

    @Test fun unreadableLedgerAfterPreparePreservesDurableIntentAndBlocksRetryOrCancel() = runBlocking {
        val ledger = tmp.newFolder("review-ledger")
        val journal = tmp.newFolder("review-intents")
        val core = TimeAuthorityCore.open(ledger, journal)
        core.initialize(); core.openOwner("page")
        val h = core.prepareStart("提交待核", "page")
        val pending = java.io.File(ledger, "timetrack.jsonl.pending")
        pending.writeText("synthetic pending\n")
        try { core.confirm(h, "page"); fail("must refuse unreadable ledger") }
        catch (e: TimeControlException) { assertEquals("TIME_PENDING_REVIEW_REQUIRED", e.code) }
        assertTrue(core.state.value.phase is AuthorityPhase.Review)
        val retained = FileTimeIntentJournal(journal).loadAll().single()
        try { core.cancel(h, "page"); fail("must refuse cancel during review") }
        catch (e: TimeControlException) { assertEquals("TIME_REVIEW_REQUIRED", e.code) }
        try { core.confirm(h, "page"); fail("must refuse a confirm retry") }
        catch (e: TimeControlException) { assertEquals("TIME_REVIEW_REQUIRED", e.code) }
        core.closeOwner("page", "test-close")
        assertTrue(core.state.value.phase is AuthorityPhase.Review)
        assertEquals(listOf(retained), FileTimeIntentJournal(journal).loadAll())
        assertTrue(pending.exists())
    }

}
