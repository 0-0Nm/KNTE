package com.knte.preview.modelconfig

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Secret-boundary contract for the CURRENT v3 interface.
 *
 * All crypto here runs on the real JCA AES/GCM provider with a SYNTHETIC in-memory
 * AES-256 key. No Android Keystore is opened, no device/emulator is required, no real
 * credential is used, and no framework stub is fabricated. The production KeyStore path
 * is deliberately NOT exercised: these tests never claim device-level Keystore validity. */
class ModelConfigSecretBoundaryTest {

    private companion object {
        const val SYNTHETIC_SECRET = "synthetic-secret-value-1234567890"
        const val HOST = "api.example.com"
        const val PORT = 443
        const val PATH = "/v1/chat/completions"
    }

    /** Deterministic synthetic key material; NOT a credential, never persisted. */
    private fun key(): SecretKey =
        SecretKeySpec(ByteArray(32) { (it * 7 + 3).toByte() }, "AES")

    private fun aad(
        provider: ProviderKind = ProviderKind.Generic,
        purpose: String = ModelConfigBinding.DEFAULT_PURPOSE,
        alias: String = ModelConfigBinding.DEFAULT_ALIAS,
        version: Int = ModelConfigBinding.CREDENTIAL_VERSION,
        host: String = HOST,
        port: Int = PORT,
        path: String = PATH
    ): ByteArray = CredentialCrypto.aad(purpose, alias, version, host, port, path, provider)

    /** Encrypt ARBITRARY bytes with the synthetic key so an authenticated-but-invalid
     * plaintext can be forged: GCM authenticates it, so only the strict UTF-8 / shape
     * checks inside open() can reject it. */
    private fun encryptRaw(key: SecretKey, aad: ByteArray, plain: ByteArray): SealedCredential {
        val cipher = Cipher.getInstance(CredentialCrypto.TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return SealedCredential(
            Base64.getEncoder().encodeToString(iv),
            Base64.getEncoder().encodeToString(body)
        )
    }

    private fun expectConfigError(expectedCode: String? = null, block: () -> Unit): ModelConfigException {
        val e = runCatching(block).exceptionOrNull()
        assertTrue("expected ModelConfigException", e is ModelConfigException)
        val mce = e as ModelConfigException
        if (expectedCode != null) assertEquals(expectedCode, mce.code)
        assertFalse(mce.toString().contains(SYNTHETIC_SECRET))
        assertFalse(mce.message.orEmpty().contains(SYNTHETIC_SECRET))
        return mce
    }

    private fun identity(): EndpointIdentity = EndpointIdentity(
        "https://$HOST$PATH", HOST, PORT, PATH, ProviderKind.Generic
    )

    private fun record(): CredentialRecord = CredentialRecord(
        endpoint = "https://$HOST$PATH",
        host = HOST, port = PORT, path = PATH,
        provider = ProviderKind.Generic,
        purpose = ModelConfigBinding.DEFAULT_PURPOSE,
        version = ModelConfigBinding.CREDENTIAL_VERSION,
        sealed = SealedCredential("aaaaaaaaaaaaaaaa", "ciphertext-placeholder"),
        storage = ModelConfigBinding.STORAGE_LABEL,
        alias = ModelConfigBinding.DEFAULT_ALIAS
    )

    // ---------- real AES/GCM seal/open ----------

    @Test
    fun sealOpenRoundTripUsesRealAesGcm() {
        val k = key()
        val a = aad()
        val secret = "sk-synthetic-Ω-😀-中-123456"
        val sealed = CredentialCrypto.seal(k, a, secret)
        assertEquals(secret, CredentialCrypto.open(k, a, sealed))
        assertEquals(CredentialCrypto.IV_BYTES, Base64.getDecoder().decode(sealed.iv).size)
        val body = Base64.getDecoder().decode(sealed.ciphertext)
        assertTrue(body.size >= AndroidKeystoreVault.MIN_SEALED_BYTES)
        assertTrue(body.size <= AndroidKeystoreVault.MAX_SEALED_BYTES)
        assertNotEquals(secret, sealed.ciphertext)
    }

    @Test
    fun everySealUsesAFreshIv() {
        val k = key()
        val a = aad()
        assertNotEquals(
            CredentialCrypto.seal(k, a, SYNTHETIC_SECRET).iv,
            CredentialCrypto.seal(k, a, SYNTHETIC_SECRET).iv
        )
    }

    // ---------- AAD v3 binding, including provider + alias ----------

    @Test
    fun aadV3IncludesProviderAndKeepsGenericDefaultCompatible() {
        val genericDefault = CredentialCrypto.aad(
            ModelConfigBinding.DEFAULT_PURPOSE, ModelConfigBinding.DEFAULT_ALIAS,
            ModelConfigBinding.CREDENTIAL_VERSION, HOST, PORT, PATH
        )
        val genericExplicit = CredentialCrypto.aad(
            ModelConfigBinding.DEFAULT_PURPOSE, ModelConfigBinding.DEFAULT_ALIAS,
            ModelConfigBinding.CREDENTIAL_VERSION, HOST, PORT, PATH,
            ProviderKind.Generic
        )
        assertArrayEquals(genericExplicit, genericDefault)
        val openAi = CredentialCrypto.aad(
            ModelConfigBinding.DEFAULT_PURPOSE, ModelConfigBinding.DEFAULT_ALIAS,
            ModelConfigBinding.CREDENTIAL_VERSION, HOST, PORT, PATH,
            ProviderKind.OpenAi
        )
        assertFalse(genericDefault.contentEquals(openAi))
        val text = String(openAi, Charsets.UTF_8)
        assertTrue(text.contains("knte-model-config-aad-v3"))
        assertTrue(text.contains("OpenAi"))
    }

    @Test
    fun everyTamperedAadFieldIsRejected() {
        val k = key()
        val sealed = CredentialCrypto.seal(k, aad(), SYNTHETIC_SECRET)
        listOf(
            aad(purpose = "other-purpose"),
            aad(alias = "other-alias"),
            aad(version = ModelConfigBinding.CREDENTIAL_VERSION - 1),
            aad(provider = ProviderKind.OpenAi),
            aad(provider = ProviderKind.Anthropic),
            aad(host = "other.example.com"),
            aad(port = 8443),
            aad(path = "/v2/chat/completions")
        ).forEach { tampered ->
            expectConfigError(ModelConfigCodes.VAULT_UNAVAILABLE) {
                CredentialCrypto.open(k, tampered, sealed)
            }
        }
    }

    @Test
    fun tamperedCiphertextOrIvFailsGcmIntegrity() {
        val k = key()
        val a = aad()
        val sealed = CredentialCrypto.seal(k, a, SYNTHETIC_SECRET)
        val body = Base64.getDecoder().decode(sealed.ciphertext).clone()
        body[0] = (body[0].toInt() xor 0x01).toByte()
        expectConfigError(ModelConfigCodes.VAULT_UNAVAILABLE) {
            CredentialCrypto.open(k, a, sealed.copy(ciphertext = Base64.getEncoder().encodeToString(body)))
        }
        val otherIv = Base64.getEncoder().encodeToString(ByteArray(12) { 9 })
        expectConfigError(ModelConfigCodes.VAULT_UNAVAILABLE) {
            CredentialCrypto.open(k, a, sealed.copy(iv = otherIv))
        }
    }

    // ---------- ciphertext size / IV / base64 bounds ----------

    @Test
    fun ivAndCiphertextBoundsAreEnforced() {
        val k = key()
        val a = aad()
        val sealed = CredentialCrypto.seal(k, a, SYNTHETIC_SECRET)
        listOf(0, 8, 13, 16).forEach { n ->
            expectConfigError(ModelConfigCodes.VAULT_READ_FAILED) {
                CredentialCrypto.open(
                    k, a,
                    sealed.copy(iv = Base64.getEncoder().encodeToString(ByteArray(n)))
                )
            }
        }
        expectConfigError(ModelConfigCodes.VAULT_READ_FAILED) {
            CredentialCrypto.open(k, a, sealed.copy(iv = "not*base64"))
        }
        expectConfigError(ModelConfigCodes.VAULT_READ_FAILED) {
            CredentialCrypto.open(k, a, sealed.copy(ciphertext = "not*base64"))
        }
        // tag-only blob is below the lower bound
        expectConfigError(ModelConfigCodes.VAULT_READ_FAILED) {
            CredentialCrypto.open(
                k, a,
                sealed.copy(
                    ciphertext = Base64.getEncoder()
                        .encodeToString(ByteArray(AndroidKeystoreVault.TAG_BYTES))
                )
            )
        }
        // oversized blob is refused before any crypto
        expectConfigError(ModelConfigCodes.VAULT_READ_FAILED) {
            CredentialCrypto.open(
                k, a,
                sealed.copy(
                    ciphertext = Base64.getEncoder()
                        .encodeToString(ByteArray(AndroidKeystoreVault.MAX_SEALED_BYTES + 1))
                )
            )
        }
    }

    // ---------- strict UTF-8 + shared shape validation AFTER decryption ----------

    @Test
    fun decryptedBytesMustBeStrictUtf8() {
        val k = key()
        val a = aad()
        listOf(
            byteArrayOf(0xC0.toByte(), 0x80.toByte()),                 // overlong
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),  // surrogate
            byteArrayOf(0xFF.toByte()),                                 // invalid lead
            byteArrayOf(0xE2.toByte(), 0x82.toByte())                  // truncated
        ).forEach { bytes ->
            expectConfigError(ModelConfigCodes.VAULT_READ_FAILED) {
                CredentialCrypto.open(k, a, encryptRaw(k, a, bytes))
            }
        }
    }

    @Test
    fun authenticatedButInvalidClearShapeIsRejected() {
        val k = key()
        val a = aad()
        listOf(
            "   ".toByteArray(Charsets.UTF_8),                                  // blank
            "ab".toByteArray() + byteArrayOf(0x01) + "cd".toByteArray(),       // ISO control
            ByteArray(ModelConfigLimits.MAX_SECRET_CHARS + 1) { 'x'.code.toByte() } // overlong
        ).forEach { bytes ->
            expectConfigError(ModelConfigCodes.VAULT_READ_FAILED) {
                CredentialCrypto.open(k, a, encryptRaw(k, a, bytes))
            }
        }
    }

    @Test
    fun sealDelegatesToSharedSecretShapeValidator() {
        val k = key()
        val a = aad()
        listOf(
            "",
            "   ",
            "ab" + 1.toChar() + "cd",
            "x".repeat(ModelConfigLimits.MAX_SECRET_CHARS + 1),
            0xD800.toChar().toString(),     // unpaired high surrogate
            "a" + 0xDC00.toChar()          // unpaired low surrogate
        ).forEach { bad ->
            expectConfigError(ModelConfigCodes.KEY_INVALID) {
                CredentialCrypto.seal(k, a, bad)
            }
        }
        CredentialCrypto.requireSecretShape(SYNTHETIC_SECRET) // no throw
        expectConfigError(ModelConfigCodes.KEY_INVALID) {
            CredentialCrypto.requireSecretShape("  ")
        }
    }

    // ---------- public API: no peek, no stored secret ----------

    @Test
    fun credentialVaultInterfaceHasNoPeekAndNoSecretGetter() {
        val methods = CredentialVault::class.java.declaredMethods
        val names = methods.map { it.name }.sorted()
        assertTrue(names.toString(), names.containsAll(
            listOf("getAlias", "getPurpose", "getVersion", "hasCredential", "seal", "withSecret")
        ))
        assertFalse(names.any {
            it.contains("peek", true) || it.contains("plain", true) || it.contains("password", true)
        })
        // Only the two public METADATA getters may return a String; nothing returns a secret.
        assertFalse(methods.any {
            it.parameterCount == 0 && it.returnType == String::class.java &&
                it.name !in setOf("getPurpose", "getAlias")
        })
    }

    @Test
    fun modelConfigStoreInterfaceHasNoPlaintextApi() {
        assertEquals(
            listOf("clearCredentialMetadata", "commitAtomic", "readCredentialRecord", "readProfile"),
            ModelConfigStore::class.java.declaredMethods.map { it.name }.sorted()
        )
        assertFalse(ModelConfigStore::class.java.declaredMethods.any {
            it.returnType == String::class.java
        })
    }

    @Test
    fun androidKeystoreVaultOverridesPublicBindingProperties() {
        val vault = AndroidKeystoreVault(BoundaryInMemoryStore())
        assertEquals(ModelConfigBinding.DEFAULT_ALIAS, vault.alias)
        assertEquals(ModelConfigBinding.DEFAULT_PURPOSE, vault.purpose)
        assertEquals(ModelConfigBinding.CREDENTIAL_VERSION, vault.version)
        // companion aliases must be the frozen contract, never a second literal
        assertEquals(ModelConfigBinding.DEFAULT_ALIAS, AndroidKeystoreVault.DEFAULT_ALIAS)
        assertEquals(ModelConfigBinding.DEFAULT_PURPOSE, AndroidKeystoreVault.DEFAULT_PURPOSE)
        assertEquals(ModelConfigBinding.CREDENTIAL_VERSION, AndroidKeystoreVault.CREDENTIAL_VERSION)
        assertEquals(ModelConfigBinding.STORAGE_LABEL, AndroidKeystoreVault.STORAGE_LABEL)
        assertEquals("app-private-sharedpreferences", AndroidKeystoreVault.STORAGE_LABEL)
        assertEquals(3, ModelConfigBinding.CREDENTIAL_VERSION)
        val custom = AndroidKeystoreVault(BoundaryInMemoryStore(), alias = "a", purpose = "p", version = 7)
        assertEquals("a", custom.alias)
        assertEquals("p", custom.purpose)
        assertEquals(7, custom.version)
        assertTrue(CredentialVault::class.java.methods.any { it.name == "getAlias" })
    }

    @Test
    fun everyPersistedBindingMismatchFailsBeforeKeystore() {
        val id = identity()
        listOf(
            record().copy(purpose = "other"),
            record().copy(alias = "other"),
            record().copy(version = ModelConfigBinding.CREDENTIAL_VERSION - 1),
            record().copy(storage = "other-storage"),
            record().copy(provider = ProviderKind.OpenAi),
            record().copy(host = "other.example.com"),
            record().copy(port = 8443),
            record().copy(path = "/other/chat/completions")
        ).forEach { rec ->
            val vault = AndroidKeystoreVault(BoundaryInMemoryStore(rec))
            expectConfigError(ModelConfigCodes.CREDENTIAL_BINDING_MISMATCH) {
                vault.withSecret(id) { it }
            }
        }
    }

    @Test
    fun absentCredentialThrowsInsteadOfFallingBackToPlaintext() {
        val vault = AndroidKeystoreVault(BoundaryInMemoryStore(null))
        assertFalse(vault.hasCredential())
        expectConfigError(ModelConfigCodes.VAULT_UNAVAILABLE) {
            vault.withSecret(identity()) { it }
        }
    }

    @Test
    fun clearOnlyDropsMetadataThroughTheStore() {
        val store = BoundaryInMemoryStore(record())
        val vault = AndroidKeystoreVault(store)
        assertTrue(vault.hasCredential())
        vault.clear()
        assertTrue(store.cleared)
        assertFalse(vault.hasCredential())
    }

    @Test
    fun withSecretInjectsTemporarilyAndDoesNotPersist() {
        val vault = BoundaryArmedVault(SYNTHETIC_SECRET)
        var seen = ""
        val result = vault.withSecret(identity()) { seen = it; "ok" }
        assertEquals("ok", result)
        assertEquals(SYNTHETIC_SECRET, seen)
        assertEquals(1, vault.calls)
    }

    @Test
    fun keystoreVaultSurfaceHoldsNoPlaintextField() {
        // A cached plaintext would have to be an INSTANCE field. Companion CODE_* constants
        // (e.g. CODE_KEY_INVALID) are error-code metadata and cannot hold a secret.
        val instanceFields = AndroidKeystoreVault::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        val names = instanceFields.map { it.name }
        assertFalse(names.toString(), names.any {
            it.contains("secret", true) || it.contains("plain", true) ||
                it.contains("password", true) || it.contains("key", true)
        })
        // The only String instance fields are the two PUBLIC metadata bindings.
        assertFalse(instanceFields.any {
            it.type == String::class.java && it.name !in setOf("alias", "purpose")
        })
        assertTrue(names.toString(),
            names.containsAll(listOf("store", "alias", "purpose", "version")))
    }

    // ---------- state / toString / exception surfaces ----------

    @Test
    fun profileHasNoSecretFieldOrGetter() {
        val fields = ModelProfile::class.java.declaredFields.map { it.name }
        assertFalse(fields.any {
            it.contains("secret", true) || it.contains("key", true) ||
                it.contains("password", true) || it.contains("plaintext", true)
        })
        val methods = ModelProfile::class.java.declaredMethods.map { it.name }
        assertFalse(methods.any {
            it.contains("secret", true) || it.contains("key", true) || it.contains("password", true)
        })
        assertFalse(ModelProfile::class.java.declaredFields.any {
            it.type == String::class.java && it.name !in setOf("endpoint", "model")
        })
    }

    @Test
    fun profileToStringHasNoSecret() {
        val s = ModelProfile(
            "https://api.openai.com/v1/chat/completions", "gpt", CredentialPresence.Present
        ).toString()
        assertFalse(s.contains(SYNTHETIC_SECRET))
        assertFalse(s.contains("secret", true))
        assertFalse(s.contains("key", true))
    }

    @Test
    fun uiStateHasNoSecretValueFieldOrGetter() {
        val fields = ModelConfigUiState::class.java.declaredFields
        // secretResetEpoch is a deliberately NON-SECRET Long reset signal: it is the only
        // "secret"-named member allowed, and it must never be a String or a getter value.
        val suspicious = fields.filter {
            (it.name.contains("secret", true) || it.name.contains("key", true) ||
                it.name.contains("password", true)) && it.name != "secretResetEpoch"
        }
        assertTrue(suspicious.map { it.name }.toString(), suspicious.isEmpty())
        val epoch = fields.single { it.name == "secretResetEpoch" }
        assertEquals(Long::class.javaPrimitiveType, epoch.type)
        val getters = ModelConfigUiState::class.java.declaredMethods.map { it.name }
        val badGetters = getters.filter {
            (it.contains("secret", true) || it.contains("key", true) ||
                it.contains("password", true)) && it != "getSecretResetEpoch"
        }
        assertTrue(badGetters.toString(), badGetters.isEmpty())
        assertFalse(getters.any {
            it.contains("secret", true) &&
                ModelConfigUiState::class.java.declaredMethods
                    .first { m -> m.name == it }.returnType == String::class.java
        })
    }

    @Test
    fun uiStateToStringOmitsSecretAndResetEpoch() {
        val s = ModelConfigUiState(
            loading = false, endpoint = "https://api.openai.com/v1/chat/completions",
            model = "gpt", credentialPresence = CredentialPresence.Present,
            draftCredentialPending = true, notice = "已保存", error = null, saved = true,
            secretResetEpoch = 42L
        ).toString()
        assertFalse(s.contains(SYNTHETIC_SECRET))
        assertFalse(s.contains("secret", true))
        assertFalse(s.contains("ResetEpoch"))
        assertFalse(s.contains("42"))
    }

    @Test
    fun uiStateEqualsCannotHideASecret() {
        val a = ModelConfigUiState(endpoint = "e", model = "m", draftCredentialPending = true)
        val b = ModelConfigUiState(endpoint = "e", model = "m", draftCredentialPending = true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, a.copy(secretResetEpoch = 1L))
    }

    @Test
    fun credentialRecordStoresCiphertextOnly() {
        val rec = record()
        val s = rec.toString()
        assertFalse(s.contains(SYNTHETIC_SECRET))
        assertFalse(s.contains("secret", true))
        assertFalse(s.contains("key", true))
        val fields = CredentialRecord::class.java.declaredFields.map { it.name }
        assertFalse(fields.any {
            it.contains("secret", true) || it.contains("password", true) || it.contains("plain", true)
        })
        assertEquals(ModelConfigBinding.DEFAULT_ALIAS, rec.alias)
        assertEquals(ModelConfigBinding.STORAGE_LABEL, rec.storage)
        assertEquals(ModelConfigBinding.CREDENTIAL_VERSION, rec.version)
    }

    @Test
    fun sealedCredentialNeverCarriesPlaintext() {
        val s = SealedCredential("aaaaaaaaaaaaaaaa", "AAAA").toString()
        assertFalse(s.contains(SYNTHETIC_SECRET))
        assertFalse(s.contains("plain", true))
        assertTrue(s.contains("hidden"))
    }

    @Test
    fun exceptionMessagesCarryNoSecret() {
        val e = ModelConfigException(ModelConfigCodes.PROVIDER_CHANGED, "提供方已变化")
        assertFalse(e.toString().contains(SYNTHETIC_SECRET))
        assertTrue(e.toString().contains("PROVIDER_CHANGED"))
        assertFalse(e.toString().contains("提供方"))
        val withCause = ModelConfigException(
            ModelConfigCodes.VAULT_UNAVAILABLE, "不可用", RuntimeException("inner")
        )
        assertFalse(withCause.toString().contains("inner"))
    }

    @Test
    fun emittedExceptionsNeverContainTheSecret() {
        val k = key()
        val a = aad()
        val sealed = CredentialCrypto.seal(k, a, SYNTHETIC_SECRET)
        listOf(
            runCatching { CredentialCrypto.seal(k, a, "  ") }.exceptionOrNull(),
            runCatching { CredentialCrypto.open(k, aad(host = "other"), sealed) }.exceptionOrNull(),
            runCatching { CredentialCrypto.open(k, a, sealed.copy(iv = "bad*")) }.exceptionOrNull()
        ).forEach { e ->
            assertTrue(e is ModelConfigException)
            assertFalse(e.toString().contains(SYNTHETIC_SECRET))
            assertFalse(e!!.message.orEmpty().contains(SYNTHETIC_SECRET))
        }
    }

    // ---------- private test doubles: NO dependency on parallel agents' fakes ----------

    private class BoundaryInMemoryStore(
        private var record: CredentialRecord? = null
    ) : ModelConfigStore {
        var cleared = false
        override fun readProfile(): ModelProfile? = null
        override fun readCredentialRecord(): CredentialRecord? = record
        override fun commitAtomic(profile: ModelProfile, credential: CredentialRecord?) {
            record = credential
        }
        override fun clearCredentialMetadata() {
            record = null
            cleared = true
        }
    }

    private class BoundaryArmedVault(private val armed: String) : CredentialVault {
        override val purpose = "test-purpose"
        override val alias = "test-alias"
        override val version = ModelConfigBinding.CREDENTIAL_VERSION
        var calls = 0
        override fun hasCredential(): Boolean = true
        override fun <T> withSecret(identity: EndpointIdentity, block: (String) -> T): T {
            calls++
            return block(armed)
        }
        override fun seal(identity: EndpointIdentity, secret: String): SealedCredential =
            throw UnsupportedOperationException("synthetic vault does not seal")
    }
}
