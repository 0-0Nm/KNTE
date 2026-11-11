package com.knte.core.chat

import com.knte.core.time.format.TimeHasher
import com.knte.core.time.format.TimeJson

/**
 * Chat configuration: PUBLIC data only (validated endpoint + model). Never a secret.
 *
 * The API key is injected by the app-side transport through [ChatSecretSource] at send time;
 * this type has no field, cache, log sink or string form that could hold one. The old shell
 * candidate's save policy (never hash secrets, compare endpoint/model only) is mirrored by
 * the config fingerprint, which is derived from public fields only.
 */
class ChatProfile private constructor(
    val endpoint: ChatEndpoint,
    val model: String,
    val configFingerprint: String,
) {
    override fun toString(): String = "ChatProfile(endpoint=$endpoint, model=$model)"

    override fun equals(other: Any?): Boolean =
        other is ChatProfile && other.endpoint.url == endpoint.url && other.model == model

    override fun hashCode(): Int = 31 * endpoint.url.hashCode() + model.hashCode()

    companion object {
        const val MAX_MODEL_CHARS = 128

        fun create(endpointUrl: String, model: String): ChatProfile {
            val endpoint = ChatEndpoint.parse(endpointUrl)
            require(model.isNotEmpty()) { "CHAT_MODEL_EMPTY" }
            require(model.length <= MAX_MODEL_CHARS) { "CHAT_MODEL_TOO_LONG" }
            require(model.none { it.code < 0x20 || it.code == 0x7F }) { "CHAT_MODEL_CONTROL_CHAR" }
            ChatText.utf8Bytes(model) // also rejects unpaired surrogates
            val fingerprint = TimeHasher.sha256(
                TimeJson.encodeUtf8Strict("knte-chat-profile-v1\u0000" + endpoint.url + "\u0000" + model)
            )
            return ChatProfile(endpoint, model, fingerprint)
        }
    }
}
