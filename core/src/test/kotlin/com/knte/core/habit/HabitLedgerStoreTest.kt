package com.knte.core.habit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime

/** Real temp-store tests: create → confirm → check → same-day correction → reopen → archive/restore. */
class HabitLedgerStoreTest {

    private fun fixture(test: (File) -> Unit) {
        val root = Files.createTempDirectory("knte-habit-store-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }

    private fun repo(root: File) = HabitLedgerStore.open(File(root, "habit"))

    private fun api(root: File) = HabitLedgerApi(repo(root))

    private fun commit(api: HabitLedgerApi, plan: HabitAppendPlan) = api.commit(plan, plan.confirmation)

    @Test fun createCheckCorrectReopenArchiveRestore() = fixture { root ->
        val r = repo(root)
        assertTrue(r.load().events().isEmpty())
        // create
        val created = commit(r.toApi(), r.toApi().planCreate("h-11111111", "晨读", 21,
            "r-10000001", "2026-10-01T08:00:00"))
        assertEquals("晨读", created.identity("h-11111111")!!.name)
        assertEquals(21, created.identity("h-11111111")!!.targetDays)
        assertFalse(created.identity("h-11111111")!!.archived)
        // check done=true, then done=false, then a same-day correction that does not rewrite history
        commit(r.toApi(), r.toApi().planCheck("h-11111111", "2026-10-05", true, null,
            "r-10000002", "2026-10-05T07:30:00"))
        commit(r.toApi(), r.toApi().planCheck("h-11111111", "2026-10-06", true, null,
            "r-10000003", "2026-10-06T07:30:00"))
        val failed = commit(r.toApi(), r.toApi().planCheck("h-11111111", "2026-10-07", false, "睡过头",
            "r-10000004", "2026-10-07T07:30:00"))
        assertEquals(HabitMark.FAILED, failed.mark("h-11111111", LocalDate.of(2026, 10, 7)))
        val corrected = commit(r.toApi(), r.toApi().planCheck("h-11111111", "2026-10-07", true, "补记",
            "r-10000005", "2026-10-07T07:35:00"))
        assertEquals(HabitMark.DONE, corrected.mark("h-11111111", LocalDate.of(2026, 10, 7)))
        assertEquals(1, corrected.sameDayCorrectionCount)
        assertEquals(3, corrected.streak("h-11111111", LocalDate.of(2026, 10, 7)))
        // Reopen: same bytes, same derived state, and the superseded done=false line still exists.
        val reopened = repo(root).load()
        assertArrayEquals(corrected.bytes(), reopened.bytes())
        assertEquals(corrected.revision, reopened.revision)
        assertEquals(HabitMark.DONE, reopened.mark("h-11111111", LocalDate.of(2026, 10, 7)))
        assertEquals(4, reopened.events().count { it.op == "check" })
        assertTrue(reopened.events().any {
            it.op == "check" && it.check!!.date == "2026-10-07" && !it.check!!.done &&
                it.check!!.note == "睡过头"
        })
        // archive → checks are refused; restore → checks work again
        commit(r.toApi(), r.toApi().planArchive("h-11111111", true, "r-10000006", "2026-10-08T09:00:00"))
        val archived = r.load()
        assertTrue(archived.identity("h-11111111")!!.archived)
        assertTrue(archived.identities(includeArchived = false).isEmpty())
        assertTrue(err { r.toApi().planCheck("h-11111111", "2026-10-09", true) }
            .contains("HABIT_ARCHIVED"))
        commit(r.toApi(), r.toApi().planArchive("h-11111111", false, "r-10000007", "2026-10-08T09:05:00"))
        assertFalse(r.load().identity("h-11111111")!!.archived)
        val afterRestore = commit(r.toApi(), r.toApi().planCheck("h-11111111", "2026-10-09", true, null,
            "r-10000008", "2026-10-09T07:30:00"))
        assertEquals(HabitMark.DONE, afterRestore.mark("h-11111111", LocalDate.of(2026, 10, 9)))
        // Archiving never deleted a row: all 8 events are still on disk.
        assertEquals(8, r.load().eventCount)
    }

    @Test fun createEditKeepsCreatedTsAndAppendsDefineOnly() = fixture { root ->
        val api = api(root)
        commit(api, api.planCreate("h-22222222", "旧名", 7, "r-20000001", "2026-10-01T08:00:00"))
        val bytes = File(root, "habit/db/habits.jsonl").readBytes()
        commit(api, api.planEdit("h-22222222", "新名", 30, "r-20000002", "2026-10-02T08:00:00"))
        val after = File(root, "habit/db/habits.jsonl").readBytes()
        assertArrayEquals(bytes, after.copyOfRange(0, bytes.size)) // append-only
        val snapshot = api.snapshot()
        assertEquals("新名", snapshot.identity("h-22222222")!!.name)
        assertEquals(30, snapshot.identity("h-22222222")!!.targetDays)
        assertEquals("2026-10-01T08:00:00", snapshot.identity("h-22222222")!!.createdTs)
        assertEquals(2, snapshot.events().size)
    }

    @Test fun duplicateCreateAndUnknownHabitFailBeforeAnyWrite() = fixture { root ->
        val api = api(root)
        commit(api, api.planCreate("h-33333333", "x", 7, "r-30000001", "2026-10-01T08:00:00"))
        val bytes = api.snapshot().bytes().copyOf()
        // A second create of the same id is refused, never silently turned into an edit.
        assertTrue(err { api.planCreate("h-33333333", "y", 7, "r-30000002", "2026-10-01T09:00:00") }
            .contains("HABIT_CREATE_CONFLICT"))
        // A create without name/target is refused.
        assertTrue(err { repo(root).planDefine("h-33333334", true, null, 7, false, "r-30000003", "2026-10-01T09:00:00") }
            .contains("HABIT_CREATE_FIELDS_REQUIRED"))
        // Editing or archiving an unknown habit is refused.
        assertTrue(err { api.planEdit("h-99999999", "y") }.contains("HABIT_NOT_FOUND"))
        assertTrue(err { api.planArchive("h-99999999", true) }.contains("HABIT_NOT_FOUND"))
        // Checks of unknown habits or invalid fields fail before any write.
        assertTrue(err { api.planCheck("h-99999999", "2026-10-05", true) }.contains("HABIT_NOT_FOUND"))
        assertTrue(err { api.planCheck("bad-id", "2026-10-05", true) }.contains("HABIT_ID_INVALID"))
        assertTrue(err { api.planCheck("h-33333333", "2026-13-01", true) }.contains("DATE"))
        assertTrue(err { api.planCheck("h-33333333", "2026-10-05", true, "x".repeat(2001)) }
            .contains("TEXT_TOO_LONG"))
        assertArrayEquals(bytes, api.snapshot().bytes())
        assertEquals(1, api.snapshot().identities().size)
    }

    @Test fun cancellationWritesNothingAndBlocksConfirmation() = fixture { root ->
        val api = api(root)
        val plan = api.planCreate("h-44444444", "x", 7, "r-40000001", "2026-10-01T08:00:00")
        assertFalse(File(root, "habit/db/habits.jsonl").exists())
        api.cancel(plan)
        assertTrue(err { api.commit(plan, plan.confirmation) }.contains("PLAN_CANCELLED"))
        assertTrue(err { api.cancel(plan) }.contains("PLAN_CANCELLED"))
        assertTrue(api.snapshot().events().isEmpty())
        assertFalse(File(root, "habit/db/habits.jsonl.pending").exists())
    }

    @Test fun wrongConfirmationIsRejectedAndBindsHashes() = fixture { root ->
        val api = api(root)
        val plan = api.planCreate("h-55555555", "x", 7, "r-50000001", "2026-10-01T08:00:00")
        assertTrue(err { api.commit(plan, "not-the-confirmation") }.contains("HABIT_CONFIRMATION_MISMATCH"))
        assertTrue(api.snapshot().events().isEmpty())
        commit(api, plan)
        val next = api.planCreate("h-55555556", "y", 7, "r-50000002", "2026-10-02T08:00:00")
        val other = api.planCreate("h-55555557", "z", 7, "r-50000003", "2026-10-02T08:00:00")
        assertFalse(next.confirmation == other.confirmation)
        assertFalse(next.afterRevision == other.afterRevision)
        // A plan built on a stale baseline carries a different confirmation and cannot be confirmed.
        val stale = api.planCreate("h-55555558", "w", 7, "r-50000004", "2026-10-03T08:00:00")
        commit(api, next)
        assertTrue(err { api.commit(stale, stale.confirmation) }.contains("HABIT_BASELINE_CHANGED"))
        assertTrue(err { api.commit(stale, next.confirmation) }.contains("HABIT_CONFIRMATION_MISMATCH"))
    }

    @Test fun externalDriftBlocksWriteWithoutClobbering() = fixture { root ->
        val api = api(root)
        commit(api, api.planCreate("h-66666666", "x", 7, "r-60000001", "2026-10-01T08:00:00"))
        val plan = api.planCheck("h-66666666", "2026-10-05", true, null, "r-60000002", "2026-10-05T07:30:00")
        val foreign = File(root, "habit/db/habits.jsonl")
        foreign.appendBytes("""{"event_id":"r-60000099","op":"check","id":"h-66666666","name":"x","date":"2026-10-04","done":true,"streak":1,"stage":"种子","ts":"2026-10-04T07:30:00","at_trust":"unverified"}""".toByteArray())
        foreign.appendBytes("\n".toByteArray())
        assertTrue(err { api.commit(plan, plan.confirmation) }.contains("HABIT_BASELINE_CHANGED"))
        // The foreign bytes are untouched and still readable.
        val snapshot = repo(root).load()
        assertTrue(snapshot.events().any { it.eventId == "r-60000099" })
        assertEquals(2, snapshot.eventCount)
    }

    @Test fun pendingMarkerBlocksReadsAndIsNeverAutoDeleted() = fixture { root ->
        val store = File(root, "habit/db")
        store.mkdirs()
        val pending = File(store, "habits.jsonl.pending")
        pending.writeText("partial-write-marker")
        assertTrue(err { repo(root).load() }.contains("HABIT_PENDING_REVIEW_REQUIRED"))
        assertEquals("partial-write-marker", pending.readText())
        pending.delete() // a human decision, never an automatic recovery
        assertTrue(repo(root).load().events().isEmpty())
    }

    @Test fun unknownStoreEntryAndSymlinkAreRefused() = fixture { root ->
        val api = api(root)
        commit(api, api.planCreate("h-77777777", "x", 7, "r-70000001", "2026-10-01T08:00:00"))
        val store = File(root, "habit/db")
        val foreign = File(store, "foreign.txt"); foreign.writeText("unknown")
        assertTrue(err { repo(root).load() }.contains("HABIT_DB_ENTRY_UNEXPECTED"))
        foreign.delete()
        val ledgerBytes = File(store, "habits.jsonl").readBytes()
        val target = Files.createTempFile("knte-habit-link-", ".jsonl").toFile()
        val link = File(store, "linked-copy.jsonl").toPath()
        try {
            Files.createSymbolicLink(link, target.toPath())
            assertTrue(err { repo(root).load() }.contains("HABIT_STORE_LINK_REJECTED"))
            assertArrayEquals(ledgerBytes, File(store, "habits.jsonl").readBytes())
        } finally {
            Files.deleteIfExists(link)
            target.delete()
        }
    }

    @Test fun cancelledPlanCannotCommitEvenAfterDiskDrift() = fixture { root ->
        val api = api(root)
        val plan = api.planCreate("h-dddddddd", "x", 7, "r-d0000001", "2026-10-01T08:00:00")
        api.cancel(plan)
        val store = File(root, "habit/db")
        store.mkdirs()
        val ledger = File(store, "habits.jsonl")
        ledger.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(err { api.commit(plan, plan.confirmation) }.contains("PLAN_CANCELLED"))
        assertTrue(err { api.commit(plan, "not-the-confirmation") }.contains("PLAN_CANCELLED"))
        assertArrayEquals(byteArrayOf(1, 2, 3), ledger.readBytes())
        assertFalse(File(store, "habits.jsonl.pending").exists())
    }

    @Test fun pendingMarkerBlocksEvenAConfirmedReplay() = fixture { root ->
        val api = api(root)
        val plan = api.planCreate("h-eeeeeeee", "x", 7, "r-e0000001", "2026-10-01T08:00:00")
        commit(api, plan)
        val store = File(root, "habit/db")
        val pending = File(store, "habits.jsonl.pending")
        pending.writeText("human-review-marker")
        assertTrue(err { api.commit(plan, plan.confirmation) }
            .contains("HABIT_PENDING_REVIEW_REQUIRED"))
        assertEquals("human-review-marker", pending.readText())
        pending.delete() // explicit human decision, never an automatic recovery
        assertEquals(1, repo(root).load().eventCount)
    }

    @Test fun journalBeforeImageIsKeptForAudit() = fixture { root ->
        val api = api(root)
        commit(api, api.planCreate("h-88888888", "x", 7, "r-80000001", "2026-10-01T08:00:00"))
        val before = api.snapshot().bytes().copyOf()
        commit(api, api.planCheck("h-88888888", "2026-10-05", true, null, "r-80000002", "2026-10-05T07:30:00"))
        val attempts = File(root, "habit/db/.journal").listFiles()!!
        assertTrue(attempts.any { it.resolve("before.bin").readBytes().contentEquals(before) })
        assertTrue(attempts.any { it.resolve("receipt.txt").exists() })
    }

    @Test fun idempotentReplayOfAPublishedEventIsNotASecondWrite() = fixture { root ->
        val api = api(root)
        val plan = api.planCreate("h-99999999", "x", 7, "r-90000001", "2026-10-01T08:00:00")
        val first = commit(api, plan)
        val again = commit(api, plan)
        assertEquals(first.revision, again.revision)
        assertEquals(1, again.eventCount)
        // A second create of the same id is a conflict, never a second row.
        assertTrue(err { api.planCreate("h-99999999", "x", 7, "r-90000002", "2026-10-01T08:00:00") }
            .contains("HABIT_CREATE_CONFLICT"))
    }

    @Test fun everyNewAppendDeclaresDeviceClockUnverified() = fixture { root ->
        val api = api(root)
        val snapshot = commit(api, api.planCreate("h-aaaa1111", "x", 7, "r-a1000001", "2026-10-01T08:00:00"))
        assertEquals("unverified", snapshot.events().single().atTrust)
        assertTrue(snapshot.events().single().encode().contains("\"at_trust\":\"unverified\""))
    }

    @Test fun apiProjectionsMatchStoreAndSurviveReopen() = fixture { root ->
        val api = api(root)
        commit(api, api.planCreate("h-bbbb1111", "x", 21, "r-b1000001", "2026-10-01T08:00:00"))
        commit(api, api.planCheck("h-bbbb1111", "2026-10-05", true, null, "r-b1000002", "2026-10-05T07:30:00"))
        commit(api, api.planCheck("h-bbbb1111", "2026-10-06", true, null, "r-b1000003", "2026-10-06T07:30:00"))
        val today = LocalDate.of(2026, 10, 6)
        assertEquals(2, api.currentStreak("h-bbbb1111", today))
        assertEquals("种子", api.stage("h-bbbb1111", today))
        val heat = api.heat("h-bbbb1111", today, 28)
        assertEquals(28, heat.size)
        assertEquals(HabitMark.DONE, heat.last())
        assertEquals(HabitMark.UNKNOWN, heat.first())
        val reopened = api(root)
        assertEquals(api.currentStreak("h-bbbb1111", today), reopened.currentStreak("h-bbbb1111", today))
        assertEquals(api.snapshot().revision, reopened.snapshot().revision)
    }

    @Test fun unknownStateAfterWriteIsNeverAutoRetried() = fixture { root ->
        val store = File(root, "habit/db")
        val faulty = HabitLedgerStore.openForFaultInjection(File(root, "habit")) { true }
        val api = HabitLedgerApi(faulty)
        val plan = api.planCreate("h-cccc1111", "x", 7, "r-c1000001", "2026-10-01T08:00:00")
        try {
            api.commit(plan, plan.confirmation)
            throw AssertionError("expected unknown state")
        } catch (e: HabitWriteUnknownStateException) {
            assertTrue(e.message!!.contains("HABIT_WRITE_RESULT_UNKNOWN"))
        }
        val pending = File(store, "habits.jsonl.pending")
        assertTrue(pending.exists())
        // No retry, no automatic recovery: reads stay blocked until a human clears the marker.
        assertTrue(err { faulty.load() }.contains("HABIT_PENDING_REVIEW_REQUIRED"))
        assertTrue(err { api.commit(plan, plan.confirmation) }.contains("HABIT_PENDING_REVIEW_REQUIRED"))
        assertTrue(File(store, ".journal").listFiles()!!.any { it.resolve("before.bin").exists() })
    }

    @Test fun corruptFileShapeIsRefusedNotRepaired() = fixture { root ->
        val store = File(root, "habit/db")
        store.mkdirs()
        val ledger = File(store, "habits.jsonl")
        ledger.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(err { repo(root).load() }.isNotEmpty())
        assertArrayEquals(byteArrayOf(1, 2, 3), ledger.readBytes())
        // A missing trailing LF means the last line is incomplete.
        ledger.writeBytes("""{"event_id":"r-d1000001","op":"define","habit":{"id":"h-dddd1111","name":"x","target_days":7,"created_ts":"2026-10-01T08:00:00","archived":false},"ts":"2026-10-01T08:00:00","at_trust":"unverified"}""".toByteArray())
        assertTrue(err { repo(root).load() }.contains("HABIT_FILE_UNTERMINATED_LAST_LINE"))
    }

    @Test fun defaultTimestampComesFromCallerClockNotFromCodec() {
        val plan = HabitCodec.stamp(LocalDateTime.of(2026, 10, 5, 7, 30, 0))
        assertEquals("2026-10-05T07:30:00", plan)
    }

    private fun HabitLedgerStore.toApi() = HabitLedgerApi(this)

    private fun err(block: () -> Unit): String =
        try {
            block()
            "NO_ERROR"
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
}
