package com.knte.core.reference

import com.knte.core.time.format.TimeFields

/**
 * Pure two-pool reference material rows (数据契约 v2 §7.1/§7.3: db/memo.jsonl, db/pitfall.jsonl).
 *
 * Rows are immutable validated values. JSONL persistence, the confirmation-card write gate
 * (契约 §十) and cross-file witnessing are repository-layer concerns and are deliberately
 * NOT implemented in this domain. Field caps follow the legacy audited implementation
 * (title 200, body 6000, world 80, 20 tags of 40 chars, pitfall count 1..100000) so rows
 * stay drop-in compatible with the shell line.
 */

enum class ReferencePool(val label: String) {
    MEMO("memo"),
    PITFALL("pitfall");

    companion object {
        fun of(label: String): ReferencePool =
            requireNotNull(values().firstOrNull { it.label == label }) { "POOL_INVALID" }
    }
}

sealed class ReferenceEntry {
    abstract val pool: ReferencePool
    abstract val id: String
    abstract val title: String
    abstract val world: String?
    abstract val body: String?
    abstract val tags: List<String>
    abstract val archived: Boolean
    abstract val createdTs: String
    abstract val ts: String

    /** A memo row: `due` null means the no-deadline tier; done or archived means weight 0.2. */
    data class Memo(
        override val id: String,
        override val title: String,
        override val createdTs: String,
        override val ts: String,
        val due: String? = null,
        val done: Boolean = false,
        override val archived: Boolean = false,
        override val tags: List<String> = emptyList(),
        override val world: String? = null,
        override val body: String? = null,
    ) : ReferenceEntry() {
        init {
            checkId(id, ReferencePool.MEMO)
            checkText(title, TITLE_MAX, true)
            checkStamp(createdTs)
            checkStamp(ts)
            due?.let { checkDue(it) }
            checkTags(tags)
            world?.let { checkText(it, WORLD_MAX, false) }
            body?.let { checkText(it, BODY_MAX, false) }
        }

        override val pool: ReferencePool get() = ReferencePool.MEMO
    }

    /** A pitfall row: `count` is how many times the SAME pitfall was hit, not a row count. */
    data class Pitfall(
        override val id: String,
        override val title: String,
        val count: Int,
        override val createdTs: String,
        override val ts: String,
        override val archived: Boolean = false,
        override val tags: List<String> = emptyList(),
        override val world: String? = null,
        override val body: String? = null,
    ) : ReferenceEntry() {
        init {
            checkId(id, ReferencePool.PITFALL)
            checkText(title, TITLE_MAX, true)
            require(count in 1..MAX_COUNT) { "PITFALL_COUNT_OUT_OF_RANGE" }
            checkStamp(createdTs)
            checkStamp(ts)
            checkTags(tags)
            world?.let { checkText(it, WORLD_MAX, false) }
            body?.let { checkText(it, BODY_MAX, false) }
        }

        override val pool: ReferencePool get() = ReferencePool.PITFALL
    }

    companion object {
        const val TITLE_MAX = 200
        const val BODY_MAX = 6000
        const val WORLD_MAX = 80
        const val STAMP_MAX = 40
        const val MAX_COUNT = 100000

        private val MEMO_ID = Regex("m-[0-9a-f]{8}")
        private val PITFALL_ID = Regex("p-[0-9a-f]{8}")

        internal fun checkId(value: String, pool: ReferencePool) {
            val pattern = if (pool == ReferencePool.MEMO) MEMO_ID else PITFALL_ID
            require(value.matches(pattern)) { "REFERENCE_ID_INVALID" }
        }

        internal fun checkText(value: String, max: Int, required: Boolean): String {
            require('\u0000' !in value) { "REFERENCE_NUL" }
            return TimeFields.limited(value, max, required)
        }

        internal fun checkDue(value: String): String = TimeFields.date(value)

        internal fun checkTags(values: List<String>): List<String> = TimeFields.tags(values)

        internal fun checkStamp(value: String): String = TimeFields.stamp(value)
    }
}
