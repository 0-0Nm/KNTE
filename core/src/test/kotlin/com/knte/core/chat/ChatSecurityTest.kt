package com.knte.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Secret-handling guarantees: the pure domain can never see, hold, print or persist an API key.
 * The key exists only inside the transport implementation at call time; every pre-send
 * rejection path leaves the secret source untouched.
 */
class ChatSecurityTest {

    private val profile = ChatProfile.create("https://api.openai.com/v1/chat/completions", "gpt-4o-mini")
    private val otherProfile = ChatProfile.create("https://api.openai.com/v1/chat/completions", "gpt-4o")

    private fun messages(text: String): List<ChatMessage> = listOf(ChatMessage.of(ChatRole.USER, text))

    private fun okTransport(): FakeChatTransport =
        FakeChatTransport { ChatTransportResult.Delivered(200, ChatCompletionBody.build()) }

    @Test fun profileRejectsNonHttpsEndpoint() {
        try {
            ChatProfile.create("http://api.openai.com/v1/chat/completions", "m")
            assertTrue("expected rejection", false)
        } catch (e: IllegalArgumentException) {
            assertEquals("CHAT_ENDPOINT_HTTP_FORBIDDEN", e.message)
        }
    }

    @Test fun fullSendFlowNeverLetsTheSecretIntoDomainSurfaces() {
        val transport = okTransport()
        val runner = ChatSendRunner(transport)
        val msgs = messages("给知温发消息")
        val plan = runner.createPlan(profile, msgs)
        val outcome = runner.execute(plan, plan.confirmationToken, profile, msgs) as ChatOutcome.Completed
        val surfaces = listOf(
            profile.toString(),
            plan.toString(),
            plan.operationSummary,
            plan.confirmationToken,
            outcome.toString(),
            transport.requests[0].toString(),
            transport.requests[0].body,
        )
        for (s in surfaces) {
            assertTrue("secret leaked into a domain surface", !s.contains(FakeChatTransport.FAKE_SECRET))
        }
        assertEquals(1, transport.secretCalls)
    }

    @Test fun rejectionPathsNeverInjectAndNeverSend() {
        // plan unknown to this runner
        run {
            val t = okTransport()
            val runner = ChatSendRunner(t)
            val stranger = ChatSendRunner(t).createPlan(profile, messages("x"))
            runner.execute(stranger, stranger.confirmationToken, profile, messages("x"))
            assertEquals(0, t.secretCalls)
            assertEquals(0, t.requests.size)
        }
        // tampered confirmation
        run {
            val t = okTransport()
            val runner = ChatSendRunner(t)
            val other = runner.createPlan(profile, messages("o"))
            val plan = runner.createPlan(profile, messages("m"))
            runner.execute(plan, other.confirmationToken, profile, messages("m"))
            assertEquals(0, t.secretCalls)
            assertEquals(0, t.requests.size)
        }
        // config changed
        run {
            val t = okTransport()
            val runner = ChatSendRunner(t)
            val msgs = messages("same")
            val plan = runner.createPlan(profile, msgs)
            runner.execute(plan, plan.confirmationToken, otherProfile, msgs)
            assertEquals(0, t.secretCalls)
            assertEquals(0, t.requests.size)
        }
        // input changed
        run {
            val t = okTransport()
            val runner = ChatSendRunner(t)
            val plan = runner.createPlan(profile, messages("draft"))
            runner.execute(plan, plan.confirmationToken, profile, messages("edited"))
            assertEquals(0, t.secretCalls)
            assertEquals(0, t.requests.size)
        }
        // cancelled before send
        run {
            val t = okTransport()
            val runner = ChatSendRunner(t)
            val msgs = messages("later")
            val plan = runner.createPlan(profile, msgs)
            runner.cancel(plan)
            runner.execute(plan, plan.confirmationToken, profile, msgs)
            assertEquals(0, t.secretCalls)
            assertEquals(0, t.requests.size)
        }
    }

    @Test fun messageAndRequestToStringNeverLeakBodies() {
        val m = ChatMessage.of(ChatRole.USER, "USERBODY-MARKER")
        assertTrue(!m.toString().contains("USERBODY-MARKER"))
        val r = ChatTransportRequest(
            endpointUrl = "https://api.example.com/v1/chat/completions",
            method = "POST",
            contentType = "application/json",
            accept = "application/json",
            body = "REQUESTBODY-MARKER",
            credentialRef = "api.example.com",
        )
        assertTrue(!r.toString().contains("REQUESTBODY-MARKER"))
    }
}
