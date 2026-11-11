package com.knte.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Strict UTF-8, size, role subset and history bounds for chat input. */
class ChatMessageTest {

    private fun expectCode(code: String, build: () -> Unit) {
        try {
            build()
            fail("expected $code")
        } catch (e: IllegalArgumentException) {
            assertEquals(code, e.message)
        }
    }

    @Test fun roleSubsetIsClosed() {
        assertEquals(listOf("system", "user", "assistant"), ChatRole.values().map { it.wireName })
    }

    @Test fun rejectsUnpairedSurrogates() {
        expectCode("CHAT_TEXT_SURROGATE") { ChatMessage.of(ChatRole.USER, "a\uD800b") }
        expectCode("CHAT_TEXT_SURROGATE") { ChatMessage.of(ChatRole.USER, "\uDC00") }
        expectCode("CHAT_TEXT_SURROGATE") { ChatProfile.create("https://api.openai.com/v1/chat/completions", "m\uD800") }
    }

    @Test fun acceptsSupplementaryCharsAndCountsUtf8Bytes() {
        val m = ChatMessage.of(ChatRole.USER, "🙂") // U+1F642: 1 code point, 4 UTF-8 bytes
        assertEquals(4, m.utf8Bytes)
        assertEquals("🙂", m.content)
    }

    @Test fun rejectsEmptyContent() {
        expectCode("CHAT_MESSAGE_EMPTY") { ChatMessage.of(ChatRole.USER, "") }
    }

    @Test fun rejectsOversizeSingleMessage() {
        expectCode("CHAT_MESSAGE_TOO_LARGE") {
            ChatMessage.of(ChatRole.USER, "a".repeat(ChatMessage.MAX_CONTENT_UTF8_BYTES + 1))
        }
    }

    @Test fun rejectsBadModel() {
        expectCode("CHAT_MODEL_EMPTY") {
            ChatProfile.create("https://api.openai.com/v1/chat/completions", "")
        }
        expectCode("CHAT_MODEL_TOO_LONG") {
            ChatProfile.create("https://api.openai.com/v1/chat/completions", "m".repeat(129))
        }
        expectCode("CHAT_MODEL_CONTROL_CHAR") {
            ChatProfile.create("https://api.openai.com/v1/chat/completions", "bad\nmodel")
        }
    }

    @Test fun profileFingerprintBindsEndpointAndModel() {
        val a = ChatProfile.create("https://api.openai.com/v1/chat/completions", "gpt-4o-mini")
        val same = ChatProfile.create("https://api.openai.com/v1/chat/completions", "gpt-4o-mini")
        val otherModel = ChatProfile.create("https://api.openai.com/v1/chat/completions", "gpt-4o")
        val otherEndpoint = ChatProfile.create("https://api.deepseek.com/v1/chat/completions", "gpt-4o-mini")
        assertEquals(a.configFingerprint, same.configFingerprint)
        assertNotEquals(a.configFingerprint, otherModel.configFingerprint)
        assertNotEquals(a.configFingerprint, otherEndpoint.configFingerprint)
        assertEquals(a, same)
        assertNotEquals(a, otherModel)
    }

    @Test fun historyBounds() {
        expectCode("CHAT_HISTORY_EMPTY") { ChatHistoryPolicy.validate(emptyList()) }
        expectCode("CHAT_HISTORY_TOO_LONG") {
            ChatHistoryPolicy.validate(
                (1..(ChatHistoryPolicy.MAX_MESSAGES + 1)).map { ChatMessage.of(ChatRole.USER, "m$it") }
            )
        }
        expectCode("CHAT_HISTORY_BYTES_TOO_LARGE") {
            // 9 x 30KiB exceeds the 256KiB total input bound while each message fits its own cap.
            ChatHistoryPolicy.validate(
                (1..9).map { ChatMessage.of(ChatRole.USER, "a".repeat(30 * 1024)) }
            )
        }
    }

    @Test fun inputHashBindsRoleAndContent() {
        val a = listOf(ChatMessage.of(ChatRole.USER, "你好"))
        val sameA = listOf(ChatMessage.of(ChatRole.USER, "你好"))
        val otherContent = listOf(ChatMessage.of(ChatRole.USER, "你好!"))
        val otherRole = listOf(ChatMessage.of(ChatRole.ASSISTANT, "你好"))
        assertEquals(ChatHistoryPolicy.inputHash(a), ChatHistoryPolicy.inputHash(sameA))
        assertNotEquals(ChatHistoryPolicy.inputHash(a), ChatHistoryPolicy.inputHash(otherContent))
        assertNotEquals(ChatHistoryPolicy.inputHash(a), ChatHistoryPolicy.inputHash(otherRole))
        // The length prefix prevents concatenation ambiguity.
        val two = listOf(ChatMessage.of(ChatRole.USER, "ab"), ChatMessage.of(ChatRole.USER, "c"))
        val otherTwo = listOf(ChatMessage.of(ChatRole.USER, "a"), ChatMessage.of(ChatRole.USER, "bc"))
        assertNotEquals(ChatHistoryPolicy.inputHash(two), ChatHistoryPolicy.inputHash(otherTwo))
    }

    @Test fun toStringNeverContainsContent() {
        val m = ChatMessage.of(ChatRole.USER, "USERBODY-MARKER-123")
        assertTrue(!m.toString().contains("USERBODY-MARKER-123"))
        assertTrue(m.toString().startsWith("ChatMessage(role=USER"))
    }
}
