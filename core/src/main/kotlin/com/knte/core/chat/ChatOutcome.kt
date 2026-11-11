package com.knte.core.chat

/**
 * Usage is Precise ONLY when the provider actually returned it. A missing usage is
 * [Unknown] and is NEVER fabricated as zeros - there is no source for those numbers.
 */
sealed class ChatUsage {
    object Unknown : ChatUsage() {
        override fun toString(): String = "ChatUsage.Unknown"
    }

    data class Precise(
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long,
    ) : ChatUsage()
}

enum class ChatRefusalKind { REFUSAL, CONTENT_FILTER }

/**
 * Answer shapes. [ChatAnswer.Text] is the ONLY shape carrying assistant text, so a refusal,
 * a tool-only or an empty answer can never surface as a fake success.
 *
 * [ToolCalls] means the provider asked for tool calls: tools are currently UNSUPPORTED -
 * zero tool payloads are parsed out, zero tools are executed, and nothing can turn this
 * into a success text. The domain has no tool execution API at all.
 */
sealed class ChatAnswer {
    data class Text(val content: String, val finishReason: String?) : ChatAnswer() {
        /** A length-limited answer must be visibly marked incomplete, never a complete result. */
        val incomplete: Boolean get() = finishReason == "length"
        override fun toString(): String = "ChatAnswer.Text(chars=${content.length}, finishReason=$finishReason, incomplete=$incomplete)"
    }

    /** The provider explicitly refused (refusal field or content_filter finish). */
    data class Refused(val kind: ChatRefusalKind, val finishReason: String?) : ChatAnswer()

    /** Tool calls / function call requested. Not a success; nothing is executed. */
    data class ToolCalls(val finishReason: String?) : ChatAnswer()

    /** content missing, empty or blank. Not a success. */
    object EmptyContent : ChatAnswer() {
        override fun toString(): String = "ChatAnswer.EmptyContent"
    }
}

/**
 * The terminal result of ONE planned request. [Rejected] and [Unknown] carry a public error
 * code plus a fixed safe summary - never response bodies, URL parameters or secrets.
 * Every outcome is terminal for the plan: a new turn must create a new plan.
 */
sealed class ChatOutcome {
    data class Completed(val answer: ChatAnswer, val usage: ChatUsage) : ChatOutcome()

    /** Definite refusal/failure. Not auto-retried. */
    data class Rejected(val code: String, val safeSummary: String) : ChatOutcome()

    /** Result unknown (timeout/IO). The request may already have been processed and billed;
     *  never auto-retried. A new turn requires a new plan and a fresh confirmation. */
    data class Unknown(val code: String, val safeSummary: String) : ChatOutcome()

    object Cancelled : ChatOutcome() {
        override fun toString(): String = "ChatOutcome.Cancelled"
    }
}
