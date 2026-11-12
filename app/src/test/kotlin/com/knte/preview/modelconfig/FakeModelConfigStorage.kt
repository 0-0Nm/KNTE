package com.knte.preview.modelconfig

import java.util.concurrent.atomic.AtomicInteger

/** Deterministic fake vault. Sealing is a synthetic transform; no Keystore, no crypto,
 * no network. Its binding constants and secret-shape gate are kept STRICTLY in sync with
 * the pure domain contract (ModelConfigBinding / ModelSecretShape). It records only the
 * endpoint identity the repository asked to seal for, NEVER a plaintext secret. */
class FakeCredentialVault(
    private val stored: Boolean = false,
    var unavailable: Boolean = false,
    override val purpose: String = ModelConfigBinding.DEFAULT_PURPOSE,
    override val alias: String = ModelConfigBinding.DEFAULT_ALIAS,
    override val version: Int = ModelConfigBinding.CREDENTIAL_VERSION
) : CredentialVault {
    /** Per-identity synthetic plaintext a test arms for injection. Never persisted. */
    private val armed = HashMap<EndpointIdentity, String>()

    val sealCalls = mutableListOf<EndpointIdentity>()
    val withSecretCalls = AtomicInteger(0)
    var throwOnSeal = false

    fun armSecret(identity: EndpointIdentity, value: String) {
        armed[identity] = value
    }

    override fun hasCredential(): Boolean = stored

    override fun <T> withSecret(identity: EndpointIdentity, block: (String) -> T): T {
        withSecretCalls.incrementAndGet()
        if (unavailable) {
            throw ModelConfigException(ModelConfigCodes.VAULT_UNAVAILABLE, "synthetic: vault unavailable")
        }
        val armedValue = armed[identity] ?: throw ModelConfigException(
            ModelConfigCodes.CREDENTIAL_BINDING_MISMATCH,
            "synthetic: no credential bound to this endpoint identity"
        )
        if (!stored) {
            throw ModelConfigException(ModelConfigCodes.VAULT_UNAVAILABLE, "synthetic: no credential stored")
        }
        return block(armedValue)
    }

    override fun seal(identity: EndpointIdentity, secret: String): SealedCredential {
        sealCalls.add(identity)
        // Same pure shape gate as the repository; the plaintext is never stored or logged.
        ModelSecretShape.requireValid(secret)
        if (throwOnSeal) {
            throw ModelConfigException(ModelConfigCodes.VAULT_UNAVAILABLE, "synthetic seal failure")
        }
        return FakeModelConfigStorage.syntheticSealed("v" + ModelConfigBinding.CREDENTIAL_VERSION)
    }
}

/** Deterministic in-memory store used ONLY by unit tests. Never touches the Keystore,
 * never performs network I/O, and never records a plaintext secret. */
class FakeModelConfigStorage : ModelConfigStore {
    var profile: ModelProfile? = null
    var credential: CredentialRecord? = null
    var failCommit = false
    var failRead = false

    /** readCredentialRecord reports an unreadable record (typed domain exception). */
    var unreadableRecord = false
    /** readCredentialRecord throws an arbitrary unchecked exception (any-exception path). */
    var failCredentialRead = false

    val commitCount = AtomicInteger(0)
    val clearCount = AtomicInteger(0)
    val commitFailures = AtomicInteger(0)

    /** Records commit ordering so a test can prove profile + ciphertext metadata were
     * written in ONE transaction rather than two separate writes. */
    val commitLog = mutableListOf<String>()

    override fun readProfile(): ModelProfile? {
        if (failRead) throw IllegalStateException("synthetic read failure")
        return profile
    }

    override fun readCredentialRecord(): CredentialRecord? {
        if (failRead) throw IllegalStateException("synthetic read failure")
        if (failCredentialRead) throw IllegalStateException("synthetic credential read failure")
        if (unreadableRecord) {
            throw ModelConfigException(
                ModelConfigCodes.VAULT_READ_FAILED, "synthetic: record unreadable"
            )
        }
        return credential
    }

    override fun commitAtomic(profile: ModelProfile, credential: CredentialRecord?) {
        if (failCommit) {
            commitFailures.incrementAndGet()
            throw ModelConfigException(ModelConfigCodes.COMMIT_FAILED, "synthetic commit failure")
        }
        commitCount.incrementAndGet()
        commitLog.add("profile=${profile.endpoint};credential=${credential != null}")
        this.profile = profile
        this.credential = credential
    }

    override fun clearCredentialMetadata() {
        clearCount.incrementAndGet()
        credential = null
        profile = profile?.copy(credentialPresence = CredentialPresence.NotConfigured)
    }

    /** Synthetic ciphertext stand-in. It is NOT AES-GCM output and never holds a real key. */
    companion object {
        fun syntheticSealed(tag: String) = SealedCredential(
            iv = "aaaaaaaaaaaaaaaa",
            ciphertext = "synthetic-$tag"
        )

        /** Builds a record that satisfies the CURRENT v3 (9 fields + alias) binding
         * contract. Every metadata field is overridable so tests can build a drift matrix. */
        fun record(
            identity: EndpointIdentity,
            provider: ProviderKind = identity.kind,
            purpose: String = ModelConfigBinding.DEFAULT_PURPOSE,
            version: Int = ModelConfigBinding.CREDENTIAL_VERSION,
            storage: String = ModelConfigBinding.STORAGE_LABEL,
            alias: String = ModelConfigBinding.DEFAULT_ALIAS,
            tag: String = "old",
            endpoint: String = identity.endpoint,
            host: String = identity.host,
            port: Int = identity.port,
            path: String = identity.path,
            sealed: SealedCredential = syntheticSealed(tag)
        ) = CredentialRecord(
            endpoint = endpoint,
            host = host,
            port = port,
            path = path,
            provider = provider,
            purpose = purpose,
            version = version,
            sealed = sealed,
            storage = storage,
            alias = alias
        )
    }
}

/** Convenience for tests: the CURRENT 5-field identity of a valid endpoint, or null. */
fun identityOf(raw: String): EndpointIdentity? =
    (ModelEndpointPolicy.check(raw) as? EndpointCheck.Valid)?.let { ModelEndpointPolicy.identityOf(it) }
