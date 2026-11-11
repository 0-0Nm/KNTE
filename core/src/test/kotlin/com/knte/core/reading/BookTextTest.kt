package com.knte.core.reading

import org.junit.Assert.*
import org.junit.Test

class BookTextTest {
    private fun fails(block: () -> Unit) {
        try { block(); fail("expected refusal") } catch (e: Exception) { }
    }

    @Test fun strictUtf8RejectsInvalidBytes() {
        fails { BookTextCodec.decode(byteArrayOf(0x61, 0xFF.toByte(), 0x80.toByte())) }
        fails { BookTextCodec.decode("正文".toByteArray(Charsets.UTF_8) + byteArrayOf(0xC0.toByte(), 0xAF.toByte())) }
        fails { BookTextCodec.decode(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())) } // surrogate half
    }

    @Test fun bomCrlfLfCrPreservedExactly() {
        val raw = "\uFEFF第一行\r\n第二行\n第三行\r末行".toByteArray(Charsets.UTF_8)
        val book = BookTextCodec.decode(raw)
        assertTrue(book.hasBom)
        assertEquals(1, book.crlfCount)
        assertEquals(1, book.lfCount)
        assertEquals(1, book.crCount)
        assertArrayEquals(raw, BookTextCodec.encodeStrict(book.text)) // round-trip identity
        assertEquals(0, book.byteOffsetOf(0))
        assertEquals(3, book.byteOffsetOf(1)) // BOM = 3 bytes
        assertEquals(raw.size, book.byteOffsetOf(book.sourceChars))
        val slice = book.slice(1, 4)
        assertEquals("第一行", slice.text)
        assertEquals(3, slice.startByte)
        assertArrayEquals("第一行".toByteArray(Charsets.UTF_8), slice.bytesCopy())
    }

    @Test fun emptyAndBomOnlyRejected() {
        fails { BookTextCodec.decode(byteArrayOf()) }
        fails { BookTextCodec.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) }
    }

    @Test fun overLimitRejectedNotTruncated() {
        val big = ByteArray(BookTextCodec.MAX_BOOK_BYTES + 1) { 'a'.code.toByte() }
        fails { BookTextCodec.decode(big) }
    }

    @Test fun byteOffsetsMatchAcrossCjkAndAscii() {
        val text = "abc正文def结尾"
        val book = BookTextCodec.decode(text.toByteArray(Charsets.UTF_8))
        for (i in 0..text.length) {
            val expected = text.substring(0, i).toByteArray(Charsets.UTF_8).size
            assertEquals("at $i", expected, book.byteOffsetOf(i))
        }
    }

    @Test fun sliceRejectsSurrogateSplits() {
        val text = "前😀后" // 前(0) 高代理(1) 低代理(2) 后(3)
        val book = BookTextCodec.decode(text.toByteArray(Charsets.UTF_8))
        fails { book.slice(2, 4) } // start落在代理对内部
        fails { book.slice(0, 2) } // end落在代理对内部
        val ok = book.slice(0, text.length)
        assertEquals(text, ok.text)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), ok.bytesCopy())
    }
    @Test fun surrogatePairCrossingSamplingBoundaryDoesNotRejectValidBook() {
        val text = "x".repeat(1023) + "😀" + "正文"
        val raw = BookTextCodec.encodeStrict(text)
        val book = BookTextCodec.decode(raw)
        assertArrayEquals(raw, book.rawBytes())
        assertEquals(1023, book.byteOffsetOf(1023))
        assertEquals(1027, book.byteOffsetOf(1025))
        assertEquals("😀", book.slice(1023,1025).text)
        try { book.byteOffsetOf(1024); fail("split surrogate must be refused") }
        catch (e: IllegalArgumentException) { assertEquals("SLICE_CHAR_BOUNDARY",e.message) }
    }

}
