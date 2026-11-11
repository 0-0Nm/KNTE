package com.knte.core.chat

import com.knte.core.time.format.TimeHasher
import com.knte.core.time.format.TimeJson

/** Only this subset of roles exists; `tool` and friends are out of scope (tools unsupported). */
enum class ChatRole(val wireName: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
}

internal object ChatText {
    /**
     * Kotlin strings are UTF-16; an unpaired surrogate can never be strict UTF-8, so it is
     * rejected with a domain code before [TimeJson.encodeUtf8Strict] is trusted with it.
     * Returns the strict UTF-8 byte size.
     */
    fun utf8Bytes(text: String): Int {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                require(i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) { "CHAT_TEXT_SURROGATE" }
                i += 2
            } else {
                require(!Character.isLowSurrogate(c)) { "CHAT_TEXT_SURROGATE" }
                i += 1
            }
        }
        return TimeJson.encodeUtf8Strict(text).size
    }
}

/**
 * One chat message: strict UTF-8, non-empty, byte-bounded. `toString` never contains the body,
 * so accidental logging cannot leak message content.
 */
class ChatMessage private constructor(
    val role: ChatRole,
    val content: String,
    val utf8Bytes: Int,
) {
    override fun toString(): String = "ChatMessage(role=$role, chars=${content.length}, utf8Bytes=$utf8Bytes)"

    companion object {
        const val MAX_CONTENT_UTF8_BYTES = 32 * 1024

        fun of(role: ChatRole, content: String): ChatMessage {
            val bytes = ChatText.utf8Bytes(content)
            require(content.isNotEmpty()) { "CHAT_MESSAGE_EMPTY" }
            require(bytes <= MAX_CONTENT_UTF8_BYTES) { "CHAT_MESSAGE_TOO_LARGE" }
            return ChatMessage(role, content, bytes)
        }
    }
}

/** History bounds + the input hash that binds a plan to the exact messages. */
object ChatHistoryPolicy {
    const val MAX_MESSAGES = 64
    const val MAX_TOTAL_UTF8_BYTES = 256 * 1024

    fun validate(messages: List<ChatMessage>) {
        require(messages.isNotEmpty()) { "CHAT_HISTORY_EMPTY" }
        require(messages.size <= MAX_MESSAGES) { "CHAT_HISTORY_TOO_LONG" }
        require(messages.sumOf { it.utf8Bytes } <= MAX_TOTAL_UTF8_BYTES) { "CHAT_HISTORY_BYTES_TOO_LARGE" }
    }

    /** Length-prefixed canonical form: role + byte length + full content per message.
     *  The byte-length prefix prevents concatenation ambiguity between message splits. */
    fun inputHash(messages: List<ChatMessage>): String {
        val sb = StringBuilder("knte-chat-input-v1")
        for (m in messages) {
            sb.append('\u0000').append(m.role.wireName).append('\u001F')
                .append(m.utf8Bytes).append('\u001F').append(m.content)
        }
        return TimeHasher.sha256(TimeJson.encodeUtf8Strict(sb.toString()))
    }
}
