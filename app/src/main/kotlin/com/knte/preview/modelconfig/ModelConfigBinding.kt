package com.knte.preview.modelconfig

/** Single public metadata contract; no Android imports or credential values. v3 adds
 * provider/alias binding. Earlier unreleased records are rejected, never auto-migrated. */
object ModelConfigBinding {
    const val DEFAULT_ALIAS = "knte.model.config.credential.v1"
    const val DEFAULT_PURPOSE = "knte-model-config-credential"
    const val CREDENTIAL_VERSION = 3
    const val STORAGE_LABEL = "app-private-sharedpreferences"
}

/** Pure shape check shared by seal, decrypt, repository and synthetic tests. */
internal object ModelSecretShape {
    fun requireValid(secret: String) {
        if (secret.isBlank() || secret.length > ModelConfigLimits.MAX_SECRET_CHARS ||
            secret.any { it.isISOControl() }) invalid()
        var i = 0
        while (i < secret.length) {
            val c = secret[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= secret.length || !secret[i + 1].isLowSurrogate()) invalid()
                i++
            } else if (c.isLowSurrogate()) invalid()
            i++
        }
    }
    private fun invalid(): Nothing = throw ModelConfigException(ModelConfigCodes.KEY_INVALID,
        "密钥须为有界、非空白、无控制字符的严格 UTF-8 文本")
}
