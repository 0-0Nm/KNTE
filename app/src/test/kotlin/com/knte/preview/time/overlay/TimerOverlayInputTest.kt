package com.knte.preview.time.overlay

import org.junit.Assert.*
import org.junit.Test

class TimerOverlayInputTest {
    private val emoji = "\uD83D\uDE00"

    @Test fun validInputAndWhitespaceArePreservedExactly() {
        listOf("项目", "  项目  ", "e\u0301", "é", "x".repeat(96)).forEach {
            assertNull(TimerOverlayText.projectInputError(it))
            assertEquals(it, TimerOverlayText.projectInput(it))
            assertEquals(it, TimerOverlayIntent.Start(it).projectText)
        }
    }

    @Test fun ninetySixSupplementaryCodePointsAreNotTruncatedOrSplit() {
        val raw = emoji.repeat(96)
        assertEquals(192, raw.length)
        assertNull(TimerOverlayText.projectInputError(raw))
        assertEquals(raw, TimerOverlayText.projectInput(raw))
        assertEquals(raw, TimerOverlayIntent.Start(raw).projectText)
    }

    @Test fun mixedBmpAndSupplementaryBoundaryUsesCodePoints() {
        val raw = "x".repeat(95) + emoji
        assertEquals(raw, TimerOverlayText.projectInput(raw))
        assertRejected(raw + "y")
    }

    @Test fun overlongInputIsRejectedNotShortened() {
        listOf("x".repeat(97), emoji.repeat(97), "x".repeat(200), " " + "x".repeat(96)).forEach(::assertRejected)
        assertTrue(TimerOverlayText.projectInputError(emoji.repeat(97))!!.contains("97"))
    }

    @Test fun emptyAndWhitespaceOnlyFailClosed() {
        listOf("", "  ", "\t\n", "\u2003").forEach(::assertRejected)
    }

    @Test fun malformedUtf16FailsClosedWithoutReplacementCharacters() {
        listOf("\uD83D", "\uDE00", "x\uD83Dy", "\uDE00\uD83D", emoji + "\uD83D").forEach(::assertRejected)
    }

    @Test fun combiningMarksAreCodePointsNotGraphemeClusters() {
        assertEquals("e\u0301".repeat(48), TimerOverlayText.projectInput("e\u0301".repeat(48)))
        assertRejected("e\u0301".repeat(49))
    }

    private fun assertRejected(raw: String) {
        assertNotNull(TimerOverlayText.projectInputError(raw))
        assertEquals("", TimerOverlayText.projectInput(raw))
        try { TimerOverlayIntent.Start(raw); fail("invalid Start accepted") }
        catch (_: IllegalArgumentException) { /* Expected boundary refusal. */ }
    }
}
