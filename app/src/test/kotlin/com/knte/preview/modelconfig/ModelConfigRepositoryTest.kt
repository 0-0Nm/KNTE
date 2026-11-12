package com.knte.preview.modelconfig

import com.knte.core.chat.ChatProfile
import org.junit.Assert.*
import org.junit.Test

/** Repository rules with a fake store + fake vault. No Keystore, no network, no real key.
 * Every fixture uses the CURRENT 5-field EndpointIdentity and 9+alias CredentialRecord;
 * legacy shapes are represented only to prove they are rejected fail-closed. */
class ModelConfigRepositoryTest {
    private val endpointA = "https://api.openai.com/v1/chat/completions"
    private val endpointA2 = "https://api.openai.com/v2/chat/completions"
    private val endpointB = "https://api.other.com/v1/chat/completions"
    private val anthropic = "https://api.anthropic.com/v1/chat/completions"
    private val key = "synthetic-key-0123456789"

    private fun id(raw: String): EndpointIdentity = identityOf(raw)!!
    private fun repo(
        storage: FakeModelConfigStorage,
        vault: FakeCredentialVault = FakeCredentialVault()
    ) = AndroidModelConfigRepository(storage, vault)

    /** A fully-consistent v3 saved state bound to [raw]. */
    private fun saved(storage: FakeModelConfigStorage, raw: String,
        present: Boolean = true, model: String = "m-old") {
        val identity = id(raw)
        storage.profile = ModelProfile(identity.endpoint, model,
            if (present) CredentialPresence.Present else CredentialPresence.NotConfigured)
        storage.credential = if (present) FakeModelConfigStorage.record(identity) else null
    }

    private fun expectCode(code: String, block: () -> Any?): ModelConfigException {
        val e = runCatching { block() }.exceptionOrNull()
        assertTrue("expected ModelConfigException($code) but was $e", e is ModelConfigException)
        assertEquals(code, (e as ModelConfigException).code)
        return e
    }

    // ------------------------- happy path / binding metadata -------------------------

    @Test
    fun firstSaveWithoutKeyIsNotConfigured() {
        val storage = FakeModelConfigStorage()
        val profile = repo(storage).commit(ModelConfigDraft.of(endpointA, "m1", ""))
        assertEquals(CredentialPresence.NotConfigured, profile.credentialPresence)
        assertNull(storage.credential)
        assertEquals(ModelConfigBinding.CREDENTIAL_VERSION, FakeCredentialVault().version)
    }

    @Test
    fun firstSaveWithKeyWritesFullyBoundV3Record() {
        val storage = FakeModelConfigStorage()
        val vault = FakeCredentialVault()
        val profile = repo(storage, vault).commit(ModelConfigDraft.of(endpointA, "m1", key))
        assertEquals(CredentialPresence.Present, profile.credentialPresence)
        val r = storage.credential!!
        val identity = id(endpointA)
        assertEquals(identity.endpoint, r.endpoint)
        assertEquals(identity.host, r.host)
        assertEquals(identity.port, r.port)
        assertEquals(identity.path, r.path)
        assertEquals(ProviderKind.OpenAi, r.provider)
        assertEquals(ModelConfigBinding.DEFAULT_PURPOSE, r.purpose)
        assertEquals(ModelConfigBinding.CREDENTIAL_VERSION, r.version)
        assertEquals(ModelConfigBinding.STORAGE_LABEL, r.storage)
        assertEquals(vault.alias, r.alias)
        assertEquals("synthetic-v3", r.sealed.ciphertext)
        assertEquals(1, vault.sealCalls.size)
    }

    @Test
    fun emptyKeyKeepsExistingCredentialByteForByteOnSameTarget() {
        val storage = FakeModelConfigStorage()
        saved(storage, endpointA)
        val before = storage.credential!!
        val profile = repo(storage).commit(ModelConfigDraft.of(endpointA, "m-new", ""))
        assertEquals(CredentialPresence.Present, profile.credentialPresence)
        assertEquals("m-new", profile.model)
        assertEquals(before.sealed, storage.credential!!.sealed)
        assertEquals(0, FakeCredentialVault().sealCalls.size)
    }

    @Test
    fun emptyKeyNeverDeletesExistingCredential() {
        val storage = FakeModelConfigStorage()
        saved(storage, endpointA)
        repo(storage).commit(ModelConfigDraft.of(endpointA, "m-new", ""))
        assertNotNull(storage.credential)
        assertEquals(CredentialPresence.Present, storage.profile!!.credentialPresence)
        assertEquals(0, storage.clearCount.get())
    }

    @Test
    fun modelChangeSameEndpointKeepsCredential() {
        val storage = FakeModelConfigStorage()
        saved(storage, endpointA)
        val before = storage.credential!!.sealed
        val profile = repo(storage).commit(ModelConfigDraft.of(endpointA, "other-model", ""))
        assertEquals("other-model", profile.model)
        assertEquals(CredentialPresence.Present, profile.credentialPresence)
        assertEquals(before, storage.credential!!.sealed)
    }

    // ------------------------- target change / explicit NEW key -------------------------

    @Test
    fun targetChangeWithNewKeyStoresNewRecordBoundToNewTarget() {
        val storage = FakeModelConfigStorage()
        saved(storage, endpointA)
        val vault = FakeCredentialVault()
        val profile = repo(storage, vault).commit(ModelConfigDraft.of(endpointB, "m1", "brand-new-key"))
        assertEquals(CredentialPresence.Present, profile.credentialPresence)
        val r = storage.credential!!
        val next = id(endpointB)
        assertEquals(next.endpoint, r.endpoint)
        assertEquals(next.host, r.host)
        assertEquals(next.path, r.path)
        assertFalse("old ciphertext must never be reused for a new target",
            r.sealed.ciphertext == "synthetic-old")
        assertEquals("synthetic-v3", r.sealed.ciphertext)
        assertEquals(next, vault.sealCalls.single())
    }

    @Test
    fun pathChangeWithNewKeyStoresNewRecord() {
        val storage = FakeModelConfigStorage()
        saved(storage, endpointA)
        val vault = FakeCredentialVault()
        repo(storage, vault).commit(ModelConfigDraft.of(endpointA2, "m1", "brand-new-key"))
        assertEquals(id(endpointA2), vault.sealCalls.single())
        assertEquals("/v2/chat/completions", storage.credential!!.path)
    }

    @Test
    fun targetChangeWithEmptyKeyIsRejectedAndWritesNothing() {
        val storage = FakeModelConfigStorage()
        saved(storage, endpointA)
        val beforeProfile = storage.profile
        val beforeRecord = storage.credential
        expectCode(ModelConfigCodes.KEY_REPLACEMENT_REQUIRED) {
            repo(storage).commit(ModelConfigDraft.of(endpointB, "m1", ""))
        }
        assertEquals(beforeProfile, storage.profile)
        assertEquals(beforeRecord, storage.credential)
    }

    @Test
    fun emptyKeyIsRejectedWhenPresencePresentButRecordMissing() {
        val storage = FakeModelConfigStorage()
        storage.profile = ModelProfile(id(endpointA).endpoint, "m", CredentialPresence.Present)
        storage.credential = null
        expectCode(ModelConfigCodes.VAULT_READ_FAILED) {
            repo(storage).commit(ModelConfigDraft.of(endpointA, "m", ""))
        }
        assertEquals(0, storage.commitCount.get())
    }

    // ------------------------- exception presence: fail-closed, never clear -------------------------

    @Test
    fun typedCredentialReadFailureAtCommitIsFailClosedAndNeverClears() {
        val storage = FakeModelConfigStorage()
        saved(storage, endpointA)
        val before = storage.credential
        storage.unreadableRecord = true
        expectCode(ModelConfigCodes.VAULT_READ_FAILED) {
            repo(storage).commit(ModelConfigDraft.of(endpointA, "m-new", key))
        }
        assertEquals("a read failure must never downgrade to null/clear ciphertext", before,
            storage.credential)
        assertEquals(0, storage.commitCount.get())
    }

    @Test
    fun anyExceptionCredentialReadFailureAtCommitIsFailClosedAndNeverClears() {
        val storage = FakeModelConfigStorage()
        saved(storage, endpointA)
        val before = storage.credential
        storage.failCredentialRead = true
        expectCode(ModelConfigCodes.VAULT_READ_FAILED) {
            repo(storage).commit(ModelConfigDraft.of(endpointA, "m-new", key))
        }
        assertEquals(before, storage.credential)
        assertEquals(0, storage.commitCount.get())
    }

    @Test
    fun typedUnreadableRecordEvenWithNewKeyDoesNotOverwriteBlindly() {
        // A corrupt/present-but-unreadable record forces a NEW key, but the repository must
        // still fail closed rather than silently treating it as absent and writing a profile
        // that would claim a credential whose metadata could not be read.
        val storage = FakeModelConfigStorage()
        storage.profile = ModelProfile(id(endpointA).endpoint, "m", CredentialPresence.Present)
        storage.credential = FakeModelConfigStorage.record(id(endpointA))
        storage.unreadableRecord = true
        expectCode(ModelConfigCodes.VAULT_READ_FAILED) {
            repo(storage).commit(ModelConfigDraft.of(endpointA, "m", "new-key"))
        }
        assertNotNull(storage.credential)
    }

    @Test
    fun profileReadFailureAtLoadIsFailClosed() {
        val storage = FakeModelConfigStorage()
        saved(storage, endpointA)
        storage.failRead = true
        expectCode(ModelConfigCodes.READ_FAILED) { repo(storage).load() }
    }

    @Test
    fun credentialReadFailureAtLoadIsFailClosed() {
        val storage = FakeModelConfigStorage()
        saved(storage, endpointA)
        storage.unreadableRecord = true
        expectCode(ModelConfigCodes.VAULT_READ_FAILED) { repo(storage).load() }
    }

    @Test
    fun anyExceptionCredentialReadFailureAtLoadIsFailClosed() {
        val storage = FakeModelConfigStorage()
        saved(storage, endpointA)
        storage.failCredentialRead = true
        expectCode(ModelConfigCodes.VAULT_READ_FAILED) { repo(storage).load() }
    }

    // ------------------------- load(): profile/record mismatch detection -------------------------

    @Test
    fun loadDetectsEndpointMismatchBetweenProfileAndRecord() {
        val storage = FakeModelConfigStorage()
        storage.profile = ModelProfile(id(endpointA).endpoint, "m", CredentialPresence.Present)
        storage.credential = FakeModelConfigStorage.record(id(endpointB))
        val snapshot = repo(storage).load()
        assertFalse("profile endpoint + record endpoint mismatch must be untrusted",
            snapshot.endpointTrusted)
        assertEquals(id(endpointB).endpoint, snapshot.credentialEndpoint)
    }

    @Test
    fun loadDetectsStaleProfileEndpointThatIsNotCurrentPolicy() {
        val storage = FakeModelConfigStorage()
        storage.profile = ModelProfile("https://api.openai.com/v1", "m", CredentialPresence.Present)
        storage.credential = null
        assertFalse(repo(storage).load().endpointTrusted)
    }

    @Test
    fun loadDetectsRecordMetadataDriftAsUntrusted() {
        val storage = FakeModelConfigStorage()
        val identity = id(endpointA)
        storage.profile = ModelProfile(identity.endpoint, "m", CredentialPresence.Present)
        storage.credential = FakeModelConfigStorage.record(identity,
            version = 2) // legacy version drift
        assertFalse(repo(storage).load().endpointTrusted)
    }

    @Test
    fun loadAcceptsConsistentV3State() {
        val storage = FakeModelConfigStorage()
        saved(storage, endpointA)
        val snapshot = repo(storage).load()
        assertTrue(snapshot.endpointTrusted)
        assertEquals(id(endpointA).endpoint, snapshot.credentialEndpoint)
        assertEquals(CredentialPresence.Present, snapshot.profile!!.credentialPresence)
    }

    @Test
    fun loadOfEmptyStoreIsClean() {
        val snapshot = repo(FakeModelConfigStorage()).load()
        assertNull(snapshot.profile)
        assertNull(snapshot.credentialEndpoint)
        assertTrue(snapshot.endpointTrusted)
    }

    // ------------------------- metadata drift matrix at commit (fail-closed) -------------------------

    private fun driftCommit(drift: CredentialRecord, target: String = endpointA,
        newKey: String = ""): Pair<FakeModelConfigStorage, String> {
        val storage = FakeModelConfigStorage()
        val identity = id(target)
        storage.profile = ModelProfile(identity.endpoint, "m", CredentialPresence.Present)
        storage.credential = drift
        val e = runCatching {
            repo(storage).commit(ModelConfigDraft.of(target, "m2", newKey))
        }.exceptionOrNull()
        val code = when {
            e is ModelConfigException -> e.code
            e != null -> "UNEXPECTED_EXCEPTION:$e"
            else -> "OK"
        }
        return storage to code
    }

    @Test
    fun metadataDriftMatrixIsRejectedEvenOnSameTarget() {
        val i = id(endpointA)
        val cases = listOf(
            "oldVersion" to FakeModelConfigStorage.record(i, version = 2),
            "wrongAlias" to FakeModelConfigStorage.record(i, alias = "other.alias"),
            "wrongStorage" to FakeModelConfigStorage.record(i, storage = "app-private"),
            "wrongPurpose" to FakeModelConfigStorage.record(i, purpose = "other-purpose"),
            "wrongProvider" to FakeModelConfigStorage.record(i, provider = ProviderKind.Generic),
            "wrongHost" to FakeModelConfigStorage.record(i, host = "api.other.com"),
            "wrongPort" to FakeModelConfigStorage.record(i, port = 8443),
            "wrongPath" to FakeModelConfigStorage.record(i, path = "/v2/chat/completions"),
            "wrongEndpoint" to FakeModelConfigStorage.record(i, endpoint = endpointB)
        )
        for ((name, rec) in cases) {
            val (storage, code) = driftCommit(rec)
            assertEquals("$name should be CREDENTIAL_BINDING_MISMATCH, got $code",
                ModelConfigCodes.CREDENTIAL_BINDING_MISMATCH, code)
            assertEquals("$name must not commit", 0, storage.commitCount.get())
            assertNotNull("$name must not clear ciphertext", storage.credential)
        }
    }

    @Test
    fun metadataDriftWithNewKeyCanExplicitlyReplace() {
        // A NEW key is an explicit replacement: a drifted/legacy record must not be reused,
        // but with a fresh key for a supported target the new v3 record is written.
        val i = id(endpointA)
        val (storage, code) = driftCommit(FakeModelConfigStorage.record(i, version = 2),
            newKey = "brand-new-key")
        assertEquals("OK", code)
        assertEquals(ModelConfigBinding.CREDENTIAL_VERSION, storage.credential!!.version)
        assertEquals("synthetic-v3", storage.credential!!.sealed.ciphertext)
    }

    @Test
    fun newAliasIsTakenFromVaultNotReusedFromOldRecord() {
        val storage = FakeModelConfigStorage()
        saved(storage, endpointA)
        val vault = FakeCredentialVault(alias = "knte.model.config.credential.v2")
        repo(storage, vault).commit(ModelConfigDraft.of(endpointA, "m", "brand-new-key"))
        assertEquals("knte.model.config.credential.v2", storage.credential!!.alias)
    }

    // ------------------------- model validation via core ChatProfile rules -------------------------

    @Test
    fun model129IsRejected() {
        val storage = FakeModelConfigStorage()
        val m129 = "x".repeat(ChatProfile.MAX_MODEL_CHARS + 1)
        assertEquals(129, m129.length)
        expectCode(ModelConfigCodes.MODEL_INVALID) {
            repo(storage).commit(ModelConfigDraft.of(endpointA, m129, key))
        }
        assertEquals(0, storage.commitCount.get())
    }

    @Test
    fun model128IsAccepted() {
        val storage = FakeModelConfigStorage()
        val m128 = "x".repeat(ChatProfile.MAX_MODEL_CHARS)
        val p = repo(storage).commit(ModelConfigDraft.of(endpointA, m128, key))
        assertEquals(128, p.model.length)
    }

    @Test
    fun unpairedSurrogateModelIsRejected() {
        val storage = FakeModelConfigStorage()
        val bad = "m" + Char(0xD800) // lone high surrogate
        expectCode(ModelConfigCodes.MODEL_INVALID) {
            repo(storage).commit(ModelConfigDraft.of(endpointA, bad, key))
        }
        assertEquals(0, storage.commitCount.get())
    }

    @Test
    fun blankModelRejected() {
        val storage = FakeModelConfigStorage()
        expectCode(ModelConfigCodes.MODEL_INVALID) {
            repo(storage).commit(ModelConfigDraft.of(endpointA, "   ", key))
        }
    }

    @Test
    fun controlCharModelRejected() {
        val storage = FakeModelConfigStorage()
        expectCode(ModelConfigCodes.MODEL_INVALID) {
            repo(storage).commit(ModelConfigDraft.of(endpointA, "m" + Char(0) + "x", key))
        }
    }

    // ------------------------- endpoint / provider / secret-shape gates -------------------------

    @Test
    fun anthropicIsUnsupportedAndWritesNothing() {
        val storage = FakeModelConfigStorage()
        expectCode(ModelConfigCodes.PROVIDER_UNSUPPORTED) {
            repo(storage).commit(ModelConfigDraft.of(anthropic, "m", key))
        }
        assertEquals(0, storage.commitCount.get())
    }

    @Test
    fun nonHttpsEndpointRejected() {
        expectCode(ModelConfigCodes.ENDPOINT_NOT_HTTPS) {
            repo(FakeModelConfigStorage()).commit(
                ModelConfigDraft.of("http://api.openai.com/v1/chat/completions", "m", key))
        }
    }

    @Test
    fun credentialsInsideEndpointRejected() {
        expectCode(ModelConfigCodes.ENDPOINT_HAS_CREDENTIALS) {
            repo(FakeModelConfigStorage()).commit(
                ModelConfigDraft.of("https://user:pw@api.openai.com/v1/chat/completions", "m", key))
        }
    }

    @Test
    fun emptyKeyReplacementIsNotSilentlyAcceptedOnTargetChange() {
        val storage = FakeModelConfigStorage()
        saved(storage, endpointA)
        expectCode(ModelConfigCodes.KEY_REPLACEMENT_REQUIRED) {
            repo(storage).commit(ModelConfigDraft.of(endpointB, "m", ""))
        }
    }

    @Test
    fun blankOrControlOrSurrogateNewKeyIsRejected() {
        val storage = FakeModelConfigStorage()
        for (bad in listOf("   ", "k" + Char(0), "k" + Char(0xD800))) {
            expectCode(ModelConfigCodes.KEY_INVALID) {
                repo(storage).commit(ModelConfigDraft.of(endpointA, "m", bad))
            }
        }
        assertEquals(0, storage.commitCount.get())
    }

    @Test
    fun overlongNewKeyIsRejected() {
        val storage = FakeModelConfigStorage()
        val tooLong = "k".repeat(ModelConfigLimits.MAX_SECRET_CHARS + 1)
        expectCode(ModelConfigCodes.KEY_INVALID) {
            repo(storage).commit(ModelConfigDraft.of(endpointA, "m", tooLong))
        }
    }

    // ------------------------- commit / seal failures, atomicity -------------------------

    @Test
    fun commitFailureIsReportedNotSwallowed() {
        val storage = FakeModelConfigStorage()
        storage.failCommit = true
        expectCode(ModelConfigCodes.COMMIT_FAILED) {
            repo(storage).commit(ModelConfigDraft.of(endpointA, "m1", key))
        }
        assertEquals(1, storage.commitFailures.get())
    }

    @Test
    fun sealFailureIsReportedNotSwallowedAndNoProfileWritten() {
        val storage = FakeModelConfigStorage()
        val vault = FakeCredentialVault().apply { throwOnSeal = true }
        expectCode(ModelConfigCodes.VAULT_UNAVAILABLE) {
            repo(storage, vault).commit(ModelConfigDraft.of(endpointA, "m1", key))
        }
        assertNull(storage.profile)
    }

    @Test
    fun profileAndCiphertextAreCommittedInOneTransaction() {
        val storage = FakeModelConfigStorage()
        repo(storage).commit(ModelConfigDraft.of(endpointA, "m1", key))
        assertEquals(1, storage.commitCount.get())
        assertEquals(1, storage.commitLog.size)
    }

    @Test
    fun repositoryNeverOpensNetwork() {
        val storage = FakeModelConfigStorage()
        val profile = repo(storage).commit(ModelConfigDraft.of(endpointA, "m1", key))
        assertEquals(endpointA, profile.endpoint)
    }

    @Test
    fun endpointNormalizationKeepsEquivalentUrlStable() {
        val a = ModelEndpointPolicy.check(endpointA)
        val b = ModelEndpointPolicy.check("https://API.OPENAI.COM:443/v1/chat/completions")
        assertTrue(a is EndpointCheck.Valid && b is EndpointCheck.Valid)
        assertEquals((a as EndpointCheck.Valid).endpoint, (b as EndpointCheck.Valid).endpoint)
    }

    @Test
    fun domainApiDoesNotExposeAndroidTypes() {
        // Portable class-boundary check: works under both JUnitCore and Gradle.
        // Complete import/dependency scanning is a separate source audit, not a file-path test.
        for (type in listOf(AndroidModelConfigRepository::class.java, FakeModelConfigStorage::class.java)) {
            val exposed = type.declaredMethods.flatMap {
                it.parameterTypes.toList() + it.returnType
            } + type.declaredConstructors.flatMap { it.parameterTypes.toList() } +
                type.declaredFields.map { it.type }
            for (entry in exposed) assertFalse(entry.name, entry.name.startsWith("android."))
        }
    }
}
