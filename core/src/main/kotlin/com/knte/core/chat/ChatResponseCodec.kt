package com.knte.core.chat

import com.knte.core.time.format.TimeJson
import com.knte.core.time.format.TimeJsonValues

/**
 * Strict-subset decoder for one OpenAI-compatible NON-STREAMING `chat.completion` response.
 * This is NOT a claim that all providers are supported.
 *
 *  - consumed fields: `choices[0].message.{role,content,refusal,tool_calls,function_call}`,
 *    `choices[0].finish_reason`, `usage`. Everything else (`id`, `created`, `model`, extra
 *    choices, ...) is ignored for compatibility and never guessed from;
 *  - numbers must be plain bounded non-negative integer literals. Usage must arrive complete
 *    (all three fields) to become [ChatUsage.Precise]; absent usage is [ChatUsage.Unknown] and
 *    is never fabricated as zeros;
 *  - answer precedence is fixed: refusal > content_filter finish > tool_calls/function_call >
 *    empty content > text. [ChatAnswer.Text] is the only shape with assistant text, so
 *    refusal/tool-only/empty answers can never fake a success. Tools are unsupported here:
 *    zero tool payload parsing, zero execution;
 *  - the body is bounded (chars, nesting depth, answer length). Anything beyond or off-shape
 *    fails closed - never truncated, never faked;
 *  - errors carry domain codes only; the response body is never echoed into any exception.
 */
object ChatResponseCodec {
    const val MAX_RESPONSE_CHARS = 262144
    const val MAX_ANSWER_CHARS = 131072
    const val MAX_TOKENS: Long = 10_000_000L
    const val MAX_NESTING_DEPTH = 64
    val ALLOWED_FINISH_REASONS: Set<String> =
        setOf("stop", "length", "content_filter", "tool_calls", "function_call")

    fun parse(body: String): Pair<ChatAnswer, ChatUsage> {
        require(body.length <= MAX_RESPONSE_CHARS) { "CHAT_RESPONSE_TOO_LARGE" }
        require(nestingDepth(body) <= MAX_NESTING_DEPTH) { "CHAT_RESPONSE_NESTING_TOO_DEEP" }
        val root = try {
            TimeJson.parse(body)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(if (e.message == "JSON_DUPLICATE_KEY")
                "CHAT_RESPONSE_DUPLICATE_KEY" else "CHAT_RESPONSE_NOT_JSON")
        }
        val members = members(root, "CHAT_RESPONSE_NOT_OBJECT", "CHAT_RESPONSE_DUPLICATE_KEY")
        members["object"]?.let {
            require(TimeJsonValues.str(it) == "chat.completion") { "CHAT_RESPONSE_OBJECT_MISMATCH" }
        }
        val choices = members["choices"] as? TimeJson.Value.Arr
            ?: throw IllegalArgumentException("CHAT_RESPONSE_CHOICES_INVALID")
        require(choices.items.isNotEmpty()) { "CHAT_RESPONSE_CHOICES_EMPTY" }
        val choice = choices.items[0] as? TimeJson.Value.Obj
            ?: throw IllegalArgumentException("CHAT_RESPONSE_CHOICE_INVALID")
        val choiceMap = members(choice, "CHAT_RESPONSE_CHOICE_INVALID", "CHAT_RESPONSE_CHOICE_DUPLICATE_KEY")
        val message = choiceMap["message"] as? TimeJson.Value.Obj
            ?: throw IllegalArgumentException("CHAT_RESPONSE_MESSAGE_INVALID")
        val messageMap = members(message, "CHAT_RESPONSE_MESSAGE_INVALID", "CHAT_RESPONSE_MESSAGE_DUPLICATE_KEY")
        messageMap["role"]?.let {
            require(TimeJsonValues.str(it) == "assistant") { "CHAT_RESPONSE_ROLE_INVALID" }
        }
        val refusal = messageMap["refusal"]
        if (refusal != null && refusal !is TimeJson.Value.Null && refusal !is TimeJson.Value.Str) {
            throw IllegalArgumentException("CHAT_RESPONSE_REFUSAL_INVALID")
        }
        val toolCalls = messageMap["tool_calls"]
        if (toolCalls != null && toolCalls !is TimeJson.Value.Null && toolCalls !is TimeJson.Value.Arr) {
            throw IllegalArgumentException("CHAT_RESPONSE_TOOL_CALLS_INVALID")
        }
        val functionCall = messageMap["function_call"]
        if (functionCall != null && functionCall !is TimeJson.Value.Null && functionCall !is TimeJson.Value.Obj) {
            throw IllegalArgumentException("CHAT_RESPONSE_FUNCTION_CALL_INVALID")
        }
        val content = messageMap["content"]
        if (content != null && content !is TimeJson.Value.Null && content !is TimeJson.Value.Str) {
            throw IllegalArgumentException("CHAT_RESPONSE_CONTENT_INVALID")
        }
        var finishReason: String? = null
        val finishNode = choiceMap["finish_reason"]
        if (finishNode != null && finishNode !is TimeJson.Value.Null) {
            finishReason = TimeJsonValues.str(finishNode)
            require(finishReason in ALLOWED_FINISH_REASONS) { "CHAT_RESPONSE_FINISH_REASON_UNSUPPORTED" }
        }
        val hasRefusal = refusal is TimeJson.Value.Str && refusal.text.isNotBlank()
        val hasTools = (toolCalls is TimeJson.Value.Arr && toolCalls.items.isNotEmpty()) ||
            (functionCall != null && functionCall !is TimeJson.Value.Null)
        val answer: ChatAnswer = when {
            hasRefusal -> ChatAnswer.Refused(ChatRefusalKind.REFUSAL, finishReason)
            finishReason == "content_filter" -> ChatAnswer.Refused(ChatRefusalKind.CONTENT_FILTER, finishReason)
            hasTools || finishReason == "tool_calls" || finishReason == "function_call" -> ChatAnswer.ToolCalls(finishReason)
            content !is TimeJson.Value.Str || content.text.isBlank() -> ChatAnswer.EmptyContent
            else -> {
                val text = content.text
                require(text.length <= MAX_ANSWER_CHARS) { "CHAT_ANSWER_TOO_LARGE" }
                ChatText.utf8Bytes(text) // strict UTF-8 again on the way out
                ChatAnswer.Text(text, finishReason)
            }
        }
        return answer to parseUsage(members["usage"])
    }

    /** Present-and-valid usage is Precise; absent or explicit null is Unknown. Never zeros. */
    private fun parseUsage(node: TimeJson.Value?): ChatUsage {
        if (node == null || node is TimeJson.Value.Null) return ChatUsage.Unknown
        val usage = node as? TimeJson.Value.Obj ?: throw IllegalArgumentException("CHAT_USAGE_INVALID")
        val map = members(usage, "CHAT_USAGE_INVALID", "CHAT_USAGE_DUPLICATE_KEY")

        fun token(name: String): Long {
            val num = map[name] as? TimeJson.Value.Num
                ?: throw IllegalArgumentException("CHAT_USAGE_INCOMPLETE")
            require(num.literal.matches(Regex("[0-9]{1,10}"))) { "CHAT_USAGE_INVALID" }
            val value = num.literal.toLong()
            require(value <= MAX_TOKENS) { "CHAT_USAGE_INVALID" }
            return value
        }
        val prompt = token("prompt_tokens")
        val completion = token("completion_tokens")
        val total = token("total_tokens")
        require(total == prompt + completion) { "CHAT_USAGE_INVALID" }
        return ChatUsage.Precise(prompt, completion, total)
    }

    private fun members(
        value: TimeJson.Value,
        notObjectCode: String,
        duplicateCode: String,
    ): LinkedHashMap<String, TimeJson.Value> {
        val obj = value as? TimeJson.Value.Obj ?: throw IllegalArgumentException(notObjectCode)
        val map = linkedMapOf<String, TimeJson.Value>()
        obj.entries.forEach { (k, v) ->
            require(!map.containsKey(k)) { duplicateCode }
            map[k] = v
        }
        return map
    }

    /** Cheap linear scan so a nesting bomb cannot drive the shared JSON parser into deep recursion. */
    private fun nestingDepth(body: String): Int {
        var depth = 0
        var max = 0
        var inString = false
        var escaped = false
        for (c in body) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[', '{' -> {
                    depth += 1
                    if (depth > max) max = depth
                }
                ']', '}' -> if (depth > 0) depth -= 1
            }
        }
        return max
    }
}
