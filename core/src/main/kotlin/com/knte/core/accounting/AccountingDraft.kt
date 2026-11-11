package com.knte.core.accounting

import java.math.BigDecimal

/** Manual confirm-card draft (the only write input of this batch).
 * Mirrors the legacy AccountingCore.Draft invariants: strict id/date/category checks, exact
 * cents amount (expense negative / income positive, at most two decimals, never Double),
 * raw_input mandatory, note ≤ 20 code points, location ≤ 200. */
class AccountingDraft internal constructor(
    val id: String,
    val date: String,
    val cents: Long,
    val category: String,
    val location: String,
    val note: String,
    val rawInput: String,
) {
    /** Content hash of the canonical row body (no ts), used to bind confirm plans. */
    val token: String = AccountingCodec.hash(AccountingCodec.encode(bodyJson()))

    internal fun bodyJson(): String {
        val b = StringBuilder()
        b.append("{\"id\":").append(AccountingCodec.quote(id))
        b.append(",\"date\":").append(AccountingCodec.quote(date))
        b.append(",\"amount\":").append(AccountingCodec.money(cents))
        b.append(",\"category\":").append(AccountingCodec.quote(category))
        if (location.isNotEmpty()) b.append(",\"location\":").append(AccountingCodec.quote(location))
        if (note.isNotEmpty()) b.append(",\"note\":").append(AccountingCodec.quote(note))
        b.append(",\"source\":\"").append(AccountingCodec.SOURCE_MANUAL)
        b.append("\",\"raw_input\":").append(AccountingCodec.quote(rawInput))
        b.append('}')
        return b.toString()
    }

    /** Canonical JSONL line in the legacy field order (id, date, amount, category, location?,
     * note?, source, raw_input, ts, at_trust[, corrected_of]). */
    internal fun encodeLine(ts: String, correctedOf: String? = null): String {
        require(AccountingCodec.validTimestamp(ts)) { "TS_INVALID" }
        val b = StringBuilder(bodyJson())
        b.setLength(b.length - 1)
        b.append(",\"ts\":").append(AccountingCodec.quote(ts))
        b.append(",\"at_trust\":\"").append(AccountingCodec.AT_TRUST_UNVERIFIED).append('"')
        if (correctedOf != null) {
            require(AccountingCodec.validTimestamp(correctedOf)) { "CORRECTION_INVALID" }
            b.append(",\"corrected_of\":").append(AccountingCodec.quote(correctedOf))
        }
        return b.append('}').toString()
    }

    companion object {
        /** Manual amount gate: plain digits with at most two decimals; no guessing or rounding.
         * `income` selects the sign (income positive / expense negative). */
        fun prepare(
            id: String,
            date: String,
            amountText: String,
            income: Boolean,
            category: String,
            location: String = "",
            note: String = "",
            rawInput: String,
        ): AccountingDraft {
            if (!AccountingCodec.validAmountText(amountText))
                throw IllegalArgumentException("AMOUNT_TEXT_INVALID")
            val cents = AccountingCodec.centsOf(BigDecimal(amountText), income)
            return checked(id, date, cents, category, location, note, rawInput)
        }

        /** Signed-cents variant for programmatic callers (tests, future import tooling). */
        fun fromCents(
            id: String, date: String, cents: Long, category: String,
            location: String = "", note: String = "", rawInput: String,
        ): AccountingDraft = checked(id, date, cents, category, location, note, rawInput)

        internal fun checked(
            id: String, date: String, cents: Long, category: String,
            location: String, note: String, rawInput: String,
        ): AccountingDraft {
            require(AccountingCodec.validId(id)) { "ID_INVALID" }
            require(AccountingCodec.validDate(date)) { "DATE_INVALID" }
            require(cents != 0L && Math.abs(cents) <= AccountingCodec.MAX_CENTS) { "AMOUNT_INVALID" }
            require(category in AccountingCodec.CATEGORIES) { "CATEGORY_NOT_IN_ENUM" }
            require(location.length <= AccountingCodec.MAX_LOCATION_CHARS) { "LOCATION_TOO_LONG" }
            require(note.codePointCount(0, note.length) <= AccountingCodec.MAX_NOTE_CODE_POINTS) { "NOTE_TOO_LONG" }
            require(
                rawInput.isNotEmpty() && rawInput.length <= AccountingCodec.MAX_RAW_CHARS &&
                    !rawInput.codePoints().allMatch { Character.isWhitespace(it) }
            ) { "RAW_INPUT_REQUIRED" }
            // Strict-UTF-8 check (rejects lone surrogates and unencodable chars).
            AccountingCodec.encode(rawInput); AccountingCodec.encode(location); AccountingCodec.encode(note)
            return AccountingDraft(id, date, cents, category, location, note, rawInput)
        }
    }
}
