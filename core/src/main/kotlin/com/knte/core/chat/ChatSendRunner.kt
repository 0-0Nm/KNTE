package com.knte.core.chat

/**
 * Synchronous send state machine (single-threaded by contract; the app owns lifecycle, threads,
 * cancellation UI and IO).
 *
 * Flow: [createPlan] -> show `operationSummary` + `confirmationToken` to the user -> the user
 * EXPLICITLY confirms -> [execute]. Sending without an explicit confirmation token is
 * impossible; nothing here ever sends, retries or executes tools on its own.
 *
 * Gate order: plan ownership -> single-use state -> confirmation token -> config fingerprint ->
 * input hash -> exactly one transport call. Every gate failure avoids the transport AND the
 * secret injection, and marks the plan terminal. Completed/Rejected/Unknown/cancelled/
 * invalidated are ALL terminal states: an unknown outcome is never auto-retried (the request
 * may already have been processed and billed) and a new turn must create a new plan and be
 * confirmed again.
 */
class ChatSendRunner(private val transport: ChatTransport) {

    private enum class PlanState { AWAITING, CONSUMED, CANCELLED }

    private val states = HashMap<String, PlanState>()

    fun createPlan(profile: ChatProfile, messages: List<ChatMessage>): ChatSendPlan {
        ChatHistoryPolicy.validate(messages)
        val plan = ChatSendPlan.create(profile, messages)
        states[plan.planId] = PlanState.AWAITING
        return plan
    }

    /** Cancel a waiting plan. Never touches the transport or the secret source. */
    fun cancel(plan: ChatSendPlan): ChatOutcome {
        val state = states[plan.planId]
            ?: return ChatOutcome.Rejected("CHAT_PLAN_UNKNOWN", "计划不属于本 runner")
        if (state != PlanState.AWAITING) {
            return ChatOutcome.Rejected("CHAT_PLAN_NOT_REUSABLE", "计划已是终态，不可取消")
        }
        states[plan.planId] = PlanState.CANCELLED
        return ChatOutcome.Cancelled
    }

    fun execute(
        plan: ChatSendPlan,
        confirmationToken: String,
        currentProfile: ChatProfile,
        currentMessages: List<ChatMessage>,
    ): ChatOutcome {
        val state = states[plan.planId]
            ?: return ChatOutcome.Rejected("CHAT_PLAN_UNKNOWN", "计划不属于本 runner")
        if (state != PlanState.AWAITING) {
            return ChatOutcome.Rejected("CHAT_PLAN_NOT_REUSABLE", "计划已消费/取消/失效；新回合必须新建计划")
        }
        if (confirmationToken != plan.confirmationToken) {
            states[plan.planId] = PlanState.CONSUMED
            return ChatOutcome.Rejected("CHAT_CONFIRMATION_MISMATCH", "确认令牌与本计划不匹配；计划已作废")
        }
        if (currentProfile.configFingerprint != plan.configFingerprint) {
            states[plan.planId] = PlanState.CONSUMED
            return ChatOutcome.Rejected("CHAT_CONFIG_CHANGED", "配置已变化，旧计划失效；须新建计划")
        }
        val inputHash = try {
            ChatHistoryPolicy.validate(currentMessages)
            ChatHistoryPolicy.inputHash(currentMessages)
        } catch (e: IllegalArgumentException) {
            states[plan.planId] = PlanState.CONSUMED
            return ChatOutcome.Rejected(e.message ?: "CHAT_INPUT_INVALID", "当前输入非法，旧计划失效")
        }
        if (inputHash != plan.inputHash) {
            states[plan.planId] = PlanState.CONSUMED
            return ChatOutcome.Rejected("CHAT_INPUT_CHANGED", "输入已变化，旧计划失效；须新建计划")
        }
        // Single use from here on: whatever happens next, this plan never sends again.
        states[plan.planId] = PlanState.CONSUMED
        val body = try {
            ChatRequestCodec.buildPayload(currentProfile, currentMessages)
        } catch (e: IllegalArgumentException) {
            return ChatOutcome.Rejected(e.message ?: "CHAT_PAYLOAD_INVALID", "请求体构建失败；未发送")
        }
        val request = ChatTransportRequest(
            endpointUrl = plan.endpointUrl,
            method = "POST",
            contentType = "application/json",
            accept = "application/json",
            body = body,
            credentialRef = plan.configFingerprint,
        )
        val delivered = try {
            transport.send(request)
        } catch (e: Exception) {
            // The transport contract says "never throw". If it does, the outcome is unknown and
            // the exception text is deliberately not echoed (it could contain anything).
            return ChatOutcome.Unknown("CHAT_TRANSPORT_THREW", "传输层异常，结果未知；不自动重试，须新建计划")
        }
        return when (delivered) {
            is ChatTransportResult.Refused -> ChatOutcome.Rejected(delivered.code, delivered.safeSummary)
            is ChatTransportResult.Indeterminate -> ChatOutcome.Unknown(delivered.code, delivered.safeSummary)
            is ChatTransportResult.Delivered -> mapDelivered(delivered)
        }
    }

    private fun mapDelivered(delivered: ChatTransportResult.Delivered): ChatOutcome {
        if (delivered.httpStatus !in 200..299) {
            return ChatOutcome.Rejected(
                "CHAT_HTTP_" + delivered.httpStatus,
                "提供方返回 HTTP ${delivered.httpStatus}；响应体不回显，不自动重试",
            )
        }
        if (delivered.body.length > ChatResponseCodec.MAX_RESPONSE_CHARS) {
            return ChatOutcome.Rejected("CHAT_RESPONSE_TOO_LARGE", "响应体超过域内上限；不截断、不伪造")
        }
        return try {
            val (answer, usage) = ChatResponseCodec.parse(delivered.body)
            ChatOutcome.Completed(answer, usage)
        } catch (e: IllegalArgumentException) {
            ChatOutcome.Rejected(e.message ?: "CHAT_RESPONSE_INVALID", "响应不符合本子集契约；响应体不回显")
        }
    }
}
