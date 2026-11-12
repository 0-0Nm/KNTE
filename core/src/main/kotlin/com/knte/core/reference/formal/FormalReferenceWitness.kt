package com.knte.core.reference.formal

import com.knte.core.reference.ReferenceEntry
import com.knte.core.reference.ReferenceScope
import com.knte.core.time.format.TimeJson
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure in-memory witness / confirmation gate for the four formal JSONL members.
 *
 * IMPORTANT: this is an IN-MEMORY validator and a deterministic append-buffer producer.
 * It is NOT a disk-atomic writer, NOT a persistent transaction, and performs NO file IO,
 * NO SAF, NO formal-root resolution, NO migration, NO network, NO Android, NO Store bridge.
 * No automatic recovery, no retry and no silent fallback are implemented. The only thing
 * confirm() returns is a frozen, defensive-copied set of append buffers for the caller to
 * decide what to do with. Applying them to real files is explicitly OUT OF SCOPE.
 */
object FormalReferenceHash {
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun ofJson(value: TimeJson.Value): String =
        sha256(TimeJson.encodeUtf8Strict(TimeJson.encode(value)))

    fun memberHash(member: String, bytes: ByteArray): String =
        sha256(("knte.reference.formal.member/1\u0000$member\u0000").toByteArray(Charsets.UTF_8) + bytes)

    fun inputBaseline(
        events: List<FormalReferenceEvent>,
        context: FormalReferenceContext,
        prefixes: FormalReferencePrefixes,
    ): String = ofJson(
        TimeJson.Value.Obj(
            listOf(
                "model" to TimeJson.Value.Str(FormalReferenceLayout.INPUT_MODEL),
                "context" to context.fingerprint(),
                "prefixes" to prefixes.toJson(),
                "events" to TimeJson.Value.Arr(events.map { FormalInputJson.event(it) }),
            )
        )
    )

    fun tokenOf(
        baseline: String,
        contextFingerprint: String,
        prefixHashes: Map<String, String>,
        memberHashes: Map<String, String>,
        lineCounts: Map<String, Int>,
        descriptions: List<String>,
    ): String = ofJson(
        TimeJson.Value.Obj(
            listOf(
                "kind" to TimeJson.Value.Str("knte.reference.formal.plan/1"),
                "baseline" to TimeJson.Value.Str(baseline),
                "context" to TimeJson.Value.Str(contextFingerprint),
                "prefixes" to hashObj(prefixHashes),
                "members" to hashObj(memberHashes),
                "line_counts" to countObj(lineCounts),
                "descriptions" to TimeJson.Value.Arr(descriptions.map { TimeJson.Value.Str(it) }),
            )
        )
    )

    fun confirmationOf(token: String, baseline: String, memberHashes: Map<String, String>): String =
        ofJson(
            TimeJson.Value.Obj(
                listOf(
                    "kind" to TimeJson.Value.Str("knte.reference.formal.confirm/1"),
                    "token" to TimeJson.Value.Str(token),
                    "baseline" to TimeJson.Value.Str(baseline),
                    "members" to hashObj(memberHashes),
                )
            )
        )

    internal fun hashObj(map: Map<String, String>): TimeJson.Value = TimeJson.Value.Obj(
        FormalReferenceLayout.MEMBERS.map { member ->
            member to TimeJson.Value.Str(requireNotNull(map[member]) { "FORMAL_MEMBER_SET" })
        }
    )

    internal fun countObj(map: Map<String, Int>): TimeJson.Value = TimeJson.Value.Obj(
        FormalReferenceLayout.MEMBERS.map { member ->
            member to TimeJson.Value.Num((map[member] ?: 0).toString())
        }
    )
}

/** Canonical JSON view of the INDEPENDENT typed input. Binds every field, including `body`
 *  (which is NOT a formal line field) and every unknown forward value, so a mutated input
 *  cannot reuse an old plan. */
internal object FormalInputJson {
    fun entry(entry: ReferenceEntry): TimeJson.Value {
        val p = mutableListOf<Pair<String, TimeJson.Value>>()
        p += "pool" to TimeJson.Value.Str(entry.pool.label)
        p += "id" to TimeJson.Value.Str(entry.id)
        p += "title" to TimeJson.Value.Str(entry.title)
        when (entry) {
            is ReferenceEntry.Memo -> {
                entry.due?.let { p += "due" to TimeJson.Value.Str(it) }
                p += "done" to TimeJson.Value.Bool(entry.done)
            }
            is ReferenceEntry.Pitfall -> p += "count" to TimeJson.Value.Num(entry.count.toString())
        }
        p += "archived" to TimeJson.Value.Bool(entry.archived)
        p += "tags" to TimeJson.Value.Arr(entry.tags.map { TimeJson.Value.Str(it) })
        entry.world?.let { p += "world" to TimeJson.Value.Str(it) }
        entry.body?.let { p += "body" to TimeJson.Value.Str(it) }
        p += "created_ts" to TimeJson.Value.Str(entry.createdTs)
        p += "ts" to TimeJson.Value.Str(entry.ts)
        return TimeJson.Value.Obj(p)
    }

    fun scope(scope: ReferenceScope): TimeJson.Value = scope.toJson()

    fun forward(forward: List<Pair<String, TimeJson.Value>>): TimeJson.Value =
        TimeJson.Value.Obj(forward.map { it.first to it.second })

    fun ids(ids: List<String>): TimeJson.Value =
        TimeJson.Value.Arr(ids.map { TimeJson.Value.Str(it) })

    fun event(event: FormalReferenceEvent): TimeJson.Value = when (event) {
        is FormalReferenceEvent.MemoUpsert -> TimeJson.Value.Obj(
            listOf(
                "kind" to TimeJson.Value.Str("memo_upsert"),
                "entry" to entry(event.entry),
                "forward" to forward(event.forward),
            )
        )
        is FormalReferenceEvent.PitfallUpsert -> TimeJson.Value.Obj(
            listOf(
                "kind" to TimeJson.Value.Str("pitfall_upsert"),
                "entry" to entry(event.entry),
                "forward" to forward(event.forward),
            )
        )
        is FormalReferenceEvent.PickRound -> {
            val p = mutableListOf<Pair<String, TimeJson.Value>>()
            p += "kind" to TimeJson.Value.Str("pick_round")
            p += "event_id" to TimeJson.Value.Str(event.eventId)
            p += "ts" to TimeJson.Value.Str(event.ts)
            p += "scope" to scope(event.scope)
            p += "entries" to TimeJson.Value.Arr(event.entries.map { entry(it) })
            p += "memo_recent_before" to ids(event.memoRecentBefore)
            p += "pitfall_recent_before" to ids(event.pitfallRecentBefore)
            event.binding?.let {
                p += "binding" to TimeJson.Value.Obj(
                    listOf(
                        "session" to TimeJson.Value.Str(it.sessionId),
                        "turn" to TimeJson.Value.Str(it.turnId),
                        "hash" to TimeJson.Value.Str(it.responseSha256),
                    )
                )
            }
            p += "forward" to forward(event.forward)
            TimeJson.Value.Obj(p)
        }
        is FormalReferenceEvent.SplitUnsupported -> TimeJson.Value.Obj(
            listOf(
                "kind" to TimeJson.Value.Str("split_unsupported"),
                "ts" to TimeJson.Value.Str(event.ts),
                "reason" to TimeJson.Value.Str(event.reason),
            )
        )
        is FormalReferenceEvent.Unsupported -> TimeJson.Value.Obj(
            listOf(
                "kind" to TimeJson.Value.Str("unsupported"),
                "ts" to TimeJson.Value.Str(event.ts),
                "event_kind" to TimeJson.Value.Str(event.kind),
                "detail" to TimeJson.Value.Str(event.detail),
            )
        )
    }
}

/** Fixed four-member set check. Rejects missing / extra / duplicate / reordered members.
 *  Also used by [FormalReferenceBuffers] and [FormalReferencePrefixes] construction. */
object FormalReferenceMemberSet {
    fun assert(entries: List<Pair<String, ByteArray>>) {
        val names = entries.map { it.first }
        if (names.size != FormalReferenceLayout.MEMBERS.size ||
            names.toSet().size != names.size ||
            names.toSet() != FormalReferenceLayout.MEMBERS.toSet()
        ) {
            throw FormalReferenceException(
                FormalReferenceCodes.MEMBER_SET,
                "expected=${FormalReferenceLayout.MEMBERS} actual=$names",
            )
        }
        if (names != FormalReferenceLayout.MEMBERS) {
            throw FormalReferenceException(
                FormalReferenceCodes.MEMBER_ORDER,
                "expected=${FormalReferenceLayout.MEMBERS} actual=$names",
            )
        }
    }
}

private fun <V> unmodifiable(values: Map<String, V>): Map<String, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))

private fun cloneMap(values: Map<String, ByteArray>): Map<String, ByteArray> {
    val copy = linkedMapOf<String, ByteArray>()
    values.forEach { (k, v) -> copy[k] = v.clone() }
    return copy
}

/** Immutable historical prefix bytes. The projection NEVER modifies them and NEVER emits them:
 *  only the NEW append bytes are produced. All access returns defensive copies. */
class FormalReferencePrefixes private constructor(private val bytes: Map<String, ByteArray>) {
    fun prefixBytes(member: String): ByteArray =
        (bytes[member] ?: throw FormalReferenceException(FormalReferenceCodes.MEMBER_SET, member)).clone()

    fun hashes(): Map<String, String> = unmodifiable(
        FormalReferenceLayout.MEMBERS.associateWith { FormalReferenceHash.memberHash(it, bytes.getValue(it)) }
    )

    fun toJson(): TimeJson.Value = FormalReferenceHash.hashObj(hashes())

    fun snapshot(): FormalReferencePrefixes = FormalReferencePrefixes(cloneMap(bytes))

    companion object {
        fun empty(): FormalReferencePrefixes = of(
            FormalReferenceLayout.MEMBERS.map { it to ByteArray(0) }
        )

        fun of(entries: List<Pair<String, ByteArray>>): FormalReferencePrefixes {
            FormalReferenceMemberSet.assert(entries)
            val copy = linkedMapOf<String, ByteArray>()
            entries.forEach { (k, v) -> copy[k] = v.clone() }
            return FormalReferencePrefixes(unmodifiable(copy) as Map<String, ByteArray>)
        }
    }
}

/** Immutable four append buffers. Every read returns a fresh copy: ByteArray / List / Map
 *  alias mutation by the caller can never change the internal state. */
class FormalReferenceBuffers private constructor(private val bytes: Map<String, ByteArray>) {
    fun memberNames(): List<String> = FormalReferenceLayout.MEMBERS

    fun appendBytes(member: String): ByteArray =
        (bytes[member] ?: throw FormalReferenceException(FormalReferenceCodes.MEMBER_SET, member)).clone()

    fun sizes(): Map<String, Int> = unmodifiable(
        FormalReferenceLayout.MEMBERS.associateWith { bytes.getValue(it).size }
    )

    fun hashes(): Map<String, String> = unmodifiable(
        FormalReferenceLayout.MEMBERS.associateWith { FormalReferenceHash.memberHash(it, bytes.getValue(it)) }
    )

    fun combinedHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FormalReferenceLayout.MEMBERS.forEach { digest.update(bytes.getValue(it)) }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    fun snapshot(): FormalReferenceBuffers = FormalReferenceBuffers(cloneMap(bytes))

    companion object {
        fun of(entries: List<Pair<String, ByteArray>>): FormalReferenceBuffers {
            FormalReferenceMemberSet.assert(entries)
            val copy = linkedMapOf<String, ByteArray>()
            entries.forEach { (k, v) -> copy[k] = v.clone() }
            return FormalReferenceBuffers(unmodifiable(copy) as Map<String, ByteArray>)
        }

        fun empty(): FormalReferenceBuffers = of(
            FormalReferenceLayout.MEMBERS.map { it to ByteArray(0) }
        )
    }
}

/** Frozen cross-file witness: input baseline + four prefix hashes + four append hashes +
 *  line counts + combined output hash + the explicit unsupported declarations. */
class FormalReferenceWitness(
    val inputBaseline: String,
    val contextFingerprint: String,
    prefixHashes: Map<String, String>,
    memberHashes: Map<String, String>,
    lineCounts: Map<String, Int>,
    val combinedHash: String,
    val inputEventCount: Int,
    val planToken: String,
    unsupported: Set<String>,
) {
    val prefixHashes: Map<String, String> = unmodifiable(prefixHashes)
    val memberHashes: Map<String, String> = unmodifiable(memberHashes)
    val lineCounts: Map<String, Int> = unmodifiable(lineCounts)
    val unsupported: Set<String> = Collections.unmodifiableSet(LinkedHashSet(unsupported))

    init {
        require(prefixHashes.keys == FormalReferenceLayout.MEMBERS.toSet()) { "FORMAL_MEMBER_SET" }
        require(memberHashes.keys == FormalReferenceLayout.MEMBERS.toSet()) { "FORMAL_MEMBER_SET" }
        require(lineCounts.keys == FormalReferenceLayout.MEMBERS.toSet()) { "FORMAL_MEMBER_SET" }
    }

    private val prefixView = unmodifiable(prefixHashes)
    private val memberView = unmodifiable(memberHashes)
    private val lineView = unmodifiable(lineCounts)
    private val unsupportedView = Collections.unmodifiableSet(LinkedHashSet(unsupported))

    fun prefixHashesView(): Map<String, String> = prefixView
    fun memberHashesView(): Map<String, String> = memberView
    fun lineCountsView(): Map<String, Int> = lineView
    fun unsupportedView(): Set<String> = unsupportedView

    fun toJson(): TimeJson.Value = TimeJson.Value.Obj(
        listOf(
            "kind" to TimeJson.Value.Str("knte.reference.formal.witness/1"),
            "input_baseline" to TimeJson.Value.Str(inputBaseline),
            "context" to TimeJson.Value.Str(contextFingerprint),
            "prefix_hashes" to FormalReferenceHash.hashObj(prefixView),
            "member_hashes" to FormalReferenceHash.hashObj(memberView),
            "line_counts" to FormalReferenceHash.countObj(lineView),
            "combined_hash" to TimeJson.Value.Str(combinedHash),
            "input_event_count" to TimeJson.Value.Num(inputEventCount.toString()),
            "plan_token" to TimeJson.Value.Str(planToken),
            "production_writes_supported" to TimeJson.Value.Bool(
                FormalReferenceUnsupported.PRODUCTION_WRITES_SUPPORTED
            ),
            "unsupported" to TimeJson.Value.Arr(unsupportedView.sorted().map { TimeJson.Value.Str(it) }),
        )
    )

    fun describe(): String = TimeJson.encode(toJson())
}

/** Non-writable preview. It NEVER carries append bytes. */
class FormalReferencePreview(
    val planToken: String,
    val baseline: String,
    val contextFingerprint: String,
    memberHashes: Map<String, String>,
    lineCounts: Map<String, Int>,
    val witness: FormalReferenceWitness,
    descriptions: List<String>,
) {
    val memberHashes: Map<String, String> = unmodifiable(memberHashes)
    val lineCounts: Map<String, Int> = unmodifiable(lineCounts)
    val descriptions: List<String> = Collections.unmodifiableList(descriptions.toList())

    val appendBuffersPresent: Boolean = false

    private val memberView = unmodifiable(memberHashes)
    private val lineView = unmodifiable(lineCounts)
    private val descriptionsView = Collections.unmodifiableList(descriptions.toList())

    fun memberHashesView(): Map<String, String> = memberView
    fun lineCountsView(): Map<String, Int> = lineView
    fun descriptionsView(): List<String> = descriptionsView
    fun describe(): String = witness.describe()
}

/** A prepared plan. NO public append buffer until [FormalReferenceWitnessGate.confirm]. */
class FormalReferencePlan internal constructor(
    val token: String,
    val confirmation: String,
    val sequence: Long,
    val predecessorToken: String?,
    val baseline: String,
    val contextFingerprint: String,
    memberHashes: Map<String, String>,
    lineCounts: Map<String, Int>,
    val witness: FormalReferenceWitness,
    val preview: FormalReferencePreview,
    internal val buffers: FormalReferenceBuffers,
    internal val prefixes: FormalReferencePrefixes,
) {
    val memberHashes: Map<String, String> = unmodifiable(memberHashes)
    val lineCounts: Map<String, Int> = unmodifiable(lineCounts)

    private val claimed = AtomicBoolean(false)

    internal fun claim(): Boolean = claimed.compareAndSet(false, true)

    fun describe(): String = witness.describe()

    override fun toString(): String =
        "FormalReferencePlan(seq=$sequence token=${token.take(12)} confirmed=false)"
}

/** Result of an explicit, single-use confirmation. Only this object exposes append buffers. */
class FormalReferenceConfirmation internal constructor(
    val token: String,
    val sequence: Long,
    val witness: FormalReferenceWitness,
    private val buffers: FormalReferenceBuffers,
) {
    val applicableInMemory: Boolean = true

    /** Fresh defensive copies each time: caller mutation cannot alter the confirmed result. */
    fun appendBuffers(): FormalReferenceBuffers = buffers.snapshot()

    fun appendBytes(member: String): ByteArray = buffers.appendBytes(member)

    fun describe(): String = witness.describe()

    override fun toString(): String =
        "FormalReferenceConfirmation(seq=$sequence token=${token.take(12)})"
}

/** Projection interface used by the gate to re-project independently at confirm time. */
interface FormalPureProjector {
    fun project(
        events: List<FormalReferenceEvent>,
        context: FormalReferenceContext,
    ): FormalPureResult
}

/** Pure projection output: append buffers only, never historical prefix bytes. */
class FormalPureResult(
    val buffers: FormalReferenceBuffers,
    lineCounts: Map<String, Int>,
    descriptions: List<String>,
) {
    val lineCounts: Map<String, Int> = unmodifiable(lineCounts)
    val descriptions: List<String> = Collections.unmodifiableList(descriptions.toList())

}

/** In-process ownership gate only, not a security sandbox or persistent writer. Tokens
 * are public integrity markers, not secrets or proof of human consent. The embedding UI
 * must obtain consent. Explicit predecessors are checked against this gate's confirmed
 * registry; unrelated plans do not have a global business order. */
object FormalReferenceWitnessGate {
    private val sequenceSource = AtomicLong(0)
    private val issued = IdentityHashMap<FormalReferencePlan, FormalPureProjector>()
    private val confirmed = HashSet<String>()
    private val consumedBaselines = HashSet<String>()
    private const val MAX_PLANS = 4096
    private var issuedBytes = 0L

    private fun fail(code: String, detail: String): Nothing =
        throw FormalReferenceException(code, detail)

    private fun <T> immutable(xs: List<T>): List<T> =
        Collections.unmodifiableList(xs.toList())

    private fun jsonCopy(v: TimeJson.Value): TimeJson.Value = when(v) {
        is TimeJson.Value.Arr -> TimeJson.Value.Arr(immutable(v.items.map { jsonCopy(it) }))
        is TimeJson.Value.Obj -> TimeJson.Value.Obj(immutable(v.entries.map { it.first to jsonCopy(it.second) }))
        else -> v
    }
    private fun entryCopy(e: ReferenceEntry): ReferenceEntry = when(e) {
        is ReferenceEntry.Memo -> e.copy(tags=immutable(e.tags))
        is ReferenceEntry.Pitfall -> e.copy(tags=immutable(e.tags))
    }
    private fun forwardCopy(xs: List<Pair<String,TimeJson.Value>>): List<Pair<String,TimeJson.Value>> {
        FormalChecks.forward(xs,emptySet())
        return immutable(xs.map { it.first to jsonCopy(it.second) })
    }
    private fun freeze(events: List<FormalReferenceEvent>): List<FormalReferenceEvent> {
        if(events.size>FormalReferenceLayout.MAX_EVENTS) fail(FormalReferenceCodes.CAPACITY,"events")
        return immutable(events.map { e -> when(e) {
            is FormalReferenceEvent.MemoUpsert -> e.copy(entry=entryCopy(e.entry) as ReferenceEntry.Memo,forward=forwardCopy(e.forward))
            is FormalReferenceEvent.PitfallUpsert -> e.copy(entry=entryCopy(e.entry) as ReferenceEntry.Pitfall,forward=forwardCopy(e.forward))
            is FormalReferenceEvent.PickRound -> {
                FormalChecks.id(e.eventId,"event")
                e.copy(entries=immutable(e.entries.map { entryCopy(it) }),
                    memoRecentBefore=immutable(e.memoRecentBefore),pitfallRecentBefore=immutable(e.pitfallRecentBefore),
                    scope=ReferenceScope(e.scope.kind,e.scope.label,e.scope.names),forward=forwardCopy(e.forward))
            }
            is FormalReferenceEvent.SplitUnsupported -> e.copy()
            is FormalReferenceEvent.Unsupported -> e.copy()
        } })
    }
    private fun contextCopy(c: FormalReferenceContext): FormalReferenceContext = when {
        c.tickets!=null && c.seed!=null -> FormalReferenceContext.seededTickets(c.today,c.seed,c.tickets.toList())
        c.tickets!=null -> FormalReferenceContext.exact(c.today,c.tickets.toList())
        c.seed!=null -> FormalReferenceContext.seeded(c.today,c.seed)
        else -> fail(FormalReferenceCodes.UNSUPPORTED,"context")
    }
    /** A test-injectable projector is re-invoked, not independently trusted. Validate its
     * bytes, actual line counts, budgets and summaries before accepting its output. */
    private fun project(projector: FormalPureProjector, events: List<FormalReferenceEvent>, c: FormalReferenceContext): FormalPureResult {
        val raw=projector.project(events,c)
        val buffers=raw.buffers.snapshot()
        if(raw.lineCounts.keys!=FormalReferenceLayout.MEMBERS.toSet()) fail(FormalReferenceCodes.MEMBER_SET,"line counts")
        var total=0L
        for(member in FormalReferenceLayout.MEMBERS) {
            val bytes=buffers.appendBytes(member);total+=bytes.size
            if(total>8L*1024*1024) fail(FormalReferenceCodes.CAPACITY,"batch")
            var begin=0;var count=0
            for(i in bytes.indices) if(bytes[i]==10.toByte()) {
                FormalReferenceCodec.decodeLine(member,bytes.copyOfRange(begin,i+1));begin=i+1;count++
                if(count>FormalReferenceLayout.MAX_LINES_PER_MEMBER) fail(FormalReferenceCodes.CAPACITY,"lines")
            }
            if(begin!=bytes.size) fail(FormalReferenceCodes.NOT_SINGLE_LINE,"unterminated member")
            if(raw.lineCounts[member]!=count) fail(FormalReferenceCodes.MEMBER_REPLACED,"line count")
        }
        if(raw.descriptions.size>FormalReferenceLayout.MAX_EVENTS) fail(FormalReferenceCodes.CAPACITY,"descriptions")
        raw.descriptions.forEach { FormalChecks.text(it,6000,"description") }
        return FormalPureResult(buffers,raw.lineCounts,raw.descriptions)
    }
    private fun token(base: String, context: String, prefixes: Map<String,String>, result: FormalPureResult, seq: Long, predecessor: String?): String =
        FormalReferenceHash.ofJson(TimeJson.Value.Obj(listOf(
            "content" to TimeJson.Value.Str(FormalReferenceHash.tokenOf(base,context,prefixes,result.buffers.hashes(),result.lineCounts,result.descriptions)),
            "sequence" to TimeJson.Value.Num(seq.toString()),
            "predecessor" to TimeJson.Value.Str(predecessor.orEmpty()),
            "combined" to TimeJson.Value.Str(result.buffers.combinedHash())
        )))

    @Synchronized
    fun prepare(events: List<FormalReferenceEvent>, context: FormalReferenceContext,
        projector: FormalPureProjector, prefixes: FormalReferencePrefixes = FormalReferencePrefixes.empty(),
        predecessorToken: String? = null): FormalReferencePlan {
        if(issued.size>=MAX_PLANS) fail(FormalReferenceCodes.CAPACITY,"in-memory issued-plan registry full; no eviction")
        val frozen=freeze(events);val c=contextCopy(context);val pref=prefixes.snapshot()
        val baseline=FormalReferenceHash.inputBaseline(frozen,c,pref)
        val cf=FormalReferenceHash.ofJson(c.fingerprint());val result=project(projector,frozen,c)
        val retained = result.buffers.sizes().values.sumOf { it.toLong() }
        if (issuedBytes + retained > 16L * 1024 * 1024)
            fail(FormalReferenceCodes.CAPACITY,"in-memory issued buffer budget full; no eviction")
        val seq=sequenceSource.incrementAndGet();val tok=token(baseline,cf,pref.hashes(),result,seq,predecessorToken)
        val hashes=result.buffers.hashes();val confirmation=FormalReferenceHash.confirmationOf(tok,baseline,hashes)
        val witness=FormalReferenceWitness(baseline,cf,pref.hashes(),hashes,result.lineCounts,result.buffers.combinedHash(),frozen.size,tok,FormalReferenceUnsupported.ALL)
        val preview=FormalReferencePreview(tok,baseline,cf,hashes,result.lineCounts,witness,result.descriptions)
        val plan=FormalReferencePlan(tok,confirmation,seq,predecessorToken,baseline,cf,hashes,result.lineCounts,witness,preview,result.buffers,pref)
        issued[plan]=projector
        issuedBytes += retained
        return plan
    }

    @Synchronized
    fun confirm(plan: FormalReferencePlan, confirmation: String, events: List<FormalReferenceEvent>,
        context: FormalReferenceContext, projector: FormalPureProjector, prefixes: FormalReferencePrefixes,
        precedingConfirmedToken: String? = null, observedBuffers: FormalReferenceBuffers? = null): FormalReferenceConfirmation {
        if(issued[plan] !== projector) fail(FormalReferenceCodes.CONFIRMATION_INVALID,"unissued plan or projector replaced")
        if(confirmation!=plan.confirmation) fail(FormalReferenceCodes.CONFIRMATION_INVALID,"token")
        if(plan.token in confirmed || plan.baseline in consumedBaselines) fail(FormalReferenceCodes.REPLAY,"already confirmed baseline")
        if(plan.predecessorToken!=precedingConfirmedToken || (plan.predecessorToken!=null && plan.predecessorToken !in confirmed))
            fail(FormalReferenceCodes.MEMBER_ORDER,"predecessor has not been confirmed by this gate")
        val frozen=freeze(events);val c=contextCopy(context);val pref=prefixes.snapshot()
        val baseline=FormalReferenceHash.inputBaseline(frozen,c,pref);val cf=FormalReferenceHash.ofJson(c.fingerprint())
        if(baseline!=plan.baseline || cf!=plan.contextFingerprint) fail(FormalReferenceCodes.BASELINE_DRIFT,"input/context/prefix changed")
        val result=project(projector,frozen,c)
        val fresh=token(baseline,cf,pref.hashes(),result,plan.sequence,plan.predecessorToken)
        if(fresh!=plan.token || result.buffers.hashes()!=plan.memberHashes || result.lineCounts!=plan.lineCounts || result.buffers.combinedHash()!=plan.witness.combinedHash)
            fail(FormalReferenceCodes.STALE_CONFIRMATION,"projector output changed")
        if(observedBuffers!=null && observedBuffers.hashes()!=plan.memberHashes) fail(FormalReferenceCodes.MEMBER_REPLACED,"observed member mismatch")
        if(!plan.claim()) fail(FormalReferenceCodes.PLAN_CONSUMED,"claimed")
        confirmed.add(plan.token);consumedBaselines.add(plan.baseline)
        return FormalReferenceConfirmation(plan.token,plan.sequence,plan.witness,result.buffers)
    }
}
