package com.knte.core.reference.store

import com.knte.core.reference.ReferenceEntry
import com.knte.core.reference.ReferencePick
import com.knte.core.reference.ReferencePool
import com.knte.core.reference.ReferenceScope
import com.knte.core.reference.ReferenceWeight
import com.knte.core.reference.RandomSource
import com.knte.core.reference.ReplayStatus
import com.knte.core.reference.TurnBinding
import com.knte.core.time.format.TimeHasher
import com.knte.core.time.format.TimeJson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Isolated reference store tests: real temp directories and synthetic rows only, no network,
 * no credentials, no real material. Compiled in ONE unit with the store + reference + time
 * format sources, so `internal` members are visible to these tests.
 *
 * Safety contract under test (this repair):
 * - plan token binds root fingerprint + before-image (head/size/sha) + suffix/after hashes;
 *   public confirm requires an explicit confirmation token and rejects a foreign root.
 * - confirm re-reads the real ledger bytes and binds them to the plan baseline (no memory-only
 *   drift check); external drift refuses and leaves the foreign bytes untouched.
 * - cancel makes a plan unconfirmable, durably, including after reopen and after drift.
 * - pending always blocks new plans/confirms; snapshot reports hasPending with the last good
 *   projection; classifyPending is strictly READ-ONLY (no append/delete/receipt/unlock).
 * - NO ATOMIC_MOVE fallback; symbolic root/entry/UTF-8/capacity checks fail closed.
 * - a torn/partial append yields an UNKNOWN classification and stays locked.
 * - unknown top-level event keys and the original event-line bytes are preserved.
 */
class ReferenceStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun dir(): Path = temp.root.toPath()
    private fun secondDir(): Path = temp.newFolder("other-root").toPath()
    private fun eventsPath(d: Path = dir()): Path = d.resolve(ReferenceStoreCodec.EVENTS_FILE)
    private fun pendingPath(d: Path = dir()): Path = d.resolve(ReferenceStoreCodec.PENDING_FILE)
    private fun beforePath(d: Path = dir()): Path = d.resolve(ReferenceStoreCodec.BEFORE_FILE)
    private fun cancelledPath(d: Path = dir()): Path = d.resolve(ReferenceStoreCodec.CANCELLED_FILE)
    private fun receiptPath(d: Path = dir()): Path = d.resolve(ReferenceStoreCodec.RECEIPT_FILE)

    private val today: LocalDate = LocalDate.parse("2026-11-11")
    private val ts1 = "2026-11-11T09:00:00"
    private val ts2 = "2026-11-11T10:00:00"
    private val ts3 = "2026-11-11T11:00:00"
    private val ts4 = "2026-11-11T12:00:00"
    private val ts5 = "2026-11-11T13:00:00"

    private class FixedRandom(private vararg val tickets: Long) : RandomSource {
        private var i = 0
        override fun next(bound: Long): Long = tickets[i++]
    }

    /** One-shot gate: each named failpoint fires at most once, so a follow-up confirm can proceed. */
    private fun gateOf(vararg points: String): ReferenceStoreFailureGate {
        val fired = HashSet<String>()
        return ReferenceStoreFailureGate { name ->
            if (name in points && fired.add(name)) throw IOException("injected:$name")
        }
    }

    private fun bindingFor(turn: Int, hash: String = "a".repeat(64)): TurnBinding =
        TurnBinding("s-" + "1".repeat(32), "t-" + "1".repeat(30) + turn.toString(16).padStart(2, '0'), hash)

    private fun store(): ReferenceStore = ReferenceStore.open(dir())
    private fun store(gate: ReferenceStoreFailureGate): ReferenceStore = ReferenceStore.openForTest(dir(), gate)

    private fun storeCode(block: () -> Unit): String {
        try {
            block()
            fail("expected ReferenceStoreException")
        } catch (e: ReferenceStoreException) {
            return e.code
        }
        throw AssertionError("unreachable")
    }

    /** Apply a plan through the PUBLIC surface with its explicit confirmation token. */
    private fun confirmPublic(s: ReferenceStore, plan: ReferenceStorePlan) = s.confirm(plan, plan.confirmation)

    private fun seedPools(store: ReferenceStore) {
        confirmPublic(store, store.planCreateMemo("m-00000001", "今天到期", ts1, due = today.toString()))
        confirmPublic(store, store.planCreateMemo("m-00000002", "三十天内", ts1, due = today.plusDays(30).toString()))
        confirmPublic(store, store.planCreateMemo("m-00000003", "九十天内", ts1, due = today.plusDays(90).toString()))
        confirmPublic(store, store.planCreateMemo("m-00000004", "远期", ts1, due = today.plusDays(3650).toString()))
        confirmPublic(store, store.planCreatePitfall("p-00000001", "熬夜", ts1, count = 1))
        confirmPublic(store, store.planCreatePitfall("p-00000002", "拖延", ts1, count = 2))
        confirmPublic(store, store.planCreatePitfall("p-00000003", "熬夜到三点", ts1, count = 3))
        confirmPublic(store, store.planCreatePitfall("p-00000004", "旧坑", ts1, count = 1))
    }

    // ------------------------------------------------------------- creates

    @Test
    fun createMemoPersistsAndReadsBack() {
        val store = store()
        val plan = store.planCreateMemo(
            id = "m-3f2a91c7", title = "交物理作业", ts = ts1,
            due = "2026-11-14", world = "素问", body = "带第三章", tags = listOf("作业"),
        )
        confirmPublic(store, plan)
        val snapshot = ReferenceStore.open(dir()).snapshot()
        assertEquals(1, snapshot.eventCount)
        val row = snapshot.entries.single() as ReferenceEntry.Memo
        assertEquals("m-3f2a91c7", row.id)
        assertEquals("交物理作业", row.title)
        assertEquals("2026-11-14", row.due)
        assertEquals("素问", row.world)
        assertEquals("带第三章", row.body)
        assertEquals(listOf("作业"), row.tags)
        assertEquals(ts1, row.createdTs)
        assertEquals(ts1, row.ts)
        assertFalse(row.done)
        assertFalse(row.archived)
        assertEquals(plan.afterHash, snapshot.head)
        assertFalse(snapshot.hasPending)
        assertNull(snapshot.pending)
        assertEquals(ReferenceStoreQueues(emptyList(), emptyList()), snapshot.recentQueues)
    }

    @Test
    fun createPitfallPersistsAndReadsBack() {
        val store = store()
        confirmPublic(store, store.planCreatePitfall("p-7b1e40aa", "又熬夜到三点", ts1, count = 3, world = "素问", body = "睡前不刷手机"))
        val snapshot = ReferenceStore.open(dir()).snapshot()
        val row = snapshot.entries.single() as ReferenceEntry.Pitfall
        assertEquals("p-7b1e40aa", row.id)
        assertEquals(3, row.count)
        assertEquals("素问", row.world)
        assertEquals("睡前不刷手机", row.body)
        assertFalse(row.archived)
        assertEquals(ts1, row.ts)
    }

    @Test
    fun duplicateIdIsRejected() {
        val store = store()
        confirmPublic(store, store.planCreateMemo("m-00000001", "一", ts1))
        assertEquals(ReferenceStoreCodes.DUPLICATE_ID, storeCode { store.planCreateMemo("m-00000001", "二", ts2) })
        // A differently-shaped id cannot collide; the store refuses duplicate ids before the
        // pure-domain id-shape check, which is the correct fail-closed ordering here.
        assertEquals(ReferenceStoreCodes.DUPLICATE_ID, storeCode { store.planCreatePitfall("m-00000001", "冒名", ts2, count = 1) })
    }

    // ------------------------------------------------------------- plan without confirm

    @Test
    fun planWithoutConfirmWritesNothingAndDescribeIsByteFree() {
        val store = store()
        val bytesBefore = Files.readAllBytes(eventsPath())
        val headBefore = store.snapshot().head
        val plan = store.planCreateMemo("m-00000001", "计划标题", ts1, body = "正文")
        assertArrayEquals("planning must not touch the event base", bytesBefore, Files.readAllBytes(eventsPath()))
        assertFalse(Files.exists(pendingPath()))
        assertFalse(Files.exists(beforePath()))
        assertEquals(0, Files.readAllBytes(receiptPath()).size)
        assertEquals(headBefore, ReferenceStore.open(dir()).snapshot().head)
        val describe = plan.describe()
        assertTrue(describe.contains(plan.rootFingerprintValue()))
        assertTrue(describe.contains(plan.token))
        assertTrue(describe.contains(plan.confirmation))
        assertTrue(describe.contains(plan.beforeSha256))
        assertFalse(describe.contains("计划标题"))
        assertFalse(describe.contains("正文"))
    }

    // ------------------------------------------------------------- done / archive transitions

    @Test
    fun memoDoneAndArchiveAreForwardEventsAndOriginalBytesPreserved() {
        val store = store()
        confirmPublic(store, store.planCreateMemo("m-00000001", "备忘", ts1, due = today.toString()))
        confirmPublic(store, store.planCreatePitfall("p-00000001", "坑", ts1, count = 3))
        val beforeBytes = Files.readAllBytes(eventsPath())
        confirmPublic(store, store.planMemoDone("m-00000001", ts2))
        val doneMemo = store.snapshot().entries.first { it.id == "m-00000001" } as ReferenceEntry.Memo
        assertTrue(doneMemo.done)
        assertEquals(ts2, doneMemo.ts)
        assertEquals(ReferenceWeight.ARCHIVED_UNITS, ReferenceWeight.units(doneMemo, today))
        assertEquals(40, ReferenceWeight.pitfallUnits(store.snapshot().entries.first { it.id == "p-00000001" } as ReferenceEntry.Pitfall))
        confirmPublic(store, store.planArchive("p-00000001", ts3))
        assertTrue((store.snapshot().entries.first { it.id == "p-00000001" } as ReferenceEntry.Pitfall).archived)
        confirmPublic(store, store.planArchive("m-00000001", ts4))
        val archivedMemo = store.snapshot().entries.first { it.id == "m-00000001" } as ReferenceEntry.Memo
        assertTrue(archivedMemo.done && archivedMemo.archived)
        val afterBytes = Files.readAllBytes(eventsPath())
        assertTrue(
            "append-only: original create lines are never rewritten",
            afterBytes.copyOfRange(0, beforeBytes.size).contentEquals(beforeBytes),
        )
    }

    @Test
    fun illegalReversalAndUnknownTargetsAreRefused() {
        val store = store()
        confirmPublic(store, store.planCreateMemo("m-00000001", "备忘", ts1))
        confirmPublic(store, store.planCreatePitfall("p-00000001", "坑", ts1, count = 1))
        assertEquals(ReferenceStoreCodes.ENTRY_NOT_FOUND, storeCode { store.planMemoDone("m-ffffffff", ts2) })
        assertEquals(ReferenceStoreCodes.WRONG_POOL, storeCode { store.planMemoDone("p-00000001", ts2) })
        assertEquals(ReferenceStoreCodes.ENTRY_NOT_FOUND, storeCode { store.planArchive("m-ffffffff", ts2) })
        confirmPublic(store, store.planMemoDone("m-00000001", ts2))
        assertEquals(ReferenceStoreCodes.ILLEGAL_TRANSITION, storeCode { store.planMemoDone("m-00000001", ts3) })
        confirmPublic(store, store.planArchive("m-00000001", ts3))
        assertEquals(ReferenceStoreCodes.ILLEGAL_TRANSITION, storeCode { store.planMemoDone("m-00000001", ts4) })
        assertEquals(ReferenceStoreCodes.ILLEGAL_TRANSITION, storeCode { store.planArchive("m-00000001", ts4) })
        confirmPublic(store, store.planArchive("p-00000001", ts4))
        assertEquals(ReferenceStoreCodes.ILLEGAL_TRANSITION, storeCode { store.planArchive("p-00000001", ts5) })
    }

    // ------------------------------------------------------------- picks: log + queues in ONE event

    @Test
    fun pickEventBindsLogQueuesAndTicketReplayInOneLineAndReopens() {
        val store = store()
        seedPools(store)
        val plan = store.planPick(bindingFor(1), today, FixedRandom(0, 0), ts3)!!
        confirmPublic(store, plan)
        val snapshot = store.snapshot()
        assertEquals(1, snapshot.pickHistory.size)
        val record = snapshot.pickHistory.single()
        assertEquals("m-00000001", record.memo!!.id)
        assertEquals("p-00000001", record.pitfall!!.id)
        assertEquals(ReferenceStoreQueues(listOf("m-00000001"), listOf("p-00000001")), snapshot.recentQueues)
        assertEquals(ts3, record.ts)
        assertEquals(bindingFor(1), record.binding)

        // ONE event line carries the pick log AND both queues after; no separate cross-file write.
        val line = String(Files.readAllBytes(eventsPath()), Charsets.UTF_8).lines().last { it.isNotEmpty() }
        assertTrue(line.contains("\"pick\""))
        assertTrue(line.contains("\"recent_after\""))
        assertTrue(line.contains("\"memo_recent_before\""))
        val reparsed = TimeJson.parse(line) as TimeJson.Value.Obj
        assertEquals(record.toJson(), reparsed.entries.first { it.first == "pick" }.second)

        val scope = ReferenceScope.all()
        assertEquals(ReplayStatus.OK, ReferencePick.replayChoice(snapshot.entries, ReferencePool.MEMO, record.memo, scope, today, record.memoRecentBefore))
        assertEquals(ReplayStatus.OK, ReferencePick.replayChoice(snapshot.entries, ReferencePool.PITFALL, record.pitfall, scope, today, record.pitfallRecentBefore))

        val reopened = ReferenceStore.open(dir())
        assertEquals(listOf("m-00000001"), reopened.snapshot().recentQueues.memo)
        assertEquals(listOf("p-00000001"), reopened.snapshot().recentQueues.pitfall)
        assertEquals(record, reopened.snapshot().pickHistory.single())
    }

    @Test
    fun queuesAdvancePerPoolAndStayBoundedAtThree() {
        val store = store()
        seedPools(store)
        val pickTss = listOf("2026-11-11T14:00:00", "2026-11-11T15:00:00", "2026-11-11T16:00:00", "2026-11-11T17:00:00")
        for (turn in 1..4) {
            val plan = store.planPick(bindingFor(turn), today, FixedRandom(0, 0), pickTss[turn - 1])
            confirmPublic(store, plan!!)
        }
        val queues = store.snapshot().recentQueues
        assertEquals(3, queues.memo.size)
        assertEquals(listOf("m-00000001", "m-00000001", "m-00000001"), queues.memo)
        assertEquals(3, queues.pitfall.size)
        assertEquals(4, store.snapshot().pickHistory.size)
    }

    @Test
    fun bothPoolsEmptyYieldsNoPickAndNoFakeRows() {
        val store = store()
        assertNull(store.planPick(bindingFor(1), today, FixedRandom(0, 0), ts1))
        assertEquals(0, store.snapshot().eventCount)
        assertEquals(0, Files.readAllBytes(eventsPath()).size)
    }

    @Test
    fun singleEmptyPoolKeepsItsQueueUntouched() {
        val store = store()
        confirmPublic(store, store.planCreateMemo("m-00000001", "备忘", ts1))
        val plan = store.planPick(bindingFor(1), today, FixedRandom(0), ts3)
        confirmPublic(store, plan!!)
        val snapshot = store.snapshot()
        val record = snapshot.pickHistory.single()
        assertEquals("m-00000001", record.memo!!.id)
        assertNull(record.pitfall)
        assertEquals(ReferenceStoreQueues(listOf("m-00000001"), emptyList()), snapshot.recentQueues)
        val reopened = ReferenceStore.open(dir())
        assertNull(reopened.snapshot().pickHistory.single().pitfall)
        assertEquals(emptyList<String>(), reopened.snapshot().recentQueues.pitfall)
    }

    @Test
    fun sameTurnOrChangedInputIsRefusedForPicks() {
        val store = store()
        seedPools(store)
        val first = store.planPick(bindingFor(1, "a".repeat(64)), today, FixedRandom(0, 0), ts3)!!
        confirmPublic(store, first)
        assertEquals(
            ReferenceStoreCodes.PICK_REPLAY,
            storeCode { store.planPick(bindingFor(1, "b".repeat(64)), today, FixedRandom(0, 0), ts4) },
        )
        val record = store.snapshot().pickHistory.single()
        assertEquals(com.knte.core.reference.BindingStatus.INPUT_CHANGED, ReferencePick.verifyBinding(record, bindingFor(1, "b".repeat(64))))
        assertEquals(com.knte.core.reference.BindingStatus.TURN_CHANGED, ReferencePick.verifyBinding(record, bindingFor(2, "a".repeat(64))))
        assertTrue(store.planPick(bindingFor(2), today, FixedRandom(0, 0), ts4) != null)
    }

    // ------------------------------------------------------------- root binding / explicit confirmation

    @Test
    fun foreignRootPlanIsRejectedBeforeAnyWrite() {
        val store = store()
        confirmPublic(store, store.planCreateMemo("m-00000001", "一", ts1))
        val bytesBefore = Files.readAllBytes(eventsPath())
        val foreign = ReferenceStore.open(secondDir()).planCreateMemo("m-00000009", "外来", ts2)
        // PUBLIC surface: foreign root is rejected even with the correct confirmation token.
        assertEquals(
            ReferenceStoreCodes.FOREIGN_ROOT,
            storeCode { store.confirm(foreign, foreign.confirmation) },
        )
        assertArrayEquals("foreign bytes untouched", bytesBefore, Files.readAllBytes(eventsPath()))
        assertFalse(Files.exists(pendingPath()))
        assertFalse(Files.exists(beforePath()))
        // Also rejected on cancel and via the internal shim.
        assertEquals(ReferenceStoreCodes.FOREIGN_ROOT, storeCode { store.cancel(foreign) })
        assertEquals(ReferenceStoreCodes.FOREIGN_ROOT, storeCode { store.confirm(foreign) })
    }

    @Test
    fun publicConfirmRejectsWrongOrMissingConfirmationToken() {
        val store = store()
        val plan = store.planCreateMemo("m-00000001", "一", ts1)
        val bytesBefore = Files.readAllBytes(eventsPath())
        assertEquals(
            ReferenceStoreCodes.CONFIRMATION_INVALID,
            storeCode { store.confirm(plan, "0".repeat(64)) },
        )
        assertArrayEquals(bytesBefore, Files.readAllBytes(eventsPath()))
        assertFalse(Files.exists(pendingPath()))
        // The plan is still usable with its exact explicit token.
        confirmPublic(store, plan)
        assertEquals(plan.afterHash, store.snapshot().head)
    }

    @Test
    fun planTokenBindsRootAndBeforeImage() {
        val store = store()
        val rootA = store.rootFingerprint()
        val other = ReferenceStore.open(secondDir())
        assertNotEquals(rootA, other.rootFingerprint())
        val planA = store.planCreateMemo("m-00000001", "一", ts1)
        val planB = store.planCreatePitfall("p-00000001", "坑", ts1, count = 1)
        assertEquals(rootA, planA.root())
        assertTrue(planA.describe().contains(rootA))
        assertEquals(0L, planA.beforeSize)
        assertEquals(TimeHasher.sha256(ByteArray(0)), planA.beforeSha256)
        assertEquals(planA.beforeHash, planB.beforeHash)
        assertNotEquals(planA.token, planB.token)
        assertNotEquals(planA.confirmation, planB.confirmation)
        assertEquals(ReferenceStoreCodec.nextHead(planA.beforeHash, planA.line), planA.afterHash)
        confirmPublic(store, planA)
        assertEquals(planA.afterHash, store.snapshot().head)
        // planB was built against the same baseline: the independent byte check refuses it.
        assertEquals(ReferenceStoreCodes.BASELINE_DRIFT, storeCode { store.confirm(planB, planB.confirmation) })
    }

    // ------------------------------------------------------------- cancel: terminal & durable

    @Test
    fun cancelThenNewPlanThenConfirmAndBytesNeverResurrect() {
        val store = store()
        val cancelled = store.planCreateMemo("m-00000001", "被取消", ts1)
        store.cancel(cancelled)
        assertTrue("cancelled marker is durable", Files.exists(cancelledPath()))
        assertTrue(String(Files.readAllBytes(cancelledPath()), Charsets.UTF_8).contains(cancelled.token))

        // The cancelled plan can never be confirmed - on this instance, after drift, or after reopen.
        assertEquals(ReferenceStoreCodes.PLAN_CANCELLED, storeCode { store.confirm(cancelled, cancelled.confirmation) })
        assertEquals(ReferenceStoreCodes.PLAN_CANCELLED, storeCode { store.cancel(cancelled) })

        val fresh = store.planCreateMemo("m-00000002", "新建", ts2)
        assertNotEquals(cancelled.token, fresh.token)
        confirmPublic(store, fresh)
        assertEquals(1, store.snapshot().eventCount)
        assertEquals("m-00000002", store.snapshot().entries.single().id)

        // The cancelled plan stays terminal across reopen even though the ledger moved on.
        val reopened = ReferenceStore.open(dir())
        assertEquals(ReferenceStoreCodes.PLAN_CANCELLED, storeCode { reopened.confirm(cancelled, cancelled.confirmation) })

        // External drift after a successful write does not make the cancelled plan confirmable
        // again: cancellation is checked BEFORE any ledger/drift read.
        val bytesBeforeDrift = Files.readAllBytes(eventsPath())
        Files.write(eventsPath(), bytesBeforeDrift + "external interference\n".toByteArray(Charsets.UTF_8))
        assertEquals(ReferenceStoreCodes.PLAN_CANCELLED, storeCode { store.confirm(cancelled, cancelled.confirmation) })
        assertArrayEquals("foreign bytes untouched", bytesBeforeDrift + "external interference\n".toByteArray(Charsets.UTF_8), Files.readAllBytes(eventsPath()))
        // Original cancelled plan's event id was never written.
        assertFalse(String(Files.readAllBytes(eventsPath()), Charsets.UTF_8).contains("\"m-00000001\""))
    }

    @Test
    fun cancelSurvivesReopenAndIsCheckedBeforeLedgerDrift() {
        val store = store()
        val plan = store.planCreateMemo("m-00000001", "一", ts1)
        store.cancel(plan)
        val reopened = ReferenceStore.open(dir())
        assertEquals(ReferenceStoreCodes.PLAN_CANCELLED, storeCode { reopened.confirm(plan, plan.confirmation) })
        // A NEW plan still works after a cancel (cancel is per-token, not a store lock).
        val next = reopened.planCreateMemo("m-00000002", "二", ts2)
        confirmPublic(reopened, next)
        assertEquals(1, reopened.snapshot().eventCount)
    }

    // ------------------------------------------------------------- confirm drift: independent ledger read

    @Test
    fun baselineDriftRefusesAndLeavesExternalBytesUntouched() {
        val store = store()
        val plan = store.planCreateMemo("m-00000001", "一", ts1)
        Files.write(eventsPath(), Files.readAllBytes(eventsPath()) + "external interference\n".toByteArray(Charsets.UTF_8))
        val beforeDrift = Files.readAllBytes(eventsPath())
        assertEquals(
            ReferenceStoreCodes.BASELINE_DRIFT,
            storeCode { store.confirm(plan, plan.confirmation) },
        )
        assertArrayEquals("the store never overwrites foreign bytes", beforeDrift, Files.readAllBytes(eventsPath()))
        assertFalse(Files.exists(pendingPath()))
        assertFalse(Files.exists(beforePath()))
        // Even a brand-new plan is refused: checkMutable independently saw the drift.
        assertEquals(ReferenceStoreCodes.BASELINE_DRIFT, storeCode { store.planCreateMemo("m-00000002", "二", ts2) })
    }

    @Test
    fun inMemoryStateAloneCannotAuthorizeAWriteAfterHiddenDrift() {
        val store = store()
        val plan = store.planCreateMemo("m-00000001", "一", ts1)
        // Simulate a stale in-memory state by rewriting the ledger to DIFFERENT bytes whose
        // length/hash differ, without going through the store. The independent re-read must win.
        val forged = TimeJson.encodeUtf8Strict("forged-but-same-shape\n")
        Files.write(eventsPath(), forged)
        assertEquals(
            ReferenceStoreCodes.BASELINE_DRIFT,
            storeCode { store.confirm(plan, plan.confirmation) },
        )
        assertArrayEquals(forged, Files.readAllBytes(eventsPath()))
        // A fresh plan is also blocked by the independent baseline read (no memory-only trust).
        assertEquals(ReferenceStoreCodes.BASELINE_DRIFT, storeCode { store.planCreateMemo("m-00000002", "二", ts2) })
    }

    // ------------------------------------------------------------- fault injection / read-only classify

    @Test
    fun failureAfterPendingLocksAndClassifyIsReadOnlyNotApplied() {
        val store = store(gateOf("after_pending"))
        val bytesBefore = Files.readAllBytes(eventsPath())
        val plan = store.planCreatePitfall("p-11111111", "坑", ts1, count = 2)
        try {
            confirmPublic(store, plan)
            fail("confirm must surface the injected failure")
        } catch (expected: IOException) {
        }
        assertEquals(0, store.snapshot().eventCount)
        assertArrayEquals("the old prefix is byte-identical", bytesBefore, Files.readAllBytes(eventsPath()))
        assertTrue(Files.exists(pendingPath()))
        assertTrue(Files.exists(beforePath()))
        assertTrue(store.snapshot().hasPending)
        assertNotEquals(null, store.snapshot().pending)

        // pending ALWAYS blocks new plans / confirms / cancels.
        assertEquals(ReferenceStoreCodes.LOCKED_UNKNOWN, storeCode { store.planCreateMemo("m-00000001", "二", ts2) })
        assertEquals(ReferenceStoreCodes.LOCKED_UNKNOWN, storeCode { store.confirm(plan, plan.confirmation) })
        assertEquals(ReferenceStoreCodes.LOCKED_UNKNOWN, storeCode { store.cancel(plan) })

        // classifyPending is READ-ONLY: NOT_APPLIED, no byte change, no pending deletion,
        // no receipt, no append, no unlock - not a fake recovery.
        val c1 = store.classifyPending()
        assertTrue(c1.readOnly)
        assertEquals(ReferencePendingClass.NOT_APPLIED, c1.state)
        assertEquals(plan.beforeSha256, c1.observedSha256)
        assertTrue(c1.beforeImageMatches)
        assertArrayEquals(bytesBefore, Files.readAllBytes(eventsPath()))
        assertTrue(Files.exists(pendingPath()))
        assertEquals(0, Files.readAllBytes(receiptPath()).size)
        val c2 = store.classifyPending()
        assertEquals(c1, c2)
        assertArrayEquals(bytesBefore, Files.readAllBytes(eventsPath()))
        assertTrue(Files.exists(pendingPath()))
        assertEquals(ReferenceStoreCodes.LOCKED_UNKNOWN, storeCode { store.planCreateMemo("m-00000001", "二", ts2) })
        // resolvePending is explicitly unsupported (no auto-recovery path).
        assertEquals(ReferenceStoreCodes.AUTO_RECOVERY_UNSUPPORTED, storeCode { store.resolvePending() })
    }

    @Test
    fun failureBeforePendingDoesNotLockAndLeavesNoPendingMarker() {
        val store = store(gateOf("after_before_image"))
        val bytesBefore = Files.readAllBytes(eventsPath())
        val plan = store.planCreateMemo("m-00000001", "一", ts1)
        try {
            confirmPublic(store, plan)
            fail("injected failure must surface")
        } catch (expected: IOException) {
        }
        assertFalse(Files.exists(pendingPath()))
        assertEquals(0, store.snapshot().eventCount)
        assertArrayEquals(bytesBefore, Files.readAllBytes(eventsPath()))
        // A fresh plan is still allowed (before-image file is not a pending proposal).
        val next = store.planCreateMemo("m-00000002", "二", ts2)
        confirmPublic(store, next)
        assertEquals(1, store.snapshot().eventCount)
    }

    @Test
    fun failureAfterAppendClassifiesAppliedWithoutAnyWrite() {
        val store = store(gateOf("after_append"))
        val plan = store.planCreateMemo("m-00000001", "一", ts1)
        try {
            confirmPublic(store, plan)
            fail("injected failure must surface")
        } catch (expected: IOException) {
        }
        val bytesAfter = Files.readAllBytes(eventsPath())
        val c = store.classifyPending()
        assertEquals(ReferencePendingClass.APPLIED, c.state)
        assertEquals(plan.afterHash, c.observedHead)
        // NO receipt completion, NO pending deletion, NO byte change from classification.
        assertEquals(0, Files.readAllBytes(receiptPath()).size)
        assertTrue(Files.exists(pendingPath()))
        assertArrayEquals(bytesAfter, Files.readAllBytes(eventsPath()))
        assertEquals(ReferenceStoreCodes.LOCKED_UNKNOWN, storeCode { store.confirm(plan, plan.confirmation) })
    }

    @Test
    fun failureAfterReceiptClassifiesAppliedAndKeepsPendingAndReceipt() {
        val store = store(gateOf("after_receipt"))
        val plan = store.planCreateMemo("m-00000001", "一", ts1)
        try {
            confirmPublic(store, plan)
            fail("injected failure must surface")
        } catch (expected: IOException) {
        }
        val receiptBefore = Files.readAllBytes(receiptPath())
        assertTrue(String(receiptBefore, Charsets.UTF_8).contains(plan.token))
        val c = store.classifyPending()
        assertEquals(ReferencePendingClass.APPLIED, c.state)
        assertArrayEquals("classify never writes a receipt", receiptBefore, Files.readAllBytes(receiptPath()))
        assertTrue("classify never deletes pending", Files.exists(pendingPath()))
    }

    @Test
    fun tornPartialAppendClassifiesUnknownAndStaysLocked() {
        val store = store()
        // Build a real pending situation via fault injection, then simulate a torn/partial append
        // of the proposal by adding half of its line bytes as an external actor would leave them.
        val fault = store(gateOf("after_pending"))
        val plan = fault.planCreateMemo("m-00000001", "一", ts1)
        try {
            confirmPublic(fault, plan)
            fail("injected")
        } catch (expected: IOException) {
        }
        val half = plan.line.toByteArray(Charsets.UTF_8).copyOf(plan.line.toByteArray(Charsets.UTF_8).size / 2)
        Files.write(eventsPath(), Files.readAllBytes(eventsPath()) + half)
        val bytes = Files.readAllBytes(eventsPath())
        val c = fault.classifyPending()
        assertEquals(ReferencePendingClass.UNKNOWN, c.state)
        assertEquals(bytes.size.toLong(), c.observedSize)
        assertArrayEquals("classify did not repair the torn tail", bytes, Files.readAllBytes(eventsPath()))
        assertTrue(Files.exists(pendingPath()))
        assertEquals(ReferenceStoreCodes.LOCKED_UNKNOWN, storeCode { fault.planCreateMemo("m-00000002", "二", ts2) })
    }

    @Test
    fun pendingBlocksAcrossReopenAndNeverAutoRecovers() {
        val fault = store(gateOf("after_pending"))
        val plan = fault.planCreateMemo("m-00000001", "一", ts1)
        try {
            confirmPublic(fault, plan)
            fail("injected")
        } catch (expected: IOException) {
        }
        val bytes = Files.readAllBytes(eventsPath())
        val reopened = ReferenceStore.open(dir())
        assertTrue(reopened.snapshot().hasPending)
        assertNotEquals(null, reopened.snapshot().pending)
        assertEquals(0, reopened.snapshot().eventCount) // last good projection, but flagged pending
        assertEquals(ReferenceStoreCodes.PENDING_UNRESOLVED, storeCode { reopened.planCreateMemo("m-00000002", "二", ts2) })
        assertEquals(ReferenceStoreCodes.PENDING_UNRESOLVED, storeCode { reopened.confirm(plan, plan.confirmation) })
        assertEquals(ReferenceStoreCodes.PENDING_UNRESOLVED, storeCode { reopened.cancel(plan) })
        val c = reopened.classifyPending()
        assertEquals(ReferencePendingClass.NOT_APPLIED, c.state)
        assertArrayEquals("no auto-recovery on reopen", bytes, Files.readAllBytes(eventsPath()))
        assertTrue(Files.exists(pendingPath()))
        assertEquals(plan.token, c.pending.token)
        assertEquals(reopened.rootFingerprint(), c.pending.rootFingerprint)
    }

    @Test
    fun corruptPendingFileFailsClosedOnOpen() {
        val store = store()
        val plan = store.planCreateMemo("m-00000001", "一", ts1)
        Files.write(pendingPath(), "garbage".toByteArray(Charsets.UTF_8))
        assertEquals(ReferenceStoreCodes.PENDING_UNRESOLVED, storeCode { store.confirm(plan, plan.confirmation) })
        assertEquals(ReferenceStoreCodes.PENDING_CORRUPT, storeCode { ReferenceStore.open(dir()) })
    }

    @Test
    fun corruptCancelledFileFailsClosedOnOpen() {
        store()
        Files.write(cancelledPath(), "not-hex\n".toByteArray(Charsets.UTF_8))
        assertEquals(ReferenceStoreCodes.CANCELLED_CORRUPT, storeCode { ReferenceStore.open(dir()) })
    }

    // ------------------------------------------------------------- strict schema / UTF-8 / symbolic / capacity

    @Test
    fun corruptEventLinesFailClosedOnOpen() {
        fun corruptOpensWith(line: String) {
            Files.write(eventsPath(), line.toByteArray(Charsets.UTF_8))
            assertEquals(ReferenceStoreCodes.FILE_CORRUPT, storeCode { ReferenceStore.open(dir()) })
        }
        val store = store()
        val head = store.snapshot().head
        val memo = ReferenceEntry.Memo(id = "m-00000001", title = "行", createdTs = ts1, ts = ts1)
        val entryJson = ReferenceStoreCodec.entryJson(memo)
        fun eventLine(mutate: (MutableList<Pair<String, TimeJson.Value>>) -> Unit): String {
            val fields = mutableListOf(
                "schema" to TimeJson.Value.Str(ReferenceStoreCodec.SCHEMA_EVENT),
                "event_id" to TimeJson.Value.Str("m-00000001"),
                "op" to TimeJson.Value.Str("create_memo"),
                "ts" to TimeJson.Value.Str(ts1),
                "at_trust" to TimeJson.Value.Str("unverified"),
                "prev_head" to TimeJson.Value.Str(head),
                "entry" to entryJson,
            )
            mutate(fields)
            return TimeJson.encode(TimeJson.Value.Obj(fields)) + "\n"
        }
        corruptOpensWith(eventLine { it[0] = "schema" to TimeJson.Value.Str("knte.reference_store.event/2") })
        corruptOpensWith(eventLine { it.removeAt(5) })
        corruptOpensWith(eventLine { it[3] = "ts" to TimeJson.Value.Str("2026-11-11 09:00") })
        corruptOpensWith(eventLine { it[4] = "at_trust" to TimeJson.Value.Str("verified") })
        corruptOpensWith(eventLine { it[2] = "op" to TimeJson.Value.Str("merge") })
        corruptOpensWith(eventLine { it[6] = "entry" to TimeJson.Value.Bool(true) })
        corruptOpensWith(eventLine { it[5] = "prev_head" to TimeJson.Value.Str("0".repeat(63) + "1") })
        corruptOpensWith(eventLine { it[1] = "event_id" to TimeJson.Value.Str("m-ZZZZZZZZ") })
        corruptOpensWith(eventLine { it[5] = "prev_head" to TimeJson.Value.Str("f".repeat(64)) })
        Files.write(eventsPath(), eventLine { }.removeSuffix("\n").toByteArray(Charsets.UTF_8))
        assertEquals(ReferenceStoreCodes.FILE_CORRUPT, storeCode { ReferenceStore.open(dir()) })
    }

    @Test
    fun strictUtf8FailureIsCorruptNotCoerced() {
        store()
        Files.write(eventsPath(), byteArrayOf(0xC3.toByte(), 0x28.toByte(), 0x0A))
        assertEquals(ReferenceStoreCodes.FILE_CORRUPT, storeCode { ReferenceStore.open(dir()) })
    }

    @Test
    fun symbolicStoreEntryIsRejectedFailClosed() {
        val p = eventsPath()
        Files.deleteIfExists(p)
        Files.createSymbolicLink(p, temp.newFile("outside-target").toPath())
        assertEquals(ReferenceStoreCodes.DIR_INVALID, storeCode { ReferenceStore.open(dir()) })
    }

    @Test
    fun symbolicRootIsRejected() {
        val link = temp.newFolder("link-parent").toPath().resolve("store-link")
        val target = temp.newFolder("link-target")
        Files.createSymbolicLink(link, target.toPath())
        assertEquals(ReferenceStoreCodes.DIR_INVALID, storeCode { ReferenceStore.open(link) })
    }

    @Test
    fun capacityChecksAreExplicitAndFailClosed() {
        val store = store()
        // A single over-long line is refused at plan time (title cap comes from the domain; here
        // we drive a plan whose proposed line is too long via body length beyond the codec cap).
        try {
            store.planCreateMemo("m-00000001", "一", ts1, body = "x".repeat(1_100_000))
            fail("line capacity must be enforced (domain or store)")
        } catch (expected: IllegalArgumentException) {
            // pure domain rejects first - still fail closed, no bytes written
        } catch (expected: ReferenceStoreException) {
            assertEquals(ReferenceStoreCodes.CAPACITY, expected.code)
        }
        assertEquals(0, Files.readAllBytes(eventsPath()).size)
        assertFalse(Files.exists(pendingPath()))
    }

    @Test
    fun productionWritesAndUnsupportedCapabilitiesAreExplicit() {
        val store = store()
        assertFalse(store.productionWritesSupported())
        assertFalse(ReferenceStoreUnsupported.PRODUCTION_WRITES_SUPPORTED)
        assertTrue(store.unsupportedCapabilities().contains(ReferenceStoreUnsupported.AUTO_RECOVERY))
        assertTrue(store.unsupportedCapabilities().contains(ReferenceStoreUnsupported.FORMAL_EXPORT_IMPORT))
        assertTrue(store.unsupportedCapabilities().contains(ReferenceStoreUnsupported.AI_SAME_PITFALL))
        assertTrue(store.unsupportedCapabilities().contains(ReferenceStoreUnsupported.MULTI_FILE_TRANSACTION))
    }

    @Test
    fun replayRejectsDuplicateIdsAndIllegalReversal() {
        fun storeWith(vararg lines: String) {
            Files.write(eventsPath(), lines.joinToString(separator = "", postfix = "").toByteArray(Charsets.UTF_8))
        }
        val store = store()
        val memo = ReferenceEntry.Memo(id = "m-00000001", title = "一", createdTs = ts1, ts = ts1)
        val head0 = store.snapshot().head
        val createJson = ReferenceStoreCodec.entryJson(memo)
        fun eventLine(prev: String, id: String, op: String, entry: TimeJson.Value = createJson, lineTs: String = ts1): String {
            val fields = mutableListOf(
                "schema" to TimeJson.Value.Str(ReferenceStoreCodec.SCHEMA_EVENT),
                "event_id" to TimeJson.Value.Str(id),
                "op" to TimeJson.Value.Str(op),
                "ts" to TimeJson.Value.Str(lineTs),
                "at_trust" to TimeJson.Value.Str("unverified"),
                "prev_head" to TimeJson.Value.Str(prev),
                "entry" to entry,
            )
            return TimeJson.encode(TimeJson.Value.Obj(fields)) + "\n"
        }
        val create = eventLine(head0, "m-00000001", "create_memo")
        storeWith(create)
        assertEquals(1, ReferenceStore.open(dir()).snapshot().eventCount)
        val head1 = ReferenceStoreCodec.nextHead(head0, create.removeSuffix("\n"))
        storeWith(create, eventLine(head1, "m-00000001", "create_memo"))
        assertEquals(ReferenceStoreCodes.FILE_CORRUPT, storeCode { ReferenceStore.open(dir()) })
        val doneEntry = ReferenceStoreCodec.entryJson(memo.copy(done = true, ts = ts2))
        val doneId = ReferenceStoreCodec.derivedEventId("m-00000001", "memo_done", ts2)
        val doneLine = eventLine(head1, doneId, "memo_done", doneEntry, ts2)
        val head2 = ReferenceStoreCodec.nextHead(head1, doneLine.removeSuffix("\n"))
        val doneId2 = ReferenceStoreCodec.derivedEventId("m-00000001", "memo_done", ts3)
        storeWith(create, doneLine, eventLine(head2, doneId2, "memo_done", ReferenceStoreCodec.entryJson(memo.copy(done = true, ts = ts3)), ts3))
        assertEquals(ReferenceStoreCodes.FILE_CORRUPT, storeCode { ReferenceStore.open(dir()) })
    }

    @Test
    fun unknownTopLevelKeysArePreservedVerbatimAndNestedClosedSchemaFails() {
        val store = store()
        val head = store.snapshot().head
        val memo = ReferenceEntry.Memo(id = "m-00000001", title = "一", createdTs = ts1, ts = ts1)
        val fields = mutableListOf(
            "schema" to TimeJson.Value.Str(ReferenceStoreCodec.SCHEMA_EVENT),
            "event_id" to TimeJson.Value.Str("m-00000001"),
            "op" to TimeJson.Value.Str("create_memo"),
            "ts" to TimeJson.Value.Str(ts1),
            "at_trust" to TimeJson.Value.Str("unverified"),
            "prev_head" to TimeJson.Value.Str(head),
            "entry" to ReferenceStoreCodec.entryJson(memo),
            "knte_future_extension" to TimeJson.Value.Str("kept"),
        )
        val original = TimeJson.encode(TimeJson.Value.Obj(fields)) + "\n"
        Files.write(eventsPath(), original.toByteArray(Charsets.UTF_8))
        val snapshot = ReferenceStore.open(dir()).snapshot()
        assertEquals(1, snapshot.eventCount)
        assertEquals("一", snapshot.entries.single().title)
        // Original event-line bytes are preserved: confirm an append-only follow-up never rewrites them.
        val reopened = ReferenceStore.open(dir())
        confirmPublic(reopened, reopened.planMemoDone("m-00000001", ts2))
        val after = Files.readAllBytes(eventsPath())
        assertTrue(after.copyOfRange(0, original.toByteArray(Charsets.UTF_8).size).contentEquals(original.toByteArray(Charsets.UTF_8)))
        // Unknown keys inside the closed entry structure fail closed.
        val badEntry = TimeJson.Value.Obj(
            (ReferenceStoreCodec.entryJson(memo) as TimeJson.Value.Obj).entries + listOf("surprise" to TimeJson.Value.Str("x"))
        )
        val badFields = fields.toMutableList()
        badFields[6] = "entry" to badEntry
        Files.write(eventsPath(), (TimeJson.encode(TimeJson.Value.Obj(badFields)) + "\n").toByteArray(Charsets.UTF_8))
        assertEquals(ReferenceStoreCodes.FILE_CORRUPT, storeCode { ReferenceStore.open(dir()) })
    }

    @Test
    fun snapshotIsDefensivelyCopied() {
        val store = store()
        confirmPublic(store, store.planCreateMemo("m-00000001", "一", ts1))
        val snap = store.snapshot()
        @Suppress("UNCHECKED_CAST")
        (snap.entries as MutableList<ReferenceEntry>).clear()
        @Suppress("UNCHECKED_CAST")
        (snap.recentQueues.memo as MutableList<String>).add("polluted")
        @Suppress("UNCHECKED_CAST")
        (snap.pickHistory as MutableList<Any>).add("polluted")
        assertEquals(1, store.snapshot().eventCount)
        assertEquals(emptyList<String>(), store.snapshot().recentQueues.memo)
        assertEquals(0, store.snapshot().pickHistory.size)
    }

    @Test
    fun storeFilesNeverImpersonateTheFormalSources() {
        store()
        val names = Files.list(dir()).map { it.fileName.toString() }.toList()
        assertTrue(ReferenceStoreCodec.EVENTS_FILE in names)
        assertTrue(ReferenceStoreCodec.RECEIPT_FILE in names)
        assertTrue(ReferenceStoreCodec.CANCELLED_FILE in names)
        for (formal in listOf("memo.jsonl", "pitfall.jsonl", "pick_log.jsonl", "memo_pick.jsonl", "db")) {
            assertFalse("$formal must not appear in the isolated store", formal in names)
        }
    }

    @Test
    fun parentFacingApiRequiresConfirmationAndExposesReadOnlyClassification() {
        val api = ReferenceStoreApi.open(dir())
        val plan = api.planCreateMemo("m-00000001", "一", ts1)
        assertEquals(
            ReferenceStoreCodes.CONFIRMATION_INVALID,
            storeCode { api.confirm(plan, "0".repeat(64)) },
        )
        api.cancel(plan)
        assertEquals(ReferenceStoreCodes.PLAN_CANCELLED, storeCode { api.confirm(plan, plan.confirmation) })
        val next = api.planCreateMemo("m-00000002", "二", ts2)
        api.confirm(next, next.confirmation)
        assertEquals(1, api.snapshot().eventCount)
        assertFalse(api.productionWritesSupported())
        assertEquals(ReferenceStoreCodes.NO_PENDING, storeCode { api.classifyPending() })
    }

    @Test
    fun atomicMoveUnavailableFailsClosedWithNoPendingNoBytesWritten() {
        // A pre-existing NON-EMPTY DIRECTORY at the pending temp path makes the store's
        // deleteIfExists(tmp) throw (DirectoryNotEmptyException). If the store had an unsafe
        // plain-move fallback, it might still publish a pending; here it must fail closed,
        // with no pending proposal, no before-image and no event bytes.
        val store = store()
        val plan = store.planCreateMemo("m-00000001", "一", ts1)
        val tmpDir = dir().resolve(ReferenceStoreCodec.PENDING_TMP)
        Files.createDirectory(tmpDir)
        Files.write(tmpDir.resolve("guard"), byteArrayOf(1))
        val bytesBefore = Files.readAllBytes(eventsPath())
        assertEquals(ReferenceStoreCodes.IO, storeCode { store.confirm(plan, plan.confirmation) })
        assertFalse(Files.exists(pendingPath()))
        assertArrayEquals(bytesBefore, Files.readAllBytes(eventsPath()))
        assertEquals(0, store.snapshot().eventCount)
        assertFalse(store.snapshot().hasPending)
    }

    @Test
    fun cancelledTokenCopiedToFreshRootIsStillForeignAndNeverConfirms() {
        // A cancelled token is bound to a plan token (which is bound to a root fingerprint).
        // Copying the marker into a different root does NOT make that root accept the plan,
        // and the foreign-root plan is rejected before any cancelled lookup.
        val store = store()
        val plan = store.planCreateMemo("m-00000001", "一", ts1)
        store.cancel(plan)
        val otherDir = secondDir()
        val other = ReferenceStore.open(otherDir)
        Files.write(
            cancelledPath(otherDir),
            Files.readAllBytes(cancelledPath()),
        )
        assertEquals(ReferenceStoreCodes.FOREIGN_ROOT, storeCode { other.confirm(plan, plan.confirmation) })
        assertEquals(0, Files.readAllBytes(eventsPath(otherDir)).size)
        // The original root still refuses it too.
        assertEquals(ReferenceStoreCodes.PLAN_CANCELLED, storeCode { store.confirm(plan, plan.confirmation) })
    }

    @Test
    fun beforeImageIsDurableAndTornTailBytesStayUntouchedByReadOnlyClassification() {
        val fault = store(gateOf("after_append"))
        val plan = fault.planCreateMemo("m-00000001", "一", ts1)
        try {
            confirmPublic(fault, plan)
            fail("injected")
        } catch (expected: IOException) {
        }
        // The real before-image is on disk and matches the plan's exact baseline bytes.
        assertTrue(Files.exists(beforePath()))
        assertEquals(plan.beforeSize, Files.size(beforePath()))
        assertEquals(plan.beforeSha256, TimeHasher.sha256(Files.readAllBytes(beforePath())))
        // External torn tail AFTER the applied append => UNKNOWN, bytes never "repaired".
        val before = Files.readAllBytes(eventsPath())
        Files.write(eventsPath(), before + "torn".toByteArray(Charsets.UTF_8))
        val withTorn = Files.readAllBytes(eventsPath())
        val c = fault.classifyPending()
        assertEquals(ReferencePendingClass.UNKNOWN, c.state)
        assertEquals(withTorn.size.toLong(), c.observedSize)
        assertArrayEquals(withTorn, Files.readAllBytes(eventsPath()))
        assertTrue(Files.exists(pendingPath()))
    }

    /** Test-only accessor for the internal root fingerprint on a plan (same compilation unit). */
    private fun ReferenceStorePlan.rootFingerprintValue(): String = root()
}
