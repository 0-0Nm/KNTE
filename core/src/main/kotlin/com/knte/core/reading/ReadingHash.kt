package com.knte.core.reading

import java.security.MessageDigest

/** SHA-256 hex used for content revisions and small non-sensitive fingerprints. */
internal object ReadingHash {
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
