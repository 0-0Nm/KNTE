package com.knte.preview.source

import com.knte.core.diary.DiaryCodec
import com.knte.core.diary.DiaryDocument
import com.knte.core.diary.DiaryMetadata

/** Formal diary source: READ-ONLY. No isolated-snapshot fallback, no write-back, no CAS. */
object SourceLimits {
    const val MAX_FILE_BYTES = DiaryCodec.MAX_FILE_BYTES
    const val MAX_DOCUMENTS = 1000
    const val MAX_TOTAL_BYTES = 16L * 1024L * 1024L
    const val READ_BLOCK = 8192
    const val DIRECTORY_MIME = "vnd.android.document/directory"
    val COMPATIBLE_MIME: Set<String> = setOf(
        "text/markdown", "text/x-markdown", "text/md", "text/plain",
        "application/markdown", "application/x-markdown", "application/octet-stream", ""
    )
}

/** Explicit permission state bound into the root identity. */
enum class SourcePermission { None, Granted, Lost }

/** Stable machine codes. A failure is never reported as an empty/successful result. */
object SourceCodes {
    const val PERMISSION_LOST = "PERMISSION_LOST"
    const val PERMISSION_NOT_GRANTED = "PERMISSION_NOT_GRANTED"
    const val QUERY_FAILED = "QUERY_FAILED"
    const val READ_FAILED = "READ_FAILED"
    const val DOCUMENT_SIZE_LIMIT = "DOCUMENT_SIZE_LIMIT"
    const val TOTAL_BUDGET_EXCEEDED = "TOTAL_BUDGET_EXCEEDED"
    const val DOCUMENT_CAPACITY = "DOCUMENT_CAPACITY"
    const val DUPLICATE_DOCUMENT_ID = "DUPLICATE_DOCUMENT_ID"
    const val NOT_ROOT_CHILD = "NOT_ROOT_CHILD"
    const val INVALID_DISPLAY_NAME = "INVALID_DISPLAY_NAME"
    const val DIRECTORY_MASQUERADE = "DIRECTORY_MASQUERADE"
    const val MIME_INCOMPATIBLE = "MIME_INCOMPATIBLE"
    const val INVALID_ENCODING = "INVALID_ENCODING"
    const val DOCUMENT_NOT_FOUND = "DOCUMENT_NOT_FOUND"
    const val ROOT_MISMATCH = "ROOT_MISMATCH"
    const val REVISION_MISMATCH = "REVISION_MISMATCH"
}

class SourceException(val code: String, message: String, cause: Throwable? = null) :
    IllegalStateException("[$code] $message", cause)

/** Strict UTF-8 + SHA-256 binding helper. Never includes a real treeUri in the digest input. */
internal fun sourceDigest(value: String): String = try {
    DiaryCodec.hash(DiaryCodec.encode(value))
} catch (e: Exception) {
    throw SourceException(SourceCodes.INVALID_ENCODING, "绑定输入非严格 UTF-8", e)
}

/** Root identity = user-chosen treeUri + documentId; permission state is bound separately.
 * The real treeUri is kept private: label/toString never expose it to UI or logs. */
class SourceRoot internal constructor(
    internal val treeUri: String,
    val rootDocumentId: String,
    val permission: SourcePermission
) {
    /** Stable source identity (uri + documentId). Changes when switching sources. */
    val identity: String = sourceDigest("root-v1\n$treeUri\n$rootDocumentId")
    /** Result binding also covers the permission snapshot at bind time. */
    val binding: String = sourceDigest("bind-v1\n$identity\n$permission")
    val label: String get() = "已选目录 · ${identity.take(8)}"
    override fun toString(): String =
        "SourceRoot(documentId=<hidden>, permission=$permission, id=${identity.take(8)})"
    override fun equals(other: Any?): Boolean = other is SourceRoot &&
        treeUri == other.treeUri && rootDocumentId == other.rootDocumentId && permission == other.permission
    override fun hashCode(): Int = (treeUri + "\n" + rootDocumentId + "\n" + permission).hashCode()

    companion object {
        /** Parent builds this only AFTER ACTION_OPEN_DOCUMENT_TREE and AFTER it has taken
         * persistable permission with the user-granted flags. No implicit grant here. */
        fun bind(treeUri: String, rootDocumentId: String, permission: SourcePermission): SourceRoot {
            if (treeUri.isBlank()) throw SourceException(SourceCodes.ROOT_MISMATCH, "treeUri 为空")
            if (rootDocumentId.isBlank()) throw SourceException(SourceCodes.ROOT_MISMATCH, "rootDocumentId 为空")
            return SourceRoot(treeUri, rootDocumentId, permission)
        }
    }
}

/** One eligible direct-child Markdown document. [documentBinding] independently binds
 * rootIdentity + documentId + displayName, so aliases / renamed files cannot be conflated. */
data class SourceEntry(
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val rootIdentity: String,
    val rootBinding: String,
    val documentBinding: String
)

/** A successful listing. An empty [entries] means a REAL empty selected directory,
 * never "not selected" and never a masked read failure. */
data class SourceListing(
    val entries: List<SourceEntry>,
    val totalBytes: Long,
    val rootIdentity: String,
    val rootBinding: String
)

/** Full read-only document: strict UTF-8 parsed body, conservative frontmatter,
 * content hash and an independent id/displayName binding from a fresh enumeration. */
data class SourceDocument(
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val revision: String,
    val documentBinding: String,
    val document: DiaryDocument,
    val metadata: DiaryMetadata?,
    val rootIdentity: String,
    val rootBinding: String
) {
    val body: String get() = document.body
    val problems: List<String> get() = document.problems
}

/** Explicit UI states required by the contract. */
sealed class SourceStatus {
    /** No directory chosen. NOT zero entries. */
    object NotSelected : SourceStatus()
    /** A previously chosen root lost / never got persisted read permission. */
    object PermissionLost : SourceStatus()
    object Loading : SourceStatus()
    /** Chosen root read successfully, contains zero Markdown documents. */
    object ReadyEmpty : SourceStatus()
    data class Ready(val entries: List<SourceEntry>, val totalBytes: Long) : SourceStatus()
    /** Read/query failure. NEVER collapsed into ReadyEmpty or a "0 entries" claim.
     * [retainedEntries] keeps the last successfully read list for THIS root, so a
     * transient failure does not zero out visible data; empty when no read ever succeeded. */
    data class Error(
        val code: String,
        val message: String,
        val retainedEntries: List<SourceEntry> = emptyList(),
        val retainedTotalBytes: Long = 0L
    ) : SourceStatus()
}

data class SourceUiState(
    val status: SourceStatus = SourceStatus.NotSelected,
    val permission: SourcePermission = SourcePermission.None,
    val rootLabel: String? = null,
    val rootIdentity: String? = null,
    val rootBinding: String? = null,
    val opened: SourceDocument? = null,
    val busy: Boolean = false,
    val notice: String? = null
) {
    /** Error keeps the last good list; only NotSelected / PermissionLost / Loading clear it. */
    val entries: List<SourceEntry> get() = when (val s = status) {
        is SourceStatus.Ready -> s.entries
        is SourceStatus.Error -> s.retainedEntries
        else -> emptyList()
    }
    val totalBytes: Long get() = when (val s = status) {
        is SourceStatus.Ready -> s.totalBytes
        is SourceStatus.Error -> s.retainedTotalBytes
        else -> 0L
    }
    val hasRoot: Boolean get() = rootIdentity != null
}
