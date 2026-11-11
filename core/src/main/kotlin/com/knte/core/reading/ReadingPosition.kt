package com.knte.core.reading

import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/** 进度 = bookIdentity + revision + chapterId/offset（旧语义：真实打开过的位置，
 * 不是读完/学完）。旧 revision 的位置绝不套用到新内容：解析结果明确要求重定位。 */
data class ReadingPosition(val worldRef: String, val bookId: String, val revision: String,
    val chapterId: String, val chapterFingerprint: String, val offset: Int, val at: String) {
    init {
        require(worldRef.isNotEmpty() && worldRef.length <= ReadingScope.MAX_WORLD_REF_CHARS &&
            worldRef.none { it.isISOControl() } && worldRef == worldRef.trim { it <= ' ' }) {
            "POSITION_WORLD_REF_INVALID"
        }
        require(bookId.matches(BookIdentity.BOOK_ID)) { "POSITION_BOOK_ID_INVALID" }
        require(revision.matches(BookIdentity.REVISION)) { "POSITION_REVISION_INVALID" }
        require(chapterId.matches(CHAPTER_ID)) { "POSITION_CHAPTER_ID_INVALID" }
        require(chapterFingerprint.matches(BookIdentity.REVISION)) { "POSITION_FINGERPRINT_INVALID" }
        require(offset >= 0) { "POSITION_OFFSET_INVALID" }
        require(at.matches(TIMESTAMP)) { "POSITION_TIMESTAMP_INVALID" }
        try {
            LocalDateTime.parse(at)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("POSITION_TIMESTAMP_INVALID", e)
        }
    }

    companion object {
        val CHAPTER_ID = Regex("c-[0-9]{4}")
        val TIMESTAMP = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}")
    }
}

sealed class PositionResolution {
    object NoSavedPosition : PositionResolution()
    data class Restored(val position: ReadingPosition, val chapter: ChapterRef) : PositionResolution()
    data class RelocationRequired(val reason: String, val savedRevision: String) : PositionResolution()
}

object ReadingPositions {
    /** 从真实打开的书记录位置：章节指纹来自当前书的精确字节。 */
    fun record(book: IndexedBook, scope: ReadingScope, chapterId: String, offset: Int, at: String): ReadingPosition {
        val ref = book.chapter(chapterId)
        require(offset in ref.startUtf16..ref.endUtf16) { "POSITION_OFFSET_INVALID" }
        return ReadingPosition(scope.worldRef, book.identity.bookId, book.identity.revision,
            chapterId, ref.fingerprint, offset, at)
    }

    /** 旧位置只在 bookId、revision、章节指纹、范围全部对上时恢复；任何漂移都
     * 明确要求重定位，绝不把旧偏移套到新内容上。 */
    fun resolve(book: IndexedBook, scope: ReadingScope, stored: ReadingPosition?): PositionResolution {
        if (stored == null) return PositionResolution.NoSavedPosition
        if (stored.worldRef != scope.worldRef)
            return PositionResolution.RelocationRequired("SCOPE_MISMATCH", stored.revision)
        if (stored.bookId != book.identity.bookId)
            return PositionResolution.RelocationRequired("BOOK_ID_MISMATCH", stored.revision)
        if (stored.revision != book.identity.revision)
            return PositionResolution.RelocationRequired("REVISION_CHANGED", stored.revision)
        val heading = book.index.byId[stored.chapterId]
            ?: return PositionResolution.RelocationRequired("CHAPTER_MISSING", stored.revision)
        val ref = book.chapter(heading.chapterId)
        if (stored.chapterFingerprint != ref.fingerprint)
            return PositionResolution.RelocationRequired("CHAPTER_DRIFTED", stored.revision)
        if (stored.offset < ref.startUtf16 || stored.offset > ref.endUtf16)
            return PositionResolution.RelocationRequired("OFFSET_DRIFTED", stored.revision)
        return PositionResolution.Restored(stored, ref)
    }
}
