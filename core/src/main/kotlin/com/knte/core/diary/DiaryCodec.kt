package com.knte.core.diary

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.LocalDate

/** Bounded UTF-8/frontmatter subset, NOT a general YAML implementation.
 * Unknown metadata is never rebuilt. Ambiguous recognized fields are read-only. */
data class DiaryMetadata(val date: String?, val source: String?, val type: String?,
                         val world: String?, val tags: List<String>)
class DiaryDocument internal constructor(bytes: ByteArray, val body: String,
    val metadata: DiaryMetadata?, val editable: Boolean, val problems: List<String>,
    internal val bodyOffset: Int) {
    private val content = bytes.clone()
    val revision: String = DiaryCodec.hash(content)
    fun bytes(): ByteArray = content.clone()
}
object DiaryCodec {
    const val MAX_FILE_BYTES = 1024 * 1024
    const val MAX_BODY_CHARS = 50000
    private val keys = setOf("date", "DIARY", "source", "type", "world", "tags")
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun encode(text: String): ByteArray {
        require('\u0000' !in text) { "NUL_REJECTED" }
        val b = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        return ByteArray(b.remaining()).also { b.get(it) }
    }
    private fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().also { require('\u0000' !in it) { "NUL_REJECTED" } }
    fun validDate(value: String): Boolean = value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
        runCatching { LocalDate.parse(value).toString() == value }.getOrDefault(false)
    fun parse(bytes: ByteArray): DiaryDocument {
        require(bytes.size <= MAX_FILE_BYTES) { "DIARY_SIZE_LIMIT" }
        val text = decode(bytes)
        val bom = if (text.startsWith('\uFEFF')) 1 else 0
        data class Line(val start: Int, val end: Int, val value: String)
        val lines = mutableListOf<Line>()
        var pos = bom
        while (pos < text.length) {
            val lf = text.indexOf('\n', pos)
            val end = if (lf < 0) text.length else lf + 1
            val raw = text.substring(pos, if (lf < 0) end else lf)
            lines += Line(pos, end, raw.removeSuffix("\r"))
            pos = end
        }
        if (lines.firstOrNull()?.value != "---") {
            val body = text.substring(bom)
            return DiaryDocument(bytes, body, null, body.length <= MAX_BODY_CHARS,
                if (body.length > MAX_BODY_CHARS) listOf("BODY_LIMIT_READ_ONLY") else emptyList(),
                if (bom == 1) 3 else 0)
        }
        val close = (1 until lines.size).firstOrNull { lines[it].value == "---" }
            ?: return DiaryDocument(bytes, "", null, false, listOf("UNCLOSED_FRONTMATTER"), bytes.size)
        val values = mutableMapOf<String, String>()
        val problems = mutableListOf<String>()
        for (line in lines.subList(1, close)) {
            val v = line.value
            if (v.isBlank() || v.trimStart().startsWith('#')) continue
            // Complex/indented YAML cannot be interpreted safely by this subset parser.
            if (v.first().isWhitespace() || ':' !in v) { problems += "COMPLEX_YAML_READ_ONLY"; continue }
            val key = v.substringBefore(':').trim()
            if (key !in keys) continue
            if (values.containsKey(key)) problems += "DUPLICATE_$key"
            values[key] = v.substringAfter(':').trim()
        }
        fun value(key: String): String? = values[key]?.let {
            runCatching { scalar(it) }.getOrElse { problems += "UNSUPPORTED_$key"; null }
        }
        val date = value("date")
        val alias = value("DIARY")
        if (date != null && alias != null) problems += "AMBIGUOUS_DATE_ALIAS"
        val chosenDate = date ?: alias
        if (chosenDate == null || !validDate(chosenDate)) problems += "DATE_INVALID_OR_MISSING"
        val source = value("source")
        if (source != null && source !in setOf("self", "reading", "lesson")) problems += "SOURCE_INVALID"
        val type = value("type")
        if (type != null && type !in setOf("journal", "insight", "confusion")) problems += "TYPE_INVALID"
        val world = value("world")
        val tags = values["tags"]?.let { runCatching { tags(it) }.getOrElse {
            problems += "UNSUPPORTED_tags"; emptyList()
        } } ?: emptyList()
        val offset = encode(text.substring(0, lines[close].end)).size
        val body = text.substring(lines[close].end)
        if (body.length > MAX_BODY_CHARS) problems += "BODY_LIMIT_READ_ONLY"
        return DiaryDocument(bytes, body, DiaryMetadata(chosenDate, source, type, world, tags),
            problems.isEmpty(), problems.toList(), offset)
    }
    private fun scalar(raw: String): String {
        val s = raw.trim()
        require(s.isNotEmpty()) { "EMPTY_SCALAR" }
        if (s.startsWith('\'')) {
            val result = StringBuilder(); var i = 1
            while (i < s.length) {
                if (s[i] == '\'') {
                    if (i + 1 < s.length && s[i + 1] == '\'') { result.append('\''); i += 2; continue }
                    val rest = s.substring(i + 1).trim()
                    require(rest.isEmpty() || rest.startsWith('#'))
                    return result.toString()
                }
                result.append(s[i++])
            }
            error("UNCLOSED_SCALAR")
        }
        if (s.startsWith('"')) {
            val end = s.indexOf('"', 1)
            require(end > 0 && '\\' !in s.substring(1, end))
            val rest = s.substring(end + 1).trim(); require(rest.isEmpty() || rest.startsWith('#'))
            return s.substring(1, end)
        }
        val plain = s.replace(Regex("\\s+#.*$"), "").trim()
        require(plain.isNotEmpty() && plain.none { it in "{}[]&*!|>@`\"'" } && ':' !in plain)
        require(plain.lowercase() !in setOf("null", "true", "false", "~"))
        return plain
    }
    private fun tags(raw: String): List<String> {
        val s = raw.trim(); require(s.startsWith('[') && s.endsWith(']'))
        val inner = s.substring(1, s.length - 1)
        if (inner.isBlank()) return emptyList()
        val result = mutableListOf<String>(); var quote: Char? = null; var start = 0; var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (quote != null) {
                if (c == quote) {
                    if (quote == '\'' && i + 1 < inner.length && inner[i + 1] == '\'') i++ else quote = null
                }
            } else if (c == '\'' || c == '"') quote = c
            else if (c == ',') { result += scalar(inner.substring(start, i)); start = i + 1 }
            i++
        }
        require(quote == null)
        result += scalar(inner.substring(start)); require(result.size <= 100)
        return result.toList()
    }
    private fun quoted(s: String): String {
        require(s.length <= 500 && s.none { it.isISOControl() || it == '\uFEFF' }) { "METADATA_INVALID" }
        encode(s)
        return "'" + s.replace("'", "''") + "'"
    }
    fun create(date: String, body: String, source: String = "self", type: String = "journal",
               world: String? = null, tags: List<String> = emptyList()): DiaryDocument {
        require(validDate(date)) { "DATE_INVALID" }
        require(source in setOf("self", "reading", "lesson")) { "SOURCE_INVALID" }
        require(type in setOf("journal", "insight", "confusion")) { "TYPE_INVALID" }
        require(tags.size <= 100 && body.length <= MAX_BODY_CHARS) { "BODY_OR_TAG_LIMIT" }
        val header = buildString {
            append("---\ndate: ").append(quoted(date)).append("\nsource: ").append(source)
            append("\ntype: ").append(type).append("\ntags: [")
            append(tags.joinToString(", ") { quoted(it) }).append("]\n")
            if (world != null) append("world: ").append(quoted(world)).append('\n')
            append("---\n")
        }
        return parse(encode(header + body)).also { check(it.editable) { "CREATE_NOT_EDITABLE" } }
    }
    fun replaceBody(document: DiaryDocument, body: String): DiaryDocument {
        require(document.editable) { "READ_ONLY_DOCUMENT" }
        require(body.length <= MAX_BODY_CHARS) { "BODY_LIMIT" }
        val prefix = document.bytes().copyOfRange(0, document.bodyOffset)
        val separator = if (document.metadata != null && body.isNotEmpty() && prefix.lastOrNull() != 10.toByte()) "\n" else ""
        val bytes = prefix + encode(separator + body)
        return parse(bytes).also { require(it.editable) { "BODY_REPLACEMENT_INVALID" } }
    }
    fun appendBody(document: DiaryDocument, addition: String): DiaryDocument {
        require(document.editable) { "READ_ONLY_DOCUMENT" }
        if (addition.isEmpty()) return parse(document.bytes())
        val original = document.bytes()
        val headerNeedsLine = document.metadata != null && document.body.isEmpty() && original.lastOrNull() != 10.toByte()
        val separator = if (!headerNeedsLine && (document.body.isEmpty() || document.body.endsWith('\n'))) "" else
            if (decode(original).contains("\r\n")) "\r\n" else "\n"
        return parse(original + encode(separator + addition)).also { require(it.editable) { "BODY_LIMIT" } }
    }
}
