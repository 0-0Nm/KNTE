package com.knte.preview.source

/** Injected persistence for a user-selected root.
 * The parent supplies a SharedPreferences-backed implementation AFTER taking
 * persistable permission with user-granted flags. A real device treeUri may be
 * stored by that implementation, but must NEVER be copied into reports, logs or UI. */
interface SourcePreferences {
    fun load(): SourceRoot?
    fun save(root: SourceRoot?)
}

/** Logic-only default: keeps the binding in memory for this process (not durable).
 * Deliberately never exposes the treeUri through toString(). */
class InMemorySourcePreferences(initial: SourceRoot? = null) : SourcePreferences {
    @Volatile private var current: SourceRoot? = initial
    override fun load(): SourceRoot? = current
    override fun save(root: SourceRoot?) { current = root }
    override fun toString(): String = "InMemorySourcePreferences(bound=${current != null})"
}
