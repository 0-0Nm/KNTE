package com.knte.preview.source

/** Formal read-only diary source contract.
 * - Only reads a root the user explicitly selected (SourceRoot.bind by the parent).
 * - Failures throw SourceException; they are NEVER converted into an empty list.
 * - No write/create/delete/rename/commit/CAS surface: SAF has no general CAS, this batch is read-only.
 * - No isolated-snapshot fallback: an absent/lost source stays absent/lost. */
interface ReadOnlyDiarySource {
    fun list(root: SourceRoot): SourceListing
    fun read(root: SourceRoot, documentId: String): SourceDocument
}
