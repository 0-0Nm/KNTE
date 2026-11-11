package com.knte.core.reading

import org.junit.Assert.*
import org.junit.Test

class IndexedBookReadTest {
    private fun fails(block: () -> Unit) {
        try { block(); fail("expected refusal") } catch (e: Exception) { }
    }
    private fun book(text: String, format: String = "md"): IndexedBook {
        val bt = BookTextCodec.decode(text.toByteArray(Charsets.UTF_8))
        return IndexedBook(BookIdentity.bind("test-source://合成", bt.sha256, "合成书"),
            format, bt, ChapterIndexer.index(bt, format))
    }

    @Test fun tocToChapterExactOriginalText() {
        val text = "# 一\n正文一\r\n第二行\n## 二\n正文二\n"
        val b = book(text)
        assertEquals(listOf("一", "二"), b.toc.map { it.title })
        val c1 = b.readChapter("c-0001")
        assertEquals(text.substring(text.indexOf("# 一"), text.indexOf("## 二")), c1.text)
        assertEquals("一", c1.chapter.title)
        assertEquals(b.revision, c1.revision)
        assertArrayEquals(b.text.rawBytes().copyOfRange(c1.chapter.startByte, c1.chapter.endByte),
            c1.slice.bytesCopy())
        assertEquals(ReadingHash.sha256(c1.slice.bytesCopy()), c1.chapter.fingerprint)
    }

    @Test fun chapterRangesContiguousAndCoverText() {
        val text = "前言\n# 一\na\n# 二\nb\n"
        val b = book(text)
        val refs = b.toc.map { b.chapter(it.chapterId) }
        assertEquals(0, refs[0].startUtf16)
        for (i in 0 until refs.size - 1) assertEquals(refs[i].endUtf16, refs[i + 1].startUtf16)
        assertEquals(b.text.sourceChars, refs.last().endUtf16)
    }

    @Test fun readSliceRangeBindingVerifiable() {
        val text = "# 一\n正文一二三\n"
        val b = book(text)
        val chapter = b.chapter("c-0001")
        val slice = b.readSlice("c-0001", chapter.startUtf16, chapter.endUtf16)
        assertEquals(b.revision, slice.revision)
        assertEquals("c-0001", slice.chapterId)
        assertEquals(ReadingHash.sha256(slice.slice.bytesCopy()), slice.slice.sha256)
        assertEquals(text.substring(chapter.startUtf16, chapter.endUtf16), slice.text)
    }

    @Test fun readSliceRefusesOutOfRangeAndSplits() {
        val text = "# 一\n😀表情\n# 二\nx\n"
        val b = book(text)
        val c1 = b.chapter("c-0001")
        fails { b.readSlice("c-0001", c1.startUtf16 - 1, c1.endUtf16) }
        fails { b.readSlice("c-0001", c1.startUtf16, c1.endUtf16 + 1) }
        fails { b.readSlice("c-9999", 0, 1) }
        val emoji = text.indexOf("😀")
        fails { b.readSlice("c-0001", emoji + 1, c1.endUtf16) } // 代理对内部
    }

    @Test fun readingNeverMutatesSource() {
        val b = book("# 一\n内容\n")
        val before = b.text.rawBytes()
        b.toc
        b.readChapter("c-0001")
        b.readSlice("c-0001", 0, 2)
        assertArrayEquals(before, b.text.rawBytes())
    }

    @Test fun unknownChapterRefused() {
        val b = book("# 一\n内容\n")
        fails { b.chapter("c-9999") }
        fails { b.readChapter("c-02") }
    }
}
