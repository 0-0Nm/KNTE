package com.knte.core.time.control

import com.knte.core.time.repository.IsolatedTimeRepository
import com.knte.core.time.repository.TimeLedgerApi
import com.knte.core.time.repository.TimeWriteUnknownStateException
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.UUID

/**
 * Real temp-file repository tests for the shared timer session controller: one unresolved op,
 * handle identity/nonce binding, cancel semantics, cross-instance rejection, forged fields,
 * review/pending/unknown conservatism, recovery, readback fault, clock boundary behaviour.
 */
class TimeSessionControllerTest {

    private fun fixture(test: (File) -> Unit) {
        val root = Files.createTempDirectory("knte-time-control-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }

    private fun source(dir: File) = TimeSessionSource.open(dir)

    private class FakeClock(var current: LocalDateTime) : TimeClock {
        override fun now(): LocalDateTime = current
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("expected TimeControlException") }
        catch (e: TimeControlException) { /* expected */ }
    }

    private fun rejectsCode(code: String, block: () -> Unit) {
        try { block(); fail("expected $code") }
        catch (e: TimeControlException) { assertEquals(code, e.code) }
    }

    private fun storeDir(root: File) = File(root, "store")

    private fun pendingFile(root: File) = File(storeDir(root), "timetrack.jsonl.pending")

    // ---- 1. start/stop receipt + independent reopen ------------------------

    @Test
    fun startConfirmProducesNewEventIdAndRunningRecordVerifiedByReadback() = fixture { root ->
        val src = source(storeDir(root))
        val c = src.controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        val h = c.prepareStart(project = "晨读")
        assertEquals("start", h.op)
        assertEquals("晨读", h.project)
        assertEquals("2026-10-23", h.date)
        assertEquals("09:00", h.start)
        assertTrue(h.eventId.isNotEmpty())
        val r = c.confirm(h)
        assertFalse(r.replayed)
        assertEquals("start", r.op)
        assertTrue(r.runningAfter)
        // Independent reopen: real bytes on disk contain the real new event id.
        val reopened = TimeLedgerApi(IsolatedTimeRepository.open(storeDir(root))).snapshot()
        assertEquals(1, reopened.events.size)
        assertEquals(r.eventId, reopened.events.single().eventId)
        assertEquals(r.token, reopened.events.single().token())
        assertEquals("t-", reopened.projection.records.single().id.take(2))
        assertTrue(reopened.projection.find(r.recordId)!!.running())
        assertEquals("晨读", reopened.projection.records.single().project)
    }

    @Test
    fun stopConfirmVerifiesExactTargetAndProjectAndCrossDayFields() = fixture { root ->
        val src = source(storeDir(root))
        val clock = FakeClock(LocalDateTime.parse("2026-10-23T23:30"))
        val c = src.controller(clock)
        val hs = c.prepareStart(project = "夜读")
        c.confirm(hs)
        clock.current = LocalDateTime.parse("2026-10-24T00:45")
        val hp = c.prepareStop(hs.targetId)
        assertEquals("stop", hp.op)
        assertEquals("2026-10-24", hp.endDate) // cross-day: end_date = stop day
        assertEquals("00:45", hp.end)
        assertEquals(hs.targetId, hp.targetId)
        val r = c.confirm(hp)
        assertTrue(!r.runningAfter)
        val reopened = TimeLedgerApi(IsolatedTimeRepository.open(storeDir(root))).snapshot()
        assertEquals(2, reopened.events.size)
        val rec = reopened.projection.find(hs.targetId)!!
        assertFalse(rec.running())
        assertEquals(75L, rec.minutes())
        assertEquals("夜读", rec.project)
    }

    @Test
    fun repeatedConfirmOfProvenCommitReturnsStableResultWithoutSecondAppend() = fixture { root ->
        val src = source(storeDir(root))
        val c = src.controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        val h = c.prepareStart(project = "晨读")
        val first = c.confirm(h)
        val again = c.confirm(h)
        assertTrue(again.replayed)
        assertEquals(first.eventId, again.eventId)
        assertEquals(first.token, again.token)
        assertEquals(first.recordId, again.recordId)
        val reopened = TimeLedgerApi(IsolatedTimeRepository.open(storeDir(root))).snapshot()
        assertEquals(1, reopened.events.size) // no duplicate append
    }

    // ---- 2. one unresolved op / double prepare ------------------------------

    @Test
    fun doublePrepareStartIsRefusedAndProducesNoSecondId() = fixture { root ->
        val src = source(storeDir(root))
        val c = src.controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        val h1 = c.prepareStart(project = "晨读")
        rejectsCode("TIME_CONTROL_UNRESOLVED_OP") { c.prepareStart(project = "午读") }
        // Only the first op can resolve; ledger untouched, then a new prepare works after confirm.
        val r = c.confirm(h1)
        assertEquals(1, TimeLedgerApi(IsolatedTimeRepository.open(storeDir(root))).snapshot().events.size)
        val h2 = c.prepareStart(project = "午读") // explicitly intended parallel record: allowed
        val r2 = c.confirm(h2)
        val snap = TimeLedgerApi(IsolatedTimeRepository.open(storeDir(root))).snapshot()
        assertEquals(2, snap.events.size)
        assertTrue(snap.projection.find(r.recordId)!!.running())
        assertTrue(snap.projection.find(r2.recordId)!!.running())
        assertNotEquals(r.recordId, r2.recordId)
    }

    @Test
    fun prepareAfterCancelWithSameParamsIssuesFreshHandleAndOldHandleStaysDead() = fixture { root ->
        val src = source(storeDir(root))
        val c = src.controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        val h1 = c.prepareStart(project = "晨读")
        c.cancel(h1)
        val h2 = c.prepareStart(project = "晨读") // same params: fresh handle, new nonce
        assertNotEquals(h1.nonce, h2.nonce)
        rejectsCode("TIME_HANDLE_CANCELLED") { c.confirm(h1) }
        val r = c.confirm(h2)
        assertTrue(r.runningAfter)
        val snap = TimeLedgerApi(IsolatedTimeRepository.open(storeDir(root))).snapshot()
        assertEquals(1, snap.events.size)
        assertEquals(r.eventId, snap.events.single().eventId)
    }

    // ---- 3. handle identity / instance / forgery ---------------------------

    @Test
    fun crossInstanceConfirmAndCancelAreRejectedWriteBeforeAnySideEffect() = fixture { root ->
        val dir = storeDir(root)
        val src = source(dir)
        val c1 = src.controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        val h1 = c1.prepareStart(project = "晨读")
        // Second controller over the SAME directory with the SAME params (simulated by two sources? source is singleton) —
        // Use two controllers via two sources over the same directory: source is per-instance; open twice is same label.
        val srcB = TimeSessionSource.open(dir)
        val c2 = srcB.controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        assertNotEquals(c1.instanceId, c2.instanceId)
        rejectsCode("TIME_HANDLE_FOREIGN_INSTANCE") { c2.confirm(h1) }
        rejectsCode("TIME_HANDLE_FOREIGN_INSTANCE") { c2.cancel(h1) }
        // Nothing written by the foreign attempts; c1's op still resolvable.
        assertEquals(0, TimeLedgerApi(IsolatedTimeRepository.open(dir)).snapshot().events.size)
        val r = c1.confirm(h1)
        assertTrue(r.runningAfter)
    }

    @Test
    fun forgedHandleFieldsAreRejectedBeforeAnyWrite() = fixture { root ->
        val dir = storeDir(root)
        val c = source(dir).controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        val h = c.prepareStart(project = "晨读")
        // Forge: copy public fields with a different instanceId/nonce into a fake handle object.
        val forgedCtor = SessionHandle::class.java.getDeclaredConstructor(
            String::class.java, String::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java
        ).apply { isAccessible = true }
        val forged = forgedCtor.newInstance(
            "forged-instance", h.nonce, "start", "evil-event", "t-evil",
            "晨读", "2026-10-23", "09:00", null, null, "deadbeef") as SessionHandle
        rejectsCode("TIME_HANDLE_FOREIGN_INSTANCE") { c.confirm(forged) }
        rejectsCode("TIME_HANDLE_FOREIGN_INSTANCE") { c.cancel(forged) }
        assertEquals(0, TimeLedgerApi(IsolatedTimeRepository.open(dir)).snapshot().events.size)
    }

    // ---- 4. multi running: explicit target, wrong id refused ---------------

    @Test
    fun multipleRunningStopRequiresExplicitIdAndWrongTargetIsRefused() = fixture { root ->
        val dir = storeDir(root)
        val c = source(dir).controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        val a = c.confirm(c.prepareStart(project = "任务A"))
        val b = c.confirm(c.prepareStart(project = "任务B"))
        assertNotEquals(a.recordId, b.recordId)
        val view = c.reload()
        assertEquals(2, view.runningCount)
        rejectsCode("TIME_TARGET_NOT_RUNNING") { c.prepareStop("t-nonexistent") }
        val rs = c.confirm(c.prepareStop(b.recordId))
        assertFalse(rs.runningAfter)
        val snap = TimeLedgerApi(IsolatedTimeRepository.open(dir)).snapshot()
        assertEquals(3, snap.events.size)
        assertTrue(snap.projection.find(a.recordId)!!.running())
        assertFalse(snap.projection.find(b.recordId)!!.running())
        assertEquals("任务B", snap.projection.find(b.recordId)!!.project)
    }

    // ---- 5. baseline drift -------------------------------------------------

    @Test
    fun baselineDriftBetweenPrepareAndConfirmIsRejectedWithZeroSideEffects() = fixture { root ->
        val dir = storeDir(root)
        val c = source(dir).controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        val h = c.prepareStart(project = "晨读")
        // External writer changes the ledger under our feet.
        val raw = TimeLedgerApi(IsolatedTimeRepository.open(dir))
        raw.commit(raw.planStart("ext-1", "t-ext", "2026-10-23", "09:05", "外部写入"), raw.planStart("ext-1", "t-ext", "2026-10-23", "09:05", "外部写入").confirmation)
        rejectsCode("TIME_BASELINE_CHANGED") { c.confirm(h) }
        // Nothing of our op landed; external event is the only one.
        val snap = TimeLedgerApi(IsolatedTimeRepository.open(dir)).snapshot()
        assertEquals(1, snap.events.size)
        assertEquals("ext-1", snap.events.single().eventId)
        // A definite pre-write rejection leaves the op unresolved: the explicit cleanup path
        // (cancel) must still work, then the handle is permanently dead.
        c.cancel(h)
        rejectsCode("TIME_HANDLE_CANCELLED") { c.confirm(h) }
    }

    // ---- 6. pending / review conservatism ----------------------------------

    @Test
    fun pendingMarkerPutsControllerInReviewAndBusinessOpsAreRefusedWithoutUnlock() = fixture { root ->
        val dir = storeDir(root)
        val c = source(dir).controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        val h = c.prepareStart(project = "晨读")
        c.cancel(h)
        pendingFile(root).writeText("manual-marker")
        // Read-only views never unlock.
        val v = c.viewSnapshot()
        assertTrue(v.reviewRequired)
        assertEquals("TIME_PENDING_REVIEW_REQUIRED", v.readError)
        rejectsCode("TIME_PENDING_REVIEW_REQUIRED") { c.reload() }
        rejectsCode("TIME_REVIEW_REQUIRED") { c.prepareStart(project = "午读") }
        // Even after human deletes the marker, the controller does NOT auto-unlock business ops
        // until the explicit acknowledgement.
        pendingFile(root).delete()
        // A failed strict reload has flagged the controller: the view stays conservative even
        // though the marker is gone, until the explicit acknowledgement.
        val v2 = c.viewSnapshot()
        assertTrue(v2.reviewRequired)
        rejectsCode("TIME_REVIEW_REQUIRED") { c.prepareStart(project = "午读") }
        val v3 = c.acknowledgeReviewAfterHumanResolution()
        assertFalse(v3.reviewRequired)
        val h3 = c.prepareStart(project = "午读") // now allowed
        c.cancel(h3)
    }

    // ---- 7. unknown write result / readback fault --------------------------

    @Test
    fun readbackFaultDuringConfirmEntersReviewAndNeverRetries() = fixture { root ->
        val dir = storeDir(root)
        var fault = false
        val src = TimeSessionSource.openWithFaultInjection(dir) { fault }
        val c = src.controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        val h = c.prepareStart(project = "晨读")
        fault = true // readback fault applies only to the write verification phase
        rejectsCode("TIME_WRITE_RESULT_UNKNOWN") { c.confirm(h) }
        // Pending marker exists: store unreadable, review required.
        assertTrue(pendingFile(root).exists())
        val v = c.viewSnapshot()
        assertTrue(v.reviewRequired)
        rejectsCode("TIME_REVIEW_REQUIRED") { c.prepareStart(project = "午读") }
        // Repeating confirm on the unknown handle re-enters commit and hits the pending refusal.
        rejects { c.confirm(h) }
        assertTrue(pendingFile(root).exists())
    }

    @Test
    fun readbackFaultThenHumanClearsPendingThenExplicitAcknowledgeResumes() = fixture { root ->
        val dir = storeDir(root)
        var fault = false
        val src = TimeSessionSource.openWithFaultInjection(dir) { fault }
        val c = src.controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        val h = c.prepareStart(project = "晨读")
        fault = true
        rejectsCode("TIME_WRITE_RESULT_UNKNOWN") { c.confirm(h) }
        // Simulate human review: the append actually landed on disk.
        pendingFile(root).delete()
        val snap = TimeLedgerApi(IsolatedTimeRepository.open(dir)).snapshot()
        assertEquals(1, snap.events.size) // the event really did land
        // Without acknowledge, business ops stay refused.
        rejectsCode("TIME_REVIEW_REQUIRED") { c.prepareStart(project = "午读") }
        c.acknowledgeReviewAfterHumanResolution()
        val h2 = c.prepareStart(project = "午读")
        c.cancel(h2)
    }

    // ---- 8. write-ahead intent + process recovery --------------------------

    @Test
    fun processDeathAfterWriteBeforeReceiptIsRecoveredFromDurableJournal() = fixture { root ->
        val dir = storeDir(root)
        val journal = InMemoryIntentJournal()
        var fault = false
        val src = TimeSessionSource.openWithFaultInjection(dir) { fault }
        val c = src.controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        c.setIntentJournal(journal)
        val h = c.prepareStart(project = "晨读")
        fault = true
        // The write lands on disk, but the receipt/readback is unconfirmed: the durable intent
        // stays saved (write-ahead), exactly like a process death right after the append.
        rejectsCode("TIME_WRITE_RESULT_UNKNOWN") { c.confirm(h) }
        assertTrue(pendingFile(root).exists())
        assertTrue(journal.loadAll().size == 1)
        // Human review: the pending marker is cleared; the append is actually on disk.
        pendingFile(root).delete()
        val freshJournal = InMemoryIntentJournal()
        journal.loadAll().forEach { freshJournal.save(it) }
        val c2 = TimeSessionSource.open(dir).controller(FakeClock(LocalDateTime.parse("2026-10-23T09:10")))
        c2.setIntentJournal(freshJournal)
        val report = c2.recoverPendingIntents()
        assertEquals(1, report.confirmed.size)
        assertTrue(report.confirmed.single().replayed)
        assertEquals(h.eventId, report.confirmed.single().eventId)
        assertTrue(report.notWrittenNonces.isEmpty())
        assertFalse(report.manualReviewRequired)
        assertEquals(0, freshJournal.loadAll().size) // cleared after proof
        // New business op now allowed (no unresolved leftover).
        val h2 = c2.prepareStart(project = "午读")
        c2.cancel(h2)
    }

    @Test
    fun intentNotWrittenIsReportedAndBlocksUntilExplicitResolution() = fixture { root ->
        val dir = storeDir(root)
        val journal = InMemoryIntentJournal()
        val c = source(dir).controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        c.setIntentJournal(journal)
        // Plant an intent for a write that never happened (process died before commit).
        journal.save(TimePendingIntent(
            nonce = "n-lost", op = "start", eventId = "e-lost", token = "tok",
            recordId = "t-lost", project = "丢失", date = "2026-10-23", start = "09:00",
            end = null, endDate = null, baselineRevision = "whatever", createdAt = "2026-10-23T09:00"
        ))
        val c2 = TimeSessionSource.open(dir).controller(FakeClock(LocalDateTime.parse("2026-10-23T09:10")))
        c2.setIntentJournal(journal)
        val report = c2.recoverPendingIntents()
        assertEquals(listOf("n-lost"), report.notWrittenNonces)
        assertTrue(report.confirmed.isEmpty())
        rejectsCode("TIME_REVIEW_REQUIRED") { c2.prepareStart(project = "晨读") }
        // DROP resolves it; block lifts without any write.
        c2.resolveRecoveredIntent("n-lost", RecoveredIntentDecision.DROP)
        val h = c2.prepareStart(project = "晨读")
        c2.cancel(h)
    }

    @Test
    fun recoverWithoutJournalReturnsEmptyReportAndDoesNotFakeSafety() = fixture { root ->
        val dir = storeDir(root)
        val c = source(dir).controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        val report = c.recoverPendingIntents()
        assertTrue(report.confirmed.isEmpty())
        assertTrue(report.notWrittenNonces.isEmpty())
        assertFalse(report.manualReviewRequired)
    }

    // ---- 9. clock boundaries ------------------------------------------------

    @Test
    fun clockBoundariesSameMinuteStartAndStopAreAccepted() = fixture { root ->
        val dir = storeDir(root)
        val c = source(dir).controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        val h = c.prepareStart(project = "晨读")
        c.confirm(h)
        // Stop at the same minute: core allows end == begin (end not before begin).
        val hs = c.prepareStop(h.targetId)
        assertEquals("09:00", hs.end)
        val r = c.confirm(hs)
        assertFalse(r.runningAfter)
        assertEquals(0L, TimeLedgerApi(IsolatedTimeRepository.open(dir)).snapshot().projection.find(h.targetId)!!.minutes())
    }

    @Test
    fun clockResetBackwardsStillProducesMinuteGrainEventsFromInjectedClock() = fixture { root ->
        val dir = storeDir(root)
        val clock = FakeClock(LocalDateTime.parse("2026-10-23T10:00"))
        val c = source(dir).controller(clock)
        val h = c.confirm(c.prepareStart(project = "晨读"))
        clock.current = LocalDateTime.parse("2026-10-23T09:30") // clock moved backwards
        // core rejects an end before the recorded begin at plan time: explicit refusal, no
        // schema gymnastics, nothing issued to confirm.
        rejectsCode("TIME_END_BEFORE_START") { c.prepareStop(h.recordId) }
        clock.current = LocalDateTime.parse("2026-10-23T10:05")
        val ok = c.confirm(c.prepareStop(h.recordId))
        assertFalse(ok.runningAfter)
        assertEquals(5L, TimeLedgerApi(IsolatedTimeRepository.open(dir)).snapshot().projection.find(h.recordId)!!.minutes())
    }

    @Test
    fun longClockEdgeMinuteFieldsComeFromClockNotWallTime() = fixture { root ->
        val dir = storeDir(root)
        // 23:59:59.5 start → minute field 23:59; stop next day 00:00 → cross-day.
        val clock = FakeClock(LocalDateTime.parse("2026-10-23T23:59:59.5"))
        val c = source(dir).controller(clock)
        val h = c.prepareStart(project = "夜读")
        assertEquals("23:59", h.start)
        assertEquals("2026-10-23", h.date)
        c.confirm(h)
        clock.current = LocalDateTime.parse("2026-10-24T00:00:00.5")
        val hs = c.prepareStop(h.targetId)
        assertEquals("00:00", hs.end)
        assertEquals("2026-10-24", hs.endDate)
        val r = c.confirm(hs)
        assertFalse(r.runningAfter)
        val rec = TimeLedgerApi(IsolatedTimeRepository.open(dir)).snapshot().projection.find(h.targetId)!!
        assertEquals(1L, rec.minutes())
    }

    // ---- 10. regression: original core413 suite runs once against new tree --

    @Test
    fun sourceFactoryIsSingletonPerDirectoryAndLabelIsRealCanonicalPath() = fixture { root ->
        val dir = storeDir(root)
        val src = source(dir)
        val c1 = src.controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        val c2 = src.controller(SystemTimeClock())
        assertSame(c1, c2)
        assertEquals(dir.canonicalPath, src.label)
        assertFalse(src.label.contains("fake"))
    }

    @Test
    fun displayModelIsMonotonicWithinBootAndClampsNegative() {
        var t = 1000L
        val m = SessionDisplayModel { t }
        val anchor = m.anchor()
        t += 6500L
        assertEquals(6500L, m.elapsedMillis(anchor))
        t -= 9000L // never trusted across boots / clock jumps
        assertEquals(0L, m.elapsedMillis(anchor))
    }
}
