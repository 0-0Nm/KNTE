package com.knte.core.time

import com.knte.core.time.format.TimeEvent
import com.knte.core.time.format.TimeEventCodec
import com.knte.core.time.format.TimeJson
import com.knte.core.time.projection.TimeProjectionBuilder
import com.knte.core.time.repository.IsolatedTimeRepository
import com.knte.core.time.repository.TimeLedgerApi
import com.knte.core.time.repository.TimeWriteUnknownStateException
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime

/** Real temp-file repository tests: start/stop/reopen, amend, rename, conflicts, pending, cancel. */
class IsolatedTimeRepositoryTest {

    private fun fixture(test: (File) -> Unit) {
        val root = Files.createTempDirectory("knte-time-test-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }

    private fun repo(root: File) = IsolatedTimeRepository.open(File(root, "timetrack"))

    private fun startPlan(api: TimeLedgerApi, eid: String, id: String, date: String,
                          at: String, project: String, tags: List<String> = emptyList(),
                          note: String? = null) =
        api.planStart(eid, id, date, at, project, tags, note)

    private fun commit(api: TimeLedgerApi, plan: com.knte.core.time.repository.TimeAppendPlan) =
        api.commit(plan, plan.confirmation)

    @Test fun startStopReopenKeepsEventLogAndDerivesRecord() = fixture { root ->
        val r = repo(root)
        assertTrue(r.load().events.isEmpty())
        val p1 = startPlan(r.toApi(), "e-1", "t-1", "2026-10-23", "09:00", "晨读", listOf("语文"), "早读")
        commit(r.toApi(), p1)
        val afterStart = r.load()
        assertEquals(1, afterStart.events.size)
        assertTrue(afterStart.projection.find("t-1")!!.running())
        val p2 = r.toApi().planStop("e-2", "t-1", "10:30")
        commit(r.toApi(), p2)
        val afterStop = r.load()
        val record = afterStop.projection.find("t-1")!!
        assertFalse(record.running())
        assertEquals(90L, record.minutes())
        // Independent reopen sees the same bytes and the same derived state.
        val reopened = repo(root).load()
        assertEquals(afterStop.revision, reopened.revision)
        assertArrayEquals(afterStop.bytes, reopened.bytes)
        assertEquals(90L, reopened.projection.find("t-1")!!.minutes())
        // Historical line is untouched: two lines, each terminated by LF.
        assertEquals(2, reopened.events.size)
        assertEquals('\n', reopened.bytes.last().toInt().toChar())
    }

    @Test fun appendNeverRewritesHistory() = fixture { root ->
        val r = repo(root); val api = r.toApi()
        commit(api, startPlan(api, "e-1", "t-1", "2026-10-23", "09:00", "晨读"))
        val first = r.load().bytes.copyOf()
        commit(api, api.planStop("e-2", "t-1", "10:30"))
        val second = r.load().bytes
        assertEquals(2, second.count { it == '\n'.code.toByte() })
        // Every prefix byte of the original file is preserved verbatim (append-only).
        assertArrayEquals(first, second.copyOfRange(0, first.size))
    }

    @Test fun amendAppendsNewLineAndKeepsOldValue() = fixture { root ->
        val r = repo(root); val api = r.toApi()
        commit(api, startPlan(api, "e-1", "t-1", "2026-10-23", "09:00", "晨读"))
        commit(api, api.planStop("e-2", "t-1", "10:30"))
        val before = r.load().bytes.copyOf()
        val amend = api.planAmend("e-3", "t-1", "start", TimeJson.Value.Str("09:05"), "记早了")
        commit(api, amend)
        val after = r.load()
        assertEquals(3, after.events.size)
        assertArrayEquals(before, after.bytes.copyOfRange(0, before.size)) // old lines intact
        val record = after.projection.find("t-1")!!
        assertEquals("09:05", record.start)
        assertEquals(85L, record.minutes())
        assertEquals(1, record.amendCount)
        // The original start event still says 09:00.
        assertEquals("09:00", after.events[0].text("start"))
    }

    @Test fun renameProjectAppendsOneLineAndChangesNothingElse() = fixture { root ->
        val r = repo(root); val api = r.toApi()
        commit(api, startPlan(api, "e-1", "t-1", "2026-10-23", "09:00", "晨读"))
        val before = r.load().bytes.copyOf()
        commit(api, api.planRenameProject("e-2", "晨读", "晨间阅读"))
        val after = r.load()
        assertEquals(2, after.events.size)
        assertArrayEquals(before, after.bytes.copyOfRange(0, before.size))
        assertEquals("晨间阅读", after.projection.find("t-1")!!.project)
    }

    @Test fun orphanRunningRecordIsVisibleWithoutStop() = fixture { root ->
        val r = repo(root); val api = r.toApi()
        commit(api, startPlan(api, "e-1", "t-1", "2026-10-23", "08:00", "晨读"))
        val record = r.load().projection.find("t-1")!!
        assertTrue(record.running())
        assertTrue(record.orphan(LocalDateTime.parse("2026-10-23T21:00:00")))
        assertFalse(record.orphan(LocalDateTime.parse("2026-10-23T10:00:00")))
        // No stop event was invented.
        assertEquals(0, r.load().events.count { it.op == "stop" })
    }

    @Test fun crossDayStopSplitsAttribution() = fixture { root ->
        val r = repo(root); val api = r.toApi()
        commit(api, startPlan(api, "e-1", "t-1", "2026-10-23", "23:30", "夜读"))
        commit(api, api.planStop("e-2", "t-1", "00:45", "2026-10-24"))
        val p = r.load().projection
        assertEquals(75L, p.find("t-1")!!.minutes())
        assertEquals(30L, p.dayMinutes(LocalDate.of(2026, 10, 23)))
        assertEquals(45L, p.dayMinutes(LocalDate.of(2026, 10, 24)))
        assertEquals(75L, p.unionMinutes())
    }

    @Test fun sameDayStopOmitsEndDate() = fixture { root ->
        val r = repo(root); val api = r.toApi()
        commit(api, startPlan(api, "e-1", "t-1", "2026-10-23", "23:30", "夜读"))
        commit(api, api.planStop("e-2", "t-1", "23:45"))
        assertFalse(r.load().events[1].encode().contains("end_date"))
    }

    @Test fun duplicateEventIdIsRejectedBeforeAnyWrite() = fixture { root ->
        val r = repo(root); val api = r.toApi()
        commit(api, startPlan(api, "e-1", "t-1", "2026-10-23", "09:00", "晨读"))
        val before = r.load().bytes.copyOf()
        rejects { api.planStart("e-1", "t-2", "2026-10-23", "09:30", "午读") }
        rejects { api.planStop("e-1", "t-1", "10:30") }
        assertArrayEquals(before, r.load().bytes)
    }

    @Test fun duplicateStartIdAndStaleBaselineAreRejected() = fixture { root ->
        val r = repo(root); val api = r.toApi()
        commit(api, startPlan(api, "e-1", "t-1", "2026-10-23", "09:00", "晨读"))
        rejects { api.planStart("e-2", "t-1", "2026-10-23", "10:00", "再开始") }
        val stale = api.planStop("e-2", "t-1", "10:30")
        val first = commit(api, stale)
        // Re-applying the same plan is an idempotent replay, not a second stop.
        val again = commit(api, stale)
        assertEquals(2, again.events.size)
        assertEquals(first.revision, again.revision)
        // A different event id under the same plan shape is still rejected by the event-id guard.
        rejects { api.planStop("e-2", "t-1", "10:30") }
    }

    @Test fun stopOfNonRunningOrUnknownTargetIsRejected() = fixture { root ->
        val r = repo(root); val api = r.toApi()
        rejects { api.planStop("e-1", "t-missing", "10:00") }
        commit(api, startPlan(api, "e-2", "t-1", "2026-10-23", "09:00", "晨读"))
        commit(api, api.planStop("e-3", "t-1", "10:00"))
        rejects { api.planStop("e-4", "t-1", "11:00") } // already stopped
    }

    @Test fun startInFutureAndEndBeforeStartAreRejected() = fixture { root ->
        val r = repo(root); val api = r.toApi()
        rejects { api.planStart("e-1", "t-1", "2099-01-01", "09:00", "未来") }
        commit(api, startPlan(api, "e-2", "t-1", "2026-10-23", "09:00", "晨读"))
        rejects { api.planStop("e-3", "t-1", "08:00") }
    }

    @Test fun amendOfUnknownTargetIsWrittenAndFlagged() = fixture { root ->
        val r = repo(root); val api = r.toApi()
        commit(api, startPlan(api, "e-1", "t-1", "2026-10-23", "09:00", "晨读"))
        commit(api, api.planAmend("e-2", "t-missing", "note", TimeJson.Value.Str("多端先到")))
        val p = r.load().projection
        assertEquals(1, p.orphanAmendCount)
        assertEquals(2, r.load().events.size)
    }

    @Test fun amendThatWouldCreateNegativeRangeIsRejected() = fixture { root ->
        val r = repo(root); val api = r.toApi()
        commit(api, startPlan(api, "e-1", "t-1", "2026-10-23", "09:00", "晨读"))
        commit(api, api.planStop("e-2", "t-1", "10:00"))
        val before = r.load().bytes.copyOf()
        rejects { api.planAmend("e-3", "t-1", "start", TimeJson.Value.Str("11:00")) }
        assertArrayEquals(before, r.load().bytes)
    }

    @Test fun renameOfMissingSourceOrSameNameIsRejected() = fixture { root ->
        val r = repo(root); val api = r.toApi()
        commit(api, startPlan(api, "e-1", "t-1", "2026-10-23", "09:00", "晨读"))
        rejects { api.planRenameProject("e-2", "不存在", "新名") }
        rejects { api.planRenameProject("e-3", "晨读", "晨读") }
        assertEquals(1, r.load().events.size)
    }

    @Test fun cancellationWritesNothing() = fixture { root ->
        val r = repo(root); val api = r.toApi()
        val plan = startPlan(api, "e-1", "t-1", "2026-10-23", "09:00", "晨读")
        assertFalse(plan.replay)
        // Caller cancels: no commit, no file, no pending marker.
        assertFalse(File(root, "timetrack").resolve("timetrack.jsonl").exists())
        assertTrue(r.load().events.isEmpty())
        assertFalse(File(root, "timetrack").resolve("timetrack.jsonl.pending").exists())
    }

    @Test fun wrongConfirmationIsRejected() = fixture { root ->
        val r = repo(root); val api = r.toApi()
        val plan = startPlan(api, "e-1", "t-1", "2026-10-23", "09:00", "晨读")
        rejects { api.commit(plan, "not-the-confirmation") }
        assertTrue(r.load().events.isEmpty())
        // Confirmation binds route identity, event id, token and baseline hash: a plan built on a
        // different baseline carries a different confirmation, so a stale plan cannot be confirmed.
        commit(api, startPlan(api, "e-other", "t-other", "2026-10-23", "08:00", "别的"))
        val otherPlan = startPlan(api, "e-1b", "t-1b", "2026-10-23", "09:00", "晨读")
        assertNotEquals(plan.confirmation, otherPlan.confirmation)
        rejects { api.commit(plan, otherPlan.confirmation) }
    }

    @Test fun pendingMarkerBlocksReadsAndIsNeverAutoDeleted() = fixture { root ->
        val r = repo(root)
        val store = File(root, "timetrack")
        val pending = store.resolve("timetrack.jsonl.pending")
        pending.writeText("partial-write-marker")
        rejects { r.load() }
        assertEquals("partial-write-marker", pending.readText())
        // Deleting it is a human decision, never an automatic recovery.
        pending.delete()
        assertTrue(r.load().events.isEmpty())
    }

    @Test fun unknownStoreEntryAndSymlinkAreRefused() = fixture { root ->
        val r = repo(root); val store = File(root, "timetrack")
        val foreign = File(store, "foreign.txt"); foreign.writeText("unknown")
        rejects { r.load() }
        assertEquals("unknown", foreign.readText())
        foreign.delete()
        val target = Files.createTempFile("knte-time-link-", ".jsonl").toFile()
        try {
            Files.createSymbolicLink(store.resolve("timetrack.jsonl").toPath(), target.toPath())
            rejects { r.load() }
        } finally { target.delete() }
    }

    @Test fun corruptFileShapeIsRefusedNotRepaired() = fixture { root ->
        val store = File(root, "timetrack")
        val ledger = store.resolve("timetrack.jsonl")
        ledger.parentFile.mkdirs()
        ledger.writeBytes(byteArrayOf(1, 2, 3)) // invalid UTF-8
        rejects { repo(root).load() }
        assertArrayEquals(byteArrayOf(1, 2, 3), ledger.readBytes())
        ledger.writeBytes("""{"op":"start","event_id":"e-1","id":"t-1","date":"2026-10-23","start":"09:00","project":"x","ts":"2026-10-23T09:00:00"}""".toByteArray())
        rejects { repo(root).load() } // missing trailing LF: last line incomplete
        assertTrue(ledger.readText().endsWith("}"))
    }

    @Test fun journalBeforeImageIsKeptForAudit() = fixture { root ->
        val r = repo(root); val api = r.toApi()
        commit(api, startPlan(api, "e-1", "t-1", "2026-10-23", "09:00", "晨读"))
        val before = r.load().bytes.copyOf()
        commit(api, api.planStop("e-2", "t-1", "10:30"))
        val journal = File(root, "timetrack/.journal")
        val attempts = journal.listFiles()!!
        assertTrue(attempts.isNotEmpty())
        // Before-image of the second append equals the file state at plan time.
        assertTrue(attempts.any { a -> a.resolve("before.bin").let { it.exists() && it.readBytes().contentEquals(before) } })
    }

    @Test fun apiProjectionsMatchRepositoryAndSurviveReopen() = fixture { root ->
        val r = repo(root); val api = r.toApi()
        commit(api, startPlan(api, "e-1", "t-1", "2026-10-23", "09:00", "晨读"))
        commit(api, api.planStop("e-2", "t-1", "10:30"))
        val direct = r.projections()
        assertEquals(90L, direct.find("t-1")!!.minutes())
        assertEquals(0, direct.runningCount)
        val reopened = repo(root).projections()
        assertEquals(direct, reopened)
    }

    @Test fun pureProjectionOfGoldenEventsMatchesRepositoryView() {
        val text = File(javaClass.classLoader!!.getResource("time/golden-events.jsonl")!!.file).readText()
        val events = text.trimEnd('\n').split('\n').map { TimeEventCodec.parse(it) }
        val pure = TimeProjectionBuilder.build(events)
        // 5 start events minus the golden orphan stop (no start) = 4 derived records.
        assertEquals(4, pure.records.size)
        assertEquals(2, pure.runningCount)
        assertEquals(1, pure.orphanAmendCount)
        assertEquals(1, pure.orphanStopCount)
        assertEquals(160L, pure.sumMinutes())
    }

    @Test fun unknownStateAfterWriteIsNeverAutoRetried() = fixture { root ->
        val store = File(root, "timetrack")
        val faulty = com.knte.core.time.repository.IsolatedTimeRepository
            .openForFaultInjection(store) { true }
        val api = TimeLedgerApi(faulty)
        val plan = startPlan(api, "e-1", "t-1", "2026-10-23", "09:00", "晨读")
        try {
            api.commit(plan, plan.confirmation)
            fail("expected unknown state")
        } catch (e: TimeWriteUnknownStateException) {
            assertTrue(e.message!!.contains("TIME_WRITE_RESULT_UNKNOWN"))
        }
        // The pending marker stays: state is unknown, no retry, no automatic recovery.
        val pending = store.resolve("timetrack.jsonl.pending")
        assertTrue(pending.exists())
        rejects { faulty.load() }
        // The journal keeps the before/suffix images for manual review.
        assertTrue(store.resolve(".journal").listFiles()!!.any { it.resolve("before.bin").exists() })
    }

    @Test fun cleanRepositoryAfterFaultyInstanceStillReads() = fixture { root ->
        val store = File(root, "timetrack")
        val faulty = com.knte.core.time.repository.IsolatedTimeRepository
            .openForFaultInjection(store) { true }
        val api = TimeLedgerApi(faulty)
        try { api.commit(startPlan(api, "e-1", "t-1", "2026-10-23", "09:00", "晨读"), startPlan(api, "e-1", "t-1", "2026-10-23", "09:00", "晨读").confirmation) } catch (_: TimeWriteUnknownStateException) { }
        // The append may already be on disk: state is unknown, so nothing is asserted about it.
        assertTrue(store.resolve("timetrack.jsonl.pending").exists())
        // A healthy instance refuses to read while a pending marker exists.
        rejects { repo(root).load() }
        store.resolve("timetrack.jsonl.pending").delete()
        // After the human clears the marker, the healthy instance reads whatever is actually there.
        val events = repo(root).load().events
        assertTrue(events.isEmpty() || events.size == 1)
    }

    private fun IsolatedTimeRepository.toApi() = TimeLedgerApi(this)
    private fun rejects(block: () -> Unit) {
        try { block(); fail("expected rejection") } catch (e: Exception) { }
    }
    @Test fun pendingBlocksEvenReplayOfAlreadyPublishedEvent() = fixture { root ->
        val store = File(root, "timetrack")
        val faulty = IsolatedTimeRepository.openForFaultInjection(store) { true }
        val api = TimeLedgerApi(faulty)
        val plan = api.planStart("pending-e", "t-pending", "2026-10-23", "09:00", "test")
        rejects { api.commit(plan, plan.confirmation) }
        assertTrue(store.resolve("timetrack.jsonl.pending").exists())
        rejects { api.commit(plan, plan.confirmation) }
        assertTrue(store.resolve("timetrack.jsonl.pending").exists())
    }
    @Test fun malformedApiFieldsRejectedBeforeAnyCommit() = fixture { root ->
        val api = TimeLedgerApi(repo(root))
        rejects { api.planStart("bad", "t-bad", "2026-10-23", "09:00", "x".repeat(101)) }
        assertTrue(api.snapshot().events.isEmpty())
    }

    @Test fun everyNewAppendDeclaresDeviceClockUnverified() = fixture { root ->
        val api = TimeLedgerApi(repo(root))
        val p = api.planStart("clock-e", "t-clock", "2026-10-23", "09:00", "clock")
        val saved = api.commit(p, p.confirmation)
        assertEquals("unverified", saved.events.single().atTrust)
        assertTrue(saved.events.single().encode().contains("\"at_trust\":\"unverified\""))
    }

}
