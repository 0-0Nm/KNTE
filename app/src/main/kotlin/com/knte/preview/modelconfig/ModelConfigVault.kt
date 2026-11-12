package com.knte.preview.modelconfig

import android.content.SharedPreferences
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM authenticated encryption for one credential, bound to the Android Keystore key
 * AND to the complete v3 metadata: purpose, alias, version, provider, storage and the
 * endpoint identity (host + port + path). A sealed blob therefore cannot be replayed
 * against another provider, alias, purpose, format version, host, port or path.
 *
 * Strictness rules (no "best effort" anywhere):
 *  - all secret validation delegates to the single shared pure-Kotlin [ModelSecretShape];
 *  - the plaintext must be bounded, control-free and strict UTF-8 (an unpaired surrogate
 *    is rejected, not replaced with '?');
 *  - the ciphertext length is bounded below (tag + at least one byte) and above;
 *  - decrypted bytes are decoded as strict UTF-8 and then re-checked against the shared
 *    shape validator, so an authenticated-but-invalid plaintext still fails closed;
 *  - no message ever interpolates a cause, a URL or a plaintext.
 *
 * Base64 is java.util.Base64 (minSdk 26), NOT the android.util.Base64 stub: the JCA
 * crypto path is therefore runnable as a plain JVM unit test with a synthetic in-memory
 * key, without an Android framework returning default nulls. The real AndroidKeyStore is
 * opened ONLY by [generateKey]/[loadKey], never by a synthetic test and never to read a
 * user credential into a field, log or public API.
 *
 * Reference: developer.android.com "Android Keystore system" / "Encrypt data with the
 * Android Keystore system". Only platform APIs are used; no security-crypto dependency. */
internal object CredentialCrypto {
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_BYTES = 12

    /** Never logs or echoes the plaintext; plaintext buffers are zeroed on the way out. */
    fun seal(key: SecretKey, aad: ByteArray, secret: String): SealedCredential {
        requireSecretShape(secret)
        val plain = encodeStrictUtf8(secret)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            val iv = cipher.iv
            if (iv.size != IV_BYTES) {
                throw ModelConfigException(AndroidKeystoreVault.CODE_WRITE, "GCM IV 长度异常")
            }
            val sealed = cipher.doFinal(plain)
            return SealedCredential(
                Base64.getEncoder().encodeToString(iv),
                Base64.getEncoder().encodeToString(sealed)
            )
        } catch (e: ModelConfigException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw ModelConfigException(AndroidKeystoreVault.CODE_WRITE, "密钥封装失败", e)
        } finally {
            Arrays.fill(plain, 0.toByte())
        }
    }

    /** Throws on any authentication, length, shape or encoding failure; the caller must
     * never fall back to plaintext. No exception text is ever interpolated into a message. */
    fun open(key: SecretKey, aad: ByteArray, sealed: SealedCredential): String {
        val iv = try {
            Base64.getDecoder().decode(sealed.iv)
        } catch (e: IllegalArgumentException) {
            throw ModelConfigException(AndroidKeystoreVault.CODE_READ, "密文 IV 无效", e)
        }
        val body = try {
            Base64.getDecoder().decode(sealed.ciphertext)
        } catch (e: IllegalArgumentException) {
            throw ModelConfigException(AndroidKeystoreVault.CODE_READ, "密文无效", e)
        }
        if (iv.size != IV_BYTES) {
            throw ModelConfigException(AndroidKeystoreVault.CODE_READ, "密文 IV 长度异常")
        }
        // Lower AND upper bound: a blob shorter than tag + 1 plaintext byte can never be
        // authentic output of seal(), and an oversized blob is refused before any crypto.
        if (body.size < AndroidKeystoreVault.MIN_SEALED_BYTES ||
            body.size > AndroidKeystoreVault.MAX_SEALED_BYTES
        ) {
            throw ModelConfigException(AndroidKeystoreVault.CODE_READ, "密文长度异常")
        }
        val clear = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(AndroidKeystoreVault.TAG_BITS, iv)
            )
            cipher.updateAAD(aad)
            cipher.doFinal(body)
        } catch (e: AEADBadTagException) {
            throw ModelConfigException(
                AndroidKeystoreVault.CODE_UNAVAILABLE, "密钥封装校验失败", e
            )
        } catch (e: GeneralSecurityException) {
            throw ModelConfigException(AndroidKeystoreVault.CODE_UNAVAILABLE, "解密失败", e)
        }
        try {
            val secret = decodeStrictUtf8(clear)
            // Reuse the ONE shared shape validator AFTER decryption: GCM authenticates the
            // bytes but says nothing about their shape. An authenticated blank / control /
            // overlong / surrogate-bearing plaintext must still fail closed here.
            ModelSecretShape.requireValid(secret)
            return secret
        } catch (e: ModelConfigException) {
            throw ModelConfigException(
                AndroidKeystoreVault.CODE_READ, "解密内容不是有效凭据文本", e
            )
        } finally {
            Arrays.fill(clear, 0.toByte())
        }
    }

    fun randomIv(): ByteArray = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }

    /** v3 AAD binds the fixed app purpose + alias + format version, the PROVIDER identity
     * and the endpoint identity (host + port + path).
     *
     * [provider] defaults to [ProviderKind.Generic] ONLY so older/synthetic six-argument
     * callers stay source-compatible; production code MUST always pass the actual provider
     * (both [AndroidKeystoreVault.aadFor] overloads do). A v2 prefix is never produced, and
     * no record carrying an older version is ever read or migrated. */
    fun aad(
        purpose: String,
        alias: String,
        version: Int,
        host: String,
        port: Int,
        path: String,
        provider: ProviderKind = ProviderKind.Generic
    ): ByteArray =
        ("knte-model-config-aad-v3|$purpose|$alias|$version|${provider.name}|$host|$port|$path")
            .toByteArray(Charsets.UTF_8)

    /** All secret validation delegates to the single shared pure-Kotlin shape check.
     * Kept as a named function because the repository validates a freshly typed draft key
     * through this exact entry point. */
    fun requireSecretShape(secret: String): Unit = ModelSecretShape.requireValid(secret)

    /** Kotlin/Java strings are UTF-16; an unpaired surrogate would be silently replaced by
     * '?' on encode. Reject instead of corrupting the secret. */
    fun encodeStrictUtf8(text: String): ByteArray {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) {
                    throw ModelConfigException(
                        AndroidKeystoreVault.CODE_KEY_INVALID, "密钥不是合法 UTF-8 文本"
                    )
                }
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) {
                throw ModelConfigException(
                    AndroidKeystoreVault.CODE_KEY_INVALID, "密钥不是合法 UTF-8 文本"
                )
            }
            i++
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    /** Strict UTF-8 decode: overlong forms, surrogates and truncated sequences fail closed
     * instead of being replaced by U+FFFD. */
    fun decodeStrictUtf8(bytes: ByteArray): String {
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val size = when (b0) {
                in 0x00..0x7F -> 1
                in 0xC2..0xDF -> 2
                in 0xE0..0xEF -> 3
                in 0xF0..0xF4 -> 4
                else -> failUtf8()
            }
            if (i + size > bytes.size) failUtf8()
            var j = 1
            while (j < size) {
                val b = bytes[i + j].toInt() and 0xFF
                if (b !in 0x80..0xBF) failUtf8()
                if (j == 1) {
                    val leadOk = when (b0) {
                        0xE0 -> b in 0xA0..0xBF // no overlong
                        0xED -> b in 0x80..0x9F // no surrogate
                        0xF0 -> b in 0x90..0xBF // no overlong
                        0xF4 -> b in 0x80..0x8F // > U+10FFFF
                        else -> true
                    }
                    if (!leadOk) failUtf8()
                }
                j++
            }
            i += size
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun failUtf8(): Nothing = throw ModelConfigException(
        AndroidKeystoreVault.CODE_READ, "解密内容不是有效 UTF-8"
    )

    /** Creates a non-exportable AES-256 key inside the Android Keystore. */
    fun generateKey(alias: String): SecretKey {
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun loadKey(alias: String): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val entry = try {
            store.getEntry(alias, null)
        } catch (e: Exception) {
            null
        }
        return (entry as? KeyStore.SecretKeyEntry)?.secretKey
    }
}

/** SharedPreferences-backed store. Only app-private ciphertext + metadata are persisted;
 * the sealed blob and its alias/purpose/storage/provider/version binding are stored as ONE
 * atomic transaction. A partial / legacy / inconsistent record is never returned as null
 * and never silently overwritten: it throws, and the repository layer fails closed. */
class AndroidModelConfigStore(private val prefs: SharedPreferences) : ModelConfigStore {
    override fun readProfile(): ModelProfile? {
        val endpoint = prefs.getString(KEY_ENDPOINT, null) ?: return null
        val model = prefs.getString(KEY_MODEL, null) ?: return null
        val presence = when (prefs.getString(KEY_PRESENCE, null)) {
            CredentialPresence.Present.name -> CredentialPresence.Present
            else -> CredentialPresence.NotConfigured
        }
        return ModelProfile(endpoint, model, presence)
    }

    override fun readCredentialRecord(): CredentialRecord? {
        // A record is either COMPLETELY absent or COMPLETELY present. Any partial set of
        // keys is an interrupted/damaged write and must fail closed, never "no credential".
        val presentKeys = CREDENTIAL_KEYS.count { prefs.contains(it) }
        if (presentKeys == 0) return null
        if (presentKeys != CREDENTIAL_KEYS.size) corruptRecord()

        val endpoint = prefs.getString(KEY_CRED_ENDPOINT, null) ?: corruptRecord()
        val iv = prefs.getString(KEY_CRED_IV, null) ?: corruptRecord()
        val body = prefs.getString(KEY_CRED_BODY, null) ?: corruptRecord()
        val host = prefs.getString(KEY_CRED_HOST, null) ?: corruptRecord()
        val path = prefs.getString(KEY_CRED_PATH, null) ?: corruptRecord()
        val alias = prefs.getString(KEY_CRED_ALIAS, null) ?: corruptRecord()
        val purpose = prefs.getString(KEY_CRED_PURPOSE, null) ?: corruptRecord()
        val storage = prefs.getString(KEY_CRED_STORAGE, null) ?: corruptRecord()
        val providerName = prefs.getString(KEY_CRED_PROVIDER, null) ?: corruptRecord()
        val port = prefs.getInt(KEY_CRED_PORT, -1)
        val version = prefs.getInt(KEY_CRED_VERSION, 0)

        // v3 only. Unreleased v1/v2 records are NEVER auto-migrated, decrypted or trusted.
        if (version != ModelConfigBinding.CREDENTIAL_VERSION) corruptRecord()
        if (alias != ModelConfigBinding.DEFAULT_ALIAS) corruptRecord()
        if (purpose != ModelConfigBinding.DEFAULT_PURPOSE) corruptRecord()
        if (storage != ModelConfigBinding.STORAGE_LABEL) corruptRecord()
        if (port !in 1..65535) corruptRecord()
        if (endpoint.isBlank() || host.isBlank() || path.isBlank() ||
            iv.isBlank() || body.isBlank()
        ) corruptRecord()
        val provider = try {
            ProviderKind.valueOf(providerName)
        } catch (e: IllegalArgumentException) {
            corruptRecord()
        }
        return CredentialRecord(
            endpoint = endpoint,
            host = host,
            port = port,
            path = path,
            provider = provider,
            purpose = purpose,
            version = version,
            sealed = SealedCredential(iv, body),
            storage = storage,
            alias = alias
        )
    }

    override fun commitAtomic(profile: ModelProfile, credential: CredentialRecord?) {
        val present = profile.credentialPresence == CredentialPresence.Present
        // Metadata consistency: a profile claiming a credential must carry exactly one
        // complete record; a profile claiming none must not carry one.
        if (present != (credential != null)) {
            throw ModelConfigException(ModelConfigCodes.COMMIT_FAILED, "凭据存在标记与元信息不一致")
        }
        if (credential != null) requireCompleteRecord(credential)
        // Never silently erase an existing (possibly partial / legacy / damaged) record with
        // a profile-only commit. Clearing is the explicit clearCredentialMetadata() path.
        if (credential == null && CREDENTIAL_KEYS.any { prefs.contains(it) }) {
            throw ModelConfigException(
                ModelConfigCodes.COMMIT_FAILED, "存在凭据元信息但提交未提供凭据；未静默覆盖"
            )
        }

        val editor = prefs.edit()
        editor.putString(KEY_ENDPOINT, profile.endpoint)
        editor.putString(KEY_MODEL, profile.model)
        editor.putString(KEY_PRESENCE, profile.credentialPresence.name)
        if (credential == null) {
            clearCredentialKeys(editor)
        } else {
            editor.putString(KEY_CRED_ENDPOINT, credential.endpoint)
            editor.putString(KEY_CRED_HOST, credential.host)
            editor.putInt(KEY_CRED_PORT, credential.port)
            editor.putString(KEY_CRED_PATH, credential.path)
            editor.putString(KEY_CRED_PROVIDER, credential.provider.name)
            editor.putString(KEY_CRED_PURPOSE, credential.purpose)
            editor.putInt(KEY_CRED_VERSION, credential.version)
            editor.putString(KEY_CRED_IV, credential.sealed.iv)
            editor.putString(KEY_CRED_BODY, credential.sealed.ciphertext)
            editor.putString(KEY_CRED_ALIAS, credential.alias)
            editor.putString(KEY_CRED_STORAGE, credential.storage)
        }
        // commit() returns false when the transaction was NOT persisted. Report failure
        // only; never claim "saved"/"durable" on a false return.
        if (!editor.commit()) {
            throw ModelConfigException(ModelConfigCodes.COMMIT_FAILED, "配置写入未完成")
        }
    }

    override fun clearCredentialMetadata() {
        val editor = prefs.edit()
        clearCredentialKeys(editor)
        // Dropping credential metadata MUST drop the public presence claim in the SAME
        // atomic transaction, so profile and record can never disagree afterwards.
        editor.putString(KEY_PRESENCE, CredentialPresence.NotConfigured.name)
        if (!editor.commit()) {
            throw ModelConfigException(ModelConfigCodes.COMMIT_FAILED, "凭据元信息清除未完成")
        }
    }

    private fun clearCredentialKeys(editor: SharedPreferences.Editor) {
        CREDENTIAL_KEYS.forEach { editor.remove(it) }
    }

    /** A record is written ONLY when its complete v3 metadata is self-consistent and is
     * bound to the frozen public contract; otherwise a damaged record would silently
     * overwrite the previously stored one. */
    private fun requireCompleteRecord(credential: CredentialRecord) {
        if (credential.version != ModelConfigBinding.CREDENTIAL_VERSION ||
            credential.alias != ModelConfigBinding.DEFAULT_ALIAS ||
            credential.purpose != ModelConfigBinding.DEFAULT_PURPOSE ||
            credential.storage != ModelConfigBinding.STORAGE_LABEL
        ) {
            throw ModelConfigException(
                ModelConfigCodes.COMMIT_FAILED, "凭据元信息与当前绑定不一致；未写入"
            )
        }
        if (credential.endpoint.isBlank() || credential.host.isBlank() ||
            credential.path.isBlank() || credential.port !in 1..65535 ||
            credential.sealed.iv.isBlank() || credential.sealed.ciphertext.isBlank()
        ) {
            throw ModelConfigException(ModelConfigCodes.COMMIT_FAILED, "凭据元信息不完整；未写入")
        }
    }

    private fun corruptRecord(): Nothing = throw ModelConfigException(
        ModelConfigCodes.VAULT_READ_FAILED,
        "凭据元信息不完整或已损坏；需要为当前接口重新输入密钥"
    )

    private companion object {
        const val KEY_ENDPOINT = "model.endpoint"
        const val KEY_MODEL = "model.model"
        const val KEY_PRESENCE = "model.credentialPresence"
        const val KEY_CRED_ENDPOINT = "model.cred.endpoint"
        const val KEY_CRED_HOST = "model.cred.host"
        const val KEY_CRED_PORT = "model.cred.port"
        const val KEY_CRED_PATH = "model.cred.path"
        const val KEY_CRED_PROVIDER = "model.cred.provider"
        const val KEY_CRED_PURPOSE = "model.cred.purpose"
        const val KEY_CRED_VERSION = "model.cred.version"
        const val KEY_CRED_IV = "model.cred.iv"
        const val KEY_CRED_BODY = "model.cred.body"
        const val KEY_CRED_ALIAS = "model.cred.alias"
        const val KEY_CRED_STORAGE = "model.cred.storage"

        val CREDENTIAL_KEYS = listOf(
            KEY_CRED_ENDPOINT, KEY_CRED_HOST, KEY_CRED_PORT, KEY_CRED_PATH,
            KEY_CRED_PROVIDER, KEY_CRED_PURPOSE, KEY_CRED_VERSION, KEY_CRED_IV,
            KEY_CRED_BODY, KEY_CRED_ALIAS, KEY_CRED_STORAGE
        )
    }
}

/** Keystore-backed vault. Non-exportable key; AES/GCM random IV; v3 AAD binds purpose,
 * alias, version, provider and the endpoint identity (host + port + path). No plaintext is
 * ever persisted, logged, cached in a field or exposed through any public method: the only
 * decryption path is the private one inside [withSecret]. */
class AndroidKeystoreVault(
    private val store: ModelConfigStore,
    override val alias: String = ModelConfigBinding.DEFAULT_ALIAS,
    override val purpose: String = ModelConfigBinding.DEFAULT_PURPOSE,
    override val version: Int = ModelConfigBinding.CREDENTIAL_VERSION
) : CredentialVault {
    override fun hasCredential(): Boolean = store.readCredentialRecord() != null

    /** Private decryption: the plaintext exists only inside [block]. The stored binding
     * must match the caller's current [identity] completely; any mismatch fails closed
     * BEFORE the Keystore is touched, instead of silently serving another endpoint's key. */
    override fun <T> withSecret(identity: EndpointIdentity, block: (String) -> T): T {
        val record = requireRecord()
        requireBinding(record, identity)
        val key = keyFor(record) ?: throw ModelConfigException(
            ModelConfigCodes.VAULT_UNAVAILABLE, "Keystore 密钥不可用"
        )
        val secret = CredentialCrypto.open(key, aadFor(record), record.sealed)
        // A JVM String cannot be reliably zeroed; documented, not claimed. The value is
        // never assigned to a field, never logged and never returned from this method.
        return block(secret)
    }

    override fun seal(identity: EndpointIdentity, secret: String): SealedCredential =
        CredentialCrypto.seal(keyForCreate(), aadFor(identity), secret)

    /** Removes the sealed credential metadata. Destructive; not wired into this batch's UI. */
    fun clear() {
        store.clearCredentialMetadata()
    }

    private fun requireRecord(): CredentialRecord =
        store.readCredentialRecord() ?: throw ModelConfigException(
            ModelConfigCodes.VAULT_UNAVAILABLE, "未配置凭据"
        )

    /** Complete v3 binding: purpose + alias + version + storage + provider + host/port/path.
     * A tampered persisted record can never be decrypted with a different AAD. */
    private fun requireBinding(record: CredentialRecord, identity: EndpointIdentity) {
        if (record.purpose != purpose || record.alias != alias ||
            record.version != version ||
            record.storage != ModelConfigBinding.STORAGE_LABEL ||
            record.provider != identity.kind ||
            record.host != identity.host || record.port != identity.port ||
            record.path != identity.path
        ) {
            throw ModelConfigException(
                ModelConfigCodes.CREDENTIAL_BINDING_MISMATCH,
                "凭据绑定与当前接口不一致；请为当前接口重新输入密钥"
            )
        }
    }

    private fun aadFor(record: CredentialRecord): ByteArray =
        CredentialCrypto.aad(
            purpose, alias, record.version, record.host, record.port, record.path,
            record.provider
        )

    private fun aadFor(identity: EndpointIdentity): ByteArray =
        CredentialCrypto.aad(
            purpose, alias, version, identity.host, identity.port, identity.path,
            identity.kind
        )

    private fun keyFor(record: CredentialRecord): SecretKey? {
        if (record.purpose != purpose || record.alias != alias ||
            record.version != version ||
            record.storage != ModelConfigBinding.STORAGE_LABEL
        ) return null
        return try {
            CredentialCrypto.loadKey(alias)
        } catch (e: Exception) {
            null
        }
    }

    private fun keyForCreate(): SecretKey = try {
        CredentialCrypto.loadKey(alias) ?: CredentialCrypto.generateKey(alias)
    } catch (e: Exception) {
        throw ModelConfigException(ModelConfigCodes.VAULT_UNAVAILABLE, "Keystore 不可用", e)
    }

    companion object {
        /** Every metadata constant is an ALIAS of the single frozen public contract. No
         * second storage label, purpose, alias or version is introduced anywhere. */
        const val DEFAULT_ALIAS = ModelConfigBinding.DEFAULT_ALIAS
        const val DEFAULT_PURPOSE = ModelConfigBinding.DEFAULT_PURPOSE
        const val CREDENTIAL_VERSION = ModelConfigBinding.CREDENTIAL_VERSION
        const val STORAGE_LABEL = ModelConfigBinding.STORAGE_LABEL

        // --- Public crypto contract (asserted by the unit tests) ---
        const val CODE_UNAVAILABLE = ModelConfigCodes.VAULT_UNAVAILABLE
        const val CODE_WRITE = ModelConfigCodes.VAULT_WRITE_FAILED
        const val CODE_READ = ModelConfigCodes.VAULT_READ_FAILED
        const val CODE_KEY_INVALID = ModelConfigCodes.KEY_INVALID

        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8

        /** A GCM blob shorter than tag + 1 plaintext byte cannot be authentic seal() output. */
        const val MIN_SEALED_BYTES = TAG_BYTES + 1

        /** Upper bound checked before any crypto work is done on attacker-sized input. */
        const val MAX_SEALED_BYTES = 16 * 1024
    }
}
