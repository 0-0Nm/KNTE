package com.knte.core.accounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class AccountingCodecTest {
    @Test fun categoriesExactlyTheTenFromContract() {
        assertEquals(
            listOf("餐饮", "交通", "学习", "购物", "医疗", "居住", "娱乐", "人情", "通讯", "其他"),
            AccountingCodec.CATEGORIES,
        )
        assertEquals(10, AccountingCodec.CATEGORIES.distinct().size)
    }

    @Test fun numbersAreBigDecimalNeverDouble() {
        val exact = AccountingCodec.parse("-25.005") as BigDecimal
        assertEquals(0, exact.compareTo(BigDecimal("-25.005")))
        assertEquals(BigDecimal("-25.00"), AccountingCodec.parse("-25.00"))
        assertEquals(0, (AccountingCodec.parse("1e2") as BigDecimal).compareTo(BigDecimal("100")))
        // 17 significant digits would already lose precision through IEEE754 Double.
        val big = AccountingCodec.parse("12345678901234567.89") as BigDecimal
        assertEquals(0, big.compareTo(BigDecimal("12345678901234567.89")))
    }

    @Test fun strictParserRejections() {
        fails { AccountingCodec.parse("{\"a\":1,\"a\":2}") }
        fails { AccountingCodec.parse("{} tail") }
        fails { AccountingCodec.parse("05") }
        fails { AccountingCodec.parse("NaN") }
        fails { AccountingCodec.parse("Infinity") }
        fails { AccountingCodec.parse("-Infinity") }
        fails { AccountingCodec.parse("\"a\u0001b\"") }
        fails { AccountingCodec.parse("\"\\ud800\"") }
        fails { AccountingCodec.parse("\"abc") }
        fails { AccountingCodec.parse("1".repeat(65)) }
        fails { AccountingCodec.parse("[".repeat(17) + "0" + "]".repeat(17)) }
    }

    @Test fun quoteRoundTripKeepsExactText() {
        val s = "a\"b\\c\n\t中'花"
        assertEquals(s, AccountingCodec.parse(AccountingCodec.quote(s)))
        assertTrue(AccountingCodec.quote("\u0001").contains("\\u0001"))
    }

    @Test fun centsExactAndSignedWithoutDouble() {
        assertEquals(2550L, AccountingCodec.centsOf(BigDecimal("25.5"), true))
        assertEquals(-2550L, AccountingCodec.centsOf(BigDecimal("25.5"), false))
        assertEquals(2500L, AccountingCodec.centsOf(BigDecimal("25"), true))
        assertEquals(99999999999L, AccountingCodec.centsOf(BigDecimal("999999999.99"), true))
        assertTrue(err { AccountingCodec.centsOf(BigDecimal("0"), true) }.contains("AMOUNT_INVALID"))
        assertTrue(err { AccountingCodec.centsOf(BigDecimal("25.005"), true) }.contains("AMOUNT_INVALID"))
        assertTrue(err { AccountingCodec.centsOf(BigDecimal("1000000000"), true) }.contains("AMOUNT_INVALID"))
        assertEquals("-25.50", AccountingCodec.money(-2550))
    }

    @Test fun dateTimestampIdValidation() {
        assertTrue(AccountingCodec.validDate("2026-02-28"))
        assertFalse(AccountingCodec.validDate("2026-02-30"))
        assertFalse(AccountingCodec.validDate("2026-2-28"))
        assertTrue(AccountingCodec.validTimestamp("2026-11-11T23:59:59"))
        assertFalse(AccountingCodec.validTimestamp("2026-11-11 23:59:59"))
        assertFalse(AccountingCodec.validTimestamp("2026-11-11T24:00:00"))
        assertTrue(AccountingCodec.validId("r-0a1b2c3d"))
        assertFalse(AccountingCodec.validId("R-0A1B2C3D"))
        assertFalse(AccountingCodec.validId("r-0a1b2c3"))
        assertTrue(AccountingCodec.validAmountText("25"))
        assertTrue(AccountingCodec.validAmountText("25.5"))
        assertTrue(AccountingCodec.validAmountText("25.55"))
        assertFalse(AccountingCodec.validAmountText("25.555"))
        assertFalse(AccountingCodec.validAmountText("-25"))
        assertFalse(AccountingCodec.validAmountText("2 5"))
    }

    @Test fun malformedUtf8AndNulRefused() {
        fails { AccountingCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)) }
        fails { AccountingCodec.decode(byteArrayOf(0)) }
        fails { AccountingCodec.encode("\uD800") }
    }
}
