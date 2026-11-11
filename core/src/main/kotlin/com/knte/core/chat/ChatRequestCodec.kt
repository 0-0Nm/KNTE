package com.knte.core.chat

import com.knte.core.time.format.TimeJson

/**
 * Request body for the OpenAI-compatible NON-STREAMING `chat/completions` subset.
 * Only the fields this subset needs are declared (`model`, `messages`, `stream=false`).
 * This is NOT a claim that every provider is supported.
 *
 * The payload is encoded with the existing TimeJson - no copied serializer, no new dependency.
 * Size gate mirrors the old shell candidate's 256 KiB request cap ("请求超过256KiB，未发送").
 */
object ChatRequestCodec {
    const val MAX_PAYLOAD_UTF8_BYTES = 256 * 1024

    fun buildPayload(profile: ChatProfile, messages: List<ChatMessage>): String {
        val body = TimeJson.Value.Obj(
            listOf(
                "model" to TimeJson.Value.Str(profile.model),
                "messages" to TimeJson.Value.Arr(
                    messages.map { m ->
                        TimeJson.Value.Obj(
                            listOf(
                                "role" to TimeJson.Value.Str(m.role.wireName),
                                "content" to TimeJson.Value.Str(m.content),
                            )
                        )
                    }
                ),
                "stream" to TimeJson.Value.Bool(false),
            )
        )
        val payload = TimeJson.encode(body)
        val bytes = TimeJson.encodeUtf8Strict(payload)
        require(bytes.size <= MAX_PAYLOAD_UTF8_BYTES) { "CHAT_PAYLOAD_TOO_LARGE" }
        return payload
    }
}
