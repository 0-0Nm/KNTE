package com.knte.core.time.control

import com.knte.core.time.repository.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Final-probe regressions committed into the repo test tree (CI-visible). The five cases are
 *  the exact scenarios of the independent final review probe: the two defects N1 (post-
 *  reconcile confirm gate for late-attached journals) and N2 (a stale absence proof must not
 *  clear an intent whose event another writer has meanwhile committed), plus the three passing
 *  controls. Fixture reuses the committed IndependentControllerTest.F. Synthetic files go to
 *  review.synthetic with a standard java.io.tmpdir fallback. */
class FinalProbe5 {
    private fun fixture(name: String) =
        IndependentControllerTest.F(
            Files.createTempDirectory(
                File(System.getProperty("review.synthetic") ?: System.getProperty("java.io.tmpdir")).toPath(),
                name + "-"
            ).toFile()
        )
    private fun absent() = TimePendingIntent(
        "pending-old", "start", "old-event", "old-token", "old-record", "Old",
        "2026-10-23", "09:00", null, null, "baseline", "2026-10-23T09:00"
    )
    private fun code(f: () -> Unit): String? = try { f(); null } catch (e: TimeControlException) { e.code }

    /** N1 regression: a journal attached AFTER prepareStart must gate the already-issued
     *  handle at confirm time, after the fresh classification, before any business write. */
    @Test fun lateAttachMustGateAlreadyPreparedConfirm() {
        val x = fixture("late-attach-"); val h = x.c.prepareStart("New")
        x.j.save(absent()); x.c.setIntentJournal(x.j)
        val refusal = code { x.c.confirm(h) }
        println("LATE_ATTACH refusal=$refusal events=${x.snap().events.size} journal=${x.j.loadAll().map { it.nonce }} review=${x.c.viewSnapshot().reviewRequired}")
        assertNotNull("attached unresolved intent must block confirm, even if handle predates attach", refusal)
        assertEquals(0, x.snap().events.size)
    }

    /** N2 regression: recover classified the intent absent, then a REAL second writer commits
     *  that exact plan; every enum decision must re-reconcile and refuse to clear. */
    @Test fun staleAbsenceClassificationMustNotClearAnEventNowWritten() {
        val refusals = mutableListOf<String?>()
        for (d in RecoveredIntentDecision.values()) {
            val x = fixture("stale-absence-"); val api = x.raw()
            val p = api.planStart("event-planned", "t-planned", "2026-10-23", "09:00", "Old")
            val intent = TimePendingIntent(
                "planned-nonce", "start", "event-planned", p.token, "t-planned", "Old",
                "2026-10-23", "09:00", null, null, p.baselineRevision, "2026-10-23T09:00"
            )
            x.j.save(intent); x.c.setIntentJournal(x.j)
            assertEquals(listOf(intent.nonce), x.c.recoverPendingIntents().notWrittenNonces)
            api.commit(p, p.confirmation)
            val refusal = code { x.c.resolveRecoveredIntent(intent.nonce, d) }
            println("STALE_ABSENCE decision=$d refusal=$refusal events=${x.snap().events.size} journal=${x.j.loadAll().size} review=${x.c.viewSnapshot().reviewRequired}")
            refusals += refusal
        }
        assertTrue("must reclassify, not clear using an obsolete absence proof: $refusals", refusals.all { it != null })
    }

    @Test fun journalCannotBeReplacedOrDetachedButSameInstanceAttachIsIdempotent() {
        val x = fixture("journal-identity-"); x.c.setIntentJournal(x.j); x.c.setIntentJournal(x.j)
        assertEquals("TIME_JOURNAL_ALREADY_ATTACHED", code { x.c.setIntentJournal(InMemoryIntentJournal()) })
        assertEquals("TIME_JOURNAL_ALREADY_ATTACHED", code { x.c.setIntentJournal(null) }); assertTrue(x.c.hasIntentJournal)
    }

    @Test fun clearFailureThenAckThenReplayIsReadOnly() {
        val x = fixture("clear-ack-"); x.c.setIntentJournal(x.j); val h = x.c.prepareStart("Old"); x.j.failClear = true
        try { x.c.confirm(h); fail("expected clear failure") } catch (e: IllegalStateException) { assertEquals("INJECTED_CLEAR_FAILURE", e.message) }
        assertTrue(x.c.viewSnapshot().reviewRequired); val bytes = File(x.dir, "timetrack.jsonl").readBytes()
        assertEquals("TIME_REVIEW_REQUIRED", code { x.c.confirm(h) })
        x.j.failClear = false; assertFalse(x.c.acknowledgeReviewAfterHumanResolution().reviewRequired)
        val r = x.c.confirm(h); assertTrue(r.replayed); assertTrue(r.runningAfter)
        assertArrayEquals(bytes, File(x.dir, "timetrack.jsonl").readBytes()); assertTrue(x.j.loadAll().isEmpty())
    }

    @Test fun journalLoadFailureNeedsExplicitAckEvenAfterRecoverySucceeds() {
        val x = fixture("load-ack-"); x.c.setIntentJournal(x.j); x.j.failLoad = true
        try { x.c.recoverPendingIntents(); fail("load must throw") } catch (e: IllegalStateException) { }
        x.j.failLoad = false; x.c.recoverPendingIntents()
        assertEquals("TIME_REVIEW_REQUIRED", code { x.c.prepareStart("New") })
        assertFalse(x.c.acknowledgeReviewAfterHumanResolution().reviewRequired)
        x.c.cancel(x.c.prepareStart("New")); assertTrue(x.snap().events.isEmpty())
    }
}
