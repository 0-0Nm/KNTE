package com.knte.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** HTTPS endpoint gate: pure syntax, no network probe, no path joining, chat/completions only. */
class ChatEndpointTest {

    private fun expectCode(url: String, code: String) {
        try {
            ChatEndpoint.parse(url)
            fail("expected rejection: $url")
        } catch (e: IllegalArgumentException) {
            assertEquals(code, e.message)
        }
    }

    @Test fun acceptsPublicHttpsChatCompletionsEndpoint() {
        val e = ChatEndpoint.parse("https://api.openai.com/v1/chat/completions")
        assertEquals("api.openai.com", e.host)
        assertEquals("https://api.openai.com/v1/chat/completions", e.url)
    }

    @Test fun normalizesSchemeAndHostCaseButKeepsPathVerbatim() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            ChatEndpoint.parse("HTTPS://API.DeepSeek.COM/v1/chat/completions").url,
        )
        // No base-URL joining ever: a deeper custom path stays exactly as typed.
        assertEquals(
            "https://proxy.example.com/llm/v1/chat/completions",
            ChatEndpoint.parse("https://proxy.example.com/llm/v1/chat/completions").url,
        )
    }

    @Test fun keepsExplicitPort() {
        assertEquals(
            "https://api.example.com:8443/v1/chat/completions",
            ChatEndpoint.parse("https://api.example.com:8443/v1/chat/completions").url,
        )
    }

    @Test fun acceptsPublicIpv4Host() {
        assertEquals("1.2.3.4", ChatEndpoint.parse("https://1.2.3.4/v1/chat/completions").host)
    }

    @Test fun rejectsPlaintextHttp() {
        expectCode("http://api.openai.com/v1/chat/completions", "CHAT_ENDPOINT_HTTP_FORBIDDEN")
    }

    @Test fun rejectsMissingOrForeignScheme() {
        expectCode("api.openai.com/v1/chat/completions", "CHAT_ENDPOINT_SCHEME_INVALID")
        expectCode("ftp://api.openai.com/v1/chat/completions", "CHAT_ENDPOINT_SCHEME_INVALID")
    }

    @Test fun rejectsUserInfo() {
        expectCode("https://user:pass@api.openai.com/v1/chat/completions", "CHAT_ENDPOINT_USERINFO_FORBIDDEN")
    }

    @Test fun rejectsQueryAndFragment() {
        expectCode("https://api.openai.com/v1/chat/completions?api-version=2024", "CHAT_ENDPOINT_QUERY_FORBIDDEN")
        expectCode("https://api.openai.com/v1/chat/completions#frag", "CHAT_ENDPOINT_FRAGMENT_FORBIDDEN")
    }

    @Test fun rejectsLocalAndPrivateHosts() {
        expectCode("https://localhost/v1/chat/completions", "CHAT_ENDPOINT_HOST_NOT_PUBLIC")
        expectCode("https://gateway.local/v1/chat/completions", "CHAT_ENDPOINT_HOST_NOT_PUBLIC")
        expectCode("https://gw.internal/v1/chat/completions", "CHAT_ENDPOINT_HOST_NOT_PUBLIC")
        expectCode("https://192.168.1.10/v1/chat/completions", "CHAT_ENDPOINT_HOST_NOT_PUBLIC")
        expectCode("https://127.0.0.1/v1/chat/completions", "CHAT_ENDPOINT_HOST_NOT_PUBLIC")
        expectCode("https://10.0.0.5/v1/chat/completions", "CHAT_ENDPOINT_HOST_NOT_PUBLIC")
    }

    @Test fun rejectsSingleLabelAndMalformedHosts() {
        expectCode("https://openai/v1/chat/completions", "CHAT_ENDPOINT_HOST_NOT_PUBLIC")
        expectCode("https://api_.openai.com/v1/chat/completions", "CHAT_ENDPOINT_HOST_NOT_PUBLIC")
        expectCode("https://[::1]/v1/chat/completions", "CHAT_ENDPOINT_HOST_NOT_PUBLIC")
    }

    @Test fun rejectsBadPorts() {
        expectCode("https://api.openai.com:0/v1/chat/completions", "CHAT_ENDPOINT_PORT_INVALID")
        expectCode("https://api.openai.com:99999/v1/chat/completions", "CHAT_ENDPOINT_PORT_INVALID")
        expectCode("https://api.openai.com:/v1/chat/completions", "CHAT_ENDPOINT_PORT_INVALID")
    }

    @Test fun requiresCompleteChatCompletionsPath() {
        expectCode("https://api.openai.com", "CHAT_ENDPOINT_PATH_REQUIRED")
        expectCode("https://api.openai.com/v1/embeddings", "CHAT_ENDPOINT_NOT_CHAT_COMPLETIONS")
        expectCode("https://api.openai.com/v1/completions", "CHAT_ENDPOINT_NOT_CHAT_COMPLETIONS")
        expectCode("https://api.openai.com/", "CHAT_ENDPOINT_NOT_CHAT_COMPLETIONS")
    }

    @Test fun rejectsBadPathCharacters() {
        expectCode("https://api.openai.com/a b/chat/completions", "CHAT_ENDPOINT_CONTROL_CHAR")
        expectCode("https://api.openai.com/v1/%zz/chat/completions", "CHAT_ENDPOINT_PATH_INVALID")
        expectCode("https://api.openai.com/v1/中文/chat/completions", "CHAT_ENDPOINT_PATH_INVALID")
    }

    @Test fun rejectsTooLongUrl() {
        expectCode("https://api.openai.com/" + "a".repeat(600) + "/chat/completions", "CHAT_ENDPOINT_TOO_LONG")
    }

    @Test fun acceptsStrictPercentEscapes() {
        val e = ChatEndpoint.parse("https://api.openai.com/v1/a%2Fb/chat/completions")
        assertTrue(e.url.endsWith("/v1/a%2Fb/chat/completions"))
    }
    @Test fun numericLegacyAddressFormsAreNotDnsFallbacks() {
        expectCode("https://0177.0.0.1/v1/chat/completions", "CHAT_ENDPOINT_HOST_NOT_PUBLIC")
        expectCode("https://999.1.2.3/v1/chat/completions", "CHAT_ENDPOINT_HOST_NOT_PUBLIC")
    }

}
