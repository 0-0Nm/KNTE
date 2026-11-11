package com.knte.core.accounting

/** Shared fixture helpers for accounting tests: raw JSON numbers and strict object building. */
internal class RawJson internal constructor(internal val text: String)

internal fun raw(text: String) = RawJson(text)

internal fun jsonLine(vararg pairs: Pair<String, Any?>): String {
    val b = StringBuilder("{")
    pairs.forEachIndexed { i, (k, v) ->
        if (i > 0) b.append(',')
        b.append('"').append(k).append("\":")
        when (v) {
            is RawJson -> b.append(v.text)
            null -> b.append("null")
            is String -> b.append(AccountingCodec.quote(v))
            is Boolean -> b.append(v)
            else -> b.append(v.toString())
        }
    }
    return b.append('}').toString()
}

/** JSONL bytes: one line each, LF-terminated. */
internal fun fixture(vararg lines: String): ByteArray =
    if (lines.isEmpty()) ByteArray(0)
    else AccountingCodec.encode(lines.joinToString("") { "$it\n" })

internal fun fails(block: () -> Unit) {
    try {
        block()
        throw AssertionError("expected refusal")
    } catch (_: Exception) {
        // required fail-closed path
    }
}

internal fun err(block: () -> Unit): String =
    try {
        block()
        "NO_ERROR"
    } catch (e: Exception) {
        e.message ?: ""
    }
