package com.knte.core.diary

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DiaryContractTest {
    private fun bytes(s: String) = DiaryCodec.encode(s)
    private fun fails(block: () -> Unit) {
        try { block(); fail("expected refusal") } catch (e: Exception) { /* required fail-closed */ }
    }
    @Test fun canonicalCreateAndReparse() {
        val d = DiaryCodec.create("2026-11-11", "合成日记\n", world = "素问", tags = listOf("物理", "a,b", "it's"))
        assertTrue(d.editable); assertEquals("self", d.metadata?.source)
        assertEquals(listOf("物理", "a,b", "it's"), d.metadata?.tags)
        assertEquals("合成日记\n", DiaryCodec.parse(d.bytes()).body)
    }
    @Test fun bomCrLfUnknownHeaderExactlyPreserved() {
        val header = "\uFEFF---\r\nDIARY: \"2026-11-11\" # old\r\nunknown: [keep, original]\r\n# comment\r\n---\r\n"
        val d = DiaryCodec.parse(bytes(header + "old\r\n"))
        assertTrue(d.editable); assertEquals("2026-11-11", d.metadata?.date)
        assertArrayEquals(bytes(header + "new\n"), DiaryCodec.replaceBody(d, "new\n").bytes())
    }
    @Test fun noFrontmatterNeverInventsMetadata() {
        val d = DiaryCodec.parse(bytes("\uFEFFold"))
        assertNull(d.metadata)
        assertArrayEquals(bytes("\uFEFFnew"), DiaryCodec.replaceBody(d, "new").bytes())
    }
    @Test fun emptyHistoricalBodyCanBeEdited() {
        val d = DiaryCodec.parse(byteArrayOf()); assertTrue(d.editable)
        assertEquals("new", DiaryCodec.replaceBody(d, "new").body)
    }
    @Test fun unclosedAndDuplicateIdentityReadOnly() {
        val u = DiaryCodec.parse(bytes("---\ndate: '2026-11-11'\nno end"))
        assertFalse(u.editable); fails { DiaryCodec.replaceBody(u, "new") }
        for (extra in listOf("date: '2026-11-12'", "DIARY: '2026-11-11'")) {
            assertFalse(DiaryCodec.parse(bytes("---\ndate: '2026-11-11'\n$extra\n---\nbody")).editable)
        }
    }
    @Test fun complexYamlNotGuessed() {
        val d = DiaryCodec.parse(bytes("---\ndate: '2026-11-11'\nworld: |\n  multiline\n---\nbody"))
        assertFalse(d.editable)
        assertArrayEquals(bytes("---\ndate: '2026-11-11'\nworld: |\n  multiline\n---\nbody"), d.bytes())
    }
    @Test fun strictEncodingNulAndSize() {
        fails { DiaryCodec.parse(byteArrayOf(0xC3.toByte(), 0x28)) }
        fails { DiaryCodec.parse(bytes("a") + byteArrayOf(0)) }
        fails { DiaryCodec.encode("\uD800") }
        fails { DiaryCodec.parse(ByteArray(DiaryCodec.MAX_FILE_BYTES + 1) { 65 }) }
        assertFalse(DiaryCodec.parse(bytes("x".repeat(DiaryCodec.MAX_BODY_CHARS + 1))).editable)
    }
    @Test fun metadataValidationAndInjection() {
        fails { DiaryCodec.create("2026-02-30", "x") }
        fails { DiaryCodec.create("2026-11-11", "x", source = "manual") }
        fails { DiaryCodec.create("2026-11-11", "x", world = "world\nsource: lesson") }
        val d = DiaryCodec.create("2026-11-11", "x", world = "world: 'quoted' # literal")
        assertEquals("world: 'quoted' # literal", d.metadata?.world)
    }
    @Test fun appendPreservesFullPrefixAndLineConvention() {
        val d = DiaryCodec.parse(bytes("---\r\ndate: '2026-11-11'\r\n---\r\nold"))
        val next = DiaryCodec.appendBody(d, "new")
        assertArrayEquals(d.bytes() + bytes("\r\nnew"), next.bytes())
    }
    @Test fun byteArraysCannotMutateDocumentOrPlan() {
        val raw = DiaryCodec.create("2026-11-11", "x").bytes()
        val d = DiaryCodec.parse(raw); raw.fill(0)
        val copy = d.bytes(); copy.fill(0)
        assertTrue(d.editable); assertTrue(d.body == "x")
        assertEquals(d.revision, DiaryCodec.parse(d.bytes()).revision)
    }
    @Test fun realFilesCreateReadBackReopenAndConflict() {
        val root = Files.createTempDirectory("knte-diary-contract-").toFile()
        val r = IsolatedDiaryRepository(root)
        val plan = r.planCreate(DiaryCodec.create("2026-11-11", "original"))
        fails { r.commit(plan, "not confirmed") }; assertTrue(r.list().isEmpty())
        val a = r.commit(plan, plan.confirmation)
        assertEquals("original", IsolatedDiaryRepository(root).get(a.name)?.document?.body)
        fails { r.planCreate(DiaryCodec.create("2026-11-11", "duplicate")) }
        val edit = r.planReplaceBody(a.name, a.document.revision, "replacement")
        val b = r.commit(edit, edit.confirmation)
        assertEquals("replacement", b.document.body)
        val before = root.resolve(".journal").walkTopDown().filter { it.name == "before.md" }.toList()
        assertEquals(1, before.size); assertArrayEquals(a.document.bytes(), before.single().readBytes())
        fails { r.planReplaceBody(a.name, a.document.revision, "stale") }
        fails { r.commit(edit, edit.confirmation) }
    }
    @Test fun externalDriftBlocksWriteWithoutReplacing() {
        val root = Files.createTempDirectory("knte-diary-drift-").toFile(); val r = IsolatedDiaryRepository(root)
        val p = r.planCreate(DiaryCodec.create("2026-11-11", "first"));val a=r.commit(p,p.confirmation)
        val update = r.planReplaceBody(a.name, a.document.revision, "must not win")
        val outside = DiaryCodec.create("2026-11-11", "outside").bytes();root.resolve(a.name).writeBytes(outside)
        fails { r.commit(update, update.confirmation) }
        assertArrayEquals(outside, root.resolve(a.name).readBytes())
    }
    @Test fun pendingAndSpecialFilesFailClosed() {
        val root = Files.createTempDirectory("knte-diary-pending-").toFile();val r=IsolatedDiaryRepository(root)
        root.resolve("2026-11-11.md.pending").writeText("review")
        fails { r.list() };fails { r.planCreate(DiaryCodec.create("2026-11-11", "x")) }
        assertTrue(root.resolve("2026-11-11.md.pending").exists())
        val other = Files.createTempDirectory("knte-diary-link-")
        val target=Files.createTempFile("outside-diary-", ".md")
        Files.createSymbolicLink(other.resolve("2026-11-11.md"), target)
        fails { IsolatedDiaryRepository(other.toFile()).list() }
    }
    @Test fun closedHeaderAtEofRemainsValidAfterEdit() {
        val d = DiaryCodec.parse(bytes("---\ndate: '2026-11-11'\n---"))
        assertTrue(d.editable)
        assertEquals("new", DiaryCodec.replaceBody(d, "new").body)
        val a = DiaryCodec.appendBody(d, "new")
        assertTrue(a.editable); assertEquals("new", a.body)
        assertArrayEquals(d.bytes() + bytes("\nnew"), a.bytes())
    }

    @Test fun injectedFailureBeforeTargetWriteRetainsOriginalAndPending() {
        val root = Files.createTempDirectory("knte-diary-before-write-").toFile()
        val initial = IsolatedDiaryRepository(root)
        val create = initial.planCreate(DiaryCodec.create("2026-11-11", "original"))
        val saved = initial.commit(create, create.confirmation)
        val r = IsolatedDiaryRepository(root) { phase ->
            if (phase == "pending-durable") throw java.io.IOException("injected-before-write")
        }
        val p = r.planReplaceBody(saved.name, saved.document.revision, "must not publish")
        fails { r.commit(p, p.confirmation) }
        assertArrayEquals(saved.document.bytes(), root.resolve(saved.name).readBytes())
        assertTrue(root.resolve("${saved.name}.pending").isFile)
        fails { IsolatedDiaryRepository(root).list() }
    }
    @Test fun injectedFailureAfterPublishIsUncertainAndNotReplayed() {
        val root = Files.createTempDirectory("knte-diary-after-write-").toFile()
        val initial=IsolatedDiaryRepository(root)
        val c=initial.planCreate(DiaryCodec.create("2026-11-11", "before"))
        val saved=initial.commit(c,c.confirmation)
        val r=IsolatedDiaryRepository(root) { phase ->
            if (phase == "target-published") throw java.io.IOException("injected-after-write")
        }
        val p=r.planReplaceBody(saved.name,saved.document.revision,"after")
        fails { r.commit(p,p.confirmation) }
        assertEquals("after",DiaryCodec.parse(root.resolve(saved.name).readBytes()).body)
        assertTrue(root.resolve("${saved.name}.pending").isFile)
        val before=root.resolve(".journal").walkTopDown().filter { it.name=="before.md" }.single()
        assertArrayEquals(saved.document.bytes(),before.readBytes())
        fails { r.commit(p,p.confirmation) }
    }
    @Test fun tamperedReadbackRetainsPendingAndReceiptFailureAlsoLocks() {
        val root=Files.createTempDirectory("knte-diary-readback-").toFile()
        val r=IsolatedDiaryRepository(root) { phase ->
            if (phase=="target-published") root.resolve("2026-11-11.md").writeBytes(DiaryCodec.create("2026-11-11","tampered").bytes())
        }
        val p=r.planCreate(DiaryCodec.create("2026-11-11","proposed"))
        fails { r.commit(p,p.confirmation) }
        assertTrue(root.resolve("2026-11-11.md.pending").exists())
        assertFalse(root.resolve(".journal").walkTopDown().any { it.name=="receipt.txt" })
        val root2=Files.createTempDirectory("knte-diary-receipt-").toFile()
        val r2=IsolatedDiaryRepository(root2) { phase ->
            if (phase=="receipt-durable") throw java.io.IOException("injected-before-ack")
        }
        val p2=r2.planCreate(DiaryCodec.create("2026-11-11","written"))
        fails { r2.commit(p2,p2.confirmation) }
        assertTrue(root2.resolve("2026-11-11.md.pending").exists())
        assertTrue(root2.resolve(".journal").walkTopDown().any { it.name=="receipt.txt" })
        fails { IsolatedDiaryRepository(root2).list() }
    }
    @Test fun confirmationCannotBeReusedAcrossRootsOrMetadataChanged() {
        val a=IsolatedDiaryRepository(Files.createTempDirectory("knte-diary-root-a-").toFile())
        val b=IsolatedDiaryRepository(Files.createTempDirectory("knte-diary-root-b-").toFile())
        val p=a.planCreate(DiaryCodec.create("2026-11-11","a"))
        val q=a.planCreate(DiaryCodec.create("2026-11-11","b"))
        assertNotEquals(p.confirmation,q.confirmation)
        fails { b.commit(p,p.confirmation) }
        fails { a.commit(q,p.confirmation) }
        assertTrue(a.list().isEmpty());assertTrue(b.list().isEmpty())
    }

}
