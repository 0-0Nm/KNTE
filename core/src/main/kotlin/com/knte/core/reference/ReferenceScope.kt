package com.knte.core.reference

import com.knte.core.time.format.TimeJson
import com.knte.core.time.format.TimeJsonValues
import java.util.Collections
import java.util.LinkedHashSet

/**
 * World scope as a pure read-side projection (数据契约 v2 §八): filtering only, never
 * migrates data. kind = all | world | unclassified; the alias cap follows the legacy
 * audited implementation. Unknown keys on parse are tolerated (契约 §三 读侧容错).
 */
class ReferenceScope(
    val kind: String,
    val label: String,
    names: Collection<String>,
) {
    val names: Set<String>

    init {
        require(kind in KINDS) { "SCOPE_KIND_INVALID" }
        ReferenceEntry.checkText(label, LABEL_MAX, true)
        names.forEach { ReferenceEntry.checkText(it, NAME_MAX, true) }
        require(names.size <= ALIAS_LIMIT) { "SCOPE_ALIAS_LIMIT" }
        this.names = Collections.unmodifiableSet(LinkedHashSet(names))
    }

    fun includes(entry: ReferenceEntry): Boolean = when (kind) {
        KIND_ALL -> true
        KIND_WORLD -> entry.world != null && names.contains(entry.world)
        else -> entry.world == null || !names.contains(entry.world)
    }

    fun toJson(): TimeJson.Value = TimeJson.Value.Obj(
        listOf(
            "kind" to TimeJson.Value.Str(kind),
            "label" to TimeJson.Value.Str(label),
            "names" to TimeJson.Value.Arr(names.map { TimeJson.Value.Str(it) }),
        )
    )

    override fun equals(other: Any?): Boolean =
        other is ReferenceScope && other.kind == kind && other.label == label && other.names == names

    override fun hashCode(): Int = kind.hashCode() * 31 + label.hashCode() + names.hashCode()

    override fun toString(): String = "ReferenceScope($kind, $label, $names)"

    companion object {
        const val KIND_ALL = "all"
        const val KIND_WORLD = "world"
        const val KIND_UNCLASSIFIED = "unclassified"
        const val LABEL_MAX = 120
        const val NAME_MAX = 80
        const val ALIAS_LIMIT = 10100
        val KINDS = setOf(KIND_ALL, KIND_WORLD, KIND_UNCLASSIFIED)

        fun all(): ReferenceScope = ReferenceScope(KIND_ALL, "全部世界", emptyList())

        fun world(label: String, names: Collection<String>): ReferenceScope =
            ReferenceScope(KIND_WORLD, label, names)

        fun unclassified(label: String, names: Collection<String>): ReferenceScope =
            ReferenceScope(KIND_UNCLASSIFIED, label, names)

        fun parse(value: TimeJson.Value): ReferenceScope {
            val obj = value as? TimeJson.Value.Obj
                ?: throw IllegalArgumentException("SCOPE_JSON_SHAPE")
            fun field(name: String): TimeJson.Value =
                requireNotNull(obj.entries.firstOrNull { it.first == name }?.second) {
                    "SCOPE_FIELD_MISSING"
                }
            return ReferenceScope(
                kind = TimeJsonValues.str(field("kind")),
                label = TimeJsonValues.str(field("label")),
                names = TimeJsonValues.strList(field("names")),
            )
        }
    }
}
