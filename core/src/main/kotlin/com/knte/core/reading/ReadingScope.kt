package com.knte.core.reading

/** Explicit world scope for reading data (数据契约_v2 §8: world is a read-side
 * projection field; switching worlds never migrates data). The reading core never
 * creates, resolves or defaults a world: every scoped API requires the caller to
 * pass an explicit reference (world id like "w-suwen" or display name like "素问").
 * With no world connected this layer is plain local reading; it never pretends to
 * be an AI classroom. */
data class ReadingScope(val worldRef: String) {
    init {
        require(worldRef.isNotEmpty() && worldRef.length <= MAX_WORLD_REF_CHARS &&
            worldRef.none { it.isISOControl() } && worldRef == worldRef.trim { it <= ' ' }) {
            "SCOPE_WORLD_REF_INVALID"
        }
    }

    companion object {
        const val MAX_WORLD_REF_CHARS = 80
    }
}
