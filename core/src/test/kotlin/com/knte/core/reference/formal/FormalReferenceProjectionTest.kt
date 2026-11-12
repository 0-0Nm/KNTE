package com.knte.core.reference.formal

import com.knte.core.reference.ReferenceEntry
import com.knte.core.reference.ReferenceScope
import com.knte.core.reference.ReferenceWeight
import com.knte.core.reference.TurnBinding
import com.knte.core.time.format.TimeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/**
 * Minimal-sufficient coverage for the candidate pure projection: golden bytes + byte hygiene,
 * upsert field mapping / optional omission / unknown-forward preservation, the 1-log + 2-queue
 * pick shape with independent pools, determinism, fail-closed unsupported / split / body / empty
 * pool, anti-replay + queue-drift rejection, exact weight reuse of the current weight
 * functions, and immutability of the returned views. Proves only deterministic in-memory
 * behavior; no disk atomicity, format authority or production write is claimed.
 */
class FormalReferenceProjectionTest {

    private val today: LocalDate = LocalDate.of(2026, 1, 15)

    // ---------- fixtures ----------

    private fun memo(
        id: String,
        title: String = "备忘",
        due: String? = null,
        done: Boolean = false,
        archived: Boolean = false,
        tags: List<String> = emptyList(),
        world: String? = null,
        body: String? = null,
        createdTs: String = "2026-01-10T08:00:00",
    ): ReferenceEntry.Memo = ReferenceEntry.Memo(
        id = id,
        title = title,
        createdTs = createdTs,
        ts = "2026-01-15T09:00:00",
        due = due,
        done = done,
        archived = archived,
        tags = tags,
        world = world,
        body = body,
    )

    private fun pitfall(
        id: String,
        title: String = "坑",
        count: Int = 1,
        archived: Boolean = false,
        tags: List<String> = emptyList(),
        world: String? = null,
        body: String? = null,
        createdTs: String = "2026-01-08T22:00:00",
    ): ReferenceEntry.Pitfall = ReferenceEntry.Pitfall(
        id = id,
        title = title,
        count = count,
        createdTs = createdTs,
        ts = "2026-01-15T09:00:00",
        archived = archived,
        tags = tags,
        world = world,
        body = body,
    )

    private fun goldenEvents(): List<FormalReferenceEvent> {
        val m1 = memo("m-00000001", "参考契约v2第7节", due = "2026-01-20", tags = listOf("契约"), world = "主世界")
        val m2 = memo("m-00000002", "补齐投影测试", createdTs = "2026-01-12T10:00:00")
        val p1 = pitfall("p-00000001", "深夜改需求", 1, tags = listOf("流程"))
        val p2 = pitfall("p-00000002", "跳过验收", 3, createdTs = "2026-01-13T23:00:00")
        return listOf(
            FormalReferenceEvent.MemoUpsert(
                m1,
                forward = listOf("x_note" to TimeJson.Value.Str("keep")),
            ),
            FormalReferenceEvent.MemoUpsert(m2),
            FormalReferenceEvent.PitfallUpsert(p1),
            FormalReferenceEvent.PitfallUpsert(p2),
            FormalReferenceEvent.PickRound(
                entries = listOf(m1, m2, p1, p2),
                scope = ReferenceScope.all(),
                ts = "2026-01-15T09:05:00",
                memoRecentBefore = emptyList(),
                pitfallRecentBefore = emptyList(),
                eventId = "r-00000001",
                forward = listOf(
                    "weight_snapshot" to TimeJson.Value.Obj(
                        listOf("uniform" to TimeJson.Value.Bool(true)),
                    ),
                ),
            ),
        )
    }

    // ---------- tests ----------

    @Test fun goldenProjectionBytesMatchNoBomLfOnly() {
        val result = FormalReferenceProjection.project(
            goldenEvents(),
            FormalReferenceContext.exact(today, listOf(0L, 0L)),
        )
        FormalReferenceLayout.MEMBERS.forEach { assertMemberEqualsGolden(it, result) }
        FormalReferenceLayout.MEMBERS.forEach { member ->
            val bytes = result.buffers.appendBytes(member)
            assertFalse(
                "BOM in $member",
                bytes.size >= 3 &&
                    (bytes[0].toInt() and 0xFF) == 0xEF &&
                    (bytes[1].toInt() and 0xFF) == 0xBB &&
                    (bytes[2].toInt() and 0xFF) == 0xBF,
            )
            if (bytes.isNotEmpty()) {
                assertEquals("trailing LF in $member", 0x0A.toByte(), bytes[bytes.size - 1])
                assertFalse("CR leaked into $member", String(bytes, Charsets.UTF_8).contains('\r'))
            }
            // every emitted line is canonical: decode -> encode is byte-identical
            memberLines(member, result).forEach { line ->
                assertTrue(
                    "non-canonical line in $member",
                    line.contentEquals(FormalReferenceCodec.canonical(member, line)),
                )
            }
        }
        assertEquals(2, result.lineCounts["memo"])
        assertEquals(2, result.lineCounts["pitfall"])
        assertEquals(2, result.lineCounts["memo_pick"])
        assertEquals(1, result.lineCounts["pick_log"])
        assertEquals(
            listOf(
                "memo-upsert:m-00000001",
                "memo-upsert:m-00000002",
                "pitfall-upsert:p-00000001",
                "pitfall-upsert:p-00000002",
                "pick:r-00000001:m-00000001+p-00000001",
            ),
            result.descriptions,
        )
    }

    @Test fun upsertOptionalOmissionAndUnknownForwardPreserved() {
        val result = FormalReferenceProjection.project(
            listOf(
                FormalReferenceEvent.MemoUpsert(
                    memo("m-00000003", "极简行", createdTs = "2026-01-12T10:00:00"),
                    forward = listOf("x_note" to TimeJson.Value.Str("keep")),
                ),
                FormalReferenceEvent.PitfallUpsert(
                    pitfall("p-00000003", "计数行", 2, createdTs = "2026-01-13T23:00:00"),
                ),
            ),
            FormalReferenceContext.exact(today, listOf(1L)),
        )
        val memoLine = result.buffers.appendBytes(FormalReferenceLayout.MEMBER_MEMO)
        val memoText = String(memoLine, Charsets.UTF_8)
        assertFalse("due must be omitted, not null", memoText.contains("\"due\""))
        assertFalse("world must be omitted, not null", memoText.contains("\"world\""))
        assertFalse("seed is never written", memoText.contains("\"seed\""))
        assertTrue("unknown forward key preserved", memoText.contains("\"x_note\":\"keep\""))
        assertTrue(
            "unknown forward key survives decode->encode roundtrip",
            memoLine.contentEquals(FormalReferenceCodec.canonical(FormalReferenceLayout.MEMBER_MEMO, memoLine)),
        )
        val pitfallText = String(
            result.buffers.appendBytes(FormalReferenceLayout.MEMBER_PITFALL),
            Charsets.UTF_8,
        )
        assertTrue(pitfallText.contains("\"count\":2"))
        assertFalse(pitfallText.contains("\"world\""))
        assertEquals(1, result.lineCounts["memo"])
        assertEquals(1, result.lineCounts["pitfall"])
        assertEquals(0, result.lineCounts["memo_pick"])
        assertEquals(0, result.lineCounts["pick_log"])
    }

    @Test fun pickEmitsOneLogTwoQueueLinesPoolsIndependentNoHistoryMixedIn() {
        val entries = listOf(
            memo("m-00000001", due = "2026-01-20"),
            memo("m-00000002"),
            pitfall("p-00000001"),
            pitfall("p-00000002"),
        )
        val result = FormalReferenceProjection.project(
            listOf(
                FormalReferenceEvent.PickRound(
                    entries, ReferenceScope.all(), "2026-01-15T09:05:00",
                    emptyList(), emptyList(), "r-00000001",
                ),
                FormalReferenceEvent.PickRound(
                    entries, ReferenceScope.all(), "2026-01-15T09:10:00",
                    listOf("m-00000001"), listOf("p-00000001"), "r-00000002",
                ),
            ),
            FormalReferenceContext.exact(today, listOf(0L, 0L, 0L, 0L)),
        )
        // history never mixes into the append: the 4 rows in `entries` emit no memo/pitfall lines
        assertEquals(0, result.buffers.sizes()[FormalReferenceLayout.MEMBER_MEMO])
        assertEquals(0, result.buffers.sizes()[FormalReferenceLayout.MEMBER_PITFALL])
        assertEquals(4, result.lineCounts["memo_pick"])
        assertEquals(2, result.lineCounts["pick_log"])

        val queueLines = memberLines(FormalReferenceLayout.MEMBER_MEMO_PICK, result)
            .map { FormalReferenceCodec.decodeLine(FormalReferenceLayout.MEMBER_MEMO_PICK, it) as FormalMemoPickLine }
        assertEquals(4, queueLines.size)
        assertEquals(FormalReferenceLayout.MEMBER_MEMO, queueLines[0].pool)
        assertEquals(listOf("m-00000001"), queueLines[0].pickedIds)
        assertEquals(FormalReferenceLayout.MEMBER_PITFALL, queueLines[1].pool)
        assertEquals(listOf("p-00000001"), queueLines[1].pickedIds)
        assertEquals(FormalReferenceLayout.MEMBER_MEMO, queueLines[2].pool)
        assertEquals(listOf("m-00000001", "m-00000001"), queueLines[2].pickedIds)
        assertEquals(FormalReferenceLayout.MEMBER_PITFALL, queueLines[3].pool)
        assertEquals(listOf("p-00000001", "p-00000001"), queueLines[3].pickedIds)
        // the two pools advance independently: memo queue ids never leak into the pitfall queue
        queueLines.forEach { line ->
            if (line.pool == FormalReferenceLayout.MEMBER_MEMO) {
                assertTrue(line.pickedIds.all { it.startsWith("m-") })
            } else {
                assertTrue(line.pickedIds.all { it.startsWith("p-") })
            }
        }

        val logs = memberLines(FormalReferenceLayout.MEMBER_PICK_LOG, result)
            .map { FormalReferenceCodec.decodeLine(FormalReferenceLayout.MEMBER_PICK_LOG, it) as FormalPickLogLine }
        assertEquals(listOf("2026-01-15T09:05:00", "2026-01-15T09:10:00"), logs.map { it.turn })
        assertEquals(listOf(emptyList<String>(), listOf("m-00000001")), logs.map { it.recentBefore })
        assertEquals(listOf("m-00000001", "m-00000001"), logs.map { it.memoId })
        assertEquals(listOf("p-00000001", "p-00000001"), logs.map { it.pitfallId })
    }

    @Test fun sameSeedContextIsDeterministicAcrossRuns() {
        val ctx = FormalReferenceContext.seeded(today, 42L)
        val events = listOf(
            FormalReferenceEvent.PickRound(
                listOf(
                    memo("m-00000001", due = "2026-01-20"),
                    memo("m-00000002"),
                    memo("m-00000003"),
                    pitfall("p-00000001"),
                ),
                ReferenceScope.all(), "2026-01-15T09:05:00",
                emptyList(), emptyList(), "r-00000001",
            ),
        )
        val a = FormalReferenceProjection.project(events, ctx)
        val b = FormalReferenceProjection.project(events, ctx)
        assertEquals(a.buffers.combinedHash(), b.buffers.combinedHash())
        assertEquals(a.lineCounts, b.lineCounts)
        assertEquals(a.descriptions, b.descriptions)
        FormalReferenceLayout.MEMBERS.forEach { member ->
            assertTrue(
                "member bytes differ for $member",
                a.buffers.appendBytes(member).contentEquals(b.buffers.appendBytes(member)),
            )
        }
        assertEquals(2, a.lineCounts["memo_pick"])
        assertEquals(1, a.lineCounts["pick_log"])
    }

    @Test fun unsupportedSplitAndBodyFailClosedWithNoPartialOutput() {
        rejects("FORMAL_UNSUPPORTED_EVENT") {
            FormalReferenceProjection.project(
                listOf(
                    FormalReferenceEvent.Unsupported("2026-01-15T10:00:00", "private-history", "opaque"),
                ),
                FormalReferenceContext.exact(today, listOf(0L)),
            )
        }
        rejects("FORMAL_UNSUPPORTED_SPLIT") {
            FormalReferenceProjection.project(
                listOf(
                    FormalReferenceEvent.SplitUnsupported("2026-01-15T10:00:00", "7.3 range unclear"),
                ),
                FormalReferenceContext.exact(today, listOf(0L)),
            )
        }
        rejects("FORMAL_UNSUPPORTED_FIELD") {
            FormalReferenceProjection.project(
                listOf(
                    FormalReferenceEvent.MemoUpsert(memo("m-00000009", body = "would be silently dropped")),
                ),
                FormalReferenceContext.exact(today, listOf(0L)),
            )
        }
        // a valid upsert followed by an unsupported event still fails the WHOLE projection
        rejects("FORMAL_UNSUPPORTED_EVENT") {
            FormalReferenceProjection.project(
                listOf(
                    FormalReferenceEvent.MemoUpsert(memo("m-00000001")),
                    FormalReferenceEvent.Unsupported("2026-01-15T10:00:00", "legacy", ""),
                ),
                FormalReferenceContext.exact(today, listOf(0L)),
            )
        }
    }

    @Test fun emptyPoolFailsClosedInsteadOfFabricatingAPick() {
        rejects("FORMAL_EMPTY_POOL") {
            FormalReferenceProjection.project(
                listOf(
                    FormalReferenceEvent.PickRound(
                        listOf(memo("m-00000001"), memo("m-00000002")),
                        ReferenceScope.all(), "2026-01-15T09:05:00",
                        emptyList(), emptyList(), "r-00000001",
                    ),
                ),
                FormalReferenceContext.exact(today, listOf(0L)),
            )
        }
        rejects("FORMAL_EMPTY_POOL") {
            FormalReferenceProjection.project(
                listOf(
                    FormalReferenceEvent.PickRound(
                        listOf(pitfall("p-00000001")),
                        ReferenceScope.all(), "2026-01-15T09:05:00",
                        emptyList(), emptyList(), "r-00000002",
                    ),
                ),
                FormalReferenceContext.exact(today, listOf(0L)),
            )
        }
        // a scope that filters every row out is also an empty pool: no fabricated sample
        rejects("FORMAL_EMPTY_POOL") {
            FormalReferenceProjection.project(
                listOf(
                    FormalReferenceEvent.PickRound(
                        listOf(memo("m-00000001"), memo("m-00000002"), pitfall("p-00000001"), pitfall("p-00000002")),
                        ReferenceScope.world("缺席", listOf("缺席")),
                        "2026-01-15T09:05:00",
                        emptyList(), emptyList(), "r-00000003",
                    ),
                ),
                FormalReferenceContext.exact(today, listOf(0L)),
            )
        }
    }

    @Test fun duplicateEntryIdsReplayedEventsAndBindingMismatchRejected() {
        val m1 = memo("m-00000001", due = "2026-01-20")
        val m2 = memo("m-00000002")
        val p1 = pitfall("p-00000001")
        val p2 = pitfall("p-00000002")
        rejects("FORMAL_DUPLICATE_KEY") {
            FormalReferenceProjection.project(
                listOf(
                    FormalReferenceEvent.PickRound(
                        listOf(m1, m1, p1, p2),
                        ReferenceScope.all(), "2026-01-15T09:05:00",
                        emptyList(), emptyList(), "r-00000001",
                    ),
                ),
                FormalReferenceContext.exact(today, listOf(0L, 0L)),
            )
        }
        rejects("FORMAL_REPLAY") {
            FormalReferenceProjection.project(
                listOf(
                    FormalReferenceEvent.PickRound(
                        listOf(m1, m2, p1, p2),
                        ReferenceScope.all(), "2026-01-15T09:05:00",
                        emptyList(), emptyList(), "r-00000001",
                    ),
                    FormalReferenceEvent.PickRound(
                        listOf(m1, m2, p1, p2),
                        ReferenceScope.all(), "2026-01-15T09:10:00",
                        listOf("m-00000001"), listOf("p-00000001"), "r-00000001",
                    ),
                ),
                FormalReferenceContext.exact(today, listOf(0L, 0L, 0L, 0L)),
            )
        }
        val binding = TurnBinding("s-" + "a".repeat(32), "t-" + "b".repeat(32), "c".repeat(64))
        assertFalse("guard: derived binding id must differ from the round id", binding.eventId == "r-00000001")
        rejects("FORMAL_REPLAY") {
            FormalReferenceProjection.project(
                listOf(
                    FormalReferenceEvent.PickRound(
                        listOf(m1, m2, p1, p2),
                        ReferenceScope.all(), "2026-01-15T09:05:00",
                        emptyList(), emptyList(), "r-00000001", binding,
                    ),
                ),
                FormalReferenceContext.exact(today, listOf(0L, 0L)),
            )
        }
    }

    @Test fun recentQueueDriftBetweenRoundsRejected() {
        val entries = listOf(
            memo("m-00000001", due = "2026-01-20"),
            memo("m-00000002"),
            pitfall("p-00000001"),
            pitfall("p-00000002"),
        )
        val round1 = FormalReferenceEvent.PickRound(
            entries, ReferenceScope.all(), "2026-01-15T09:05:00",
            emptyList(), emptyList(), "r-00000001",
        )
        rejects("FORMAL_BASELINE_DRIFT") {
            FormalReferenceProjection.project(
                listOf(
                    round1,
                    FormalReferenceEvent.PickRound(
                        entries, ReferenceScope.all(), "2026-01-15T09:10:00",
                        listOf("m-00000002"), listOf("p-00000001"), "r-00000002",
                    ),
                ),
                FormalReferenceContext.exact(today, listOf(0L, 0L, 0L, 0L)),
            )
        }
        rejects("FORMAL_BASELINE_DRIFT") {
            FormalReferenceProjection.project(
                listOf(
                    round1,
                    FormalReferenceEvent.PickRound(
                        entries, ReferenceScope.all(), "2026-01-15T09:10:00",
                        listOf("m-00000001"), listOf("p-00000002"), "r-00000003",
                    ),
                ),
                FormalReferenceContext.exact(today, listOf(0L, 0L, 0L, 0L)),
            )
        }
    }

    @Test fun exactTicketsReproduceWeightsFromCurrentWeightFunctions() {
        val m1 = memo("m-00000001") // no due -> 20 units
        val m2 = memo("m-00000002", due = "2026-01-16") // due_0_3 -> 42 units
        val m3 = memo("m-00000003", archived = true) // archived -> 4 units
        val m4 = memo("m-00000004", due = "2026-06-01") // due_90_plus -> 14 units
        val p1 = pitfall("p-00000001", count = 1) // 20 base units
        val p2 = pitfall("p-00000002", count = 2) // 30 base units
        val p3 = pitfall("p-00000003", count = 1)
        // memo pool size 4 > 3 -> weighted, nothing recent -> effective = base, total = 80
        // ticket 25 lands in [20, 62) -> m-00000002
        val result = FormalReferenceProjection.project(
            listOf(
                FormalReferenceEvent.PickRound(
                    listOf(m1, m2, m3, m4, p1, p2, p3),
                    ReferenceScope.all(), "2026-01-15T09:05:00",
                    emptyList(), emptyList(), "r-00000001",
                ),
            ),
            FormalReferenceContext.exact(today, listOf(25L, 1L)),
        )
        val log = FormalReferenceCodec.decodeLine(
            FormalReferenceLayout.MEMBER_PICK_LOG,
            result.buffers.appendBytes(FormalReferenceLayout.MEMBER_PICK_LOG),
        ) as FormalPickLogLine
        assertEquals("m-00000002", log.memoId)
        // pitfall pool size 3 <= 3 -> uniform; ticket 1 -> second sorted id p-00000002
        assertEquals("p-00000002", log.pitfallId)
        assertEquals(ReferenceWeight.decimal(ReferenceWeight.units(m2, today)), log.memoW)
        assertEquals(ReferenceWeight.decimal(ReferenceWeight.units(p2, today)), log.pitfallW)
        assertEquals("2.1", log.memoW)
        assertEquals("1.5", log.pitfallW)
    }

    @Test fun resultViewsAreImmutableAndInputsNeverMutated() {
        val events = ArrayList<FormalReferenceEvent>()
        events += FormalReferenceEvent.MemoUpsert(memo("m-00000001", due = "2026-01-20"))
        events += FormalReferenceEvent.PitfallUpsert(pitfall("p-00000001"))
        val result = FormalReferenceProjection.project(
            events, FormalReferenceContext.exact(today, listOf(0L)),
        )
        assertEquals(2, events.size)
        assertTrue(events[0] is FormalReferenceEvent.MemoUpsert)
        assertTrue(events[1] is FormalReferenceEvent.PitfallUpsert)
        assertEquals(
            setOf("memo", "pitfall", "memo_pick", "pick_log"),
            result.lineCounts.keys,
        )
        try {
            @Suppress("UNCHECKED_CAST")
            (result.lineCounts as MutableMap<String, Int>).put("memo", 99)
            fail("lineCounts must be an unmodifiable copy")
        } catch (expected: UnsupportedOperationException) {
        }
        val memoBytes = result.buffers.appendBytes(FormalReferenceLayout.MEMBER_MEMO)
        memoBytes[0] = 0x23
        assertFalse(
            "appendBytes must return defensive copies",
            memoBytes.contentEquals(result.buffers.appendBytes(FormalReferenceLayout.MEMBER_MEMO)),
        )
        assertEquals(1, result.lineCounts["memo"])
        assertEquals(1, result.lineCounts["pitfall"])
    }

    // ---------- helpers ----------

    private fun assertMemberEqualsGolden(member: String, result: FormalPureResult) {
        val golden = javaClass.classLoader!!
            .getResourceAsStream("reference/formal/golden-$member.jsonl")!!
            .readBytes()
        val actual = result.buffers.appendBytes(member)
        if (!golden.contentEquals(actual)) {
            fail(
                "golden-$member.jsonl mismatch\n" +
                    "expected:\n" + String(golden, Charsets.UTF_8) +
                    "actual:\n" + String(actual, Charsets.UTF_8),
            )
        }
    }

    /** Splits one member buffer into individual LF-terminated raw lines. */
    private fun memberLines(member: String, result: FormalPureResult): List<ByteArray> {
        val all = result.buffers.appendBytes(member)
        val out = ArrayList<ByteArray>()
        var start = 0
        for (i in all.indices) {
            if (all[i] == 0x0A.toByte()) {
                if (i > start) out.add(all.copyOfRange(start, i + 1))
                start = i + 1
            }
        }
        return out
    }

    private fun rejects(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected rejection $code")
        } catch (e: FormalReferenceException) {
            assertEquals(code, e.code)
        }
    }
    @Test fun aggregateAppendBudgetFailsBeforeReturningHugeBatch() {
        val row=memo("m-00000009")
        val events=List(1500) { FormalReferenceEvent.MemoUpsert(row,
            listOf("future_text" to TimeJson.Value.Str("x".repeat(6000)))) }
        try {
            FormalReferenceProjection.project(events,FormalReferenceContext.seeded(today,7))
            fail("aggregate append budget")
        } catch(e:FormalReferenceException) { assertEquals(FormalReferenceCodes.CAPACITY,e.code) }
    }

}
