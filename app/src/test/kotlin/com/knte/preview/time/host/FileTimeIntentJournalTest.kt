package com.knte.preview.time.host

import com.knte.core.time.control.RecoveredIntentDecision
import com.knte.core.time.control.TimeClock
import com.knte.core.time.control.TimeControlException
import com.knte.core.time.control.TimeIntentJournal
import com.knte.core.time.control.TimePendingIntent
import com.knte.core.time.control.TimeSessionSource
import com.knte.core.time.repository.IsolatedTimeRepository
import com.knte.core.time.repository.TimeLedgerApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.UUID

/**
 * Real-JVM tests for [FileTimeIntentJournal]: real journal + the fixed TimeSessionController +
 * the real IsolatedTimeRepository on real temp filesystems (java.io.tmpdir fallback, never a
 * pre-existing shared dir). No InMemory stand-in: every controller test runs against the real
 * file journal, with a thin recording decorator only where call ORDER must be observed.
 */
class FileTimeIntentJournalTest {

    // ---- fixtures -----------------------------------------------------------

    private fun fixture(test: (File) -> Unit) {
        val base = System.getProperty("review.synthetic")?.takeIf { it.isNotBlank() }?.let { File(it) }
        val root = if (base != null) {
            Files.createTempDirectory(base.toPath(), "knte-intent-journal-").toFile()
        } else {
            Files.createTempDirectory("knte-intent-journal-").toFile() // java.io.tmpdir fallback
        }
        try { test(root) } finally { root.deleteRecursively() }
    }

    /** Journal root is a SIBLING of the ledger root, never inside it. */
    private fun journalDir(root: File) = File(root, "isolated-time-intents-v1")
    private fun storeDir(root: File) = File(root, "store")

    private class FakeClock(var current: LocalDateTime) : TimeClock {
        override fun now(): LocalDateTime = current
    }

    private fun startIntent(nonce: String = UUID.randomUUID().toString()) = TimePendingIntent(
        nonce = nonce, op = "start",
        eventId = UUID.randomUUID().toString(), token = "a".repeat(64),
        recordId = "t-" + UUID.randomUUID().toString().replace("-", ""),
        project = "晨读", date = "2026-10-23", start = "09:00",
        end = null, endDate = null,
        baselineRevision = "0".repeat(64), createdAt = "2026-10-23T09:00"
    )

    private fun stopIntent(nonce: String = UUID.randomUUID().toString()) = TimePendingIntent(
        nonce = nonce, op = "stop",
        eventId = UUID.randomUUID().toString(), token = "b".repeat(64),
        recordId = "t-" + UUID.randomUUID().toString().replace("-", ""),
        project = "晨读", date = "2026-10-23", start = "09:00",
        end = "10:15", endDate = null,
        baselineRevision = "1".repeat(64), createdAt = "2026-10-23T10:15"
    )

    private fun rejectsJournalCode(code: String, block: () -> Unit) {
        try { block(); fail("expected $code") }
        catch (e: TimeIntentJournalException) { assertEquals(code, e.code) }
    }

    private fun rejectsCode(code: String, block: () -> Unit) {
        try { block(); fail("expected $code") }
        catch (e: TimeControlException) { assertEquals(code, e.code) }
    }

    private fun snapshot(store: File) = TimeLedgerApi(IsolatedTimeRepository.open(store)).snapshot()

    /** Recording decorator over the REAL journal: observes call order while all persistence
     *  behaviour stays in the real FileTimeIntentJournal underneath. */
    private class RecordingJournal(
        val real: FileTimeIntentJournal,
        val onSave: (TimePendingIntent) -> Unit = {},
        val onClear: (String) -> Unit = {}
    ) : TimeIntentJournal {
        val calls = mutableListOf<String>()
        override fun save(intent: TimePendingIntent) {
            real.save(intent); calls += "save:${intent.nonce}"; onSave(intent)
        }
        override fun clear(nonce: String) {
            real.clear(nonce); calls += "clear:$nonce"; onClear(nonce)
        }
        override fun loadAll(): List<TimePendingIntent> = real.loadAll()
    }

    // ---- 1. durable roundtrip / reopen --------------------------------------

    @Test
    fun saveRoundtripKeepsEveryFieldAndNullSemantics() = fixture { root ->
        val j = FileTimeIntentJournal(journalDir(root))
        val start = startIntent()
        val stop = stopIntent()
        j.save(start)
        j.save(stop)
        val loaded = j.loadAll()
        assertEquals(listOf(start, stop).sortedBy { it.nonce }, loaded)
        assertEquals(start, loaded.single { it.op == "start" })
        assertEquals(null, loaded.single { it.op == "start" }.end)
        assertEquals(null, loaded.single { it.op == "start" }.endDate)
        assertEquals("10:15", loaded.single { it.op == "stop" }.end)
        // nonce is bound to the file name
        assertTrue(File(journalDir(root), "intent-${start.nonce}.json").isFile)
    }

    @Test
    fun secondInstanceReopenReadsPersistedBytesFromDisk() = fixture { root ->
        val dir = journalDir(root)
        val intent = startIntent()
        FileTimeIntentJournal(dir).save(intent)
        val reopened = FileTimeIntentJournal(dir) // fresh object, no shared memory
        assertEquals(listOf(intent), reopened.loadAll())
        reopened.clear(intent.nonce)
        assertTrue(FileTimeIntentJournal(dir).loadAll().isEmpty())
    }

    // ---- 2. nonce binding / conflict / idempotence --------------------------

    @Test
    fun sameNonceSameContentIsIdempotent() = fixture { root ->
        val j = FileTimeIntentJournal(journalDir(root))
        val intent = startIntent()
        j.save(intent)
        j.save(intent) // identical replay: no-op
        assertEquals(listOf(intent), j.loadAll())
    }

    @Test
    fun sameNonceDifferentContentIsRefusedAndOriginalKept() = fixture { root ->
        val dir = journalDir(root)
        val j = FileTimeIntentJournal(dir)
        val original = startIntent()
        j.save(original)
        rejectsJournalCode("TIME_JOURNAL_NONCE_CONFLICT") {
            j.save(original.copy(token = "f".repeat(64)))
        }
        assertEquals(listOf(original), j.loadAll()) // never overwritten
        val raw = String(File(dir, "intent-${original.nonce}.json").readBytes(), Charsets.UTF_8)
        assertTrue(raw.contains("a".repeat(64)))
    }

    @Test
    fun unsafeNonceIsRejectedForSaveAndClear() = fixture { root ->
        val j = FileTimeIntentJournal(journalDir(root))
        rejectsJournalCode("TIME_JOURNAL_NONCE_INVALID") { j.save(startIntent(nonce = "../x")) }
        rejectsJournalCode("TIME_JOURNAL_NONCE_INVALID") { j.save(startIntent(nonce = "")) }
        rejectsJournalCode("TIME_JOURNAL_NONCE_INVALID") { j.clear("..") }
        rejectsJournalCode("TIME_JOURNAL_NONCE_INVALID") { j.clear("a/b") }
    }

    // ---- 3. path / symlink safety -------------------------------------------

    @Test
    fun symlinkedRootIsRejected() = fixture { root ->
        val real = File(root, "real-journal")
        val link = File(root, "link-journal")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        rejectsJournalCode("TIME_JOURNAL_LINK_REJECTED") { FileTimeIntentJournal(link) }
    }

    @Test
    fun symlinkEntryInsideJournalFailsClosed() = fixture { root ->
        val dir = journalDir(root)
        val j = FileTimeIntentJournal(dir)
        j.save(startIntent())
        val outside = File(root, "outside.txt").apply { writeText("x") }
        Files.createSymbolicLink(File(dir, "intent-${UUID.randomUUID()}.json").toPath(), outside.toPath())
        rejectsJournalCode("TIME_JOURNAL_LINK_REJECTED") { j.loadAll() }
        rejectsJournalCode("TIME_JOURNAL_LINK_REJECTED") { j.save(stopIntent()) }
    }

    // ---- 4. corruption / unknown / leftover: fail closed, nothing deleted ---

    @Test
    fun corruptUtf8EntryFailsClosedAndIsPreserved() = fixture { root ->
        val dir = journalDir(root)
        val j = FileTimeIntentJournal(dir)
        val bad = File(dir, "intent-${UUID.randomUUID()}.json")
        bad.writeBytes(byteArrayOf(0xC3.toByte(), 0x28)) // invalid UTF-8
        rejectsJournalCode("TIME_JOURNAL_CORRUPT") { j.loadAll() }
        rejectsJournalCode("TIME_JOURNAL_CORRUPT") { j.save(startIntent()) } // read-before-write blocked
        assertTrue(bad.isFile) // never silently deleted
    }

    @Test
    fun corruptJsonAndWrongShapeFailClosed() = fixture { root ->
        val dir = journalDir(root)
        val j = FileTimeIntentJournal(dir)
        val broken = File(dir, "intent-${UUID.randomUUID()}.json")
        broken.writeText("{\"nonce\":")
        rejectsJournalCode("TIME_JOURNAL_CORRUPT") { j.loadAll() }
        assertTrue(broken.isFile)
        broken.delete()
        val notObject = File(dir, "intent-${UUID.randomUUID()}.json")
        notObject.writeText("[1,2,3]")
        rejectsJournalCode("TIME_JOURNAL_CORRUPT") { j.loadAll() }
        assertTrue(notObject.isFile)
    }

    @Test
    fun unknownOpAndFieldViolationsFailClosed() = fixture { root ->
        val dir = journalDir(root)
        val j = FileTimeIntentJournal(dir)
        val unknownOp = File(dir, "intent-${UUID.randomUUID()}.json")
        val nonce = unknownOp.name.removePrefix("intent-").removeSuffix(".json")
        unknownOp.writeText(
            """{"nonce":"$nonce","op":"pause","eventId":"e","token":"t","recordId":"r",""" +
                """"project":"p","date":"2026-10-23","start":"09:00","end":null,"endDate":null,""" +
                """"baselineRevision":"b","createdAt":"c"}"""
        )
        rejectsJournalCode("TIME_JOURNAL_CORRUPT") { j.loadAll() }
        assertTrue(unknownOp.isFile)
    }

    @Test
    fun nonceFilenameMismatchFailsClosed() = fixture { root ->
        val dir = journalDir(root)
        val j = FileTimeIntentJournal(dir)
        val file = File(dir, "intent-${UUID.randomUUID()}.json")
        val other = UUID.randomUUID().toString()
        file.writeText(
            """{"nonce":"$other","op":"start","eventId":"e","token":"t","recordId":"r",""" +
                """"project":"p","date":"2026-10-23","start":"09:00","end":null,"endDate":null,""" +
                """"baselineRevision":"b","createdAt":"c"}"""
        )
        rejectsJournalCode("TIME_JOURNAL_NONCE_MISMATCH") { j.loadAll() }
        assertTrue(file.isFile)
    }

    @Test
    fun foreignEntryFailsClosed() = fixture { root ->
        val dir = journalDir(root)
        val j = FileTimeIntentJournal(dir)
        File(dir, "README.txt").writeText("foreign")
        rejectsJournalCode("TIME_JOURNAL_FOREIGN_ENTRY") { j.loadAll() }
    }

    @Test
    fun crashLeftoverTempBlocksReadsAndWritesAndIsPreserved() = fixture { root ->
        val dir = journalDir(root)
        val j = FileTimeIntentJournal(dir)
        val good = startIntent()
        j.save(good)
        val leftover = File(dir, "intent-${UUID.randomUUID()}.json.tmp-deadbeef")
        leftover.writeText("partial-bytes-from-crash")
        rejectsJournalCode("TIME_JOURNAL_LEFTOVER_TEMP") { j.loadAll() }
        rejectsJournalCode("TIME_JOURNAL_LEFTOVER_TEMP") { j.save(stopIntent()) }
        assertTrue(leftover.isFile) // preserved for a human, never auto-deleted by name
        leftover.delete() // human resolution
        assertEquals(listOf(good), j.loadAll())
    }

    // ---- 5. budgets: refuse, never trim -------------------------------------

    @Test
    fun capacityRefusesNewIntentWithoutTrimming() = fixture { root ->
        val j = FileTimeIntentJournal(journalDir(root), maxIntents = 2, maxEntryBytes = 4096)
        val a = startIntent(); val b = stopIntent()
        j.save(a); j.save(b)
        rejectsJournalCode("TIME_JOURNAL_CAPACITY") { j.save(startIntent()) }
        assertEquals(listOf(a, b).sortedBy { it.nonce }, j.loadAll())
    }

    @Test
    fun oversizeAndOverlongFieldsAreRefusedNotTrimmed() = fixture { root ->
        val dir = journalDir(root)
        val j = FileTimeIntentJournal(dir, maxIntents = 8, maxEntryBytes = 4096)
        // project limit follows the project rule (100 chars)
        rejectsJournalCode("TIME_JOURNAL_FIELD_INVALID") {
            j.save(startIntent().copy(project = "长".repeat(101)))
        }
        // generic field bound
        rejectsJournalCode("TIME_JOURNAL_FIELD_INVALID") {
            j.save(startIntent().copy(token = "t".repeat(300)))
        }
        // encoded entry over the byte budget
        val tight = FileTimeIntentJournal(dir, maxIntents = 8, maxEntryBytes = 512)
        rejectsJournalCode("TIME_JOURNAL_ENTRY_TOO_LARGE") {
            tight.save(startIntent().copy(project = "p".repeat(90)))
        }
        // an oversize file planted on disk fails the read closed
        val planted = File(dir, "intent-${UUID.randomUUID()}.json")
        planted.writeBytes(ByteArray(5000) { 'x'.code.toByte() })
        rejectsJournalCode("TIME_JOURNAL_ENTRY_TOO_LARGE") { j.loadAll() }
        assertTrue(planted.isFile)
    }

    // ---- 6. clear semantics --------------------------------------------------

    @Test
    fun clearRemovesOnlyTheExactNonceTarget() = fixture { root ->
        val dir = journalDir(root)
        val j = FileTimeIntentJournal(dir)
        val a = startIntent(); val b = stopIntent()
        j.save(a); j.save(b)
        j.clear(a.nonce)
        assertEquals(listOf(b), j.loadAll())
        assertFalse(File(dir, "intent-${a.nonce}.json").exists())
        assertTrue(File(dir, "intent-${b.nonce}.json").isFile)
    }

    @Test
    fun clearOfMissingNonceIsDocumentedNoOp() = fixture { root ->
        val j = FileTimeIntentJournal(journalDir(root))
        j.clear(UUID.randomUUID().toString()) // absent: idempotent, no throw
        val intent = startIntent()
        j.save(intent)
        j.clear(intent.nonce)
        j.clear(intent.nonce) // repeated clear still a no-op
        assertTrue(j.loadAll().isEmpty())
    }

    @Test
    fun clearRefusesCorruptTargetInsteadOfDeleting() = fixture { root ->
        val dir = journalDir(root)
        val j = FileTimeIntentJournal(dir)
        val nonce = UUID.randomUUID().toString()
        val corrupt = File(dir, "intent-$nonce.json")
        corrupt.writeText("not json")
        rejectsJournalCode("TIME_JOURNAL_CORRUPT") { j.clear(nonce) }
        assertTrue(corrupt.isFile) // fail closed: nothing deleted
    }

    @Test
    fun clearNeverTouchesTheLedger() = fixture { root ->
        val store = storeDir(root)
        val dir = journalDir(root)
        val c = TimeSessionSource.open(store).controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        val h = c.prepareStart(project = "晨读")
        c.confirm(h)
        val before = snapshot(store).revision
        val j = FileTimeIntentJournal(dir)
        val intent = startIntent()
        j.save(intent)
        j.clear(intent.nonce)
        assertEquals(before, snapshot(store).revision) // ledger bytes untouched
        assertEquals(1, snapshot(store).events.size)
    }

    // ---- 7. controller integration over the REAL journal ---------------------

    @Test
    fun controllerSavesDurableIntentBeforeLedgerWriteAndClearsAfterProvenCommit() = fixture { root ->
        val store = storeDir(root)
        val dir = journalDir(root)
        val real = FileTimeIntentJournal(dir)
        lateinit var saved: TimePendingIntent
        val spy = RecordingJournal(real, onSave = { intent ->
            saved = intent
            // durable BEFORE the write: a second instance on the same root reads it from disk
            assertEquals(listOf(intent), FileTimeIntentJournal(dir).loadAll())
            // and the ledger does NOT contain the event yet
            assertEquals(null, snapshot(store).findEvent(intent.eventId))
        }, onClear = { nonce ->
            assertEquals(saved.nonce, nonce)
            // clear happens only AFTER the event is really committed
            assertNotEquals(null, snapshot(store).findEvent(saved.eventId))
        })
        val c = TimeSessionSource.open(store).controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        c.setIntentJournal(spy)
        assertTrue(c.hasIntentJournal)
        val h = c.prepareStart(project = "晨读")
        val r = c.confirm(h)
        assertFalse(r.replayed)
        assertEquals(listOf("save:${h.nonce}", "clear:${h.nonce}"), spy.calls)
        assertTrue(real.loadAll().isEmpty())
        assertTrue(FileTimeIntentJournal(dir).loadAll().isEmpty()) // reopen agrees
        assertEquals(1, snapshot(store).events.size)
    }

    @Test
    fun recoveryRetainsIntentWhileLedgerPendingThenConfirmsReadOnly() = fixture { root ->
        val store = storeDir(root)
        val dir = journalDir(root)
        val real = FileTimeIntentJournal(dir)
        lateinit var saved: TimePendingIntent
        val journal = RecordingJournal(real, onSave = { saved = it })
        val c = TimeSessionSource.open(store).controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        c.setIntentJournal(journal)
        val h = c.prepareStart(project = "晨读")
        c.confirm(h)
        val revision = snapshot(store).revision
        // Model the durable post-append, pre-proof state using this synthetic store.
        // Fault injection during commit itself is covered in :core, where the hook is internal.
        val pending = File(store, "timetrack.jsonl.pending")
        val attempt = File(store, ".journal").listFiles()!!.single()
        pending.writeText(attempt.name + "\n")
        real.save(saved)
        val c2 = TimeSessionSource.open(store).controller(FakeClock(LocalDateTime.parse("2026-10-23T09:10")))
        c2.setIntentJournal(FileTimeIntentJournal(dir))
        val gated = c2.recoverPendingIntents()
        assertTrue(gated.manualReviewRequired)
        assertTrue(gated.confirmed.isEmpty())
        assertTrue(gated.notWrittenNonces.isEmpty())
        assertEquals(listOf(saved), real.loadAll())
        rejectsCode("TIME_REVIEW_REQUIRED") { c2.prepareStart(project = "其他") }
        assertTrue(pending.exists())
        // Test-only simulation of human resolution. Recovery must not append or retry.
        assertTrue(pending.delete())
        val report = c2.recoverPendingIntents()
        assertEquals(1, report.confirmed.size)
        assertTrue(report.confirmed.single().replayed)
        assertEquals(h.eventId, report.confirmed.single().eventId)
        assertTrue(report.notWrittenNonces.isEmpty())
        assertFalse(report.manualReviewRequired)
        assertTrue(FileTimeIntentJournal(dir).loadAll().isEmpty())
        assertEquals(1, snapshot(store).events.size) // exactly one event, no double write
        assertEquals(revision, snapshot(store).revision)
    }

    @Test
    fun unreadableJournalBlocksControllerWritesUntilHumanResolution() = fixture { root ->
        val store = storeDir(root)
        val dir = journalDir(root)
        val real = FileTimeIntentJournal(dir)
        val corrupt = File(dir, "intent-${UUID.randomUUID()}.json")
        corrupt.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        val c = TimeSessionSource.open(store).controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        c.setIntentJournal(real)
        rejectsCode("TIME_REVIEW_REQUIRED") { c.prepareStart(project = "晨读") }
        assertTrue(c.viewSnapshot().reviewRequired)
        assertEquals(0, snapshot(store).events.size) // nothing was written
        // human removes the corrupt artifact and explicitly acknowledges
        assertTrue(corrupt.delete())
        val view = c.acknowledgeReviewAfterHumanResolution()
        assertFalse(view.reviewRequired)
        val h = c.prepareStart(project = "晨读")
        c.confirm(h)
        assertEquals(1, snapshot(store).events.size)
    }

    @Test
    fun recoveryOfNotWrittenIntentNeverAutoWritesAndBlocksUntilDecision() = fixture { root ->
        val store = storeDir(root)
        val dir = journalDir(root)
        // crash window: intent durable, ledger write never happened
        FileTimeIntentJournal(dir).save(startIntent().let { it.copy(baselineRevision = "0".repeat(64)) })
        val saved = FileTimeIntentJournal(dir).loadAll().single()
        val c = TimeSessionSource.open(store).controller(FakeClock(LocalDateTime.parse("2026-10-23T09:05")))
        c.setIntentJournal(FileTimeIntentJournal(dir))
        val report = c.recoverPendingIntents()
        assertEquals(listOf(saved.nonce), report.notWrittenNonces)
        assertFalse(report.manualReviewRequired)
        assertTrue(report.confirmed.isEmpty())
        assertEquals(0, snapshot(store).events.size) // recovery is read-only
        rejectsCode("TIME_REVIEW_REQUIRED") { c.prepareStart(project = "其他") } // blocked until decision
        c.resolveRecoveredIntent(saved.nonce, RecoveredIntentDecision.DROP)
        assertTrue(FileTimeIntentJournal(dir).loadAll().isEmpty())
        assertEquals(0, snapshot(store).events.size) // DROP writes nothing either
        val h = c.prepareStart(project = "晨读") // fresh explicit op now allowed
        c.confirm(h)
        assertEquals(1, snapshot(store).events.size)
    }

    @Test
    fun recoveryResubmitClearsIntentButStillRequiresFreshConfirm() = fixture { root ->
        val store = storeDir(root)
        val dir = journalDir(root)
        FileTimeIntentJournal(dir).save(startIntent())
        val saved = FileTimeIntentJournal(dir).loadAll().single()
        val c = TimeSessionSource.open(store).controller(FakeClock(LocalDateTime.parse("2026-10-23T09:05")))
        c.setIntentJournal(FileTimeIntentJournal(dir))
        val report = c.recoverPendingIntents()
        assertEquals(listOf(saved.nonce), report.notWrittenNonces)
        c.resolveRecoveredIntent(saved.nonce, RecoveredIntentDecision.RESUBMIT_CONFIRM_REQUIRED)
        assertTrue(FileTimeIntentJournal(dir).loadAll().isEmpty())
        assertEquals(0, snapshot(store).events.size) // no automatic replay ever
        val h = c.prepareStart(project = "晨读")
        assertNotEquals(saved.eventId, h.eventId) // brand-new op, not a replay
        c.confirm(h)
        assertEquals(1, snapshot(store).events.size)
    }

    @Test
    fun recoveryConfirmsCrashBetweenCommitAndClearReadOnly() = fixture { root ->
        val store = storeDir(root)
        val dir = journalDir(root)
        val real = FileTimeIntentJournal(dir)
        lateinit var captured: TimePendingIntent
        val spy = RecordingJournal(real, onSave = { captured = it })
        val c1 = TimeSessionSource.open(store).controller(FakeClock(LocalDateTime.parse("2026-10-23T09:00")))
        c1.setIntentJournal(spy)
        val h = c1.prepareStart(project = "晨读")
        val r1 = c1.confirm(h)
        assertTrue(real.loadAll().isEmpty())
        // crash window: the clear never ran; the durable intent is still on disk
        real.save(captured)
        val c2 = TimeSessionSource.open(store).controller(FakeClock(LocalDateTime.parse("2026-10-23T09:10")))
        c2.setIntentJournal(FileTimeIntentJournal(dir))
        val report = c2.recoverPendingIntents()
        assertEquals(1, report.confirmed.size)
        val confirmed = report.confirmed.single()
        assertTrue(confirmed.replayed)
        assertEquals(r1.eventId, confirmed.eventId)
        assertEquals(r1.token, confirmed.token)
        assertTrue(confirmed.runningAfter)
        assertTrue(report.notWrittenNonces.isEmpty())
        assertFalse(report.manualReviewRequired)
        assertTrue(FileTimeIntentJournal(dir).loadAll().isEmpty()) // cleared read-only
        assertEquals(1, snapshot(store).events.size) // no second append
    }

    @Test
    fun stopIntentFullRoundtripThroughControllerAndJournal() = fixture { root ->
        val store = storeDir(root)
        val dir = journalDir(root)
        val clock = FakeClock(LocalDateTime.parse("2026-10-23T23:30"))
        val c = TimeSessionSource.open(store).controller(clock)
        c.setIntentJournal(FileTimeIntentJournal(dir))
        val hs = c.prepareStart(project = "夜读")
        c.confirm(hs)
        clock.current = LocalDateTime.parse("2026-10-24T00:45")
        val hp = c.prepareStop(hs.targetId)
        c.confirm(hp)
        assertTrue(FileTimeIntentJournal(dir).loadAll().isEmpty())
        val snap = snapshot(store)
        assertEquals(2, snap.events.size)
        val rec = snap.projection.find(hs.targetId)!!
        assertFalse(rec.running())
        assertEquals(75L, rec.minutes())
    }
}
