package com.knte.core.time.format

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.LocalTime

/** Strict bounded JSON support for the C-3 time event stream. NOT a general JSON library.
 * Unknown keys are preserved verbatim; unknown/unsupported *values* inside known keys fail closed.
 * License/ownership: written for this project; behaviour mirrors the KNTE Java candidate
 * `LedgerJson`/`TimeCore` reader (same product line, project-owned). */
object TimeJson {
    const val MAX_TEXT_BYTES = 8 * 1024 * 1024

    sealed class Value {
        data class Str(val text: String) : Value()
        data class Num(val literal: String) : Value()
        data class Bool(val value: Boolean) : Value()
        object Null : Value()
        data class Arr(val items: List<Value>) : Value()
        data class Obj(val entries: List<Pair<String, Value>>) : Value()
    }

    fun parse(text: String): Value {
        val p = Parser(text)
        val v = p.value(0)
        p.ws()
        require(p.atEnd()) { "JSON_TRAILING" }
        return v
    }

    fun encode(value: Value): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Value, out: StringBuilder) {
        when (value) {
            is Value.Str -> quote(value.text, out)
            is Value.Num -> out.append(value.literal)
            is Value.Bool -> out.append(if (value.value) "true" else "false")
            Value.Null -> out.append("null")
            is Value.Arr -> {
                out.append('[')
                value.items.forEachIndexed { i, v -> if (i > 0) out.append(','); write(v, out) }
                out.append(']')
            }
            is Value.Obj -> {
                out.append('{')
                value.entries.forEachIndexed { i, e ->
                    if (i > 0) out.append(',')
                    quote(e.first, out); out.append(':'); write(e.second, out)
                }
                out.append('}')
            }
        }
    }

    /** Escapes everything below 0x20 plus quote/backslash; no raw control characters ever emitted. */
    fun quote(text: String, out: StringBuilder) {
        out.append('"')
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> { out.append('\\'); out.append('"') }
                c == '\\' -> { out.append('\\'); out.append('\\') }
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c < ' ' -> out.append("\\u").append(String.format("%04x", c.code))
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    fun quote(text: String): String = StringBuilder().also { quote(text, it) }.toString()

    fun decodeUtf8Strict(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    fun encodeUtf8Strict(text: String): ByteArray {
        require(text.length <= MAX_TEXT_BYTES / 4) { "JSON_TEXT_TOO_LONG" }
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = encoder.encode(CharBuffer.wrap(text))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private class Parser(val s: String) {
        var i = 0
        fun atEnd() = i >= s.length
        fun ws() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
            }
        }
        private fun eat(c: Char): Boolean {
            ws()
            if (i < s.length && s[i] == c) { i++; return true }
            return false
        }
        private fun need(c: Char) { require(eat(c)) { "JSON_DELIMITER_$c" } }

        fun value(depth: Int): Value {
            require(depth <= 16) { "JSON_NESTING_LIMIT" }
            ws()
            require(i < s.length) { "JSON_TRUNCATED" }
            return when (val c = s[i]) {
                '"' -> Value.Str(string())
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                't' -> literal("true").let { Value.Bool(true) }
                'f' -> literal("false").let { Value.Bool(false) }
                'n' -> literal("null").let { Value.Null }
                else -> {
                    require(c == '-' || c in '0'..'9') { "JSON_VALUE" }
                    number()
                }
            }
        }

        private fun literal(word: String) {
            require(s.startsWith(word, i)) { "JSON_LITERAL" }
            i += word.length
        }

        private fun objectValue(depth: Int): Value.Obj {
            i++ // '{'
            val entries = mutableListOf<Pair<String, Value>>()
            if (eat('}')) return Value.Obj(entries)
            do {
                ws()
                require(i < s.length && s[i] == '"') { "JSON_KEY" }
                val key = string()
                require(entries.none { it.first == key }) { "JSON_DUPLICATE_KEY" }
                need(':')
                entries += key to value(depth + 1)
            } while (eat(','))
            need('}')
            return Value.Obj(entries)
        }

        private fun arrayValue(depth: Int): Value.Arr {
            i++ // '['
            val items = mutableListOf<Value>()
            if (eat(']')) return Value.Arr(items)
            do { items += value(depth + 1) } while (eat(','))
            need(']')
            return Value.Arr(items)
        }

        private fun number(): Value.Num {
            val start = i
            if (i < s.length && s[i] == '-') i++
            require(i < s.length && s[i] in '0'..'9') { "JSON_NUMBER" }
            if (s[i] == '0') {
                i++
                require(i >= s.length || s[i] !in '0'..'9') { "JSON_LEADING_ZERO" }
            } else {
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && s[i] == '.') {
                i++
                require(i < s.length && s[i] in '0'..'9') { "JSON_FRACTION" }
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                require(i < s.length && s[i] in '0'..'9') { "JSON_EXPONENT" }
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val literal = s.substring(start, i)
            require(literal.length <= 64) { "JSON_NUMBER_LENGTH" }
            return Value.Num(literal)
        }

        private fun string(): String {
            i++ // opening quote
            val out = StringBuilder()
            var closed = false
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') { closed = true; break }
                require(c >= ' ') { "JSON_CONTROL_CHAR" }
                if (c != '\\') { out.append(c); continue }
                require(i < s.length) { "JSON_ESCAPE" }
                when (val e = s[i++]) {
                    '"', '\\', '/' -> out.append(e)
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        require(i + 4 <= s.length) { "JSON_UNICODE_ESCAPE" }
                        val hex = s.substring(i, i + 4)
                        require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "JSON_UNICODE_HEX" }
                        out.append(hex.toInt(16).toChar())
                        i += 4
                    }
                    else -> require(false) { "JSON_ESCAPE_TYPE" }
                }
            }
            require(closed) { "JSON_UNTERMINATED_STRING" }
            val text = out.toString()
            var j = 0
            while (j < text.length) {
                val c = text[j]
                if (Character.isHighSurrogate(c)) {
                    require(j + 1 < text.length && Character.isLowSurrogate(text[j + 1])) { "JSON_SURROGATE_PAIR" }
                    j += 2
                    continue
                }
                require(!Character.isLowSurrogate(c)) { "JSON_SURROGATE_PAIR" }
                j++
            }
            return text
        }
    }
}

/** Value accessors that never guess: a wrong JSON shape is an error, not a default. */
object TimeJsonValues {
    fun str(value: TimeJson.Value): String =
        (value as? TimeJson.Value.Str)?.text ?: error("JSON_NOT_STRING")

    fun bool(value: TimeJson.Value): Boolean =
        (value as? TimeJson.Value.Bool)?.value ?: error("JSON_NOT_BOOL")

    fun strList(value: TimeJson.Value): List<String> {
        val arr = value as? TimeJson.Value.Arr ?: error("JSON_NOT_ARRAY")
        return arr.items.map { str(it) }
    }
}

/** Field validation shared by the event codec and the projection reader. */
object TimeFields {
    val AMEND_FIELDS: List<String> =
        listOf("start", "end", "project", "tags", "note", "date", "end_date")

    fun id(value: String): String {
        require(value.matches(Regex("t-[A-Za-z0-9_-]{1,64}"))) { "TIME_ID_INVALID" }
        return value
    }

    fun date(value: String): String {
        require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) { "DATE_FORMAT_INVALID" }
        require(LocalDate.parse(value).toString() == value) { "DATE_NOT_REAL" }
        return value
    }

    fun hm(value: String): String {
        require(value.matches(Regex("[0-9]{2}:[0-9]{2}"))) { "TIME_FORMAT_INVALID" }
        require(LocalTime.parse(value).toString() == value) { "TIME_NOT_REAL" }
        return value
    }

    /** ISO second precision, local time. Field ranges are validated by parsing each part; the
     *  textual shape must stay exactly `YYYY-MM-DDTHH:MM:SS` (zero seconds included). */
    fun stamp(value: String): String {
        require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"))) {
            "TS_FORMAT_INVALID"
        }
        val day = value.substring(0, 10)
        val clock = value.substring(11, 16)
        val seconds = value.substring(17)
        require(date(day) == day && hm(clock) == clock) { "TS_NOT_REAL" }
        require(seconds.toInt() in 0..59) { "TS_SECONDS_INVALID" }
        return value
    }

    fun limited(value: String, max: Int, nonEmpty: Boolean): String {
        require(value.codePointCount(0, value.length) <= max) { "TEXT_TOO_LONG" }
        require(!nonEmpty || value.isNotBlank()) { "TEXT_BLANK" }
        TimeJson.encodeUtf8Strict(value)
        return value
    }

    fun tags(value: List<String>): List<String> {
        require(value.size <= 20) { "TAGS_TOO_MANY" }
        value.forEach { limited(it, 40, true) }
        return value.toList()
    }

    fun repeatId(value: String): String = limited(value, 100, true)
}
