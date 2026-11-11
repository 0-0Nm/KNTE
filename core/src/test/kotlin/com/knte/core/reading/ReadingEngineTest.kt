package com.knte.core.reading

import com.knte.core.reading.position.FileReadingPositionStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadingEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fails(block: () -> Unit) {
        try { block(); fail("expected refusal") } catch (e: Exception) { }
    }

    @Test fun importBookFileIsReadOnly() {
        val f = tmp.newFile("合成书.txt")
        f.writeText("第一章 甲\n正文一\n")
        val before = f.readBytes()
        val dirBefore = f.parentFile!!.list()!!.toSet()
        val b = ReadingEngine.importBookFile(f, "file://合成书.txt", "合成书.txt", "txt")
        assertEquals(listOf("第一章 甲"), b.toc.map { it.title })
        assertArrayEquals(before, f.readBytes())
        assertEquals(dirBefore, f.parentFile!!.list()!!.toSet()) // 不自动永久复制
    }

    @Test fun endToEndWithPositionStore() {
        val f = tmp.newFile("book.md")
        f.writeText("# 一\n正文一\n## 二\n正文二\n")
        val book = ReadingEngine.importBookFile(f, "file://book.md", "book.md", "md")
        val scope = ReadingScope("w-suwen")
        val dir = tmp.newFolder()
        val store = FileReadingPositionStore(dir)
        val chapter = book.chapter("c-0001")
        val pos = ReadingPositions.record(book, scope, "c-0001", chapter.startUtf16 + 1, "2026-11-11T22:01:07")
        store.save(pos)
        val stored = FileReadingPositionStore(dir).load(scope, pos.bookId)
        assertEquals(pos, stored)
        when (val r = ReadingPositions.resolve(book, scope, stored)) {
            is PositionResolution.Restored -> assertEquals("c-0001", r.chapter.chapterId)
            else -> fail("expected restore")
        }
        // 内容修改后：明确要求重定位，旧偏移绝不套用
        f.writeText("# 一\n正文一改\n## 二\n正文二\n")
        val v2 = ReadingEngine.importBookFile(f, "file://book.md", "book.md", "md")
        when (val r = ReadingPositions.resolve(v2, scope, stored)) {
            is PositionResolution.RelocationRequired -> { /* 旧偏移不套用 */ }
            else -> fail("expected relocation")
        }
        // 位置文件只存指纹/offset，绝不存书内容
        val storeFile = dir.listFiles()!!.single()
        val content = String(storeFile.readBytes(), Charsets.UTF_8)
        assertFalse(content.contains("正文一"))
        assertTrue(content.contains("\"schema\":1"))
    }

    @Test fun formatAndLimitRefused() {
        fails { ReadingEngine.importBook("文".toByteArray(Charsets.UTF_8), "s", "n", "pdf") }
        val big = tmp.newFile("big.txt")
        big.writeBytes(ByteArray(BookTextCodec.MAX_BOOK_BYTES + 1) { 'a'.code.toByte() })
        fails { ReadingEngine.importBookFile(big, "s", "n", "txt") }
    }

    @Test fun importBytesIsSideEffectFree() {
        val bytes = "# 一\n内容\n".toByteArray(Charsets.UTF_8)
        val b = ReadingEngine.importBook(bytes, "saf://合成", "合成.md", "md")
        assertEquals(listOf("一"), b.toc.map { it.title })
        assertArrayEquals(bytes, b.text.rawBytes())
        assertTrue(b.identity.bookId.matches(Regex("b-[0-9a-f]{8}")))
        assertEquals(b.text.sha256, b.identity.revision)
    }
}
