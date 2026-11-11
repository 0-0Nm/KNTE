package com.knte.preview.source

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream

/** Framework-only READ adapter. Never grants permission and has no output/delete API. */
class AndroidSourceDocumentProvider(private val resolver: ContentResolver) : SourceDocumentProvider {
    override fun permissionState(root: SourceRoot): SourcePermission = try {
        val uri = Uri.parse(root.treeUri)
        if (resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
            SourcePermission.Granted else SourcePermission.Lost
    } catch (_: Exception) { SourcePermission.Lost }

    override fun queryChildren(root: SourceRoot): List<SourceDocumentMeta> {
        if (permissionState(root) != SourcePermission.Granted)
            throw SourceException(SourceCodes.PERMISSION_LOST, "目录读取授权已不可用")
        val tree = Uri.parse(root.treeUri)
        if (DocumentsContract.getTreeDocumentId(tree) != root.rootDocumentId)
            throw SourceException(SourceCodes.ROOT_MISMATCH, "目录身份不匹配")
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root.rootDocumentId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE)
        try {
            val cursor = resolver.query(uri, projection, null, null, null)
                ?: throw SourceException(SourceCodes.QUERY_FAILED, "文档提供方未返回列表")
            return cursor.use { c ->
                val result = mutableListOf<SourceDocumentMeta>()
                while (c.moveToNext()) {
                    if (result.size >= 2000) throw SourceException(SourceCodes.DOCUMENT_CAPACITY, "目录枚举超过预算")
                    val id = c.getString(0) ?: throw SourceException(SourceCodes.QUERY_FAILED, "文档身份缺失")
                    val name = c.getString(1) ?: throw SourceException(SourceCodes.QUERY_FAILED, "文档名称缺失")
                    val mime = c.getString(2).orEmpty()
                    result.add(SourceDocumentMeta(id, name, mime,
                        mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        if (c.isNull(3)) -1L else c.getLong(3), root.rootDocumentId))
                }
                result
            }
        } catch (e: SourceException) { throw e }
        catch (_: SecurityException) { throw SourceException(SourceCodes.PERMISSION_LOST, "文档读取授权不可用") }
        catch (_: Exception) { throw SourceException(SourceCodes.QUERY_FAILED, "提供方列举失败") }
    }
    override fun openDocument(root: SourceRoot, documentId: String): InputStream {
        if (permissionState(root) != SourcePermission.Granted)
            throw SourceException(SourceCodes.PERMISSION_LOST, "读取授权不可用")
        // The validation engine has independently enumerated direct children before this call.
        val uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(root.treeUri), documentId)
        try { return resolver.openInputStream(uri)
            ?: throw SourceException(SourceCodes.READ_FAILED, "未能打开只读输入流") }
        catch (_: SecurityException) { throw SourceException(SourceCodes.PERMISSION_LOST, "读取授权不可用") }
        catch (e: SourceException) { throw e }
        catch (_: Exception) { throw SourceException(SourceCodes.READ_FAILED, "提供方读取失败") }
    }
}

/** Stores ONLY the user's explicit source binding. Does not access content or release grants. */
class AndroidSourcePreferences(context: Context) : SourcePreferences {
    private val prefs = context.applicationContext.getSharedPreferences("diary-readonly-source-v1", Context.MODE_PRIVATE)
    override fun load(): SourceRoot? {
        val value = prefs.getString("selectedTree", null) ?: return null
        return runCatching {
            val uri = Uri.parse(value)
            SourceRoot.bind(value, DocumentsContract.getTreeDocumentId(uri), SourcePermission.Granted)
        }.getOrNull()
    }
    override fun save(root: SourceRoot?) {
        val edit = prefs.edit()
        if (root == null) edit.remove("selectedTree") else edit.putString("selectedTree", root.treeUri)
        check(edit.commit()) { "SOURCE_BINDING_SAVE_FAILED" }
    }
}
