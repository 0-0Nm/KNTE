package com.knte.core.reference

import java.security.MessageDigest
import java.time.LocalDate

internal val TEST_TODAY: LocalDate = LocalDate.parse("2026-11-11")

internal fun memoEntry(
    id: String,
    title: String = "备忘",
    due: String? = null,
    done: Boolean = false,
    archived: Boolean = false,
    world: String? = null,
    createdTs: String = "2026-10-28T09:00:00",
    ts: String = "2026-10-28T09:00:00",
): ReferenceEntry.Memo = ReferenceEntry.Memo(
    id = id,
    title = title,
    createdTs = createdTs,
    ts = ts,
    due = due,
    done = done,
    archived = archived,
    world = world,
)

internal fun pitfallEntry(
    id: String,
    title: String = "吃一堑",
    count: Int = 1,
    archived: Boolean = false,
    world: String? = null,
    createdTs: String = "2026-10-28T09:00:00",
    ts: String = "2026-10-28T09:00:00",
): ReferenceEntry.Pitfall = ReferenceEntry.Pitfall(
    id = id,
    title = title,
    count = count,
    createdTs = createdTs,
    ts = ts,
    archived = archived,
    world = world,
)

/** Weighted memo pool: 42 / 30 / 20 / 14 units in id order. */
internal fun fourMemos(): List<ReferenceEntry> = listOf(
    memoEntry("m-00000001", title = "今天到期", due = TEST_TODAY.toString()),
    memoEntry("m-00000002", title = "三十天内", due = TEST_TODAY.plusDays(30).toString()),
    memoEntry("m-00000003", title = "九十天内", due = TEST_TODAY.plusDays(90).toString()),
    memoEntry("m-00000004", title = "远期", due = TEST_TODAY.plusDays(3650).toString()),
)

/** Weighted pitfall pool: 20 / 30 / 40 / 4 units in id order. */
internal fun fourPitfalls(): List<ReferenceEntry> = listOf(
    pitfallEntry("p-00000001", title = "熬夜", count = 1),
    pitfallEntry("p-00000002", title = "拖延", count = 2),
    pitfallEntry("p-00000003", title = "熬夜到三点", count = 3),
    pitfallEntry("p-00000004", title = "旧坑", count = 1, archived = true),
)

/** Serves fixed tickets in order; cycles back to the first when exhausted, unless [cycle]
 *  is false, which fails once the script runs out. */
internal class ScriptedRandom(private val tickets: LongArray, private val cycle: Boolean = true) : RandomSource {
    private var index = 0

    override fun next(bound: Long): Long {
        val position = if (cycle) index % tickets.size else index
        if (!cycle) require(position < tickets.size) { "SCRIPT_RANDOM_EXHAUSTED" }
        index += 1
        return tickets[position]
    }
}

internal fun validBinding(hash: String = "a".repeat(64)): TurnBinding =
    TurnBinding("s-" + "1".repeat(32), "t-" + "2".repeat(32), hash)

/** Independent SHA-256 probe (does not call the domain's own hasher). */
internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

internal fun fails(block: () -> Unit) {
    try {
        block()
        throw AssertionError("expected refusal")
    } catch (refused: Exception) {
        // fail-closed path accepted
    }
}
