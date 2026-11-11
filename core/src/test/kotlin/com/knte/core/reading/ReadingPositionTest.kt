package com.knte.core.reading

import org.junit.Assert.*
import org.junit.Test

class ReadingPositionTest {
    private fun fails(block: () -> Unit) {
        try { block(); fail("expected refusal") } catch (e: Exception) { }
    }
    private fun book(text: String, sourceKey: String = "test://书"): IndexedBook {
        val bt = BookTextCodec.decode(text.toByteArray(Charsets.UTF_8))
        return IndexedBook(BookIdentity.bind(sourceKey, bt.sha256, "合成"),
            "txt", bt, ChapterIndexer.index(bt, "txt"))
    }

    @Test fun recordAndRestoreSameRevision() {
        val b = book("第一章 甲\n正文一\n")
        val scope = ReadingScope("w-suwen")
        val chapter = b.chapter("c-0001")
        val pos = ReadingPositions.record(b, scope, "c-0001", chapter.startUtf16 + 2, "2026-11-11T22:01:07")
        when (val r = ReadingPositions.resolve(b, scope, pos)) {
            is PositionResolution.Restored -> {
                assertEquals(pos, r.position)
                assertEquals("c-0001", r.chapter.chapterId)
            }
            else -> fail("expected restore")
        }
    }

    @Test fun revisionChangeRequiresRelocation() {
        val scope = ReadingScope("素问")
        val v1 = book("第一章 甲\n正文一\n")
        val pos = ReadingPositions.record(v1, scope, "c-0001", 0, "2026-11-11T22:01:07")
        // 同一显式 bookId、新版内容（revision 变化）：绝不套旧偏移
        val bt2 = BookTextCodec.decode("第一章 甲\n正文一改\n".toByteArray(Charsets.UTF_8))
        val v2 = IndexedBook(BookIdentity.existing(pos.bookId, bt2.sha256, "test://书", "合成"),
            "txt", bt2, ChapterIndexer.index(bt2, "txt"))
        when (val r = ReadingPositions.resolve(v2, scope, pos)) {
            is PositionResolution.RelocationRequired -> assertEquals("REVISION_CHANGED", r.reason)
            else -> fail("expected relocation")
        }
    }

    @Test fun contentRebindAlsoRequiresRelocation() {
        val scope = ReadingScope("素问")
        val v1 = book("第一章 甲\n正文一\n")
        val pos = ReadingPositions.record(v1, scope, "c-0001", 0, "2026-11-11T22:01:07")
        val v2 = book("第一章 甲\n正文一改\n") // bind()：内容变 → bookId 变
        when (val r = ReadingPositions.resolve(v2, scope, pos)) {
            is PositionResolution.RelocationRequired -> assertEquals("BOOK_ID_MISMATCH", r.reason)
            else -> fail("expected relocation")
        }
    }

    @Test fun chapterDriftRefused() {
        val b = book("第一章 甲\n正文\n")
        val scope = ReadingScope("素问")
        val wrong = ReadingPosition(scope.worldRef, b.identity.bookId, b.identity.revision, "c-0001",
            ReadingHash.sha256("篡改指纹".toByteArray(Charsets.UTF_8)), 0, "2026-11-11T22:01:07")
        when (val r = ReadingPositions.resolve(b, scope, wrong)) {
            is PositionResolution.RelocationRequired -> assertEquals("CHAPTER_DRIFTED", r.reason)
            else -> fail("expected refusal")
        }
    }

    @Test fun scopeMismatchRefused() {
        val b = book("第一章 甲\n正文\n")
        val pos = ReadingPositions.record(b, ReadingScope("素问"), "c-0001", 0, "2026-11-11T22:01:07")
        when (val r = ReadingPositions.resolve(b, ReadingScope("w-other"), pos)) {
            is PositionResolution.RelocationRequired -> assertEquals("SCOPE_MISMATCH", r.reason)
            else -> fail("expected refusal")
        }
    }

    @Test fun positionValidation() {
        val b = book("第一章 甲\n正文\n")
        val scope = ReadingScope("素问")
        val good = ReadingPositions.record(b, scope, "c-0001", 0, "2026-11-11T22:01:07")
        fails { good.copy(bookId = "b-ZZZZZZZZ") }
        fails { good.copy(revision = "xyz") }
        fails { good.copy(chapterId = "c-99999") }
        fails { good.copy(offset = -1) }
        fails { good.copy(at = "2026-11-11 22:01:07") }
        fails { good.copy(at = "2026-13-40T99:99:99") }
        fails { ReadingPosition("", b.identity.bookId, b.identity.revision, "c-0001",
            good.chapterFingerprint, 0, "2026-11-11T22:01:07") }
        fails { ReadingPositions.record(b, scope, "c-0001", 999, "2026-11-11T22:01:07") }
    }

    @Test fun noSavedPositionIsExplicit() {
        val b = book("第一章 甲\n正文\n")
        assertEquals(PositionResolution.NoSavedPosition,
            ReadingPositions.resolve(b, ReadingScope("素问"), null))
    }
}
