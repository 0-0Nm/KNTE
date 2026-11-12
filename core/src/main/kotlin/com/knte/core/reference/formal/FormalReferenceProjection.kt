package com.knte.core.reference.formal

import com.knte.core.reference.Choice
import com.knte.core.time.format.TimeJson
import com.knte.core.reference.ReferencePool
import com.knte.core.reference.ReferenceSelect
import com.knte.core.reference.ReferenceWeight
import com.knte.core.reference.RecentQueue
import java.io.ByteArrayOutputStream
import java.util.Collections

/**
 * Pure typed-event -> four-member append projection (candidate implementation, NOT a
 * normative contract schema; parent review gate applies).
 *
 * Semantics (each point is a deliberate, documented choice):
 *  - A top-level list copy is made for direct pure callers. Nested input mutation during
 *    project() is unsupported; the confirmation gate deep-freezes input before invoking it.
 *    Inputs are never mutated by this implementation.
 *  - One [FormalReferenceContext.newRandom] stream is drawn once per project() call and shared
 *    by every pick in it; `today` is the explicit caller context, never a clock read.
 *  - Upserts map 1:1 onto [FormalMemoLine]/[FormalPitfallLine]. A non-null `body` fails closed
 *    with FORMAL_UNSUPPORTED_FIELD: the formal upsert line has no body key, so carrying a body
 *    would be a silent data drop. PickRound entries may carry `body` (a pick line records only
 *    ids/weights - no row payload is part of that line's schema at all).
 *  - Each PickRound emits exactly 1 pick_log line + 2 memo_pick queue lines (one per pool).
 *    Either pool empty fails FORMAL_EMPTY_POOL instead of fabricating a pick, so the 1+2
 *    shape is invariant. Split/unsupported events fail the whole projection - no partial output.
 *  - Memo and pitfall recent queues are independent (two queues, contract §7.4). Within one
 *    projection the queues must stay continuous: round N+1's *_recent_before must equal
 *    [RecentQueue.advance] of round N's before-queue with round N's winner, else
 *    FORMAL_BASELINE_DRIFT. Duplicate event ids (and a [com.knte.core.reference.TurnBinding]
 *    whose derived event id disagrees with the round id) fail FORMAL_REPLAY.
 *  - pick_log `memo_w`/`pitfall_w` carry the picked row's BASE weight
 *    ([ReferenceWeight.decimal] of [ReferenceWeight.units]) - the only literal that is always
 *    inside the frozen [FormalChecks.weightLiteral] range [0.2, 50000.5]. Effective units
 *    (uniform-mode 1 unit = 0.05, recent-halved archived 0.1) can fall below it, so they are
 *    NOT invented here: callers forward them as candidate extension keys when wanted.
 *  - The pitfall recent queue has no pick_log field
 *    ([FormalReferenceUnsupported.PITFALL_RECENT_BEFORE_FIELD]); it drives the pitfall draw and
 *    the continuity check only. Event `forward` (caller-supplied candidate extensions such as
 *    weight_snapshot) is attached to the pick_log audit line only, never duplicated onto the
 *    queue lines.
 *  - No file IO, no network, no Store bridge, no clock: see [FormalReferenceUnsupported].
 */
object FormalReferenceProjection : FormalPureProjector {

    override fun project(
        events: List<FormalReferenceEvent>,
        context: FormalReferenceContext,
    ): FormalPureResult {
        checkBounds(events)

        // Frozen snapshot of the caller's list: later caller-side mutation cannot affect this run.
        val frozen = events.toList()

        // ONE rng stream shared by every draw of this projection (explicit today, no clock).
        val random = context.newRandom()

        val streams = LinkedHashMap<String, ByteArrayOutputStream>()
        val counts = LinkedHashMap<String, Int>()
        FormalReferenceLayout.MEMBERS.forEach {
            streams[it] = ByteArrayOutputStream()
            counts[it] = 0
        }
        val descriptions = ArrayList<String>(frozen.size)
        val seenEventIds = HashSet<String>()
        var memoRecent: List<String>? = null
        var pitfallRecent: List<String>? = null

        frozen.forEach { event ->
            when (event) {
                is FormalReferenceEvent.MemoUpsert -> {
                    val entry = event.entry
                    if (entry.body != null) {
                        fail(FormalReferenceCodes.UNSUPPORTED_FIELD, "body:${entry.id}")
                    }
                    append(
                        streams, counts, FormalReferenceLayout.MEMBER_MEMO,
                        FormalMemoLine(
                            id = entry.id,
                            title = entry.title,
                            due = entry.due,
                            done = entry.done,
                            archived = entry.archived,
                            tags = entry.tags,
                            world = entry.world,
                            createdTs = entry.createdTs,
                            ts = entry.ts,
                            forward = event.forward,
                        ),
                    )
                    descriptions += "memo-upsert:${entry.id}"
                }

                is FormalReferenceEvent.PitfallUpsert -> {
                    val entry = event.entry
                    if (entry.body != null) {
                        fail(FormalReferenceCodes.UNSUPPORTED_FIELD, "body:${entry.id}")
                    }
                    append(
                        streams, counts, FormalReferenceLayout.MEMBER_PITFALL,
                        FormalPitfallLine(
                            id = entry.id,
                            title = entry.title,
                            count = entry.count,
                            archived = entry.archived,
                            tags = entry.tags,
                            world = entry.world,
                            createdTs = entry.createdTs,
                            ts = entry.ts,
                            forward = event.forward,
                        ),
                    )
                    descriptions += "pitfall-upsert:${entry.id}"
                }

                is FormalReferenceEvent.PickRound -> {
                    FormalChecks.stamp(event.ts)
                    FormalChecks.id(event.eventId, "event")
                    if (!seenEventIds.add(event.eventId)) {
                        fail(FormalReferenceCodes.REPLAY, "event:${event.eventId}")
                    }
                    event.binding?.let { binding ->
                        if (binding.eventId != event.eventId) {
                            fail(FormalReferenceCodes.REPLAY, "binding-event:${event.eventId}")
                        }
                    }
                    val entryIds = event.entries.map { it.id }
                    if (entryIds.toSet().size != entryIds.size) {
                        fail(FormalReferenceCodes.DUPLICATE_KEY, "pick-entries:${event.eventId}")
                    }
                    if (event.memoRecentBefore.size > FormalReferenceLayout.N_RECENT) {
                        fail(FormalReferenceCodes.CAPACITY, "memo-recent-before")
                    }
                    event.memoRecentBefore.forEach {
                        FormalChecks.id(it, FormalReferenceLayout.MEMBER_MEMO)
                    }
                    if (event.pitfallRecentBefore.size > FormalReferenceLayout.N_RECENT) {
                        fail(FormalReferenceCodes.CAPACITY, "pitfall-recent-before")
                    }
                    event.pitfallRecentBefore.forEach {
                        FormalChecks.id(it, FormalReferenceLayout.MEMBER_PITFALL)
                    }
                    // Queue continuity across rounds of one projection (drift = inconsistent input).
                    if (memoRecent != null && memoRecent != event.memoRecentBefore) {
                        fail(FormalReferenceCodes.BASELINE_DRIFT, "memo-recent-queue:${event.eventId}")
                    }
                    if (pitfallRecent != null && pitfallRecent != event.pitfallRecentBefore) {
                        fail(
                            FormalReferenceCodes.BASELINE_DRIFT,
                            "pitfall-recent-queue:${event.eventId}",
                        )
                    }

                    val memoChoice = ReferenceSelect.draw(
                        event.entries,
                        ReferencePool.MEMO,
                        event.scope,
                        context.today,
                        event.memoRecentBefore,
                        random,
                    ) ?: fail(FormalReferenceCodes.EMPTY_POOL, "memo:${event.eventId}")
                    val pitfallChoice = ReferenceSelect.draw(
                        event.entries,
                        ReferencePool.PITFALL,
                        event.scope,
                        context.today,
                        event.pitfallRecentBefore,
                        random,
                    ) ?: fail(FormalReferenceCodes.EMPTY_POOL, "pitfall:${event.eventId}")

                    append(
                        streams, counts, FormalReferenceLayout.MEMBER_PICK_LOG,
                        FormalPickLogLine(
                            turn = event.ts,
                            memoId = memoChoice.entry.id,
                            memoW = ReferenceWeight.decimal(memoChoice.candidate.baseUnits),
                            pitfallId = pitfallChoice.entry.id,
                            pitfallW = ReferenceWeight.decimal(pitfallChoice.candidate.baseUnits),
                            recentBefore = event.memoRecentBefore,
                            ts = event.ts,
                            forward = candidateAudit(event.forward, memoChoice, pitfallChoice,
                                event.memoRecentBefore, event.pitfallRecentBefore),
                        ),
                    )
                    append(
                        streams, counts, FormalReferenceLayout.MEMBER_MEMO_PICK,
                        FormalMemoPickLine(
                            FormalReferenceLayout.MEMBER_MEMO,
                            RecentQueue.advance(event.memoRecentBefore, memoChoice.entry.id),
                            event.ts,
                        ),
                    )
                    append(
                        streams, counts, FormalReferenceLayout.MEMBER_MEMO_PICK,
                        FormalMemoPickLine(
                            FormalReferenceLayout.MEMBER_PITFALL,
                            RecentQueue.advance(event.pitfallRecentBefore, pitfallChoice.entry.id),
                            event.ts,
                        ),
                    )
                    descriptions +=
                        "pick:${event.eventId}:${memoChoice.entry.id}+${pitfallChoice.entry.id}"

                    memoRecent = RecentQueue.advance(event.memoRecentBefore, memoChoice.entry.id)
                    pitfallRecent =
                        RecentQueue.advance(event.pitfallRecentBefore, pitfallChoice.entry.id)
                }

                is FormalReferenceEvent.SplitUnsupported ->
                    fail(FormalReferenceCodes.UNSUPPORTED_SPLIT, event.reason)

                is FormalReferenceEvent.Unsupported ->
                    fail(
                        FormalReferenceCodes.UNSUPPORTED_EVENT,
                        "${event.kind}:${event.detail}",
                    )
            }
        }

        val buffers = FormalReferenceBuffers.of(
            FormalReferenceLayout.MEMBERS.map { it to streams.getValue(it).toByteArray() },
        )
        val lineCounts = Collections.unmodifiableMap(LinkedHashMap(counts))
        return FormalPureResult(buffers, lineCounts, descriptions.toList())
    }


    /** Explicitly provisional audit extension, never asserted as the normative v2 shape.
     * Actual computed candidate weights/tickets are frozen, never supplied by the caller. */
    private fun candidateAudit(
        forward: List<Pair<String, TimeJson.Value>>, memo: Choice, pitfall: Choice,
        memoBefore: List<String>, pitfallBefore: List<String>,
    ): List<Pair<String, TimeJson.Value>> {
        if (forward.any { it.first == "candidate_audit_v1" })
            fail(FormalReferenceCodes.FORWARD_KNOWN_KEY, "candidate_audit_v1")
        fun ids(xs: List<String>) = TimeJson.Value.Arr(xs.map { TimeJson.Value.Str(it) })
        fun pool(choice: Choice, before: List<String>): TimeJson.Value = TimeJson.Value.Obj(listOf(
            "recent_before" to ids(before),
            "picked_id" to TimeJson.Value.Str(choice.entry.id),
            "mode" to TimeJson.Value.Str(if(choice.snapshot.uniform) "uniform" else "weighted"),
            "ticket" to TimeJson.Value.Num(choice.ticket.toString()),
            "total_units" to TimeJson.Value.Num(choice.snapshot.totalUnits.toString()),
            "weights" to TimeJson.Value.Arr(choice.snapshot.candidates.map { c ->
                TimeJson.Value.Obj(listOf(
                    "id" to TimeJson.Value.Str(c.id),
                    "base_units" to TimeJson.Value.Num(c.baseUnits.toString()),
                    "effective_units" to TimeJson.Value.Num(c.effectiveUnits.toString()),
                    "recent" to TimeJson.Value.Bool(c.recent)
                ))
            })
        ))
        return forward + ("candidate_audit_v1" to TimeJson.Value.Obj(listOf(
            "memo" to pool(memo,memoBefore), "pitfall" to pool(pitfall,pitfallBefore))))
    }

    /** Capacity is checked BEFORE any buffer byte is produced: no giant buffer can be built. */
    private fun checkBounds(events: List<FormalReferenceEvent>) {
        if (events.size > FormalReferenceLayout.MAX_EVENTS) {
            fail(FormalReferenceCodes.CAPACITY, "events>${FormalReferenceLayout.MAX_EVENTS}")
        }
        var memoLines = 0
        var pitfallLines = 0
        var pickQueueLines = 0
        var pickLogLines = 0
        events.forEach { event ->
            when (event) {
                is FormalReferenceEvent.MemoUpsert -> memoLines++
                is FormalReferenceEvent.PitfallUpsert -> pitfallLines++
                is FormalReferenceEvent.PickRound -> {
                    pickQueueLines += 2
                    pickLogLines++
                }
                else -> Unit
            }
        }
        val max = FormalReferenceLayout.MAX_LINES_PER_MEMBER
        if (memoLines > max) fail(FormalReferenceCodes.CAPACITY, "memo-lines>$max")
        if (pitfallLines > max) fail(FormalReferenceCodes.CAPACITY, "pitfall-lines>$max")
        if (pickQueueLines > max) fail(FormalReferenceCodes.CAPACITY, "memo-pick-lines>$max")
        if (pickLogLines > max) fail(FormalReferenceCodes.CAPACITY, "pick-log-lines>$max")
    }

    private fun append(
        streams: MutableMap<String, ByteArrayOutputStream>,
        counts: MutableMap<String, Int>,
        member: String,
        line: FormalReferenceLine,
    ) {
        // encodeLine enforces canonical UTF-8 (no BOM), single LF line, byte cap, strict surrogates.
        val bytes = FormalReferenceCodec.encodeLine(line)
        val total = streams.values.sumOf { it.size().toLong() }
        if (total + bytes.size > 8L * 1024 * 1024)
            fail(FormalReferenceCodes.CAPACITY, "candidate append batch exceeds 8 MiB")
        streams.getValue(member).write(bytes)
        counts[member] = counts.getValue(member) + 1
    }
}
