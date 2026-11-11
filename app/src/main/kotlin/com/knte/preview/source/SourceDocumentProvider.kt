package com.knte.preview.source

import java.io.InputStream

/** Provider-neutral metadata for ONE direct child of the chosen root.
 * [parentDocumentId] MUST equal the root documentId; the engine rejects anything else. */
data class SourceDocumentMeta(
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val parentDocumentId: String
)

/** Abstraction over ContentResolver/SAF so host tests use an in-memory fake.
 * Implementations MUST enumerate ONLY direct children of [root], never recurse the
 * whole device, never write/delete/create, never request permission, never auto-select. */
interface SourceDocumentProvider {
    /** Current persisted permission state for this root. No implicit grant. */
    fun permissionState(root: SourceRoot): SourcePermission

    /** Direct children only. Throws on provider failure. Never returns the root itself. */
    fun queryChildren(root: SourceRoot): List<SourceDocumentMeta>

    /** Open a read-mode stream. The engine enforces the byte budget while reading and
     * closes the stream. Android implementation uses ContentResolver.openInputStream. */
    fun openDocument(root: SourceRoot, documentId: String): InputStream
}
