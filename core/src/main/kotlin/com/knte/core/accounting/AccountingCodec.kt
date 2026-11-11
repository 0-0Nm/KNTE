package com.knte.core.accounting

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Shared strict primitives for the offline accounting domain.
 * Category enum, field names, and append-only framing follow 数据契约_v2 §五/C-2 and the
 * legacy Java store (knte-shell 0.18.x AccountingCore/LedgerJson): amount is exact cents,
 * money text always has two decimals, and numbers never pass through Double. */
object AccountingCodec {
    const val DATASET = "db/accounting.jsonl"
    const val MAX_FILE_BYTES = 2 * 1024 * 1024
    const val MAX_ROWS = 5000
    const val MAX_LINE_CHARS = 32768
    const val MAX_CENTS = 999_999_999_99L // 999999999.99 in cents
    const val MAX_RAW_CHARS = 4096
    const val MAX_LOCATION_CHARS = 200
    const val MAX_NOTE_CODE_POINTS = 20
    const val SOURCE_MANUAL = "manual"
    const val SOURCE_AI = "ai"
    const val AT_TRUST_UNVERIFIED = "unverified"

    /** Closed 10-category enum, verbatim from 数据契约_v2 §五 (same order as the legacy store). */
    val CATEGORIES: List<String> = listOf(
        "餐饮", "交通", "学习", "购物", "医疗", "居住", "娱乐", "人情", "通讯", "其他"
    )

    private val idPattern = Pattern.compile("r-[0-9a-f]{8}")
    private val datePattern = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private val tsPattern = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}")
    private val amountTextPattern = Pattern.compile("[0-9]{1,9}(?:\\.[0-9]{1,2})?")
    private val jsonNumber = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    private val hex4 = Pattern.compile("[0-9a-fA-F]{4}")
    private val random = SecureRandom()
    private val isoSeconds = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

    val EMPTY_REVISION: String = hash(ByteArray(0))

    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

    /** Strict UTF-8: rejects malformed sequences, unmappable characters, and NUL. */
    fun encode(text: String): ByteArray {
        require('\u0000' !in text) { "TEXT_NUL_REJECTED" }
        val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    fun decode(bytes: ByteArray): String {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require('\u0000' !in text) { "TEXT_NUL_REJECTED" }
        return text
    }

    fun nowTimestamp(): String = LocalDateTime.now().format(isoSeconds)

    fun newRecordId(): String {
        val bytes = ByteArray(4); random.nextBytes(bytes)
        return "r-" + bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }

    fun validId(id: String): Boolean = idPattern.matcher(id).matches()
    fun validDate(value: String): Boolean = datePattern.matcher(value).matches() &&
        runCatching { LocalDate.parse(value).toString() == value }.getOrDefault(false)

    fun validTimestamp(value: String): Boolean = tsPattern.matcher(value).matches() &&
        runCatching { LocalDateTime.parse(value) }.isSuccess

    fun validAmountText(value: String): Boolean = amountTextPattern.matcher(value).matches()

    /** Exact cents normalization; never routes through Double.
     * Rejects zero, out-of-range values, and sub-cent precision (no guessing, no rounding). */
    fun centsOf(value: BigDecimal, income: Boolean): Long {
        val cents = try {
            value.movePointRight(2).longValueExact()
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("AMOUNT_INVALID")
        }
        if (cents == 0L || cents > MAX_CENTS) throw IllegalArgumentException("AMOUNT_INVALID")
        return if (income) cents else -cents
    }

    /** Exact cents for an already-signed ledger amount (read path). Null when not exactly representable. */
    fun signedCentsOf(value: BigDecimal): Long? = try {
        val cents = value.movePointRight(2).longValueExact()
        if (cents == 0L || cents > MAX_CENTS || cents < -MAX_CENTS) null else cents
    } catch (e: ArithmeticException) {
        null
    }

    fun money(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    // ---- Strict JSON (Kotlin port of the legacy pure-Java LedgerJson; no library dependency) ----

    /** Parses one JSON document. Objects keep insertion order (LinkedHashMap); numbers are
     * BigDecimal; duplicate keys, trailing content, unescaped controls, lone surrogates,
     * non-finite literals, and over-deep/over-long tokens are rejected. */
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.value(0)
        parser.ws()
        if (parser.i != text.length) throw IllegalArgumentException("JSON_TRAILING_CONTENT")
        return value
    }

    fun obj(value: Any?): LinkedHashMap<String, Any?> {
        if (value !is LinkedHashMap<*, *>) throw IllegalArgumentException("NOT_AN_OBJECT")
        @Suppress("UNCHECKED_CAST")
        return value as LinkedHashMap<String, Any?>
    }

    fun quote(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' || c == '\\' -> b.append('\\').append(c)
                c < ' ' -> b.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> b.append(c)
            }
        }
        return b.append('"').toString()
    }

    private class Parser internal constructor(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && " \r\n\t".indexOf(s[i]) >= 0) i++ }
        fun eat(c: Char): Boolean { ws(); if (i < s.length && s[i] == c) { i++; return true }; return false }
        fun need(c: Char) { if (!eat(c)) throw IllegalArgumentException("JSON_DELIMITER") }
        fun value(depth: Int): Any? {
            if (depth > 16) throw IllegalArgumentException("JSON_NESTING_LIMIT")
            ws()
            if (i >= s.length) throw IllegalArgumentException("JSON_TRUNCATED")
            val c = s[i]
            if (c == '"') return string()
            if (c == '{') {
                i++
                val m = LinkedHashMap<String, Any?>()
                if (eat('}')) return m
                while (true) {
                    ws()
                    if (i >= s.length || s[i] != '"') throw IllegalArgumentException("JSON_KEY")
                    val k = string()
                    need(':')
                    if (m.containsKey(k)) throw IllegalArgumentException("JSON_DUPLICATE_KEY")
                    m[k] = value(depth + 1)
                    if (eat(',')) continue
                    need('}')
                    return m
                }
            }
            if (c == '[') {
                i++
                val a = ArrayList<Any?>()
                if (eat(']')) return a
                while (true) {
                    a.add(value(depth + 1))
                    if (eat(',')) continue
                    need(']')
                    return a
                }
            }
            if (s.startsWith("true", i)) { i += 4; return java.lang.Boolean.TRUE }
            if (s.startsWith("false", i)) { i += 5; return java.lang.Boolean.FALSE }
            if (s.startsWith("null", i)) { i += 4; return null }
            val match: Matcher = jsonNumber.matcher(s)
            match.region(i, s.length)
            if (!match.lookingAt()) throw IllegalArgumentException("JSON_VALUE")
            val number = match.group()
            if (number.length > 64) throw IllegalArgumentException("JSON_NUMERIC_LENGTH")
            val n = BigDecimal(number)
            if (n.precision() > 50 || Math.abs(n.scale().toLong()) > 50)
                throw IllegalArgumentException("JSON_NUMERIC_BOUNDS")
            i = match.end()
            return n
        }
        fun string(): String {
            i++
            val b = StringBuilder()
            var end = false
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') { end = true; break }
                if (c < ' ') throw IllegalArgumentException("JSON_UNESCAPED_CONTROL")
                if (c != '\\') { b.append(c); continue }
                if (i >= s.length) throw IllegalArgumentException("JSON_ESCAPE")
                val e = s[i++]
                when (e) {
                    '"', '\\', '/' -> b.append(e)
                    'b' -> b.append('\b')
                    'f' -> b.append('\u000C')
                    'n' -> b.append('\n')
                    'r' -> b.append('\r')
                    't' -> b.append('\t')
                    'u' -> {
                        if (i + 4 > s.length) throw IllegalArgumentException("JSON_UNICODE_ESCAPE")
                        val h = s.substring(i, i + 4)
                        if (!hex4.matcher(h).matches()) throw IllegalArgumentException("JSON_UNICODE_HEX")
                        b.append(h.toInt(16).toChar()); i += 4
                    }
                    else -> throw IllegalArgumentException("JSON_ESCAPE_TYPE")
                }
            }
            if (!end) throw IllegalArgumentException("JSON_UNTERMINATED_STRING")
            val result = b.toString()
            var j = 0
            while (j < result.length) {
                val c = result[j]
                if (Character.isHighSurrogate(c)) {
                    if (++j >= result.length || !Character.isLowSurrogate(result[j]))
                        throw IllegalArgumentException("JSON_UNICODE_PAIR")
                } else if (Character.isLowSurrogate(c)) throw IllegalArgumentException("JSON_UNICODE_PAIR")
                j++
            }
            return result
        }
    }
}
