package com.knte.preview.modelconfig

/** Stable machine codes for the model-configuration slice. Never a "silent success". */
object ModelConfigCodes {
    const val ENDPOINT_INVALID = "ENDPOINT_INVALID"
    const val ENDPOINT_NOT_HTTPS = "ENDPOINT_NOT_HTTPS"
    const val ENDPOINT_HAS_CREDENTIALS = "ENDPOINT_HAS_CREDENTIALS"
    const val ENDPOINT_HAS_QUERY = "ENDPOINT_HAS_QUERY"
    const val ENDPOINT_HAS_FRAGMENT = "ENDPOINT_HAS_FRAGMENT"
    const val ENDPOINT_PATH_REQUIRED = "ENDPOINT_PATH_REQUIRED"
    const val ENDPOINT_PATH_TOO_LONG = "ENDPOINT_PATH_TOO_LONG"
    const val ENDPOINT_PATH_INVALID = "ENDPOINT_PATH_INVALID"
    const val ENDPOINT_NOT_CHAT_COMPLETIONS = "ENDPOINT_NOT_CHAT_COMPLETIONS"
    const val ENDPOINT_HOST_NOT_PUBLIC = "ENDPOINT_HOST_NOT_PUBLIC"
    const val MODEL_INVALID = "MODEL_INVALID"
    const val PROVIDER_CHANGED = "PROVIDER_CHANGED"
    const val PROVIDER_UNSUPPORTED = "PROVIDER_UNSUPPORTED"
    const val KEY_REPLACEMENT_REQUIRED = "KEY_REPLACEMENT_REQUIRED"
    const val KEY_INVALID = "KEY_INVALID"
    const val KEY_CLEAR_UNSUPPORTED = "KEY_CLEAR_UNSUPPORTED"
    const val CREDENTIAL_BINDING_MISMATCH = "CREDENTIAL_BINDING_MISMATCH"
    const val VAULT_UNAVAILABLE = "VAULT_UNAVAILABLE"
    const val VAULT_WRITE_FAILED = "VAULT_WRITE_FAILED"
    const val VAULT_READ_FAILED = "VAULT_READ_FAILED"
    const val COMMIT_FAILED = "COMMIT_FAILED"
    /** Reserved for a parent-level busy gate; this slice's VM uses its own single gate. */
    const val BUSY = "BUSY"
    const val READ_FAILED = "READ_FAILED"
}

/** Domain-only failure. The message is a fixed, code-first string that never carries a
 * secret, a URL password or a cause text; [toString] exposes the code only, so a stack
 * trace printed by a host can never turn into a credential echo. */
class ModelConfigException(val code: String, message: String, cause: Throwable? = null) :
    IllegalStateException("[$code] $message", cause) {
    override fun toString(): String = "ModelConfigException(code=$code)"
}

/** Endpoint validation result. [Invalid.echo] is ALWAYS a sanitized address: it is rebuilt
 * from the parsed host/port/path and therefore can never contain userinfo, a query or a
 * fragment. When the raw input cannot be parsed safely the echo stays null. */
sealed class EndpointCheck {
    data class Valid(
        val endpoint: String,
        val host: String,
        val port: Int,
        val path: String
    ) : EndpointCheck()

    data class Invalid(val code: String, val message: String, val echo: String? = null) :
        EndpointCheck()
}

/** Credential presence is the ONLY credential fact exposed to UI / state / logs. */
enum class CredentialPresence { NotConfigured, Present }

/** Saved profile: endpoint + model + credentialPresence. Deliberately NO secret field,
 * NO secret getter and NO secret in toString(); equals/hashCode stay secret-free.
 * [endpoint] is public and displayable, but the policy guarantees it can never contain a
 * query or userinfo, so an already-stored broken configuration is never presented as a
 * trusted address (see AndroidModelConfigRepository.load). */
data class ModelProfile(
    val endpoint: String,
    val model: String,
    val credentialPresence: CredentialPresence
) {
    override fun toString(): String =
        "ModelProfile(endpoint=$endpoint, model=$model, credentialPresence=$credentialPresence)"
}

/** User-visible state of the configuration screen. Secret-free by construction:
 * it carries only the endpoint, the model id and credential PRESENCE, never a key,
 * never a draft secret and nothing that could be replayed to read a key back. */
data class ModelConfigUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val endpoint: String = "",
    val model: String = "",
    val credentialPresence: CredentialPresence = CredentialPresence.NotConfigured,
    /** True while a newly typed key has NOT been committed yet. Never a saved-key claim. */
    val draftCredentialPending: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    val saved: Boolean = false,
    /** Non-secret snapshot of the last committed profile, for dirty checks only. */
    val savedEndpoint: String = "",
    val savedModel: String = "",
    /** Non-secret reset signal: UI-local draft clears only after an explicit VM reset. */
    val secretResetEpoch: Long = 0L
) {
    /** Parent gate: dirty means the non-secret fields differ from the saved profile OR a
     * freshly typed credential draft has not been committed yet. The pending draft counts
     * as dirty so leaving the screen can never silently drop a typed key. */
    val hasUnsavedChanges: Boolean
        get() = !loading &&
            (endpoint != savedEndpoint || model != savedModel || draftCredentialPending)

    /** Parent gate: only the non-secret part plus the pending draft decides leaving. */
    val canLeave: Boolean
        get() = !busy && !loading && !hasUnsavedChanges

    override fun toString(): String =
        "ModelConfigUiState(loading=$loading, busy=$busy, endpoint=$endpoint, model=$model, " +
            "credentialPresence=$credentialPresence, draftCredentialPending=$draftCredentialPending, " +
            "notice=$notice, error=$error, saved=$saved)"
}

/** Provider classification used for the "old key must not travel to a new host" rule.
 * Derived from host + port + URL shape only; never from the credential itself.
 * [Unknown] survives only for legacy persisted records: a fresh endpoint that is not a
 * public host shape is rejected by the policy instead of being classified as Unknown. */
enum class ProviderKind { Unknown, Anthropic, OpenAi, Generic }

/** The endpoint facts a sealed credential is bound to. Public data only. */
data class EndpointIdentity(
    val endpoint: String,
    val host: String,
    val port: Int,
    val path: String,
    val kind: ProviderKind
) {
    /** The AAD/binding input: host + port + path. Never contains a credential. */
    fun bindingToken(): String = "$host|$port|$path"

    override fun toString(): String = "EndpointIdentity(kind=$kind, port=$port)"
}

/** Seal result. Never carries the plaintext; both fields are safe to persist. */
data class SealedCredential(val iv: String, val ciphertext: String) {
    override fun toString(): String = "SealedCredential(iv=<hidden>, bytes=${ciphertext.length})"
}

/** Persisted metadata: endpoint identity (host/port/path) + ciphertext location +
 * provider/purpose binding. No field ever holds a secret, a password or a raw key. */
data class CredentialRecord(
    val endpoint: String,
    val host: String,
    val port: Int,
    val path: String,
    val provider: ProviderKind,
    val purpose: String,
    val version: Int,
    val sealed: SealedCredential,
    val storage: String,
    val alias: String = ModelConfigBinding.DEFAULT_ALIAS
) {
    override fun toString(): String =
        "CredentialRecord(provider=$provider, purpose=$purpose, version=$version, " +
            "storage=$storage, sealed=$sealed)"
}

/** Temporary, in-memory credential injection for a parent transport. The block never
 * receives a stored reference and must not leak the value into state, logs or a
 * SavedStateHandle. [purpose]/[alias]/[version] describe the fixed app binding used for AAD.
 *
 * There is deliberately NO method that returns a stored secret: decryption happens inside
 * [withSecret] and the plaintext exists only for the duration of [block]. */
interface CredentialVault {
    val purpose: String
    val alias: String
    val version: Int

    /** Presence only. Never reveals whether the content is still decryptable. */
    fun hasCredential(): Boolean

    /** Injects the plaintext credential for the duration of [block] only, and only when the
     * stored binding matches [identity] (host + port + path). Throws
     * [ModelConfigException] with VAULT_UNAVAILABLE / CREDENTIAL_BINDING_MISMATCH /
     * VAULT_READ_FAILED; there is no plaintext fallback and no null result. */
    fun <T> withSecret(identity: EndpointIdentity, block: (String) -> T): T

    /** Seals a new credential bound to [identity]. Returns ciphertext only. */
    fun seal(identity: EndpointIdentity, secret: String): SealedCredential
}

/** Injectable persistence for the sealed blob + profile metadata. Tests supply a fake;
 * production uses app-private SharedPreferences with ONE atomic transaction. A read that
 * finds a partial record must fail closed (throw), never return null. */
interface ModelConfigStore {
    fun readProfile(): ModelProfile?
    fun readCredentialRecord(): CredentialRecord?

    /** One atomic commit of profile metadata and ciphertext metadata. Returns only when the
     * underlying transaction has actually completed (commit()/apply() flushed), so a
     * false "saved" claim is impossible. Throws on failure. */
    fun commitAtomic(profile: ModelProfile, credential: CredentialRecord?)

    /** Drops the credential metadata (not the Keystore key). Absent record is a no-op. */
    fun clearCredentialMetadata()
}
