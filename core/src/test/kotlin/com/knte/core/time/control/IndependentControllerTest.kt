package com.knte.core.time.control

import com.knte.core.time.repository.*
import com.knte.core.time.format.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.time.LocalDateTime
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.*

/** Real controller + real repository. Only clock and application journal are controlled.
 * Compiled in same Kotlin module: internal fixture access is NOT an external attacker claim.
 * All synthetic ledgers/journals retained under review.synthetic, no production mutation. */
class IndependentControllerTest {
    class Clock(var time: LocalDateTime = LocalDateTime.parse("2026-10-23T09:00")) : TimeClock {
        var callback: (() -> Unit)? = null
        override fun now(): LocalDateTime { callback?.also { callback = null }?.invoke(); return time }
    }
    class DiskJournal(val dir: File) : TimeIntentJournal {
        var failSave = false; var failClear = false; var failLoad = false
        init { dir.mkdirs() }
        override fun save(intent: TimePendingIntent) {
            if (failSave) error("INJECTED_SAVE_FAILURE")
            val i = intent
            val p = Properties()
            listOf("nonce" to i.nonce,"op" to i.op,"eventId" to i.eventId,"token" to i.token,"recordId" to i.recordId,"project" to i.project,"date" to i.date,"start" to i.start,"baselineRevision" to i.baselineRevision,"createdAt" to i.createdAt).forEach { p.setProperty(it.first,it.second) }
            i.end?.let { p.setProperty("end",it) }; i.endDate?.let { p.setProperty("endDate",it) }
            File(dir,i.nonce+".properties").outputStream().use { p.store(it,"synthetic app intent") }
        }
        override fun clear(nonce: String) {
            if (failClear) error("INJECTED_CLEAR_FAILURE")
            check(File(dir,nonce+".properties").delete())
        }
        override fun loadAll(): List<TimePendingIntent> {
            if (failLoad) error("INJECTED_LOAD_FAILURE")
            return dir.listFiles()!!.sortedBy { it.name }.map { f ->
                val p=Properties(); f.inputStream().use { p.load(it) }
                TimePendingIntent(p.getProperty("nonce"),p.getProperty("op"),p.getProperty("eventId"),p.getProperty("token"),p.getProperty("recordId"),p.getProperty("project"),p.getProperty("date"),p.getProperty("start"),p.getProperty("end"),p.getProperty("endDate"),p.getProperty("baselineRevision"),p.getProperty("createdAt"))
            }
        }
    }
    class F(val root: File) {
        val dir=File(root,"store"); val clock=Clock(); val src=TimeSessionSource.open(dir); val c=src.controller(clock)
        val j=DiskJournal(File(root,"app-journal"))
        fun raw()=TimeLedgerApi(IsolatedTimeRepository.open(dir))
        fun snap()=raw().snapshot()
        fun fresh()=TimeSessionSource.open(dir).controller(clock)
    }
    private fun f(name: String): F = F(Files.createTempDirectory(File(System.getProperty("review.synthetic") ?: System.getProperty("java.io.tmpdir")).toPath(),name+"-").toFile())
    private fun refusal(block: () -> Unit): String? = try { block(); null } catch(e: TimeControlException) { e.code }
    private fun absent(n: String="not-written")=TimePendingIntent(n,"start","e-absent","token-absent","t-absent","QA","2026-10-23","09:00",null,null,"baseline","2026-10-23T09:00")
    private fun clearFailureWritten(x:F): SessionHandle {
        x.c.setIntentJournal(x.j); x.j.failClear=true
        val h=x.c.prepareStart("QA")
        try { x.c.confirm(h); fail("clear fault must throw") } catch(e:IllegalStateException) { assertEquals("INJECTED_CLEAR_FAILURE",e.message) }
        assertEquals(1,x.snap().events.size); assertEquals(1,x.j.loadAll().size)
        x.j.failClear=false
        return h
    }

    @Test fun exactCloneSameNonceAndForeignHandlesRejectedBeforeWrite() {
        val x=f("identity"); val h=x.c.prepareStart("QA")
        val ctor=SessionHandle::class.java.declaredConstructors.single().apply { isAccessible=true }
        val clone=ctor.newInstance(h.instanceId,h.nonce,h.op,h.eventId,h.targetId,h.project,h.date,h.start,h.end,h.endDate,h.baselineRevision) as SessionHandle
        assertEquals("TIME_HANDLE_UNKNOWN",refusal { x.c.confirm(clone) }); assertEquals("TIME_HANDLE_UNKNOWN",refusal { x.c.cancel(clone) })
        val c2=x.fresh(); assertEquals("TIME_HANDLE_FOREIGN_INSTANCE",refusal { c2.confirm(h) })
        x.c.cancel(h); val replacement=x.c.prepareStart("QA")
        assertEquals("TIME_HANDLE_CANCELLED",refusal { x.c.confirm(h) }); assertNotEquals(h.nonce,replacement.nonce)
        assertSame(x.c,x.src.controller(Clock(LocalDateTime.parse("2026-01-01T00:00")))); assertEquals(0,x.snap().events.size)
        val r=x.c.confirm(replacement); assertEquals(replacement.eventId,r.eventId)
    }
    @Test fun concurrentSurfacesUseSingleControllerAndOneOperationGate() {
        val x=f("threads"); val pool=Executors.newFixedThreadPool(8); val latch=CountDownLatch(1)
        try {
            val tasks=(1..8).map { pool.submit(Callable { latch.await(); val c=x.src.controller(x.clock); assertSame(x.c,c); try { c.prepareStart("QA"); "issued" } catch(e:TimeControlException) { e.code } }) }
            latch.countDown(); val results=tasks.map { it.get(10,TimeUnit.SECONDS) }
            assertEquals(1,results.count { it=="issued" }); assertEquals(7,results.count { it=="TIME_CONTROL_UNRESOLVED_OP" }); assertEquals(0,x.snap().events.size)
        } finally { pool.shutdownNow() }
    }
    @Test fun saveFailureIsBeforeAnyLedgerWrite() {
        val x=f("save-fail"); x.c.setIntentJournal(x.j); x.j.failSave=true; val h=x.c.prepareStart("QA")
        try { x.c.confirm(h); fail("save fault must throw") } catch(e:IllegalStateException) { assertEquals("INJECTED_SAVE_FAILURE",e.message) }
        assertEquals(0,x.snap().events.size); assertFalse(File(x.dir,"timetrack.jsonl.pending").exists())
    }
    @Test fun clearFailureMustEnterReviewRatherThanReenterCommitAsFreshSuccess() {
        val x=f("clear-fail"); val h=clearFailureWritten(x)
        val review=x.c.viewSnapshot().reviewRequired
        val code=refusal { val r=x.c.confirm(h); println("CLEAR_RETRY: replayed=${r.replayed}, events=${x.snap().events.size}, journal=${x.j.loadAll().size}") }
        println("CLEAR_FAILURE: review=$review retryRefusal=$code")
        assertTrue("journal.clear failed after append but controller did not enter review",review)
        assertEquals("TIME_REVIEW_REQUIRED",code)
    }
    @Test fun realUnknownPendingCannotBeClearedOrResubmittedByRecoveryEnumsOrAck() {
        val x=f("real-unknown"); val src=TimeSessionSource.openWithFaultInjection(x.dir) { true }; val c=src.controller(x.clock); c.setIntentJournal(x.j)
        val h=c.prepareStart("QA"); assertEquals("TIME_WRITE_RESULT_UNKNOWN",refusal { c.confirm(h) })
        val pending=File(x.dir,"timetrack.jsonl.pending"); val history=File(x.dir,"timetrack.jsonl"); val pb=pending.readBytes(); val hb=history.readBytes()
        val c2=x.fresh(); c2.setIntentJournal(DiskJournal(x.j.dir)); val report=c2.recoverPendingIntents()
        assertTrue(report.manualReviewRequired)
        assertEquals("TIME_INTENT_UNKNOWN",refusal { c2.resolveRecoveredIntent(h.nonce,RecoveredIntentDecision.RESUBMIT_CONFIRM_REQUIRED) })
        assertEquals("TIME_INTENT_UNKNOWN",refusal { c2.resolveRecoveredIntent(h.nonce,RecoveredIntentDecision.DROP) })
        assertEquals("TIME_PENDING_REVIEW_REQUIRED",refusal { c2.acknowledgeReviewAfterHumanResolution() })
        assertEquals("TIME_REVIEW_REQUIRED",refusal { c2.prepareStart("QA") })
        assertArrayEquals(pb,pending.readBytes()); assertArrayEquals(hb,history.readBytes())
    }
    @Test fun mismatchedExistingEventMustNotBeDroppedOrUnlockResubmit() {
        val x=f("mismatch-resubmit"); val h=clearFailureWritten(x)
        // Legitimate later stop means current projection no longer matches original START.
        val api=x.raw(); val stop=api.planStop("later-stop",h.targetId,"09:01"); api.commit(stop,stop.confirmation)
        val c2=x.fresh(); c2.setIntentJournal(DiskJournal(x.j.dir)); val report=c2.recoverPendingIntents()
        println("MISMATCH_REPORT: $report")
        val code=refusal { c2.resolveRecoveredIntent(h.nonce,RecoveredIntentDecision.RESUBMIT_CONFIRM_REQUIRED) }
        if(code==null) { c2.confirm(c2.prepareStart("QA")); println("RESUBMIT_DUPLICATE: starts=${x.snap().events.count { it.op=="start" }} journal=${x.j.loadAll().size}") }
        assertTrue("existing event classified not-written and RESUBMIT cleared intent without proof",code!=null)
    }
    @Test fun tokenMismatchDropMustNotClearUnresolvedIntent() {
        val x=f("token-drop"); val h=clearFailureWritten(x); val original=x.j.loadAll().single(); x.j.save(original.copy(token="corrupted-token"))
        val c2=x.fresh(); c2.setIntentJournal(DiskJournal(x.j.dir)); val report=c2.recoverPendingIntents()
        val code=refusal { c2.resolveRecoveredIntent(h.nonce,RecoveredIntentDecision.DROP) }
        println("TOKEN_MISMATCH: manual=${report.manualReviewRequired} drop=$code journal=${x.j.loadAll().size} review=${c2.viewSnapshot().reviewRequired}")
        assertTrue("token mismatch DROP deleted unresolved intent",code!=null); assertEquals(1,x.j.loadAll().size)
    }
    @Test fun acknowledgeMustNotUnlockWhileDurableRecoveryIntentsRemain() {
        val x=f("ack-bypass"); x.j.save(absent()); val c=x.fresh(); c.setIntentJournal(x.j); c.recoverPendingIntents()
        val code=refusal { c.acknowledgeReviewAfterHumanResolution() }
        val review=c.viewSnapshot().reviewRequired
        val prepare=refusal { c.prepareStart("QA") }
        println("ACK_BYPASS: ack=$code review=$review prepare=$prepare journal=${x.j.loadAll().size}")
        assertTrue("ack unlocked without resolving/clearing durable intent",code!=null || review)
        assertEquals("TIME_REVIEW_REQUIRED",prepare)
    }
    @Test fun attachedPendingJournalMustGateBeforeAnyNewBusinessWrite() {
        val x=f("startup-gate"); x.j.save(absent()); val c=x.fresh(); c.setIntentJournal(DiskJournal(x.j.dir))
        val code=refusal { c.confirm(c.prepareStart("QA")) }
        println("ATTACH_PENDING: refusal=$code events=${x.snap().events.size} remaining=${x.j.loadAll().size}")
        assertNotNull("journal attached with pending intents but new write admitted before recovery",code)
    }
    @Test fun journalLoadFailureMustLatchReview() {
        val x=f("journal-load-fail"); x.j.save(absent()); val c=x.fresh(); c.setIntentJournal(x.j); x.j.failLoad=true
        try { c.recoverPendingIntents(); fail("expected load fault") } catch(e:IllegalStateException) { assertEquals("INJECTED_LOAD_FAILURE",e.message) }
        val code=refusal { c.prepareStart("QA") }; println("LOAD_FAILURE: review=${c.viewSnapshot().reviewRequired} prepare=$code")
        assertEquals("TIME_REVIEW_REQUIRED",code)
    }
    @Test fun recoveryReceiptMustMatchExactEventTargetAndOperationNotJustAnyRunningRecord() {
        val x=f("recovery-binding"); val h=clearFailureWritten(x); val api=x.raw(); val p=api.planStart("another-start","t-another","2026-10-23","08:59","Other"); api.commit(p,p.confirmation)
        val old=x.j.loadAll().single(); x.j.save(old.copy(recordId="t-another",project="Other",date="2000-01-01",start="00:00"))
        val c=x.fresh(); c.setIntentJournal(DiskJournal(x.j.dir)); val report=c.recoverPendingIntents()
        println("RECOVERY_WRONG_TARGET: $report actualEventTarget=${x.snap().findEvent(h.eventId)!!.text("id")}")
        assertTrue("returned confirmed for unrelated target using another event token",report.confirmed.isEmpty())
    }
    @Test fun stableReplayMustRetainHistoricalRunningAfterNotCurrentLaterStoppedState() {
        val x=f("stable-replay"); val h=x.c.prepareStart("QA"); val first=x.c.confirm(h); x.clock.time=x.clock.time.plusMinutes(1); x.c.confirm(x.c.prepareStop(h.targetId)); val again=x.c.confirm(h)
        assertEquals(2,x.snap().events.size); assertEquals(first.eventId,again.eventId)
        println("STABLE_REPLAY: first=$first again=$again")
        assertEquals("historical start receipt changed after later stop",first.runningAfter,again.runningAfter)
    }
    @Test fun stopHandleProjectAndBaselineMustDescribeSameSnapshot() {
        val x=f("stop-race"); val start=x.c.confirm(x.c.prepareStart("Before")); x.clock.time=x.clock.time.plusMinutes(1)
        x.clock.callback={ val api=x.raw(); val p=api.planRenameProject("race-rename","Before","After"); api.commit(p,p.confirmation) }
        var h: SessionHandle?=null
        val code=refusal { h=x.c.prepareStop(start.recordId) }
        if(code==null) {
            val r=x.c.confirm(h!!); val actual=x.snap().projection.find(start.recordId)!!
            println("STOP_SNAPSHOT_RACE: handleProject=${h!!.project} actualProject=${actual.project} confirmed=$r")
            assertEquals("stop approval binds old project with newer baseline",h!!.project,actual.project)
        }
    }
    @Test fun validAbsentIntentDropAndResubmitAreExplicitNonWritingResolutions() {
        for(decision in RecoveredIntentDecision.values()) {
            val x=f("absent-safe"); x.j.save(absent()); x.c.setIntentJournal(x.j); val report=x.c.recoverPendingIntents()
            assertEquals(listOf("not-written"),report.notWrittenNonces); assertFalse(report.manualReviewRequired)
            assertEquals("TIME_REVIEW_REQUIRED",refusal { x.c.prepareStart("QA") })
            x.c.resolveRecoveredIntent("not-written",decision); assertEquals(0,x.snap().events.size); assertEquals(0,x.j.loadAll().size)
            val h=x.c.prepareStart("QA"); x.c.cancel(h); assertEquals(0,x.snap().events.size)
        }
    }
    @Test fun provenRecoveryIsReadOnlyAndClearsOnlyExactIntent() {
        val x=f("recover-proven"); val h=clearFailureWritten(x); val before=File(x.dir,"timetrack.jsonl").readBytes()
        val c=x.fresh(); c.setIntentJournal(DiskJournal(x.j.dir)); val report=c.recoverPendingIntents()
        assertEquals(h.eventId,report.confirmed.single().eventId); assertTrue(report.confirmed.single().replayed); assertEquals(0,x.j.loadAll().size)
        assertArrayEquals(before,File(x.dir,"timetrack.jsonl").readBytes())
    }
    @Test fun immutablePlanAndValidationKeepClosedMinuteSchemaAndUnverifiedTrust() {
        val x=f("schema"); val tags=mutableListOf("old"); val h=x.c.prepareStart("QA",tags=tags); tags[0]="new"; x.c.confirm(h)
        val e=x.snap().events.single(); assertEquals("unverified",e.atTrust); assertEquals(listOf("old"),e.textList("tags")); assertEquals("2026-10-23T09:00:00",e.ts)
        assertFalse(e.encode().contains("duration")); assertTrue(e.unknownKeys.isEmpty())
        assertEquals("TIME_PLAN_START_FAILED",refusal { x.c.prepareStart("") }); assertNull(x.c.viewSnapshot().unresolvedOp)
    }
}
