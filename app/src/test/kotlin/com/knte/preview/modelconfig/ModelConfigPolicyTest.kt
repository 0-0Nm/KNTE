package com.knte.preview.modelconfig

import com.knte.core.chat.ChatProfile
import org.junit.Assert.*
import org.junit.Test

/** Endpoint/provider policy rules and the CURRENT credential-decision contract.
 * All endpoints are COMPLETE chat/completions addresses; all identities use the current
 * 5-field EndpointIdentity. No Android, no Keystore, no network. */
class ModelConfigPolicyTest {
    private fun id(raw: String): EndpointIdentity = identityOf(raw)!!
    private fun repo() = AndroidModelConfigRepository(FakeModelConfigStorage(), FakeCredentialVault())

    private val openAiV1 = "https://api.openai.com/v1/chat/completions"
    private val openAiV2 = "https://api.openai.com/v2/chat/completions"
    private val genericA = "https://api.example.com/v1/chat/completions"
    private val genericB = "https://api.other.com/v1/chat/completions"
    private val anthropic = "https://api.anthropic.com/v1/chat/completions"

    private fun expectCode(code: String, block: () -> Any?) {
        val e = runCatching { block() }.exceptionOrNull()
        assertTrue("expected ModelConfigException($code) but was $e", e is ModelConfigException)
        assertEquals(code, (e as ModelConfigException).code)
    }

    @Test
    fun completeChatCompletionsIsValidAndClassified() {
        val check = ModelEndpointPolicy.check(openAiV1)
        assertTrue(check is EndpointCheck.Valid)
        check as EndpointCheck.Valid
        assertEquals("api.openai.com", check.host)
        assertEquals(443, check.port)
        assertEquals("/v1/chat/completions", check.path)
        assertEquals(openAiV1, check.endpoint)
        assertEquals(ProviderKind.OpenAi, ModelEndpointPolicy.classify(check.host, check.port))
        assertTrue(ModelEndpointPolicy.isSaveableProvider(ProviderKind.OpenAi))
    }

    @Test
    fun anthropicIsRecognisedButExplicitlyUnsupportedForSave() {
        assertTrue(ModelEndpointPolicy.check(anthropic) is EndpointCheck.Valid)
        assertEquals(ProviderKind.Anthropic, ModelEndpointPolicy.classify("api.anthropic.com", 443))
        assertFalse(ModelEndpointPolicy.isSaveableProvider(ProviderKind.Anthropic))
        expectCode(ModelConfigCodes.PROVIDER_UNSUPPORTED) {
            repo().commit(ModelConfigDraft.of(anthropic, "m", "new-key"))
        }
    }

    @Test
    fun genericPublicHostIsGenericNotUnknown() {
        assertEquals(ProviderKind.Generic, ModelEndpointPolicy.classify("api.example.com", 443))
        assertTrue(ModelEndpointPolicy.isSaveableProvider(ProviderKind.Generic))
    }

    @Test
    fun httpIsRejected() {
        val check = ModelEndpointPolicy.check("http://api.openai.com/v1/chat/completions")
        assertTrue(check is EndpointCheck.Invalid)
        assertEquals(ModelConfigCodes.ENDPOINT_NOT_HTTPS, (check as EndpointCheck.Invalid).code)
    }

    @Test
    fun endpointWithoutChatCompletionsSuffixIsRejected() {
        val check = ModelEndpointPolicy.check("https://api.openai.com/v1")
        assertTrue(check is EndpointCheck.Invalid)
        assertEquals(ModelConfigCodes.ENDPOINT_NOT_CHAT_COMPLETIONS,
            (check as EndpointCheck.Invalid).code)
    }

    @Test
    fun embeddedCredentialsQueryAndFragmentRejected() {
        assertEquals(ModelConfigCodes.ENDPOINT_HAS_CREDENTIALS,
            (ModelEndpointPolicy.check("https://user:pw@api.openai.com/v1/chat/completions")
                as EndpointCheck.Invalid).code)
        assertEquals(ModelConfigCodes.ENDPOINT_HAS_QUERY,
            (ModelEndpointPolicy.check(openAiV1 + "?k=x") as EndpointCheck.Invalid).code)
        assertEquals(ModelConfigCodes.ENDPOINT_HAS_FRAGMENT,
            (ModelEndpointPolicy.check(openAiV1 + "#f") as EndpointCheck.Invalid).code)
    }

    @Test
    fun blankAndControlCharactersAreRejected() {
        assertTrue(ModelEndpointPolicy.check("") is EndpointCheck.Invalid)
        val withControl = "https://api.openai.com/v1/chat/completions" + Char(10) + "x"
        assertTrue(ModelEndpointPolicy.check(withControl) is EndpointCheck.Invalid)
    }

    @Test
    fun modelLimitComesFromCoreChatProfile() {
        assertEquals(128, ChatProfile.MAX_MODEL_CHARS)
        assertEquals(ChatProfile.MAX_MODEL_CHARS, ModelConfigLimits.MAX_MODEL_CHARS)
    }

    // ---- CURRENT decision contract: a NEW key is an explicit replacement; old key is
    //      never reused when the target (host/port/path/provider) changes. ----

    @Test
    fun changedTargetWithoutNewKeyIsRejected() {
        expectCode(ModelConfigCodes.KEY_REPLACEMENT_REQUIRED) {
            repo().decide(id(openAiV1), id(genericA), hasCredential = true, hasNewKey = false)
        }
    }

    @Test
    fun changedTargetWithNewKeyStoresNew() {
        val decision = repo().decide(id(openAiV1), id(genericA),
            hasCredential = true, hasNewKey = true)
        assertTrue(decision is CredentialDecision.StoreNew)
        assertEquals(ProviderKind.OpenAi, (decision as CredentialDecision.StoreNew).provider)
    }

    @Test
    fun changedPathWithoutNewKeyIsRejected() {
        expectCode(ModelConfigCodes.KEY_REPLACEMENT_REQUIRED) {
            repo().decide(id(openAiV2), id(openAiV1), hasCredential = true, hasNewKey = false)
        }
    }

    @Test
    fun sameTargetWithoutNewKeyKeepsExisting() {
        assertEquals(CredentialDecision.KeepExisting,
            repo().decide(id(openAiV1), id(openAiV1), hasCredential = true, hasNewKey = false))
    }

    @Test
    fun sameTargetWithNewKeyStoresNew() {
        assertTrue(repo().decide(id(openAiV1), id(openAiV1),
            hasCredential = true, hasNewKey = true) is CredentialDecision.StoreNew)
    }

    @Test
    fun crossGenericHostWithNewKeyStoresNewAndNeverReusesOldKey() {
        val decision = repo().decide(id(genericB), id(genericA),
            hasCredential = true, hasNewKey = true)
        assertTrue(decision is CredentialDecision.StoreNew)
        assertEquals(ProviderKind.Generic, (decision as CredentialDecision.StoreNew).provider)
        expectCode(ModelConfigCodes.KEY_REPLACEMENT_REQUIRED) {
            repo().decide(id(genericB), id(genericA), hasCredential = true, hasNewKey = false)
        }
    }

    @Test
    fun noCredentialAndNoKeyIsAbsentByDesign() {
        assertEquals(CredentialDecision.AbsentByDesign,
            repo().decide(id(openAiV1), null, hasCredential = false, hasNewKey = false))
    }

    @Test
    fun firstSaveWithNewKeyStoresNew() {
        assertTrue(repo().decide(id(openAiV1), null,
            hasCredential = false, hasNewKey = true) is CredentialDecision.StoreNew)
    }
}
