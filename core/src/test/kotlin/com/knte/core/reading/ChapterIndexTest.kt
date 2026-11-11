package com.knte.core.reading

import org.junit.Assert.*
import org.junit.Test

class ChapterIndexTest {
    private fun fails(block: () -> Unit) {
        try { block(); fail("expected refusal") } catch (e: Exception) { }
    }
    private fun index(text: String, format: String): Pair<BookText, ChapterIndex> {
        val book = BookTextCodec.decode(text.toByteArray(Charsets.UTF_8))
        return book to ChapterIndexer.index(book, format)
    }

    @Test fun markdownAtxGolden() {
        val text = "# 甲\n## 乙\n### 丙\n#### 丁\n##### 戊\n###### 己\n####### 庚\n#无空格\n# 辛 #\n"
        val (book, idx) = index(text, "md")
        assertEquals(listOf("甲", "乙", "丙", "丁", "戊", "己", "辛"), idx.headings.map { it.title })
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 1), idx.headings.map { it.depth })
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 9), idx.headings.map { it.line })
        assertEquals("c-0001", idx.headings[0].chapterId)
        assertEquals("c-0007", idx.headings[6].chapterId)
        assertEquals(text.indexOf("# 甲"), idx.headings[0].offset)
        assertEquals(text.indexOf("# 辛"), idx.headings[6].offset)
        assertFalse(idx.singleWholeTextChapter)
        assertTrue(idx.duplicateTitles.isEmpty())
    }

    @Test fun setextGolden() {
        val text = "标题甲\n===\n正文\n标题乙\n---\n"
        val (book, idx) = index(text, "md")
        assertEquals(listOf("标题甲", "标题乙"), idx.headings.map { it.title })
        assertEquals(listOf(1, 2), idx.headings.map { it.depth })
        assertEquals(text.indexOf("标题甲"), idx.headings[0].offset)
        assertEquals(listOf(1, 4), idx.headings.map { it.line })
    }

    @Test fun yamlAndFencesSkipped() {
        val text = "---\ntitle: 假\n---\n# 真\n\n```\n# 假一\n第一章 假二\n```\n### 真二\n"
        val (book, idx) = index(text, "md")
        assertEquals(listOf("正文起点", "真", "真二"), idx.headings.map { it.title })
        assertTrue(idx.headings[0].preamble)
        assertEquals(0, idx.headings[0].offset)
    }

    @Test fun chineseChapterGoldenTxt() {
        val text = "第一章 起点\n正文甲\n第二章 转折\n第十二章 十二\n第100章 百\n" +
            "Chapter 4 后记\npart v 小写\n他念了第一章就睡了\n第节 无数字\n"
        val (book, idx) = index(text, "txt")
        assertEquals(listOf("第一章 起点", "第二章 转折", "第十二章 十二", "第100章 百",
            "Chapter 4 后记", "part v 小写"), idx.headings.map { it.title })
        assertEquals(listOf(1, 3, 4, 5, 6, 7), idx.headings.map { it.line })
        assertEquals(idx.headings.size, idx.byId.size)
    }

    @Test fun txtWithoutHeadingsIsOneActualChapter() {
        val (book, idx) = index("只是普通文本，没有章题。\n第二行。\n", "txt")
        assertTrue(idx.singleWholeTextChapter)
        assertEquals(1, idx.headings.size)
        assertEquals("正文起点", idx.headings[0].title)
        assertEquals(0, idx.headings[0].offset)
        assertTrue(idx.headings[0].preamble)
    }

    @Test fun atxIgnoredInTxtFormat() {
        val (book, idx) = index("# 一\n正文\n", "txt")
        assertTrue(idx.singleWholeTextChapter)
        assertEquals("正文起点", idx.headings[0].title)
    }

    @Test fun longAtxTitleRejectedNotTruncated() {
        val long = "长".repeat(200)
        val text = "# $long\n# 短\n"
        val (book, idx) = index(text, "md")
        assertEquals(listOf("正文起点", "短"), idx.headings.map { it.title })
        val b = IndexedBook(BookIdentity.bind("s://x", book.sha256, "n"), "md", book, idx)
        val c = b.readChapter("c-0001")
        assertEquals("# $long\n", c.text) // 200字原样保留，未截断
        assertEquals(203, c.text.length)
    }

    @Test fun longChineseLineIsNotATitle() {
        val long = "零".repeat(200)
        val (book, idx) = index("第${long}章 超长\n正文\n", "txt")
        assertTrue(idx.singleWholeTextChapter)
    }

    @Test fun duplicateTitlesKeptDistinctAndDisclosed() {
        val text = "## 备注\n甲\n## 备注\n乙\n"
        val (book, idx) = index(text, "md")
        assertEquals(listOf("备注", "备注"), idx.headings.map { it.title })
        assertEquals(listOf("c-0001", "c-0002"), idx.headings.map { it.chapterId })
        assertEquals(listOf("备注"), idx.duplicateTitles)
    }

    @Test fun tooManyHeadingsRejected() {
        val sb = StringBuilder()
        for (i in 1..501) sb.append("# 第").append(i).append("节\n")
        fails { index(sb.toString(), "md") }
    }

    @Test fun crOnlyLineEndingsWork() {
        val text = "第一章 甲\r正文\r第二章 乙\r"
        val (book, idx) = index(text, "txt")
        assertEquals(listOf("第一章 甲", "第二章 乙"), idx.headings.map { it.title })
    }

    @Test fun bomOnFirstLineDoesNotHideChapter() {
        val text = "\uFEFF第一章 起点\n正文\n"
        val (book, idx) = index(text, "txt")
        assertEquals(listOf("第一章 起点"), idx.headings.map { it.title })
        assertEquals(0, idx.headings[0].offset) // 旧兼容：BOM 计入位置 0
    }

    @Test fun preambleBecomesActualChapter() {
        val text = "书名\n前言文字\n\n# 一\n内容\n"
        val (book, idx) = index(text, "md")
        assertEquals(listOf("正文起点", "一"), idx.headings.map { it.title })
        assertTrue(idx.headings[0].preamble)
        assertEquals(0, idx.headings[0].offset)
        assertEquals(text.indexOf("# 一"), idx.headings[1].offset)
    }

    @Test fun blankPreambleOmitted() {
        val (book, idx) = index("\n\n# 一\n", "md")
        assertEquals(listOf("一"), idx.headings.map { it.title })
        assertFalse(idx.headings[0].preamble)
    }
}
