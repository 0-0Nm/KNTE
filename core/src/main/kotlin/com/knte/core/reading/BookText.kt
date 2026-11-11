package com.knte.core.reading

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

private const val BOOK_TEXT_SAMPLE = 1024

/** One verifiable slice of the original book bytes: exact text, exact UTF-16 range,
 * exact byte range and content hash. */
class TextSlice internal constructor(val text: String, val startUtf16: Int, val endUtf16: Int,
    val startByte: Int, val endByte: Int, val sha256: String, bytes: ByteArray) {
    private val owned: ByteArray = bytes.clone()
    fun bytesCopy(): ByteArray = owned.clone()
}

/** Strict bounded UTF-8 book text. Original bytes are preserved verbatim: BOM, CRLF,
 * LF and lone CR all survive, and UTF-16 offsets follow the legacy Java candidate
 * exactly (a leading U+FEFF, when present, is char index 0 — same as BookChapters /
 * BookFiles). Nothing is normalized or fabricated; empty or oversized input is
 * rejected, never truncated. */
class BookText internal constructor(bytes: ByteArray, val text: String) {
    private val owned: ByteArray = bytes.clone()
    fun rawBytes(): ByteArray = owned.clone()
    val hasBom: Boolean = text.startsWith('\uFEFF')
    val sourceChars: Int = text.length
    val sha256: String = ReadingHash.sha256(owned)
    val crlfCount: Int
    val lfCount: Int
    val crCount: Int
    private val sampleByteOffsets: IntArray
    private val sampleCharOffsets: IntArray

    init {
        var crlf = 0
        var lf = 0
        var cr = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') { crlf++; i += 2 }
            else if (c == '\r') { cr++; i++ }
            else if (c == '\n') { lf++; i++ }
            else i++
        }
        crlfCount = crlf
        lfCount = lf
        crCount = cr
        // Byte offset of every BOOK_TEXT_SAMPLE-th char, so any UTF-16 position can be
        // mapped to the original bytes exactly and cheaply.
        val sampleCount = text.length / BOOK_TEXT_SAMPLE + 1
        val map = IntArray(sampleCount)
        val chars = IntArray(sampleCount)
        var bytePos = 0
        var previous = 0
        for (k in 0 until sampleCount) {
            var at = k * BOOK_TEXT_SAMPLE
            if (at < text.length && Character.isLowSurrogate(text[at])) at--
            bytePos += BookTextCodec.encodeStrict(text.substring(previous, at)).size
            chars[k] = at
            map[k] = bytePos
            previous = at
        }
        sampleByteOffsets = map
        sampleCharOffsets = chars
    }

    fun byteOffsetOf(utf16: Int): Int {
        require(utf16 in 0..sourceChars) { "OFFSET_OUT_OF_RANGE" }
        require(utf16 == sourceChars || !Character.isLowSurrogate(text[utf16])) { "SLICE_CHAR_BOUNDARY" }
        val k = utf16 / BOOK_TEXT_SAMPLE
        return sampleByteOffsets[k] +
            BookTextCodec.encodeStrict(text.substring(sampleCharOffsets[k], utf16)).size
    }

    /** Exact original-text slice. Ranges that split a surrogate pair are rejected —
     * the implementation never truncates or extends to hide a boundary error. */
    fun slice(startUtf16: Int, endUtf16: Int): TextSlice {
        require(startUtf16 in 0..sourceChars) { "SLICE_RANGE_INVALID" }
        require(endUtf16 in startUtf16..sourceChars) { "SLICE_RANGE_INVALID" }
        if (endUtf16 > startUtf16) {
            require(!Character.isLowSurrogate(text[startUtf16])) { "SLICE_CHAR_BOUNDARY" }
            require(!Character.isHighSurrogate(text[endUtf16 - 1])) { "SLICE_CHAR_BOUNDARY" }
        }
        val startByte = byteOffsetOf(startUtf16)
        val endByte = byteOffsetOf(endUtf16)
        val bytes = owned.copyOfRange(startByte, endByte)
        return TextSlice(text.substring(startUtf16, endUtf16), startUtf16, endUtf16,
            startByte, endByte, ReadingHash.sha256(bytes), bytes)
    }
}

object BookTextCodec {
    /** 旧 BookFiles.MAX_TEXT_BYTES：内置文本导入上限 8MiB，超限拒绝不截断。 */
    const val MAX_BOOK_BYTES = 8 * 1024 * 1024

    fun decode(bytes: ByteArray): BookText {
        require(bytes.size <= MAX_BOOK_BYTES) { "BOOK_SIZE_LIMIT" }
        require(bytes.isNotEmpty()) { "BOOK_EMPTY" }
        val text = decodeStrict(bytes)
        if (text == "\uFEFF") throw IllegalArgumentException("BOOK_EMPTY") // BOM-only file
        return BookText(bytes, text)
    }

    fun encodeStrict(text: String): ByteArray = try {
        val buffer = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } catch (e: CharacterCodingException) {
        throw IllegalArgumentException("UTF8_INVALID", e)
    }

    private fun decodeStrict(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        throw IllegalArgumentException("UTF8_INVALID", e)
    }
}
