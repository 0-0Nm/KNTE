package com.knte.core.accounting

import java.nio.charset.StandardCharsets

/** Row role in a correction chain, mirroring the legacy v2 read projection. */
enum class AccountingKind { ORIGINAL, CORRECTION, REVERSAL }

/** One non-silent anomaly: carried on the snapshot so reports never drop rows silently. */
data class AccountingIssue(val lineNumber: Int, val code: String, val detail: String)

/** One ledger row. `raw` keeps the EXACT source line (unknown keys included, trailing LF when
 * present); `fields()` re-parses it fresh. Invalid rows stay listed but never enter aggregates. */
class AccountingRow internal constructor(
    private val raw: ByteArray,
    val lineNumber: Int,
    val id: String,
    val ts: String,
    val date: String,
    val cents: Long,
    val category: String,
    val location: String,
    val note: String,
    val source: String,
    val rawInput: String,
    val atTrust: String?,
    val correctedOf: String,
    val declaredVoidedBy: String,
    val kind: AccountingKind,
    val legacyClockMetadataAbsent: Boolean,
    val valid: Boolean,
    val issues: List<AccountingIssue>,
    voidedBy: String,
) {
    val viewKey: String = "ts:$ts"
    val voidedBy: String = voidedBy
    val active: Boolean
        get() = valid && kind != AccountingKind.REVERSAL && voidedBy.isEmpty()

    fun rawBytes(): ByteArray = raw.clone()

    fun fields(): LinkedHashMap<String, Any?> = AccountingCodec.obj(
        AccountingCodec.parse(String(raw, StandardCharsets.UTF_8))
    )
}

/** Immutable projection of the whole ledger file: rows, chain resolution, findings, exact totals. */
class AccountingSnapshot internal constructor(
    bytes: ByteArray,
    rows: List<AccountingRow>,
    val unterminatedTail: Boolean,
    val overCapacity: Boolean,
    fileIssues: List<AccountingIssue>,
) {
    private val content = bytes.clone()
    val revision: String = AccountingCodec.hash(content)
    val rows: List<AccountingRow> = ArrayList(rows)
    val effectiveRows: List<AccountingRow> = rows.filter { it.active }
    val findings: List<AccountingIssue> = fileIssues + rows.flatMap { it.issues }
    val income: Long
    val expense: Long
    val net: Long
    private val byTime: Map<String, AccountingRow>
    private val byIdMap: Map<String, AccountingRow>

    init {
        var incomeSum = 0L
        var expenseSum = 0L
        for (row in effectiveRows) {
            if (row.cents > 0) incomeSum = Math.addExact(incomeSum, row.cents)
            else expenseSum = Math.addExact(expenseSum, -row.cents)
        }
        income = incomeSum; expense = expenseSum; net = Math.subtractExact(income, expense)
        val times = LinkedHashMap<String, AccountingRow>()
        val ids = LinkedHashMap<String, AccountingRow>()
        for (row in rows) {
            if (!row.valid) continue
            times[row.ts] = row
            if (row.id.isNotEmpty()) ids[row.id] = row
        }
        byTime = times
        byIdMap = ids
    }

    fun rawBytes(): ByteArray = content.clone()
    fun byTimestamp(ts: String): AccountingRow? = byTime[ts]
    fun byId(id: String): AccountingRow? = byIdMap[id]

    fun find(key: String): AccountingRow? {
        if (key.isEmpty()) return null
        val tsKey = if (key.startsWith("ts:")) key.removePrefix("ts:") else key
        return byTime[tsKey] ?: byIdMap[key]
    }

    private class Parsed(val lineNumber: Int, private val raw: ByteArray) {
        var id = ""; var ts = ""; var date = ""; var cents = 0L
        var category = ""; var location = ""; var note = ""; var source = ""; var rawInput = ""
        var atTrust: String? = null
        var correctedOf = ""; var declaredVoidedBy = ""
        var kind = AccountingKind.ORIGINAL
        var legacyClockMetadataAbsent = false
        var valid = true
        var voidedBy = ""
        val issues = mutableListOf<AccountingIssue>()

        fun issue(code: String, detail: String = "") { issues += AccountingIssue(lineNumber, code, detail) }
        fun invalidate(code: String, detail: String = "") { valid = false; issue(code, detail) }
        fun materialize() = AccountingRow(
            raw, lineNumber, id, ts, date, cents, category, location, note, source, rawInput,
            atTrust, correctedOf, declaredVoidedBy, kind, legacyClockMetadataAbsent, valid, issues.toList(), voidedBy
        )
    }

    companion object {
        /** Parses the whole JSONL file. Structural file faults (size, malformed UTF-8) throw;
         * per-line anomalies become row findings (invalid rows are excluded from aggregates
         * but never silently dropped). */
        fun parse(bytes: ByteArray): AccountingSnapshot {
            require(bytes.size <= AccountingCodec.MAX_FILE_BYTES) { "SIZE_LIMIT" }
            AccountingCodec.decode(bytes) // strict UTF-8 gate (throws on malformed/NUL)
            val fileIssues = mutableListOf<AccountingIssue>()
            val parsed = mutableListOf<Parsed>()
            var unterminated = false
            var lineNo = 0
            var start = 0
            var index = 0
            while (index <= bytes.size) {
                if (index == bytes.size) {
                    if (start < bytes.size) { // tail without trailing LF
                        unterminated = true
                        val tail = bytes.copyOfRange(start, index)
                        val row = Parsed(++lineNo, tail)
                        row.invalidate("UNTERMINATED_LAST_LINE")
                        decodeLine(row, String(tail, StandardCharsets.UTF_8))
                        parsed += row
                    }
                    break
                }
                if (bytes[index] == '\n'.code.toByte()) {
                    val lineBytes = bytes.copyOfRange(start, index + 1)
                    val number = ++lineNo
                    val text = String(lineBytes, StandardCharsets.UTF_8).dropLast(1)
                    if (text.isEmpty()) {
                        fileIssues += AccountingIssue(number, "BLANK_LINE", "")
                    } else if (text.length > AccountingCodec.MAX_LINE_CHARS) {
                        fileIssues += AccountingIssue(number, "LINE_OVERLONG", "")
                    } else {
                        val row = Parsed(number, lineBytes)
                        decodeLine(row, text)
                        parsed += row
                    }
                    start = index + 1
                }
                index++
            }
            markDuplicates(parsed)
            resolveChains(parsed)
            val rows = parsed.map { it.materialize() }
            return AccountingSnapshot(
                bytes, rows, unterminated, rows.size > AccountingCodec.MAX_ROWS, fileIssues
            )
        }

        private fun decodeLine(row: Parsed, text: String) {
            val map = try {
                AccountingCodec.obj(AccountingCodec.parse(text))
            } catch (e: IllegalArgumentException) {
                row.invalidate("INVALID_JSON", e.message ?: ""); return
            }
            if (map.containsKey("archived") || map.containsKey("deleted")) {
                row.invalidate("LEGACY_STATE_MARKER", "archived/deleted require review")
            }
            when (val id = map["id"]) {
                null, is String -> row.id = id as String? ?: ""
                else -> row.invalidate("ID_INVALID", "id must be a string")
            }
            val amount = map["amount"]
            when {
                !map.containsKey("amount") || amount == null -> row.invalidate("AMOUNT_MISSING", "")
                amount !is java.math.BigDecimal -> row.invalidate("AMOUNT_INVALID", "not a JSON number")
                else -> {
                    val cents = AccountingCodec.signedCentsOf(amount)
                    if (cents == null) row.invalidate("AMOUNT_INVALID", "zero/out-of-range/sub-cent")
                    else row.cents = cents
                }
            }
            when (val ts = map["ts"]) {
                is String -> {
                    if (AccountingCodec.validTimestamp(ts)) row.ts = ts
                    else row.invalidate("TS_INVALID", ts)
                }
                else -> row.invalidate("TS_MISSING", "")
            }
            when (val date = map["date"]) {
                is String -> {
                    if (AccountingCodec.validDate(date)) row.date = date
                    else row.invalidate("DATE_INVALID", date)
                }
                else -> row.invalidate("DATE_MISSING", "")
            }
            when (val category = map["category"]) {
                is String -> {
                    row.category = category
                    if (category !in AccountingCodec.CATEGORIES) row.issue("CATEGORY_OUT_OF_ENUM", category)
                }
                else -> row.invalidate(if (map.containsKey("category")) "CATEGORY_INVALID" else "CATEGORY_MISSING", "")
            }
            when (val source = map["source"]) {
                is String -> {
                    row.source = source
                    if (source != AccountingCodec.SOURCE_MANUAL && source != AccountingCodec.SOURCE_AI)
                        row.issue("SOURCE_INVALID", source)
                }
                else -> row.issue("SOURCE_MISSING", "")
            }
            when (val rawInput = map["raw_input"]) {
                is String -> {
                    row.rawInput = rawInput
                    if (rawInput.isEmpty()) row.issue("RAW_INPUT_MISSING", "raw_input is never omitted")
                    if (rawInput.length > AccountingCodec.MAX_RAW_CHARS) row.issue("RAW_INPUT_OVERLONG", "")
                }
                else -> row.issue("RAW_INPUT_MISSING", "")
            }
            when (val location = map["location"]) {
                is String -> row.location = location
                null -> {}
                else -> row.invalidate("FIELD_TYPE_INVALID", "location must be a string")
            }
            when (val note = map["note"]) {
                is String -> {
                    row.note = note
                    if (note.codePointCount(0, note.length) > AccountingCodec.MAX_NOTE_CODE_POINTS)
                        row.issue("NOTE_OVERLONG", "")
                }
                null -> {}
                else -> row.invalidate("FIELD_TYPE_INVALID", "note must be a string")
            }
            when (val corrected = map["corrected_of"]) {
                null -> {}
                is String -> {
                    if (AccountingCodec.validTimestamp(corrected)) row.correctedOf = corrected
                    else row.invalidate("CORRECTION_INVALID", corrected)
                }
                else -> row.invalidate("CORRECTION_INVALID", "must be a timestamp string")
            }
            when (val voided = map["voided_by"]) {
                null -> {}
                is String -> row.declaredVoidedBy = voided
                else -> row.invalidate("FIELD_TYPE_INVALID", "voided_by must be a string")
            }
            when (val trust = map["at_trust"]) {
                null -> {
                    row.legacyClockMetadataAbsent = true
                    row.issue("AT_TRUST_ABSENT", "legacy clock trust metadata missing")
                }
                is String -> {
                    row.atTrust = trust
                    if (trust != AccountingCodec.AT_TRUST_UNVERIFIED) row.issue("AT_TRUST_UNEXPECTED", trust)
                }
                else -> row.issue("AT_TRUST_INVALID", "")
            }
            row.kind = if (row.correctedOf.isEmpty()) AccountingKind.ORIGINAL
            else if (row.note == "冲销") AccountingKind.REVERSAL
            else AccountingKind.CORRECTION
        }

        private fun markDuplicates(parsed: List<Parsed>) {
            val byId = LinkedHashMap<String, MutableList<Parsed>>()
            val byTs = LinkedHashMap<String, MutableList<Parsed>>()
            for (row in parsed) {
                if (row.id.isNotEmpty()) byId.getOrPut(row.id) { mutableListOf() }.add(row)
                if (row.ts.isNotEmpty()) byTs.getOrPut(row.ts) { mutableListOf() }.add(row)
            }
            for (group in byId.values) if (group.size > 1)
                for (row in group) row.invalidate("DUPLICATE_ID", group.size.toString())
            for (group in byTs.values) if (group.size > 1)
                for (row in group) row.invalidate("DUPLICATE_TIMESTAMP", group.size.toString())
        }

        /** Legacy v2 chain semantics: a correction replaces its predecessor in the effective
         * view; a reversal (note=冲销, manual, opposite amount, identical identity fields)
         * cancels both sides. Faulty chains invalidate only the rows involved. */
        private fun resolveChains(parsed: List<Parsed>) {
            val byTime = LinkedHashMap<String, Parsed>()
            for (row in parsed) if (row.valid && row.ts.isNotEmpty()) byTime[row.ts] = row
            val successors = HashMap<String, String>()
            for (row in parsed) {
                if (!row.valid || row.correctedOf.isEmpty()) continue
                val target = byTime[row.correctedOf]
                if (target == null) { row.invalidate("CHAIN_TARGET_MISSING", row.correctedOf); continue }
                if (target.lineNumber >= row.lineNumber) {
                    row.invalidate("CHAIN_FORWARD_REFERENCE", row.correctedOf); continue
                }
                if (target.kind == AccountingKind.REVERSAL) {
                    row.invalidate("CHAIN_TARGET_IS_REVERSAL", row.correctedOf); continue
                }
                if (row.kind == AccountingKind.REVERSAL && (
                        row.source != AccountingCodec.SOURCE_MANUAL ||
                        row.cents != -target.cents ||
                        row.date != target.date || row.category != target.category ||
                        row.location != target.location || row.rawInput != target.rawInput)
                ) {
                    // A mismatched reversal must not void its target: check before registering.
                    row.invalidate("REVERSAL_MISMATCH", "identity fields must match with opposite amount"); continue
                }
                if (successors.put(target.ts, row.ts) != null) {
                    row.invalidate("CHAIN_BRANCHED", row.correctedOf); continue
                }
            }
            for (row in parsed) {
                if (!row.valid || row.ts.isEmpty()) continue
                val successor = successors[row.ts]
                if (row.declaredVoidedBy.isNotEmpty() && row.declaredVoidedBy != successor) {
                    row.invalidate("VOIDED_BY_MISMATCH", row.declaredVoidedBy); continue
                }
                row.voidedBy = successor ?: ""
            }
        }
    }
}
