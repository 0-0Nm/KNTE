package com.knte.preview.source

import com.knte.core.diary.DiaryCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** Fake provider contract tests, NOT evidence of an actual Android SAF provider. */
class SourceContractTest {
    private class Provider : SourceDocumentProvider {
        var permission = SourcePermission.Granted
        var fail = false
        var afterOpen: (() -> Unit)? = null
        var metas = listOf(SourceDocumentMeta("doc", "day.md", "text/markdown", false, 4, "root"))
        var bytes = DiaryCodec.encode("body")
        override fun permissionState(root: SourceRoot) = permission
        override fun queryChildren(root: SourceRoot): List<SourceDocumentMeta> {
            check(!fail) { "synthetic provider failure" };return metas
        }
        override fun openDocument(root: SourceRoot, documentId: String): InputStream {
            afterOpen?.invoke();return ByteArrayInputStream(bytes)
        }
    }
    private fun root(id: String = "tree-a") = SourceRoot.bind("content://synthetic/$id", "root", SourcePermission.Granted)
    private fun fails(code: String, block: () -> Unit) {
        try { block();fail("expected refusal $code") } catch (e: SourceException) { assertEquals(code,e.code) }
    }
    @Test fun notSelectedIsNotReadyEmpty() {
        val session=SourceSession(ReadOnlyDiarySourceImpl(Provider()))
        assertTrue(session.reload().status is SourceStatus.NotSelected)
    }
    @Test fun realSuccessfulEmptyIsDistinct() {
        val p=Provider();p.metas=emptyList();val s=SourceSession(ReadOnlyDiarySourceImpl(p));s.bind(root())
        assertTrue(s.reload().status is SourceStatus.ReadyEmpty)
    }
    @Test fun strictReadBindsMetadataAndRevision() {
        val p=Provider();p.bytes=DiaryCodec.create("2026-11-11","合成正文").bytes()
        val d=ReadOnlyDiarySourceImpl(p).read(root(),"doc")
        assertEquals("合成正文",d.body);assertEquals(DiaryCodec.hash(p.bytes),d.revision)
        assertEquals(root().identity,d.rootIdentity)
    }
    @Test fun permissionLossClearsPriorDocumentAndList() {
        val p=Provider();val s=SourceSession(ReadOnlyDiarySourceImpl(p));s.bind(root());s.reload();s.open("doc")
        p.permission=SourcePermission.Lost
        val result=s.reload();assertTrue(result.status is SourceStatus.PermissionLost)
        assertTrue(result.entries.isEmpty());assertNull(result.opened)
    }
    @Test fun sameSourceQueryErrorRetainsListAsErrorNotEmpty() {
        val p=Provider();val s=SourceSession(ReadOnlyDiarySourceImpl(p));s.bind(root());s.reload();p.fail=true
        val result=s.reload();assertTrue(result.status is SourceStatus.Error);assertEquals(1,result.entries.size)
    }
    @Test fun sourceSwitchAndForgetClearPreviousData() {
        val s=SourceSession(ReadOnlyDiarySourceImpl(Provider()));s.bind(root());s.reload();s.open("doc")
        val changed=s.bind(root("tree-b"));assertTrue(changed.entries.isEmpty());assertNull(changed.opened)
        assertEquals(root("tree-b").identity,changed.rootIdentity)
        assertTrue(s.forget().status is SourceStatus.NotSelected)
    }
    @Test fun duplicateAndForeignParentRejected() {
        val p=Provider();p.metas=p.metas+p.metas
        fails(SourceCodes.DUPLICATE_DOCUMENT_ID){ReadOnlyDiarySourceImpl(p).list(root())}
        p.metas=listOf(SourceDocumentMeta("doc","day.md","text/plain",false,4,"foreign"))
        fails(SourceCodes.NOT_ROOT_CHILD){ReadOnlyDiarySourceImpl(p).list(root())}
    }
    @Test fun ordinaryDirectoryAndNonMarkdownNeverOpened() {
        val p=Provider();p.metas=listOf(SourceDocumentMeta("sub","folder",SourceLimits.DIRECTORY_MIME,true,0,"root"),
            SourceDocumentMeta("pdf","paper.pdf","application/pdf",false,100,"root"))
        assertTrue(ReadOnlyDiarySourceImpl(p).list(root()).entries.isEmpty())
    }
    @Test fun directoryMasqueradeAndBadMimeRejected() {
        val p=Provider();p.metas=listOf(SourceDocumentMeta("doc","day.md",SourceLimits.DIRECTORY_MIME,true,0,"root"))
        fails(SourceCodes.DIRECTORY_MASQUERADE){ReadOnlyDiarySourceImpl(p).list(root())}
        p.metas=listOf(SourceDocumentMeta("doc","day.md","application/pdf",false,0,"root"))
        fails(SourceCodes.MIME_INCOMPATIBLE){ReadOnlyDiarySourceImpl(p).list(root())}
    }
    @Test fun metadataAndActualReadBudgetsEnforced() {
        val p=Provider();p.metas=p.metas.map { it.copy(sizeBytes=SourceLimits.MAX_FILE_BYTES+1L) }
        fails(SourceCodes.DOCUMENT_SIZE_LIMIT){ReadOnlyDiarySourceImpl(p).list(root())}
        p.metas=p.metas.map { it.copy(sizeBytes=-1) };p.bytes=ByteArray(SourceLimits.MAX_FILE_BYTES+1){65}
        fails(SourceCodes.DOCUMENT_SIZE_LIMIT){ReadOnlyDiarySourceImpl(p).read(root(),"doc")}
    }
    @Test fun invalidUtf8NeverShownAsBody() {
        val p=Provider();p.bytes=byteArrayOf(0xc3.toByte(),0x28)
        fails(SourceCodes.INVALID_ENCODING){ReadOnlyDiarySourceImpl(p).read(root(),"doc")}
    }
    @Test fun grantRevokedDuringReadDoesNotPublishBytes() {
        val p=Provider();p.afterOpen={p.permission=SourcePermission.Lost}
        fails(SourceCodes.PERMISSION_LOST){ReadOnlyDiarySourceImpl(p).read(root(),"doc")}
    }
    @Test fun renameDuringReadInvalidatesIndependentBinding() {
        val p=Provider();p.afterOpen={p.metas=p.metas.map{it.copy(displayName="renamed.md")}}
        fails(SourceCodes.ROOT_MISMATCH){ReadOnlyDiarySourceImpl(p).read(root(),"doc")}
    }
}
