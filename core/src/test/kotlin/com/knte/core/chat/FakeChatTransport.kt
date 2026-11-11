package com.knte.core.chat

import com.knte.core.time.format.TimeJson

/**
 * Test-only fake transport: records requests, answers from a script, and injects the fake key
 * at call time only (mirroring the app-side secure injection). No real network ever happens
 * in this suite.
 */
class FakeChatTransport(
    private val responder: (ChatTransportRequest) -> ChatTransportResult,
) : ChatTransport {
    val requests = mutableListOf<ChatTransportRequest>()
    var secretCalls = 0
    val secretSource = ChatSecretSource { _ ->
        secretCalls += 1
        FAKE_SECRET
    }

    override fun send(request: ChatTransportRequest): ChatTransportResult {
        requests.add(request)
        secretSource.secretFor(request.credentialRef)
        return responder(request)
    }

    companion object {
        const val FAKE_SECRET = "sk-FAKE-SECRET-2b7e31"
    }
}

/** Builds OpenAI-shaped completion bodies with the existing TimeJson; no hand-escaped JSON. */
object ChatCompletionBody {
    data class Usage(val prompt: Long, val completion: Long, val total: Long)

    fun build(
        content: TimeJson.Value? = TimeJson.Value.Str("好的。"),
        finishReason: String? = "stop",
        refusal: TimeJson.Value = TimeJson.Value.Null,
        toolCallCount: Int = 0,
        functionCall: Boolean = false,
        usage: Usage? = null,
        role: String = "assistant",
    ): String {
        val message = mutableListOf<Pair<String, TimeJson.Value>>()
        message += "role" to TimeJson.Value.Str(role)
        if (content != null) message += "content" to content
        message += "refusal" to refusal
        if (toolCallCount > 0) {
            message += "tool_calls" to TimeJson.Value.Arr((0 until toolCallCount).map { i ->
                TimeJson.Value.Obj(
                    listOf(
                        "id" to TimeJson.Value.Str("call_$i"),
                        "type" to TimeJson.Value.Str("function"),
                        "function" to TimeJson.Value.Obj(
                            listOf(
                                "name" to TimeJson.Value.Str("demo_tool"),
                                "arguments" to TimeJson.Value.Str("{}"),
                            )
                        ),
                    )
                )
            })
        }
        if (functionCall) {
            message += "function_call" to TimeJson.Value.Obj(
                listOf(
                    "name" to TimeJson.Value.Str("demo_fn"),
                    "arguments" to TimeJson.Value.Str("{}"),
                )
            )
        }
        val choice = mutableListOf<Pair<String, TimeJson.Value>>()
        choice += "index" to TimeJson.Value.Num("0")
        choice += "message" to TimeJson.Value.Obj(message)
        if (finishReason != null) choice += "finish_reason" to TimeJson.Value.Str(finishReason)
        val root = mutableListOf<Pair<String, TimeJson.Value>>()
        root += "id" to TimeJson.Value.Str("chatcmpl-test")
        root += "object" to TimeJson.Value.Str("chat.completion")
        root += "created" to TimeJson.Value.Num("1731000000")
        root += "model" to TimeJson.Value.Str("test-model")
        root += "choices" to TimeJson.Value.Arr(listOf(TimeJson.Value.Obj(choice)))
        if (usage != null) {
            root += "usage" to TimeJson.Value.Obj(
                listOf(
                    "prompt_tokens" to TimeJson.Value.Num(usage.prompt.toString()),
                    "completion_tokens" to TimeJson.Value.Num(usage.completion.toString()),
                    "total_tokens" to TimeJson.Value.Num(usage.total.toString()),
                )
            )
        }
        return TimeJson.encode(TimeJson.Value.Obj(root))
    }
}
