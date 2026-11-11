package com.knte.core.chat

import com.knte.core.time.format.TimeHasher
import com.knte.core.time.format.TimeJson
import java.util.UUID

/**
 * Immutable send plan. Creation binds the plan to:
 *  - the config fingerprint (public endpoint+model only),
 *  - the messages input hash,
 *  - the expected-operation summary, which explicitly states the COST IS UNKNOWN.
 *
 * Any later input or configuration change invalidates the plan at execute time; a new turn
 * always requires a new plan and a fresh confirmation. The plan carries no secret; the
 * confirmation token is a binding checksum, not a key.
 */
class ChatSendPlan private constructor(
    val planId: String,
    val endpointUrl: String,
    val host: String,
    val model: String,
    val messageCount: Int,
    val approxInputBytes: Int,
    val configFingerprint: String,
    val inputHash: String,
    val operationSummary: String,
    val confirmationToken: String,
) {
    override fun toString(): String =
        "ChatSendPlan(planId=$planId, host=$host, model=$model, messages=$messageCount, utf8Bytes=$approxInputBytes)"

    companion object {
        fun create(profile: ChatProfile, messages: List<ChatMessage>): ChatSendPlan {
            ChatHistoryPolicy.validate(messages)
            val inputHash = ChatHistoryPolicy.inputHash(messages)
            val summary = operationSummary(profile, messages)
            val planId = "c-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            val binding = "knte-chat-confirm-v1\u0000$planId\u0000" +
                profile.configFingerprint + "\u0000" + inputHash + "\u0000" + summary
            val token = TimeHasher.sha256(TimeJson.encodeUtf8Strict(binding))
            return ChatSendPlan(
                planId = planId,
                endpointUrl = profile.endpoint.url,
                host = profile.endpoint.host,
                model = profile.model,
                messageCount = messages.size,
                approxInputBytes = messages.sumOf { it.utf8Bytes },
                configFingerprint = profile.configFingerprint,
                inputHash = inputHash,
                operationSummary = summary,
                confirmationToken = token,
            )
        }

        private fun operationSummary(profile: ChatProfile, messages: List<ChatMessage>): String {
            val bytes = messages.sumOf { it.utf8Bytes }
            return "向 ${profile.endpoint.host} 的模型 ${profile.model} 发送 ${messages.size} 条消息" +
                "（约 $bytes 字节，OpenAI 兼容非流式 chat/completions，单次请求）。" +
                "费用未知：本计划无法预估本次调用费用，实际计费由提供方决定；确认即表示接受可能产生费用。"
        }
    }
}
