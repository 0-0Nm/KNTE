package com.knte.preview.modelconfig
import com.knte.core.chat.ChatProfile

/** Endpoint rules for the model-configuration slice.
 *
 * The address the user saves is the COMPLETE request address of exactly one dialect
 * (OpenAI-compatible non-streaming chat/completions), so the domain must never join,
 * append or re-encode path segments: the path is taken VERBATIM and only its characters
 * and its required suffix are checked.
 *
 * Hard rules, all fail-closed:
 *  - `https` only; plaintext `http` is rejected;
 *  - no userinfo (`user:pass@host`) - and the raw input is NEVER echoed back, because a
 *    password may live inside it;
 *  - no query (`?`) and no fragment (`#`) - the query is the classic place where a
 *    credential or a tracking parameter hides;
 *  - the host must be a public candidate in SHAPE only: a dotted DNS name or a public
 *    IPv4 literal. `localhost`, single-label names, `.local`/`.internal`/`.lan`/`.home`/
 *    `.localdomain`, private/loopback/link-local/CGNAT IPv4 and IPv6 literals are rejected.
 *    No DNS resolution and no reachability probe is ever performed from here;
 *  - the path must be present, bounded, built from the safe path character set and end
 *    with `/chat/completions`.
 *
 * Classification is derived from host + port + URL shape only, NEVER from a credential. */
object ModelEndpointPolicy {
    private val HOST_LABEL = Regex("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?")
    private val IPV4 = Regex(
        "(?:(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\.){3}" +
            "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"
    )

    fun check(raw: String): EndpointCheck {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return EndpointCheck.Invalid(ModelConfigCodes.ENDPOINT_INVALID, "接口地址不能为空")
        }
        if (trimmed.length > ModelConfigLimits.MAX_ENDPOINT_CHARS) {
            return EndpointCheck.Invalid(ModelConfigCodes.ENDPOINT_INVALID, "接口地址过长")
        }
        if (trimmed.any { it.isISOControl() }) {
            // No echo: control characters can forge the visual shape of an address.
            return EndpointCheck.Invalid(ModelConfigCodes.ENDPOINT_INVALID, "接口地址含控制字符")
        }
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) {
            return EndpointCheck.Invalid(ModelConfigCodes.ENDPOINT_INVALID, "接口地址不是有效 URL")
        }
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (scheme != "https") {
            // No echo: the raw text is attacker/user-controlled and may hide a password.
            val code = if (scheme == "http") {
                ModelConfigCodes.ENDPOINT_NOT_HTTPS
            } else {
                ModelConfigCodes.ENDPOINT_INVALID
            }
            val message = if (scheme == "http") {
                "只允许 HTTPS 接口地址"
            } else {
                "接口地址只支持 HTTPS"
            }
            return EndpointCheck.Invalid(code, message)
        }
        val rest = trimmed.substring(schemeEnd + 3)
        if (rest.isEmpty()) {
            return EndpointCheck.Invalid(ModelConfigCodes.ENDPOINT_INVALID, "接口地址缺少主机名")
        }
        val slash = rest.indexOf('/')
        val authority = if (slash < 0) rest else rest.substring(0, slash)
        val tail = if (slash < 0) "" else rest.substring(slash)
        if (authority.isEmpty()) {
            return EndpointCheck.Invalid(ModelConfigCodes.ENDPOINT_INVALID, "接口地址缺少主机名")
        }
        if ('@' in authority) {
            // No echo, ever: the authority may be user:password@host.
            return EndpointCheck.Invalid(
                ModelConfigCodes.ENDPOINT_HAS_CREDENTIALS,
                "接口地址不能包含用户名或密码"
            )
        }
        if ('[' in authority || ']' in authority) {
            return EndpointCheck.Invalid(
                ModelConfigCodes.ENDPOINT_HOST_NOT_PUBLIC, "接口地址不支持 IPv6 字面量"
            )
        }
        if ('?' in authority) {
            return EndpointCheck.Invalid(
                ModelConfigCodes.ENDPOINT_HAS_QUERY, "接口地址不能包含查询参数"
            )
        }
        if ('#' in authority) {
            return EndpointCheck.Invalid(
                ModelConfigCodes.ENDPOINT_HAS_FRAGMENT, "接口地址不能包含片段"
            )
        }
        var host = authority
        var port = DEFAULT_PORT
        val colon = authority.lastIndexOf(':')
        if (colon >= 0) {
            host = authority.substring(0, colon)
            val portText = authority.substring(colon + 1)
            val parsed = portText.toIntOrNull()
            if (parsed == null || parsed !in 1..65535 || portText.length > 5) {
                return EndpointCheck.Invalid(ModelConfigCodes.ENDPOINT_INVALID, "接口端口无效")
            }
            port = parsed
        }
        if (host.isEmpty()) {
            return EndpointCheck.Invalid(ModelConfigCodes.ENDPOINT_INVALID, "接口地址缺少主机名")
        }
        val hostLower = host.lowercase()
        if (!isPublicHostShape(hostLower)) {
            return EndpointCheck.Invalid(
                ModelConfigCodes.ENDPOINT_HOST_NOT_PUBLIC,
                "接口主机名不是公网候选形状（不接受 localhost、内网地址或单段名称）"
            )
        }
        if (tail.isEmpty()) {
            return EndpointCheck.Invalid(
                ModelConfigCodes.ENDPOINT_PATH_REQUIRED,
                "接口地址必须包含完整请求路径（以 ${ModelConfigLimits.REQUIRED_PATH_SUFFIX} 结尾）"
            )
        }
        if ('?' in tail) {
            // No echo: the query may carry a credential.
            return EndpointCheck.Invalid(
                ModelConfigCodes.ENDPOINT_HAS_QUERY, "接口地址不能包含查询参数"
            )
        }
        if ('#' in tail) {
            return EndpointCheck.Invalid(
                ModelConfigCodes.ENDPOINT_HAS_FRAGMENT, "接口地址不能包含片段"
            )
        }
        if (tail.length > ModelConfigLimits.MAX_PATH_CHARS) {
            return EndpointCheck.Invalid(ModelConfigCodes.ENDPOINT_PATH_TOO_LONG, "接口路径过长")
        }
        if (!isValidPath(tail)) {
            return EndpointCheck.Invalid(
                ModelConfigCodes.ENDPOINT_PATH_INVALID, "接口路径含不合法字符"
            )
        }
        if (!tail.endsWith(ModelConfigLimits.REQUIRED_PATH_SUFFIX)) {
            return EndpointCheck.Invalid(
                ModelConfigCodes.ENDPOINT_NOT_CHAT_COMPLETIONS,
                "接口路径必须以 ${ModelConfigLimits.REQUIRED_PATH_SUFFIX} 结尾：" +
                    "本批次只支持这一种请求地址"
            )
        }
        val canonical = "https://" + hostLower +
            (if (port == DEFAULT_PORT) "" else ":$port") + tail
        return EndpointCheck.Valid(canonical, hostLower, port, tail)
    }

    fun identityOf(valid: EndpointCheck.Valid): EndpointIdentity = EndpointIdentity(
        valid.endpoint, valid.host, valid.port, valid.path, classify(valid.host, valid.port)
    )

    /** Host-shape classification only. Never inspects a credential.
     * [ProviderKind.Anthropic] exists ONLY so an already-persisted legacy record can be
     * recognised and rejected safely; a fresh save to that provider is refused with
     * PROVIDER_UNSUPPORTED instead of pretending compatibility. */
    fun classify(host: String, port: Int): ProviderKind = when {
        host == "api.anthropic.com" && port == DEFAULT_PORT -> ProviderKind.Anthropic
        host == "api.openai.com" && port == DEFAULT_PORT -> ProviderKind.OpenAi
        else -> ProviderKind.Generic
    }

    /** True when a provider can still be SAVED by this batch. Anthropic is legacy-only:
     * its request shape is not the one dialect this subset speaks, so it is rejected
     * loudly rather than adapted into something it is not. */
    fun isSaveableProvider(kind: ProviderKind): Boolean = kind != ProviderKind.Anthropic

    /** Shape check only. No DNS resolution, no network probe, no reachability claim. */
    private fun isPublicHostShape(hostLower: String): Boolean {
        if (hostLower == "localhost" ||
            hostLower.endsWith(".local") || hostLower.endsWith(".internal") ||
            hostLower.endsWith(".lan") || hostLower.endsWith(".home") ||
            hostLower.endsWith(".localdomain")
        ) {
            return false
        }
        if (IPV4.matches(hostLower)) return !isPrivateIpv4(hostLower)
        // Never reinterpret malformed/octal-looking numeric IPv4 as a DNS name.
        if (hostLower.split('.').all { label ->
                label.isNotEmpty() && label.all { it in '0'..'9' }
            }
        ) {
            return false
        }
        if (hostLower.isEmpty() || hostLower.length > 253) return false
        if ('.' !in hostLower) return false // a public candidate must be a dotted name
        return hostLower.split('.').all { HOST_LABEL.matches(it) }
    }

    private fun isPrivateIpv4(ip: String): Boolean {
        val o = ip.split('.').map { it.toInt() }
        return o[0] == 0 || o[0] == 10 || o[0] == 127 ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 169 && o[1] == 254) ||
            (o[0] == 100 && o[1] in 64..127)
    }

    private fun isValidPath(path: String): Boolean {
        var i = 1 // the leading '/'
        while (i < path.length) {
            val c = path[i]
            if (c == '%') {
                if (i + 2 >= path.length || !isHexDigit(path[i + 1]) || !isHexDigit(path[i + 2])) {
                    return false
                }
                i += 3
                continue
            }
            val allowed = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
                c == '/' || c == '.' || c == '-' || c == '_' || c == '~' ||
                c == '$' || c == '&' || c == '+' || c == ',' || c == ';' || c == '=' ||
                c == ':' || c == '@' || c == '!' || c == '\'' || c == '(' || c == ')' || c == '*'
            if (!allowed) return false
            i++
        }
        return true
    }

    private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    const val DEFAULT_PORT = 443
}
/** Draft the UI submits. The key is carried in-memory only, never stored, logged or copied
 * into any published state object. A NON-EMPTY key is an EXPLICIT replacement; an empty key
 * only keeps an existing credential when the bound target is unchanged. */
class ModelConfigDraft private constructor(
    val endpoint: String,
    val model: String,
    val newKey: String
) {
    override fun toString(): String =
        "ModelConfigDraft(endpoint=$endpoint, model=$model, " +
            "newKey=${if (newKey.isEmpty()) "empty" else "provided"})"

    companion object {
        fun of(endpoint: String, model: String, newKey: String): ModelConfigDraft =
            ModelConfigDraft(endpoint.trim(), model.trim(), newKey)
    }
}

/** What a save decided to do with the credential. Never carries the secret itself.
 * CURRENT contract: a NEW key is an explicit [StoreNew] replacement for ANY supported
 * target change; the old key is never reused. Without a key, only an identical
 * host+port+path+provider binding keeps the existing sealed blob; a changed target fails
 * with KEY_REPLACEMENT_REQUIRED (a fail-closed exception, not a decision). [RejectReuse] is
 * retained for sealed-type compatibility with the frozen surface, but the repository now
 * reports that situation as an exception / a fresh [StoreNew] instead of a silent decision. */
sealed class CredentialDecision {
    /** Endpoint binding unchanged and no new key: keep the stored sealed record EXACTLY. */
    object KeepExisting : CredentialDecision()

    /** Caller supplied a NEW key: seal a fresh v3 record bound to the requested identity. */
    data class StoreNew(val provider: ProviderKind) : CredentialDecision()

    /** Legacy type; retained for API compatibility, no silent reuse is ever accepted. */
    data class RejectReuse(val previousProvider: ProviderKind?, val nextProvider: ProviderKind) :
        CredentialDecision()

    /** First save without a key is allowed and is reported as NotConfigured. */
    object AbsentByDesign : CredentialDecision()
}

/** Read-only view of what is persisted, safe to hand to the ViewModel and the UI.
 * [endpointTrusted] is false when the persisted endpoint is not a current complete
 * chat/completions address, when profile and credential record disagree, or when the
 * record's v3 binding metadata has drifted. The caller then treats the saved address as
 * untrusted and asks for a corrected one; it never clears the ciphertext. */
data class ModelConfigSnapshot(
    val profile: ModelProfile?,
    val credentialEndpoint: String?,
    val endpointTrusted: Boolean = true
)

/** Persistence for the model-configuration slice. Deterministic; never opens a network
 * connection: saving is a local operation only. */
interface ModelConfigRepository {
    fun load(): ModelConfigSnapshot
    fun commit(draft: ModelConfigDraft): ModelProfile
}

/** Local, PURE-domain repository. Enforces endpoint, model, secret-shape and v3 binding
 * rules and never performs network I/O or decryption. It depends only on
 * ModelConfigBinding / ModelSecretShape (pure Kotlin), the core ChatProfile public rules,
 * and the injected ModelConfigStore / CredentialVault. NO Android / Keystore / crypto import. */
class AndroidModelConfigRepository(
    private val store: ModelConfigStore,
    private val vault: CredentialVault
) : ModelConfigRepository {

    override fun load(): ModelConfigSnapshot {
        val profile = try {
            store.readProfile()
        } catch (e: Exception) {
            if (e is ModelConfigException) throw e
            throw ModelConfigException(ModelConfigCodes.READ_FAILED, "配置读取失败", e)
        }
        // Any credential-read exception is FAIL-CLOSED: never downgraded to "absent".
        val credential = try {
            store.readCredentialRecord()
        } catch (e: Exception) {
            if (e is ModelConfigException) throw e
            throw ModelConfigException(ModelConfigCodes.VAULT_READ_FAILED, "凭据元信息读取失败", e)
        }
        if (profile == null) {
            return ModelConfigSnapshot(null, credential?.endpoint, credential == null)
        }
        val valid = ModelEndpointPolicy.check(profile.endpoint) as? EndpointCheck.Valid
        val trusted = valid != null &&
            valid.endpoint == profile.endpoint &&
            ModelEndpointPolicy.isSaveableProvider(ModelEndpointPolicy.identityOf(valid).kind) &&
            isRecordConsistent(credential, profile.endpoint) &&
            (credential != null) == (profile.credentialPresence == CredentialPresence.Present)
        return ModelConfigSnapshot(profile, credential?.endpoint, trusted)
    }

    override fun commit(draft: ModelConfigDraft): ModelProfile {
        val check = ModelEndpointPolicy.check(draft.endpoint)
        if (check is EndpointCheck.Invalid) {
            throw ModelConfigException(check.code, check.message)
        }
        check as EndpointCheck.Valid
        // Model rules are REUSED from the core public entry point (non-empty,
        // <= ChatProfile.MAX_MODEL_CHARS, control-free, strict UTF-8 / no lone surrogate).
        validateModel(draft.model, check.endpoint)
        // Secret shape uses the pure shared gate (no crypto / Keystore reference).
        if (draft.newKey.isNotEmpty()) ModelSecretShape.requireValid(draft.newKey)

        val next = ModelEndpointPolicy.identityOf(check)
        if (!ModelEndpointPolicy.isSaveableProvider(next.kind)) {
            throw ModelConfigException(
                ModelConfigCodes.PROVIDER_UNSUPPORTED,
                "该接口提供方暂不受支持（仅支持 ${ModelConfigLimits.REQUIRED_PATH_SUFFIX} " +
                    "兼容地址）；旧密钥不会迁移到不支持的提供方。"
            )
        }

        val previousProfile = try {
            store.readProfile()
        } catch (e: Exception) {
            if (e is ModelConfigException) throw e
            throw ModelConfigException(ModelConfigCodes.READ_FAILED, "配置读取失败", e)
        }
        // FAIL-CLOSED on ANY read exception (typed ModelConfigException or any other):
        // never downgrade to null, never clear the ciphertext, never decrypt.
        val previousRecord = try {
            store.readCredentialRecord()
        } catch (e: Exception) {
            if (e is ModelConfigException) throw e
            throw ModelConfigException(ModelConfigCodes.VAULT_READ_FAILED, "凭据元信息读取失败", e)
        }

        val presencePresent = previousProfile?.credentialPresence == CredentialPresence.Present
        if (previousRecord == null && presencePresent) {
            throw ModelConfigException(ModelConfigCodes.VAULT_READ_FAILED,
                "配置声明存在凭据但元信息不可用；不自动降级为未配置，也不清除密文。")
        }
        if (previousRecord != null && previousProfile != null && !presencePresent) {
            throw ModelConfigException(ModelConfigCodes.VAULT_READ_FAILED,
                "凭据元信息与已保存状态不一致；不自动改写。")
        }

        val hasNewKey = draft.newKey.isNotEmpty()
        // Without a NEW key, an existing record must be a fully-consistent v3 binding.
        // With a NEW key the caller explicitly replaces it: a drifted/legacy record is never
        // reused and never silently carried forward.
        if (previousRecord != null && !hasNewKey) {
            requireConsistentRecord(previousRecord, previousProfile?.endpoint)
        }

        val previousIdentity = previousRecord?.let { identityOfRecord(it) }
        val hasCredential = previousRecord != null
        val decision = decide(next, previousIdentity, hasCredential, hasNewKey)
        when (decision) {
            is CredentialDecision.RejectReuse -> throw ModelConfigException(
                ModelConfigCodes.PROVIDER_CHANGED, "接口绑定已变化：旧密钥不会带到新地址。"
            )
            is CredentialDecision.KeepExisting -> Unit
            is CredentialDecision.StoreNew -> Unit
            CredentialDecision.AbsentByDesign -> Unit
        }

        val credential: CredentialRecord? = when (decision) {
            is CredentialDecision.StoreNew -> {
                val sealed = vault.seal(next, draft.newKey)
                CredentialRecord(
                    endpoint = next.endpoint,
                    host = next.host,
                    port = next.port,
                    path = next.path,
                    provider = next.kind,
                    purpose = ModelConfigBinding.DEFAULT_PURPOSE,
                    version = ModelConfigBinding.CREDENTIAL_VERSION,
                    sealed = sealed,
                    storage = ModelConfigBinding.STORAGE_LABEL,
                    alias = vault.alias
                )
            }
            // Same binding: keep the sealed blob AND all v3 metadata BYTE-FOR-BYTE.
            // No decryption, no re-encryption, no metadata rewrite.
            is CredentialDecision.KeepExisting -> previousRecord
            else -> null
        }

        val presence = when (decision) {
            is CredentialDecision.StoreNew, is CredentialDecision.KeepExisting ->
                CredentialPresence.Present
            else -> CredentialPresence.NotConfigured
        }
        val profile = ModelProfile(next.endpoint, draft.model, presence)
        store.commitAtomic(profile, credential)
        return profile
    }

    /** Reuse the core public rule (ChatProfile.create) as the single source of truth for
     * non-empty / 128-char / control-free / strict-UTF-8 model validation. Its
     * IllegalArgumentException is translated into a code-first domain exception. */
    private fun validateModel(model: String, endpoint: String) {
        try {
            ChatProfile.create(endpoint, model)
        } catch (e: IllegalArgumentException) {
            throw ModelConfigException(
                ModelConfigCodes.MODEL_INVALID,
                "模型名称须非空、不超过 ${ChatProfile.MAX_MODEL_CHARS} 字符且为严格 UTF-8 文本"
            )
        }
    }

    /** A record is consistent only when its endpoint is a current complete address and
     * EVERY v3 metadata field agrees: endpoint == profileEndpoint, host/port/path from the
     * endpoint, provider from host/port classification, and the pure binding constants
     * (purpose/version/storage) plus the vault alias. This is the binding-drift gate. */
    private fun isRecordConsistent(record: CredentialRecord?, profileEndpoint: String?): Boolean {
        if (record == null) return true
        val valid = ModelEndpointPolicy.check(record.endpoint) as? EndpointCheck.Valid ?: return false
        return valid.endpoint == record.endpoint &&
            record.endpoint == profileEndpoint &&
            record.host == valid.host &&
            record.port == valid.port &&
            record.path == valid.path &&
            record.provider == ModelEndpointPolicy.classify(valid.host, valid.port) &&
            record.purpose == ModelConfigBinding.DEFAULT_PURPOSE &&
            record.version == ModelConfigBinding.CREDENTIAL_VERSION &&
            record.storage == ModelConfigBinding.STORAGE_LABEL &&
            record.alias == vault.alias
    }

    private fun requireConsistentRecord(record: CredentialRecord, profileEndpoint: String?) {
        if (!isRecordConsistent(record, profileEndpoint)) {
            throw ModelConfigException(
                ModelConfigCodes.CREDENTIAL_BINDING_MISMATCH,
                "凭据绑定元信息（alias/provider/storage/purpose/version/host/port/path）" +
                    "不一致或为旧版本；不复用旧密钥，请为当前接口重新输入密钥。"
            )
        }
    }

    private fun identityOfRecord(record: CredentialRecord): EndpointIdentity = EndpointIdentity(
        endpoint = record.endpoint,
        host = record.host,
        port = record.port,
        path = record.path,
        kind = record.provider
    )

    /** The binding rule. A NEW key is an EXPLICIT replacement for any supported target
     * change; the old key is NEVER reused. Without a key the sealed record survives ONLY
     * for an identical host + port + path + provider (model-only change). Any target change
     * without a new key fails with KEY_REPLACEMENT_REQUIRED. */
    internal fun decide(
        next: EndpointIdentity,
        previousIdentity: EndpointIdentity?,
        hasCredential: Boolean,
        hasNewKey: Boolean
    ): CredentialDecision = when {
        hasNewKey -> CredentialDecision.StoreNew(next.kind)
        hasCredential && previousIdentity != null && sameTarget(previousIdentity, next) ->
            CredentialDecision.KeepExisting
        hasCredential -> throw ModelConfigException(
            ModelConfigCodes.KEY_REPLACEMENT_REQUIRED,
            "接口地址指向的服务已变化：旧密钥不会自动带到新地址。" +
                "请输入新密钥后保存，或改回原地址保存以保留旧配置。"
        )
        else -> CredentialDecision.AbsentByDesign
    }

    private fun sameTarget(a: EndpointIdentity, b: EndpointIdentity): Boolean =
        a.host == b.host && a.port == b.port && a.path == b.path && a.kind == b.kind
}
