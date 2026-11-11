package com.knte.preview.source

import com.knte.core.diary.DiaryCodec
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Pure-Kotlin validation engine over a SourceDocumentProvider (no Android types).
 * Reuses core DiaryCodec for strict UTF-8 (REPORT), NUL rejection, 1MiB limit,
 * conservative frontmatter metadata and SHA-256 revision. */
class ReadOnlyDiarySourceImpl(
    private val provider: SourceDocumentProvider,
    private val limits: SourceLimits = SourceLimits
) : ReadOnlyDiarySource {

    override fun list(root: SourceRoot): SourceListing {
        assertPermission(root)
        val children = try {
            provider.queryChildren(root)
        } catch (e: SourceException) {
            throw e
        } catch (e: Exception) {
            throw SourceException(SourceCodes.QUERY_FAILED, "列出直接子文档失败", e)
        }
        val seen = HashSet<String>()
        val entries = ArrayList<SourceEntry>()
        var totalBytes = 0L
        for (meta in children) {
            if (!seen.add(meta.documentId)) throw SourceException(
                SourceCodes.DUPLICATE_DOCUMENT_ID, "枚举结果包含重复 documentId")
            validateIdentity(root, meta)
            val directory = meta.isDirectory || meta.mimeType == SourceLimits.DIRECTORY_MIME
            if (directory) {
                // Ordinary subdirectories are skipped and NEVER recursed into.
                // A directory named like a diary is refused, not silently skipped.
                if (isMarkdownName(meta.displayName)) throw SourceException(
                    SourceCodes.DIRECTORY_MASQUERADE, "目录伪装为 Markdown 文档")
                continue
            }
            if (!isMarkdownName(meta.displayName)) continue
            if (!compatible(meta.mimeType)) throw SourceException(
                SourceCodes.MIME_INCOMPATIBLE, "Markdown 文档声明了不兼容的 MIME")
            val size = meta.sizeBytes
            if (size > limits.MAX_FILE_BYTES) throw SourceException(
                SourceCodes.DOCUMENT_SIZE_LIMIT, "单文档超过字节上限")
            if (size >= 0) {
                totalBytes += size
                if (totalBytes > limits.MAX_TOTAL_BYTES) throw SourceException(
                    SourceCodes.TOTAL_BUDGET_EXCEEDED, "总字节预算超过上限")
            }
            if (entries.size >= limits.MAX_DOCUMENTS) throw SourceException(
                SourceCodes.DOCUMENT_CAPACITY, "文档数超过上限")
            entries.add(SourceEntry(
                documentId = meta.documentId,
                displayName = meta.displayName,
                mimeType = meta.mimeType,
                sizeBytes = size,
                rootIdentity = root.identity,
                rootBinding = root.binding,
                documentBinding = sourceDigest(
                    "doc-v1\n${root.identity}\n${meta.documentId}\n${meta.displayName}")
            ))
        }
        return SourceListing(entries, totalBytes, root.identity, root.binding)
    }

    override fun read(root: SourceRoot, documentId: String): SourceDocument {
        assertPermission(root)
        if (documentId.isBlank()) throw SourceException(SourceCodes.DOCUMENT_NOT_FOUND, "空 documentId")
        // Independent re-enumeration: the id MUST currently be a direct, non-directory,
        // md-compatible child. Rejects stale ids, cross-root ids and directory confusion.
        val listing = list(root)
        val entry = listing.entries.firstOrNull { it.documentId == documentId }
            ?: throw SourceException(
                SourceCodes.DOCUMENT_NOT_FOUND, "文档不在当前已选目录的直接子列表中")
        val bytes = readBounded(root, documentId)
        val parsed = try {
            DiaryCodec.parse(bytes)
        } catch (e: Exception) {
            throw SourceException(SourceCodes.INVALID_ENCODING, "严格 UTF-8 / frontmatter 解析失败", e)
        }
        // Revocation during IO must not publish content from an obsolete grant.
        assertPermission(root)
        val current = list(root).entries.firstOrNull { it.documentId == documentId }
        if (current?.documentBinding != entry.documentBinding)
            throw SourceException(SourceCodes.ROOT_MISMATCH, "文档身份在读取期间发生变化")
        val revision = DiaryCodec.hash(bytes)
        if (revision != parsed.revision) throw SourceException(
            SourceCodes.REVISION_MISMATCH, "revision 与实际内容不一致")
        return SourceDocument(
            documentId = entry.documentId,
            displayName = entry.displayName,
            mimeType = entry.mimeType,
            sizeBytes = bytes.size.toLong(),
            revision = revision,
            documentBinding = entry.documentBinding,
            document = parsed,
            metadata = parsed.metadata,
            rootIdentity = root.identity,
            rootBinding = root.binding
        )
    }

    private fun assertPermission(root: SourceRoot) {
        val state = try {
            provider.permissionState(root)
        } catch (e: Exception) {
            throw SourceException(SourceCodes.PERMISSION_LOST, "权限状态不可确认", e)
        }
        if (root.permission != SourcePermission.Granted) throw SourceException(
            SourceCodes.PERMISSION_NOT_GRANTED, "该来源未获得持久读取权限；不显示旧内容")
        if (state != SourcePermission.Granted) throw SourceException(
            SourceCodes.PERMISSION_LOST, "已选目录的读取权限不可用或已撤销")
    }

    private fun readBounded(root: SourceRoot, documentId: String): ByteArray {
        val stream: InputStream = try {
            provider.openDocument(root, documentId)
        } catch (e: SourceException) {
            throw e
        } catch (e: Exception) {
            throw SourceException(SourceCodes.READ_FAILED, "打开文档输入流失败", e)
        }
        return try {
            val out = ByteArrayOutputStream()
            val block = ByteArray(limits.READ_BLOCK)
            var total = 0
            var emptyReads = 0
            while (true) {
                val n = stream.read(block)
                if (n < 0) break
                if (n == 0) {
                    if (++emptyReads > 8) throw SourceException(SourceCodes.READ_FAILED, "输入流没有取得进展")
                    continue
                }
                emptyReads = 0
                total += n
                if (total > limits.MAX_FILE_BYTES) throw SourceException(
                    SourceCodes.DOCUMENT_SIZE_LIMIT, "有界读取超过单文档字节上限")
                out.write(block, 0, n)
            }
            out.toByteArray()
        } catch (e: SourceException) {
            throw e
        } catch (e: Exception) {
            throw SourceException(SourceCodes.READ_FAILED, "有界读取失败", e)
        } finally {
            try { stream.close() } catch (_: Exception) { }
        }
    }

    private fun validateIdentity(root: SourceRoot, meta: SourceDocumentMeta) {
        if (meta.documentId.isBlank()) throw SourceException(
            SourceCodes.NOT_ROOT_CHILD, "空 documentId")
        if (meta.documentId == root.rootDocumentId) throw SourceException(
            SourceCodes.NOT_ROOT_CHILD, "root 自身不是子文档")
        if (meta.parentDocumentId != root.rootDocumentId) throw SourceException(
            SourceCodes.NOT_ROOT_CHILD, "文档不属于当前已选 root 的直接子级")
        val n = meta.displayName
        if (n.isBlank() || n == "." || n == ".." || '/' in n || '\\' in n || '\u0000' in n)
            throw SourceException(SourceCodes.INVALID_DISPLAY_NAME, "显示名含路径分隔或非法字符")
    }

    private fun isMarkdownName(name: String): Boolean =
        name.length > 3 && name.endsWith(".md", ignoreCase = true)

    private fun compatible(mime: String): Boolean = mime.trim().lowercase() in limits.COMPATIBLE_MIME
}
