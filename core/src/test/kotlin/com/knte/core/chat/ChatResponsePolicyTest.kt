package com.knte.core.chat

import com.knte.core.time.format.TimeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Response policy: refusal/tools/empty can never fake a success; usage is Precise only when
 * the provider really returned it (missing = Unknown, never zeros); strict subset with
 * bounded body; no response text is ever echoed into an error.
 */
class ChatResponsePolicyTest {

    private fun parse(body: String): Pair<ChatAnswer, ChatUsage> = ChatResponseCodec.parse(body)

    private fun expectCode(code: String, body: String) {
        try {
            parse(body)
            fail("expected $code")
        } catch (e: IllegalArgumentException) {
            assertEquals(code, e.message)
        }
    }

    @Test fun textAnswerWithPreciseUsage() {
        val (answer, usage) = parse(
            ChatCompletionBody.build(
                content = TimeJson.Value.Str("你好，世界。"),
                usage = ChatCompletionBody.Usage(prompt = 12, completion = 34, total = 46),
            )
        )
        val text = answer as? ChatAnswer.Text ?: throw AssertionError("expected text")
        assertEquals("你好，世界。", text.content)
        assertEquals("stop", text.finishReason)
        assertEquals(ChatUsage.Precise(12, 34, 46), usage)
    }

    @Test fun missingUsageIsUnknownNeverZero() {
        val (_, usage) = parse(ChatCompletionBody.build())
        assertEquals(ChatUsage.Unknown, usage)
        assertTrue(usage !is ChatUsage.Precise)
    }

    @Test fun refusalBeatsToolCallsAndContent() {
        val (answer, _) = parse(
            ChatCompletionBody.build(
                content = TimeJson.Value.Str("看似有内容"),
                refusal = TimeJson.Value.Str("我不能这样做"),
                toolCallCount = 2,
                finishReason = "tool_calls",
            )
        )
        val refused = answer as? ChatAnswer.Refused ?: throw AssertionError("expected refused")
        assertEquals(ChatRefusalKind.REFUSAL, refused.kind)
        assertEquals("tool_calls", refused.finishReason)
    }

    @Test fun contentFilterFinishIsRefused() {
        val (answer, _) = parse(
            ChatCompletionBody.build(content = TimeJson.Value.Str("部分文本"), finishReason = "content_filter")
        )
        assertEquals(ChatRefusalKind.CONTENT_FILTER, (answer as ChatAnswer.Refused).kind)
    }

    @Test fun toolsOnlyIsExplicitlyNotASuccess() {
        val (answer, _) = parse(
            ChatCompletionBody.build(
                content = TimeJson.Value.Null,
                finishReason = "tool_calls",
                toolCallCount = 1,
            )
        )
        val tools = answer as? ChatAnswer.ToolCalls ?: throw AssertionError("expected tool kind")
        assertEquals("tool_calls", tools.finishReason)
        // The domain carries no tool payload and no tool executor at all: nothing can run.
    }

    @Test fun functionCallShapeIsAlsoToolKind() {
        val (answer, _) = parse(ChatCompletionBody.build(content = TimeJson.Value.Null, functionCall = true))
        assertTrue(answer is ChatAnswer.ToolCalls)
    }

    @Test fun toolCallsWithTextIsStillNotASuccess() {
        val (answer, _) = parse(
            ChatCompletionBody.build(
                content = TimeJson.Value.Str("我先调工具"),
                toolCallCount = 1,
                finishReason = "tool_calls",
            )
        )
        assertTrue(answer is ChatAnswer.ToolCalls)
    }

    @Test fun emptyContentVariantsAreEmptyAnswer() {
        for (content in listOf(TimeJson.Value.Null, TimeJson.Value.Str(""), TimeJson.Value.Str("   "))) {
            val (answer, _) = parse(ChatCompletionBody.build(content = content))
            assertEquals(ChatAnswer.EmptyContent, answer)
        }
    }

    @Test fun omittedContentIsAlsoEmptyAnswer() {
        val (answer, _) = parse(ChatCompletionBody.build(content = null))
        assertEquals(ChatAnswer.EmptyContent, answer)
    }

    private fun rawCompletion(usageValue: TimeJson.Value): String {
        val choice = TimeJson.Value.Obj(
            listOf(
                "index" to TimeJson.Value.Num("0"),
                "message" to TimeJson.Value.Obj(
                    listOf(
                        "role" to TimeJson.Value.Str("assistant"),
                        "content" to TimeJson.Value.Str("ok"),
                        "refusal" to TimeJson.Value.Null,
                    )
                ),
                "finish_reason" to TimeJson.Value.Str("stop"),
            )
        )
        return TimeJson.encode(
            TimeJson.Value.Obj(
                listOf(
                    "id" to TimeJson.Value.Str("chatcmpl-x"),
                    "object" to TimeJson.Value.Str("chat.completion"),
                    "choices" to TimeJson.Value.Arr(listOf(choice)),
                    "usage" to usageValue,
                )
            )
        )
    }

    @Test fun usageMustArriveCompleteAndBounded() {
        expectCode("CHAT_USAGE_INCOMPLETE", rawCompletion(TimeJson.Value.Obj(listOf("prompt_tokens" to TimeJson.Value.Num("12")))))
        expectCode("CHAT_USAGE_INVALID", rawCompletion(TimeJson.Value.Obj(listOf(
            "prompt_tokens" to TimeJson.Value.Num("12"),
            "completion_tokens" to TimeJson.Value.Num("-3"),
            "total_tokens" to TimeJson.Value.Num("9"),
        ))))
        expectCode("CHAT_USAGE_INVALID", rawCompletion(TimeJson.Value.Obj(listOf(
            "prompt_tokens" to TimeJson.Value.Num("12"),
            "completion_tokens" to TimeJson.Value.Num("12.5"),
            "total_tokens" to TimeJson.Value.Num("9"),
        ))))
        expectCode("CHAT_USAGE_INVALID", rawCompletion(TimeJson.Value.Obj(listOf(
            "prompt_tokens" to TimeJson.Value.Num("12"),
            "completion_tokens" to TimeJson.Value.Num("3"),
            "total_tokens" to TimeJson.Value.Num("2"), // total < completion: contradiction
        ))))
    }

    @Test fun oversizedBodyAndNestingBombFailClosed() {
        expectCode("CHAT_RESPONSE_TOO_LARGE", "x".repeat(ChatResponseCodec.MAX_RESPONSE_CHARS + 1))
        expectCode(
            "CHAT_RESPONSE_NESTING_TOO_DEEP",
            "[".repeat(ChatResponseCodec.MAX_NESTING_DEPTH + 1) + "]".repeat(ChatResponseCodec.MAX_NESTING_DEPTH + 1),
        )
    }

    @Test fun answerTooLargeIsRejectedNeverTruncated() {
        expectCode(
            "CHAT_ANSWER_TOO_LARGE",
            ChatCompletionBody.build(content = TimeJson.Value.Str("x".repeat(ChatResponseCodec.MAX_ANSWER_CHARS + 1))),
        )
    }

    @Test fun strictSubsetRejectsBadShapes() {
        expectCode("CHAT_RESPONSE_NOT_OBJECT", "[1,2,3]")
        expectCode("CHAT_RESPONSE_NOT_JSON", "{\"choices\": trunc")
        expectCode("CHAT_RESPONSE_CHOICES_INVALID", "{\"choices\":{}}")
        expectCode("CHAT_RESPONSE_CHOICES_EMPTY", "{\"choices\":[]}")
        expectCode("CHAT_RESPONSE_ROLE_INVALID", ChatCompletionBody.build(role = "tool"))
        expectCode("CHAT_RESPONSE_FINISH_REASON_UNSUPPORTED", ChatCompletionBody.build(finishReason = "weird_reason"))
        expectCode("CHAT_RESPONSE_OBJECT_MISMATCH", ChatCompletionBody.build().replace("chat.completion", "other.object"))
        expectCode("CHAT_RESPONSE_DUPLICATE_KEY", ChatCompletionBody.build().dropLast(1) + ",\"id\":\"second\"}")
        expectCode("CHAT_RESPONSE_CONTENT_INVALID", ChatCompletionBody.build(content = TimeJson.Value.Bool(true)))
    }

    @Test fun unknownTopLevelFieldsAndExtraChoicesAreIgnored() {
        val secondChoice = TimeJson.Value.Obj(
            listOf(
                "index" to TimeJson.Value.Num("1"),
                "message" to TimeJson.Value.Obj(
                    listOf(
                        "role" to TimeJson.Value.Str("assistant"),
                        "content" to TimeJson.Value.Str("second"),
                        "refusal" to TimeJson.Value.Null,
                    )
                ),
                "finish_reason" to TimeJson.Value.Str("stop"),
            )
        )
        val parsed = TimeJson.parse(ChatCompletionBody.build(content = TimeJson.Value.Str("first"))) as TimeJson.Value.Obj
        val updated = parsed.entries
            .map { (k, v) ->
                if (k == "choices" && v is TimeJson.Value.Arr) "choices" to TimeJson.Value.Arr(v.items + secondChoice) else k to v
            }
            .toMutableList()
        updated += "system_fingerprint" to TimeJson.Value.Str("fp1")
        val (answer, usage) = parse(TimeJson.encode(TimeJson.Value.Obj(updated)))
        assertEquals("first", (answer as ChatAnswer.Text).content)
        assertEquals(ChatUsage.Unknown, usage)
    }

    @Test fun noResponseBodyEverEchoedInErrors() {
        val marker = "SECRETISH-BODY-987"
        val bodies = listOf(
            "{oops" + marker,
            ChatCompletionBody.build(content = TimeJson.Value.Str(marker), finishReason = "weird"),
        )
        for (b in bodies) {
            try {
                parse(b)
                fail("expected failure")
            } catch (e: IllegalArgumentException) {
                assertTrue("message leaked body", e.message?.contains(marker) != true)
                assertTrue(e.message?.startsWith("CHAT_") == true)
            }
        }
    }
    @Test fun toolFinishWithoutPayloadIsNotOrdinaryText() {
        val (answer, _) = parse(ChatCompletionBody.build(content=TimeJson.Value.Str("partial"),finishReason="tool_calls"))
        assertTrue(answer is ChatAnswer.ToolCalls)
    }
    @Test fun lengthFinishIsExplicitlyIncompleteAndStringFormDoesNotLeakBody() {
        val (answer, _) = parse(ChatCompletionBody.build(content=TimeJson.Value.Str("PRIVATE-SYNTHETIC-BODY"),finishReason="length"))
        val text=answer as ChatAnswer.Text
        assertTrue(text.incomplete)
        assertTrue(!text.toString().contains("PRIVATE-SYNTHETIC-BODY"))
    }
    @Test fun contradictoryUsageCannotClaimPreciseValue() {
        expectCode("CHAT_USAGE_INVALID",ChatCompletionBody.build(usage=ChatCompletionBody.Usage(12,34,34)))
    }

}
