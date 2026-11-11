package com.knte.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Plan lifecycle: confirmation binding, single-use state gate, cancel-without-sending,
 * unknown-never-retried, input/config invalidation. Every pre-send rejection must leave the
 * transport and the secret source untouched.
 */
class ChatSendRunnerTest {

    private val profile = ChatProfile.create("https://api.openai.com/v1/chat/completions", "gpt-4o-mini")
    private val otherProfile = ChatProfile.create("https://api.openai.com/v1/chat/completions", "gpt-4o")

    private fun messages(vararg texts: String): List<ChatMessage> =
        texts.map { ChatMessage.of(ChatRole.USER, it) }

    private fun okTransport(): FakeChatTransport =
        FakeChatTransport { ChatTransportResult.Delivered(200, ChatCompletionBody.build()) }

    @Test fun happyPathDeliversExactlyOneRequest() {
        val transport = okTransport()
        val runner = ChatSendRunner(transport)
        val msgs = messages("今天天气如何？")
        val plan = runner.createPlan(profile, msgs)
        assertTrue(plan.operationSummary.contains("费用未知"))
        val outcome = runner.execute(plan, plan.confirmationToken, profile, msgs)
        val completed = outcome as? ChatOutcome.Completed ?: throw AssertionError("expected Completed, got $outcome")
        val answer = completed.answer as? ChatAnswer.Text ?: throw AssertionError("expected Text, got ${completed.answer}")
        assertEquals("好的。", answer.content)
        assertEquals("stop", answer.finishReason)
        assertEquals(ChatUsage.Unknown, completed.usage) // no usage in body -> Unknown, never zeros
        assertEquals(1, transport.requests.size)
        assertEquals(1, transport.secretCalls) // key injected at call time, exactly once
        val request = transport.requests[0]
        assertEquals("POST", request.method)
        assertEquals("application/json", request.contentType)
        assertEquals("application/json", request.accept)
        // The validated endpoint is used verbatim; the domain never joins a different path.
        assertEquals("https://api.openai.com/v1/chat/completions", request.endpointUrl)
        assertEquals(profile.configFingerprint, request.credentialRef) // opaque ref, not a secret
        assertTrue(request.body.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(request.body.contains("\"stream\":false"))
        assertTrue(request.body.contains("\"role\":\"user\""))
        assertTrue(request.body.contains("今天天气如何？"))
    }

    @Test fun cancelNeverCallsTransportAndPlanIsDead() {
        val transport = okTransport()
        val runner = ChatSendRunner(transport)
        val plan = runner.createPlan(profile, messages("hi"))
        assertEquals(ChatOutcome.Cancelled, runner.cancel(plan))
        assertEquals("CHAT_PLAN_NOT_REUSABLE", (runner.execute(plan, plan.confirmationToken, profile, messages("hi")) as ChatOutcome.Rejected).code)
        assertEquals(0, transport.requests.size)
        assertEquals(0, transport.secretCalls)
    }

    @Test fun cancelOnTerminalPlanIsRejected() {
        val runner = ChatSendRunner(okTransport())
        val plan = runner.createPlan(profile, messages("hi"))
        runner.cancel(plan)
        assertEquals("CHAT_PLAN_NOT_REUSABLE", (runner.cancel(plan) as ChatOutcome.Rejected).code)
    }

    @Test fun secondExecuteNeverSendsTwice() {
        val transport = okTransport()
        val runner = ChatSendRunner(transport)
        val msgs = messages("one")
        val plan = runner.createPlan(profile, msgs)
        runner.execute(plan, plan.confirmationToken, profile, msgs)
        val second = runner.execute(plan, plan.confirmationToken, profile, msgs)
        assertEquals("CHAT_PLAN_NOT_REUSABLE", (second as ChatOutcome.Rejected).code)
        assertEquals(1, transport.requests.size)
        assertEquals(1, transport.secretCalls)
    }

    @Test fun unknownOutcomeIsNeverAutoRetried() {
        val transport = FakeChatTransport { ChatTransportResult.Indeterminate("TEST_TIMEOUT", "超时，结果未知") }
        val runner = ChatSendRunner(transport)
        val msgs = messages("q")
        val plan = runner.createPlan(profile, msgs)
        val first = runner.execute(plan, plan.confirmationToken, profile, msgs)
        assertEquals("TEST_TIMEOUT", (first as ChatOutcome.Unknown).code)
        val second = runner.execute(plan, plan.confirmationToken, profile, msgs)
        assertEquals("CHAT_PLAN_NOT_REUSABLE", (second as ChatOutcome.Rejected).code)
        assertEquals(1, transport.requests.size)
    }

    @Test fun refusedOutcomeConsumesPlanWithoutRetry() {
        val transport = FakeChatTransport { ChatTransportResult.Refused("TEST_HTTP_401", "鉴权失败") }
        val runner = ChatSendRunner(transport)
        val msgs = messages("q")
        val plan = runner.createPlan(profile, msgs)
        assertEquals("TEST_HTTP_401", (runner.execute(plan, plan.confirmationToken, profile, msgs) as ChatOutcome.Rejected).code)
        assertEquals("CHAT_PLAN_NOT_REUSABLE", (runner.execute(plan, plan.confirmationToken, profile, msgs) as ChatOutcome.Rejected).code)
        assertEquals(1, transport.requests.size)
    }

    @Test fun tamperedConfirmationInvalidatesPlanWithoutSending() {
        val transport = okTransport()
        val runner = ChatSendRunner(transport)
        val otherPlan = runner.createPlan(profile, messages("other"))
        val plan = runner.createPlan(profile, messages("mine"))
        val outcome = runner.execute(plan, otherPlan.confirmationToken, profile, messages("mine"))
        assertEquals("CHAT_CONFIRMATION_MISMATCH", (outcome as ChatOutcome.Rejected).code)
        assertEquals(0, transport.requests.size)
        assertEquals(0, transport.secretCalls)
        // The plan is dead: even the correct token cannot send it any more.
        val retry = runner.execute(plan, plan.confirmationToken, profile, messages("mine"))
        assertEquals("CHAT_PLAN_NOT_REUSABLE", (retry as ChatOutcome.Rejected).code)
        assertEquals(0, transport.requests.size)
        assertEquals(0, transport.secretCalls)
    }

    @Test fun changedInputInvalidatesOldPlanWithoutSending() {
        val transport = okTransport()
        val runner = ChatSendRunner(transport)
        val plan = runner.createPlan(profile, messages("first draft"))
        val outcome = runner.execute(plan, plan.confirmationToken, profile, messages("edited draft"))
        assertEquals("CHAT_INPUT_CHANGED", (outcome as ChatOutcome.Rejected).code)
        assertEquals(0, transport.requests.size)
        assertEquals(0, transport.secretCalls)
        val retry = runner.execute(plan, plan.confirmationToken, profile, messages("first draft"))
        assertEquals("CHAT_PLAN_NOT_REUSABLE", (retry as ChatOutcome.Rejected).code)
        assertEquals(0, transport.requests.size)
    }

    @Test fun changedConfigInvalidatesOldPlanWithoutSending() {
        val transport = okTransport()
        val runner = ChatSendRunner(transport)
        val msgs = messages("same")
        val plan = runner.createPlan(profile, msgs)
        val outcome = runner.execute(plan, plan.confirmationToken, otherProfile, msgs)
        assertEquals("CHAT_CONFIG_CHANGED", (outcome as ChatOutcome.Rejected).code)
        assertEquals(0, transport.requests.size)
        assertEquals(0, transport.secretCalls)
    }

    @Test fun planFromAnotherRunnerIsUnknown() {
        val runnerA = ChatSendRunner(okTransport())
        val runnerB = ChatSendRunner(okTransport())
        val plan = runnerA.createPlan(profile, messages("x"))
        assertEquals("CHAT_PLAN_UNKNOWN", (runnerB.execute(plan, plan.confirmationToken, profile, messages("x")) as ChatOutcome.Rejected).code)
        assertEquals("CHAT_PLAN_UNKNOWN", (runnerB.cancel(plan) as ChatOutcome.Rejected).code)
    }

    @Test fun non2xxDeliveredIsRejectedWithoutBodyEcho() {
        val transport = FakeChatTransport {
            ChatTransportResult.Delivered(500, "{\"error\":\"SECRETISH-99\"}")
        }
        val runner = ChatSendRunner(transport)
        val msgs = messages("q")
        val plan = runner.createPlan(profile, msgs)
        val outcome = runner.execute(plan, plan.confirmationToken, profile, msgs) as ChatOutcome.Rejected
        assertEquals("CHAT_HTTP_500", outcome.code)
        assertTrue(!outcome.safeSummary.contains("SECRETISH-99"))
    }

    @Test fun throwingTransportIsUnknownAndNeverRetried() {
        val transport = FakeChatTransport { throw RuntimeException("boom-SECRETISH-99") }
        val runner = ChatSendRunner(transport)
        val msgs = messages("q")
        val plan = runner.createPlan(profile, msgs)
        val outcome = runner.execute(plan, plan.confirmationToken, profile, msgs) as ChatOutcome.Unknown
        assertEquals("CHAT_TRANSPORT_THREW", outcome.code)
        assertTrue(!outcome.safeSummary.contains("boom"))
        assertEquals("CHAT_PLAN_NOT_REUSABLE", (runner.execute(plan, plan.confirmationToken, profile, msgs) as ChatOutcome.Rejected).code)
        assertEquals(1, transport.requests.size)
    }

    @Test fun oversizeResponseIsRejectedNotTruncated() {
        val huge = "x".repeat(ChatResponseCodec.MAX_RESPONSE_CHARS + 1)
        val transport = FakeChatTransport { ChatTransportResult.Delivered(200, huge) }
        val runner = ChatSendRunner(transport)
        val msgs = messages("q")
        val plan = runner.createPlan(profile, msgs)
        val outcome = runner.execute(plan, plan.confirmationToken, profile, msgs)
        assertEquals("CHAT_RESPONSE_TOO_LARGE", (outcome as ChatOutcome.Rejected).code)
    }

    @Test fun confirmationTokenBindsPlanAndConfig() {
        val runner = ChatSendRunner(okTransport())
        val p1 = runner.createPlan(profile, messages("a"))
        val p2 = runner.createPlan(profile, messages("a"))
        val p3 = runner.createPlan(otherProfile, messages("a"))
        assertNotEquals(p1.confirmationToken, p2.confirmationToken) // fresh confirmation per plan
        assertEquals(p1.inputHash, p2.inputHash)
        assertNotEquals(p1.configFingerprint, p3.configFingerprint)
        assertNotEquals(p1.confirmationToken, p3.confirmationToken)
    }
}
