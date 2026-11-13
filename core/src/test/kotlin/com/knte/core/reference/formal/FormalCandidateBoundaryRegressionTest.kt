package com.knte.core.reference.formal

import com.knte.core.reference.ReferenceEntry
import com.knte.core.reference.ReferenceScope
import com.knte.core.reference.ReferenceWeight
import com.knte.core.time.format.TimeJson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/**
 * CANDIDATE pure-core compatibility regression slice (task 6694137d).
 *
 * Scope: boundary cases that the existing FormalReference*Test classes do NOT already
 * cover, asserted only against real public production APIs. Nothing here promotes an
 * uncertain semantics into a formal requirement; where the contract is still open the
 * test pins the CURRENT conservative behaviour and says so.
 *
 * Not covered here on purpose (already covered by FormalReferenceProjectionTest /
 * FormalReferenceCodecTest / FormalReferenceWitnessTest): golden bytes, 1+2 line shape,
 * determinism, replay/duplicate-event rejection, binding mismatch, forward depth/duplicate
 * numeric-literal rejection, plan single-use, immutability of result views.
 */
class FormalCandidateBoundaryRegressionTest {

    private val today: LocalDate = LocalDate.of(2026, 1, 15)
    private val ts = "2026-01-15T09:05:00"

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
        id = id, title = title, createdTs = createdTs, ts = ts,
        due = due, done = done, archived = archived, tags = tags, world = world, body = body,
    )

    private fun pitfall(
        id: String,
        title: String = "坑",
        count: Int = 1,
        archived: Boolean = false,
        tags: List<String> = emptyList(),
        world: String? = null,
        createdTs: String = "2026-01-08T22:00:00",
    ): ReferenceEntry.Pitfall = ReferenceEntry.Pitfall(
        id = id, title = title, count = count, createdTs = createdTs, ts = ts,
        archived = archived, tags = tags, world = world,
    )

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(
            "reference/formal/provisional-candidate-boundary/$name"
        )!!.readBytes()

    private fun fixtureLines(name: String): List<ByteArray> =
        String(fixture(name), Charsets.UTF_8).trimEnd('\n').split("\n")
            .filter { it.isNotEmpty() }
            .map { (it + "\n").toByteArray(Charsets.UTF_8) }

    private fun rejects(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected rejection $code")
        } catch (e: FormalReferenceException) {
            assertEquals(code, e.code)
        }
    }

    private fun obj(v: TimeJson.Value): TimeJson.Value.Obj = v as TimeJson.Value.Obj
    private fun field(o: TimeJson.Value.Obj, name: String): TimeJson.Value =
        o.entries.first { it.first == name }.second
    private fun num(v: TimeJson.Value): Long = (v as TimeJson.Value.Num).literal.toLong()
    private fun text(v: TimeJson.Value): String = (v as TimeJson.Value.Str).text
    private fun arr(v: TimeJson.Value): List<TimeJson.Value> = (v as TimeJson.Value.Arr).items

    private fun pickLogOf(bytes: ByteArray): FormalPickLogLine =
        FormalReferenceCodec.decodeLine(FormalReferenceLayout.MEMBER_PICK_LOG, bytes) as FormalPickLogLine

    /** `forward` lives on each concrete line class; FormalReferenceLine itself does not expose it. */
    private fun forwardOf(line: FormalReferenceLine, key: String): TimeJson.Value? =
        when (line) {
            is FormalMemoLine -> line.forward.firstOrNull { it.first == key }?.second
            is FormalPitfallLine -> line.forward.firstOrNull { it.first == key }?.second
            is FormalMemoPickLine -> line.forward.firstOrNull { it.first == key }?.second
            is FormalPickLogLine -> line.forward.firstOrNull { it.first == key }?.second
            else -> null
        }

    private fun project(vararg events: FormalReferenceEvent, tickets: List<Long>): FormalPureResult =
        FormalReferenceProjection.project(events.toList(), FormalReferenceContext.exact(today, tickets))

    // =====================================================================
    // A. optional null: known field rejected vs unknown seed:null preserved
    // =====================================================================

    @Test fun knownOptionalExplicitNullIsRejectedButUnknownSeedNullSurvivesRoundtrip() {
        // known top-level optional keys: explicit JSON null is a fail-closed conflict,
        // NOT silently treated as omission (contract conflict is still open).
        rejects(FormalReferenceCodes.OPTIONAL_NULL) {
            FormalReferenceCodec.decodeLine(FormalReferenceLayout.MEMBER_MEMO, fixtureLines("reject-known-optional-null.jsonl")[0])
        }
        rejects(FormalReferenceCodes.OPTIONAL_NULL) {
            FormalReferenceCodec.decodeLine(FormalReferenceLayout.MEMBER_MEMO, fixtureLines("reject-known-optional-null.jsonl")[1])
        }
        // at_trust may be omitted but never explicitly null
        rejects(FormalReferenceCodes.OPTIONAL_NULL) {
            FormalReferenceCodec.decodeLine(FormalReferenceLayout.MEMBER_MEMO, fixtureLines("reject-known-optional-null.jsonl")[2])
        }
        // the rejection detail must name the offending field, not a generic message
        try {
            FormalReferenceCodec.decodeLine(FormalReferenceLayout.MEMBER_MEMO, fixtureLines("reject-known-optional-null.jsonl")[0])
            fail("expected FORMAL_OPTIONAL_NULL")
        } catch (e: FormalReferenceException) {
            assertTrue("detail should name the field: ${e.message}", e.message!!.contains("due"))
        }
        // unknown seed:null is forward-preserved and byte-stable
        val bytes = fixture("roundtrip-memo.jsonl")
        val line = FormalReferenceCodec.decodeLine(FormalReferenceLayout.MEMBER_MEMO, bytes) as FormalMemoLine
        assertEquals(null, line.due)
        assertEquals(null, line.world)
        assertEquals(TimeJson.Value.Null, forwardOf(line, "seed"))
        assertArrayEquals(bytes, FormalReferenceCodec.canonical(FormalReferenceLayout.MEMBER_MEMO, bytes))
    }

    @Test fun pickLogKnownOptionalNullIsRejectedWhileUnknownKeysBecomeForward() {
        rejects(FormalReferenceCodes.OPTIONAL_NULL) {
            FormalReferenceCodec.decodeLine(FormalReferenceLayout.MEMBER_PICK_LOG, fixture("reject-picklog-optional-null.jsonl"))
        }
        for (key in listOf("memo_id", "memo_w", "pitfall_id", "pitfall_w")) {
            assertTrue("$key must be a known pick_log key", key in FormalPickLogLine.PICK_LOG_KNOWN)
        }
        // candidate_audit_v1 / weight_snapshot are NOT known pick_log keys: they are forward
        assertFalse(FormalPickLogLine.PICK_LOG_KNOWN.contains("candidate_audit_v1"))
        assertFalse(FormalPickLogLine.PICK_LOG_KNOWN.contains("weight_snapshot"))
        // hand-built unknown keys on pick_log decode as forward and roundtrip byte-identically
        val raw = (
            "{\"turn\":\"2026-01-15T09:05:00\",\"memo_id\":\"m-00000001\",\"memo_w\":1," +
                "\"pitfall_id\":\"p-00000001\",\"pitfall_w\":1,\"recent_before\":[]," +
                "\"ts\":\"2026-01-15T09:05:00\",\"at_trust\":\"unverified\"," +
                "\"weight_snapshot\":{\"uniform\":true}," +
                "\"candidate_audit_v1\":{\"memo\":{\"ticket\":0}}}" + "\n"
            ).toByteArray(Charsets.UTF_8)
        val line = pickLogOf(raw)
        assertEquals(TimeJson.Value.Obj(listOf("uniform" to TimeJson.Value.Bool(true))), forwardOf(line, "weight_snapshot"))
        assertEquals(TimeJson.Value.Obj(listOf("memo" to TimeJson.Value.Obj(listOf("ticket" to TimeJson.Value.Num("0"))))), forwardOf(line, "candidate_audit_v1"))
        assertArrayEquals(raw, FormalReferenceCodec.canonical(FormalReferenceLayout.MEMBER_PICK_LOG, raw))
    }

    @Test fun omittedAtTrustIsAcceptedOnReadButCanonicalWriteAlwaysAddsIt() {
        val raw = (
            "{\"op\":\"upsert\",\"id\":\"m-00000001\",\"title\":\"no-trust\",\"done\":false," +
                "\"archived\":false,\"tags\":[],\"created_ts\":\"2026-01-10T08:00:00\"," +
                "\"ts\":\"2026-01-15T09:00:00\"}" + "\n"
            ).toByteArray(Charsets.UTF_8)
        val line = FormalReferenceCodec.decodeLine(FormalReferenceLayout.MEMBER_MEMO, raw) as FormalMemoLine
        assertTrue("omitted at_trust must not become forward", line.forward.isEmpty())
        val canonical = FormalReferenceCodec.canonical(FormalReferenceLayout.MEMBER_MEMO, raw)
        assertFalse("canonical write must add at_trust", raw.contentEquals(canonical))
        assertTrue(String(canonical, Charsets.UTF_8).contains("\"at_trust\":\"unverified\""))
        // a non-canonical value still fails closed (only omission and the canonical value pass)
        val bad = raw.toString(Charsets.UTF_8).replace(
            "\"ts\":\"2026-01-15T09:00:00\"}", "\"ts\":\"2026-01-15T09:00:00\",\"at_trust\":\"trusted\"}",
        ).toByteArray(Charsets.UTF_8)
        rejects(FormalReferenceCodes.TRUST_INVALID) { FormalReferenceCodec.decodeLine(FormalReferenceLayout.MEMBER_MEMO, bad) }
    }

    // =====================================================================
    // B. unknown nested field canonical preservation
    // =====================================================================

    @Test fun unknownNestedSubtreeWithConflictingKeyNamesIsPreservedVerbatim() {
        val bytes = fixture("roundtrip-memo.jsonl")
        val line = FormalReferenceCodec.decodeLine(FormalReferenceLayout.MEMBER_MEMO, bytes) as FormalMemoLine
        val meta = obj(forwardOf(line, "x_meta")!!)
        // nested keys that COLLIDE with known top-level optional names are still unknown here
        assertEquals(TimeJson.Value.Null, field(meta, "due"))
        val nested = obj(field(meta, "nested"))
        assertEquals(TimeJson.Value.Null, field(nested, "seed"))
        assertEquals(4, arr(field(nested, "list")).size)
        assertEquals(TimeJson.Value.Num("1"), arr(field(nested, "list"))[0])
        assertEquals(TimeJson.Value.Null, arr(field(nested, "list"))[3])
        // nested nulls never trigger the known-optional-null rejection
        assertArrayEquals(bytes, FormalReferenceCodec.encodeLine(line))
        // deep equality of the whole forward subtree after a full decode->encode->decode cycle
        val again = FormalReferenceCodec.decodeLine(
            FormalReferenceLayout.MEMBER_MEMO,
            FormalReferenceCodec.encodeLine(line),
        ) as FormalMemoLine
        assertEquals(line.forward, again.forward)
    }

    @Test fun constructedUnknownTreeSurvivesEncodeDecodeWithoutEscapingOrLoss() {
        val tree = TimeJson.Value.Obj(
            listOf(
                "esc" to TimeJson.Value.Str("a\"b\\c\nd\te"),
                "deep" to TimeJson.Value.Arr(
                    listOf(
                        TimeJson.Value.Obj(listOf("k" to TimeJson.Value.Num("1.25"))),
                        TimeJson.Value.Arr(listOf(TimeJson.Value.Str("x"), TimeJson.Value.Null)),
                    ),
                ),
            ),
        )
        val line = FormalMemoLine(
            "m-00000001", "unknown-tree", null, false, false, emptyList(), null,
            "2026-01-10T08:00:00", ts, listOf("future" to tree),
        )
        val bytes = FormalReferenceCodec.encodeLine(line)
        assertEquals("no raw LF may escape", 1, bytes.count { it == 0x0A.toByte() })
        assertFalse("no raw CR may escape", String(bytes, Charsets.UTF_8).contains('\r'))
        val back = FormalReferenceCodec.decodeLine(FormalReferenceLayout.MEMBER_MEMO, bytes) as FormalMemoLine
        assertEquals(tree, forwardOf(back, "future"))
        assertArrayEquals(bytes, FormalReferenceCodec.canonical(FormalReferenceLayout.MEMBER_MEMO, bytes))
    }

    // =====================================================================
    // C. candidate_audit_v1 key conflict fails and does not tamper the input
    // =====================================================================

    @Test fun callerSuppliedCandidateAuditKeyConflictsAndLeavesTheInputUntouched() {
        val entries = listOf(
            memo("m-00000001", due = "2026-01-20"), memo("m-00000002"),
            memo("m-00000003", archived = true), memo("m-00000004", due = "2026-06-01"),
            pitfall("p-00000001"), pitfall("p-00000002"),
            pitfall("p-00000003"), pitfall("p-00000004"),
        )
        val injected = TimeJson.Value.Obj(listOf("memo" to TimeJson.Value.Num("0")))
        val forward = listOf("candidate_audit_v1" to injected)
        val round = FormalReferenceEvent.PickRound(
            entries, ReferenceScope.all(), ts, emptyList(), emptyList(), "r-00000001", null, forward,
        )
        val snapshotForward = round.forward.toList()
        val snapshotEntries = round.entries.toList()
        rejects(FormalReferenceCodes.FORWARD_KNOWN_KEY) {
            FormalReferenceProjection.project(listOf(round), FormalReferenceContext.exact(today, listOf(5L, 0L)))
        }
        // fail-closed WITHOUT mutating the caller's event
        assertEquals("forward must not be rewritten", snapshotForward, round.forward)
        assertEquals("entries must not be rewritten", snapshotEntries, round.entries)
        assertEquals(injected, round.forward.single().second)
        // deterministic: the same conflict is reported again, no hidden state carried over
        rejects(FormalReferenceCodes.FORWARD_KNOWN_KEY) {
            FormalReferenceProjection.project(listOf(round), FormalReferenceContext.exact(today, listOf(5L, 0L)))
        }
    }

    @Test fun domainGeneratedCandidateAuditIsAttachedAsForwardAndIsNotAFormalKey() {
        val entries = listOf(
            memo("m-00000001", due = "2026-01-20"), memo("m-00000002"),
            pitfall("p-00000001"), pitfall("p-00000002"),
        )
        val result = project(
            FormalReferenceEvent.PickRound(entries, ReferenceScope.all(), ts, emptyList(), emptyList(), "r-00000001"),
            tickets = listOf(0L, 0L),
        )
        val log = pickLogOf(result.buffers.appendBytes(FormalReferenceLayout.MEMBER_PICK_LOG))
        assertNotNull("candidate_audit_v1 must be present on the audit line", forwardOf(log, "candidate_audit_v1"))
        val audit = obj(forwardOf(log, "candidate_audit_v1")!!)
        assertEquals(setOf("memo", "pitfall"), audit.entries.map { it.first }.toSet())
        // the caller never supplied it: the event forward stayed empty
        assertTrue(log.forward.any { it.first == "candidate_audit_v1" })
        // it is provisional, never a formal pick_log key
        assertFalse(FormalPickLogLine.PICK_LOG_KNOWN.contains("candidate_audit_v1"))
        assertArrayEquals(
            result.buffers.appendBytes(FormalReferenceLayout.MEMBER_PICK_LOG),
            FormalReferenceCodec.canonical(
                FormalReferenceLayout.MEMBER_PICK_LOG,
                result.buffers.appendBytes(FormalReferenceLayout.MEMBER_PICK_LOG),
            ),
        )
    }

    // =====================================================================
    // D. caller weight_snapshot extension: preserved, but explicitly non-formal
    // =====================================================================

    @Test fun callerWeightSnapshotIsPreservedOnPickLogOnlyAndNeverOnQueueLines() {
        assertFalse("weight_snapshot is not a formal key", FormalPickLogLine.PICK_LOG_KNOWN.contains("weight_snapshot"))
        assertTrue(
            "weight_snapshot must stay declared unsupported",
            FormalReferenceUnsupported.WEIGHT_SNAPSHOT_SCHEMA in FormalReferenceUnsupported.ALL,
        )
        val caller = TimeJson.Value.Obj(listOf("uniform" to TimeJson.Value.Bool(true)))
        val entries = listOf(
            memo("m-00000001", due = "2026-01-20"), memo("m-00000002"),
            pitfall("p-00000001"), pitfall("p-00000002"),
        )
        val result = project(
            FormalReferenceEvent.PickRound(
                entries, ReferenceScope.all(), ts, emptyList(), emptyList(), "r-00000001",
                forward = listOf("weight_snapshot" to caller),
            ),
            tickets = listOf(0L, 0L),
        )
        val log = pickLogOf(result.buffers.appendBytes(FormalReferenceLayout.MEMBER_PICK_LOG))
        assertEquals("caller value must be forwarded verbatim", caller, forwardOf(log, "weight_snapshot"))
        // the domain adds its own provisional audit next to it, never replacing it
        assertNotNull(forwardOf(log, "candidate_audit_v1"))
        // queue lines never inherit the caller extension
        val queue = result.buffers.appendBytes(FormalReferenceLayout.MEMBER_MEMO_PICK)
            .let { raw -> String(raw, Charsets.UTF_8).trimEnd('\n').split("\n").map { (it + "\n").toByteArray(Charsets.UTF_8) } }
            .map { FormalReferenceCodec.decodeLine(FormalReferenceLayout.MEMBER_MEMO_PICK, it) as FormalMemoPickLine }
        assertEquals(2, queue.size)
        queue.forEach { assertTrue("queue line must carry no forward keys", it.forward.isEmpty()) }
    }

    // =====================================================================
    // E. two independent pools: cross-pool leak, single-side drift, one-side empty
    // =====================================================================

    @Test fun crossPoolRecentIdsAreRejectedSoTheTwoQueuesCannotLeak() {
        val entries = listOf(memo("m-00000001"), pitfall("p-00000001"))
        rejects(FormalReferenceCodes.ID_INVALID) {
            project(
                FormalReferenceEvent.PickRound(
                    entries, ReferenceScope.all(), ts, listOf("p-00000001"), emptyList(), "r-00000001",
                ),
                tickets = listOf(0L, 0L),
            )
        }
        rejects(FormalReferenceCodes.ID_INVALID) {
            project(
                FormalReferenceEvent.PickRound(
                    entries, ReferenceScope.all(), ts, emptyList(), listOf("m-00000001"), "r-00000002",
                ),
                tickets = listOf(0L, 0L),
            )
        }
    }

    @Test fun recentBeforeCapacityIsEnforcedPerPool() {
        val memos = (1..4).map { memo("m-%08d".format(it)) }
        val pitfalls = (1..4).map { pitfall("p-%08d".format(it)) }
        rejects(FormalReferenceCodes.CAPACITY) {
            project(
                FormalReferenceEvent.PickRound(
                    memos + pitfalls, ReferenceScope.all(), ts,
                    (1..4).map { "m-%08d".format(it) }, emptyList(), "r-00000001",
                ),
                tickets = listOf(0L, 0L),
            )
        }
        rejects(FormalReferenceCodes.CAPACITY) {
            project(
                FormalReferenceEvent.PickRound(
                    memos + pitfalls, ReferenceScope.all(), ts, emptyList(),
                    (1..4).map { "p-%08d".format(it) }, "r-00000002",
                ),
                tickets = listOf(0L, 0L),
            )
        }
    }

    @Test fun driftInOnePoolAloneIsRejectedAndNamesThatPool() {
        val entries = listOf(
            memo("m-00000001"), memo("m-00000002"),
            pitfall("p-00000001"), pitfall("p-00000002"),
        )
        val round1 = FormalReferenceEvent.PickRound(
            entries, ReferenceScope.all(), "2026-01-15T09:05:00", emptyList(), emptyList(), "r-00000001",
        )
        // round1 picks m-00000001 / p-00000001, so both queues become [winner]
        val ok = project(round1, tickets = listOf(0L, 0L))
        val queue = String(ok.buffers.appendBytes(FormalReferenceLayout.MEMBER_MEMO_PICK), Charsets.UTF_8)
            .trimEnd('\n').split("\n")
            .map { FormalReferenceCodec.decodeLine(FormalReferenceLayout.MEMBER_MEMO_PICK, (it + "\n").toByteArray()) as FormalMemoPickLine }
        val memoAfter = queue.first { it.pool == FormalReferenceLayout.MEMBER_MEMO }.pickedIds
        val pitfallAfter = queue.first { it.pool == FormalReferenceLayout.MEMBER_PITFALL }.pickedIds
        assertEquals(listOf("m-00000001"), memoAfter)
        assertEquals(listOf("p-00000001"), pitfallAfter)
        // memo queue consistent, pitfall queue drifted -> fail-closed naming the pitfall side
        rejects(FormalReferenceCodes.BASELINE_DRIFT) {
            FormalReferenceProjection.project(
                listOf(
                    round1,
                    FormalReferenceEvent.PickRound(
                        entries, ReferenceScope.all(), "2026-01-15T09:10:00",
                        memoAfter, listOf("p-00000002"), "r-00000002",
                    ),
                ),
                FormalReferenceContext.exact(today, listOf(0L, 0L, 0L, 0L)),
            )
        }
        try {
            FormalReferenceProjection.project(
                listOf(
                    round1,
                    FormalReferenceEvent.PickRound(
                        entries, ReferenceScope.all(), "2026-01-15T09:10:00",
                        memoAfter, listOf("p-00000002"), "r-00000002",
                    ),
                ),
                FormalReferenceContext.exact(today, listOf(0L, 0L, 0L, 0L)),
            )
            fail("expected FORMAL_BASELINE_DRIFT")
        } catch (e: FormalReferenceException) {
            assertTrue("detail should name the pitfall queue: ${e.message}", e.message!!.contains("pitfall-recent-queue"))
        }
    }

    @Test fun oneEmptySideFailsTheWholeRoundEvenWhenTheOtherPoolHasRows() {
        val memos = listOf(memo("m-00000001", world = "主世界"), memo("m-00000002", world = "主世界"))
        val pitfalls = listOf(pitfall("p-00000001"), pitfall("p-00000002"))
        val scope = ReferenceScope.world("主世界", listOf("主世界"))
        rejects(FormalReferenceCodes.EMPTY_POOL) {
            project(
                FormalReferenceEvent.PickRound(memos + pitfalls, scope, ts, emptyList(), emptyList(), "r-00000001"),
                tickets = listOf(0L),
            )
        }
        try {
            project(
                FormalReferenceEvent.PickRound(memos + pitfalls, scope, ts, emptyList(), emptyList(), "r-00000001"),
                tickets = listOf(0L),
            )
            fail("expected FORMAL_EMPTY_POOL")
        } catch (e: FormalReferenceException) {
            assertTrue("detail should name the pitfall pool: ${e.message}", e.message!!.contains("pitfall"))
        }
        // and the mirror case
        val memosNoWorld = listOf(memo("m-00000001"), memo("m-00000002"))
        val pitfallsWorld = listOf(pitfall("p-00000001", world = "主世界"), pitfall("p-00000002", world = "主世界"))
        rejects(FormalReferenceCodes.EMPTY_POOL) {
            project(
                FormalReferenceEvent.PickRound(memosNoWorld + pitfallsWorld, scope, ts, emptyList(), emptyList(), "r-00000002"),
                tickets = listOf(0L),
            )
        }
        // a round with no entries at all cannot fabricate anything either
        rejects(FormalReferenceCodes.EMPTY_POOL) {
            project(
                FormalReferenceEvent.PickRound(emptyList(), ReferenceScope.all(), ts, emptyList(), emptyList(), "r-00000003"),
                tickets = listOf(0L),
            )
        }
    }

    // =====================================================================
    // F. weighted recent base/effective vs uniform
    // =====================================================================

    @Test fun weightedRecentWinnerKeepsBaseInScalarAndShowsHalvedEffectiveOnlyInTheExtension() {
        val entries = listOf(
            memo("m-00000001", due = "2026-01-20"), // 38 units (due_3_7), recent -> 19 effective
            memo("m-00000002"),                     // 20
            memo("m-00000003", archived = true),    // 4
            memo("m-00000004", due = "2026-06-01"), // 14
            pitfall("p-00000001"), pitfall("p-00000002"),
            pitfall("p-00000003"), pitfall("p-00000004"),
        )
        // memo pool size 4 > 3 -> weighted; effective 19+20+4+14 = 57; ticket 5 -> m-00000001
        // pitfall pool size 4 -> weighted; p-00000001 recent -> 10+20+20+20 = 70; ticket 0 -> p-00000001
        val result = project(
            FormalReferenceEvent.PickRound(
                entries, ReferenceScope.all(), ts, listOf("m-00000001"), listOf("p-00000001"), "r-00000001",
            ),
            tickets = listOf(5L, 0L),
        )
        val log = pickLogOf(result.buffers.appendBytes(FormalReferenceLayout.MEMBER_PICK_LOG))
        assertEquals("m-00000001", log.memoId)
        assertEquals("p-00000001", log.pitfallId)
        // the scalar is the BASE weight, never the halved effective one
        assertEquals("1.9", log.memoW)
        assertEquals(ReferenceWeight.decimal(38), log.memoW)
        assertFalse("scalar must not carry the halved effective weight", log.memoW == ReferenceWeight.decimal(19))

        val memoPool = obj(field(obj(forwardOf(log, "candidate_audit_v1")!!), "memo"))
        assertEquals("weighted", text(field(memoPool, "mode")))
        assertEquals(57L, num(field(memoPool, "total_units")))
        val weights = arr(field(memoPool, "weights")).map { obj(it) }
        assertEquals(4, weights.size)
        val winner = weights.first { text(field(it, "id")) == "m-00000001" }
        assertEquals(38L, num(field(winner, "base_units")))
        assertEquals(19L, num(field(winner, "effective_units")))
        assertTrue((field(winner, "recent") as TimeJson.Value.Bool).value)
        // only the recent row is halved
        val other = weights.first { text(field(it, "id")) == "m-00000002" }
        assertEquals(20L, num(field(other, "base_units")))
        assertEquals(20L, num(field(other, "effective_units")))
        // pitfall side reports the same base/effective split
        val pitfallPool = obj(field(obj(forwardOf(log, "candidate_audit_v1")!!), "pitfall"))
        assertEquals(70L, num(field(pitfallPool, "total_units")))
        val pWinner = arr(field(pitfallPool, "weights")).map { obj(it) }
            .first { text(field(it, "id")) == "p-00000001" }
        assertEquals(20L, num(field(pWinner, "base_units")))
        assertEquals(10L, num(field(pWinner, "effective_units")))
    }

    @Test fun uniformPoolCollapsesEveryEffectiveToOneUnitWhileBaseStaysInTheScalar() {
        val entries = listOf(
            memo("m-00000001", due = "2026-01-20"), // 42
            memo("m-00000002"),                     // 20
            memo("m-00000003", archived = true),    // 4
            pitfall("p-00000001"), pitfall("p-00000002"), pitfall("p-00000003"),
        )
        val recentAll = listOf("m-00000001", "m-00000002", "m-00000003")
        val recentP = listOf("p-00000001", "p-00000002", "p-00000003")
        // both pools size 3 <= 3 -> uniform; ticket 1 -> m-00000002, ticket 2 -> p-00000003
        val result = project(
            FormalReferenceEvent.PickRound(entries, ReferenceScope.all(), ts, recentAll, recentP, "r-00000001"),
            tickets = listOf(1L, 2L),
        )
        val log = pickLogOf(result.buffers.appendBytes(FormalReferenceLayout.MEMBER_PICK_LOG))
        assertEquals("m-00000002", log.memoId)
        assertEquals("p-00000003", log.pitfallId)
        assertEquals("1", log.memoW)   // base 20 -> "1"
        assertEquals("1", log.pitfallW) // base 20 -> "1"
        val audit = obj(forwardOf(log, "candidate_audit_v1")!!)
        val memoPool = obj(field(audit, "memo"))
        assertEquals("uniform", text(field(memoPool, "mode")))
        assertEquals(3L, num(field(memoPool, "total_units")))
        arr(field(memoPool, "weights")).map { obj(it) }.forEach {
            assertEquals("uniform collapses every effective unit to 1", 1L, num(field(it, "effective_units")))
        }
        val winner = arr(field(memoPool, "weights")).map { obj(it) }
            .first { text(field(it, "id")) == "m-00000002" }
        assertEquals(20L, num(field(winner, "base_units")))
        assertTrue("recent flag is still reported in uniform mode", (field(winner, "recent") as TimeJson.Value.Bool).value)
        // base and effective genuinely diverge here, unlike a weighted non-recent row
        assertTrue(num(field(winner, "base_units")) != num(field(winner, "effective_units")))
        val pitfallPool = obj(field(audit, "pitfall"))
        assertEquals("uniform", text(field(pitfallPool, "mode")))
        arr(field(pitfallPool, "weights")).map { obj(it) }.forEach {
            assertEquals(1L, num(field(it, "effective_units")))
        }
    }

    // =====================================================================
    // G. witness unsupported set and production_writes_supported=false
    // =====================================================================

    @Test fun witnessDeclaresTheFullUnsupportedSetAndProductionWritesStayClosed() {
        assertFalse(
            "production writes must stay closed",
            FormalReferenceUnsupported.PRODUCTION_WRITES_SUPPORTED,
        )
        val context = FormalReferenceContext.seeded(today, 42L)
        val events = listOf(FormalReferenceEvent.MemoUpsert(memo("m-00000001", due = "2026-01-20")))
        val plan = FormalReferenceWitnessGate.prepare(events, context, FormalReferenceProjection)
        val witness = plan.witness

        // witness output is exactly the declared unsupported set, no more, no less
        assertEquals(FormalReferenceUnsupported.ALL, witness.unsupportedView())
        // the four candidate-conflict markers must be declared, not silently dropped
        for (marker in listOf(
            FormalReferenceUnsupported.WEIGHT_SNAPSHOT_SCHEMA,
            FormalReferenceUnsupported.PITFALL_RECENT_BEFORE_FIELD,
            FormalReferenceUnsupported.OPTIONAL_NULL_CONFLICT,
            FormalReferenceUnsupported.SEED_EXAMPLE_CONFLICT,
        )) {
            assertTrue("$marker must be declared unsupported", marker in witness.unsupportedView())
        }
        assertTrue(FormalReferenceUnsupported.PRODUCTION_WRITE in witness.unsupportedView())

        // the serialized witness carries the same declarations
        val json = obj(TimeJson.parse(witness.describe()))
        assertEquals(TimeJson.Value.Bool(false), field(json, "production_writes_supported"))
        val declared = arr(field(json, "unsupported")).map { text(it) }
        assertEquals(FormalReferenceUnsupported.ALL.sorted(), declared)
        // unsupported keys are not silently emulated as supported ones
        assertFalse(declared.contains("weight_snapshot"))

        // the exposed view is defensive
        try {
            @Suppress("UNCHECKED_CAST")
            (witness.unsupportedView() as MutableSet<String>).add("injected")
            fail("unsupportedView must be unmodifiable")
        } catch (expected: UnsupportedOperationException) {
        }
    }

    @Test fun previewNeverCarriesAppendBytesEvenThoughThePlanDoes() {
        val context = FormalReferenceContext.seeded(today, 42L)
        val events = listOf(FormalReferenceEvent.MemoUpsert(memo("m-00000001")))
        val plan = FormalReferenceWitnessGate.prepare(events, context, FormalReferenceProjection)
        assertFalse("preview must never claim append buffers", plan.preview.appendBuffersPresent)
        assertTrue(plan.preview.descriptions.isNotEmpty())
        assertEquals(plan.witness.describe(), plan.preview.describe())
    }
}
