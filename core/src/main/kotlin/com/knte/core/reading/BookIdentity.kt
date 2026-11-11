package com.knte.core.reading

/** Book identity: bookId is bound explicitly from the caller's source key plus the
 * content hash (deterministic, never random, never implicit); revision is the
 * SHA-256 of the exact book bytes (旧 catalog 的 sha256 字段语义). A position saved
 * for another revision is never silently re-applied. */
data class BookIdentity(val bookId: String, val revision: String,
    val sourceKey: String, val originalName: String) {
    init {
        require(bookId.matches(BOOK_ID)) { "BOOK_ID_INVALID" }
        require(revision.matches(REVISION)) { "REVISION_INVALID" }
        require(sourceKey.isNotEmpty() && sourceKey.length <= MAX_SOURCE_KEY_CHARS &&
            sourceKey.none { it.isISOControl() }) { "SOURCE_KEY_INVALID" }
        require(originalName.length <= MAX_ORIGINAL_NAME_CHARS &&
            originalName.none { it.isISOControl() }) { "ORIGINAL_NAME_INVALID" }
    }

    companion object {
        const val MAX_SOURCE_KEY_CHARS = 240
        const val MAX_ORIGINAL_NAME_CHARS = 240
        val BOOK_ID = Regex("b-[0-9a-f]{8}")
        val REVISION = Regex("[0-9a-f]{64}")

        /** 内容 hash/来源绑定的确定性 bookId（旧格式 b-[0-9a-f]{8}）。 */
        fun bind(sourceKey: String, contentSha256: String, originalName: String): BookIdentity {
            require(contentSha256.matches(REVISION)) { "REVISION_INVALID" }
            val bookId = "b-" + ReadingHash.sha256(
                ("knte-reading-book\n$sourceKey\n$contentSha256").toByteArray(Charsets.UTF_8)
            ).substring(0, 8)
            return BookIdentity(bookId, contentSha256, sourceKey, originalName)
        }

        /** Adopt a legacy/caller-provided id (validated) instead of deriving one. */
        fun existing(bookId: String, revision: String, sourceKey: String, originalName: String): BookIdentity =
            BookIdentity(bookId, revision, sourceKey, originalName)
    }
}
