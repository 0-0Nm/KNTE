package com.knte.core.notes

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class FileNoteRepositoryTest {
    private fun fixture(test: (java.io.File) -> Unit) {
        val root = Files.createTempDirectory("knte-notes-test-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
    private fun rejected(block: () -> Unit) { try { block(); fail("accepted invalid operation") } catch (e: Exception) { } }
    @Test fun createListAndIndependentReopen() = fixture { root ->
        val repository = FileNoteRepository(root)
        assertTrue(repository.list().isEmpty())
        val created = repository.create(" title ", "内容\nbody", 100)
        assertEquals("title", created.title)
        assertEquals(created, FileNoteRepository(root).get(created.id))
        assertEquals(listOf(created), repository.list())
    }
    @Test fun revisionCheckedUpdatePreservesIdentityAndCreatedTime() = fixture { root ->
        val r = FileNoteRepository(root); val a = r.create("A", "one", 1)
        val b = FileNoteRepository(root).update(a.id, a.revision, "B", "two", 2)
        assertEquals(a.id, b.id); assertEquals(a.createdAtMillis, b.createdAtMillis)
        assertNotEquals(a.revision, b.revision)
        rejected { r.update(a.id, a.revision, "stale", "overwrite", 3) }
        assertEquals(b, r.get(a.id))
    }
    @Test fun invalidInputNeverCreatesARecord() = fixture { root ->
        val r = FileNoteRepository(root)
        rejected { r.create(" ", "x", 1) }
        rejected { r.create("x".repeat(121), "x", 1) }
        rejected { r.create("x", "x".repeat(FileNoteRepository.MAX_BODY_BYTES + 1), 1) }
        rejected { r.create("x", "\uD800", 1) }
        rejected { r.get("../secret") }
        rejected { r.create("x", "x", -1) }
        assertTrue(r.list().isEmpty())
    }
    @Test fun corruptForeignAndPendingFilesAreNotRemoved() = fixture { root ->
        val r = FileNoteRepository(root); val note = r.create("A", "original", 1)
        val file = root.resolve("${note.id}.note"); val original = file.readBytes()
        file.writeBytes(byteArrayOf(1, 2, 3)); rejected { r.list() }; assertEquals(3L, file.length())
        file.writeBytes(original)
        val pending = root.resolve("${note.id}.pending"); pending.writeText("partial")
        rejected { r.get(note.id) }; assertEquals("partial", pending.readText()); pending.delete()
        val foreign = root.resolve("foreign.txt"); foreign.writeText("unknown")
        rejected { r.list() }; assertEquals("unknown", foreign.readText())
    }
    @Test fun linkedFilesRefusedWithoutTouchingTarget() = fixture { root ->
        val target = Files.createTempFile("knte-target-", ".txt")
        try {
            Files.write(target, byteArrayOf(1, 2))
            val repo = FileNoteRepository(root)
            Files.createSymbolicLink(root.toPath().resolve("foreign.note"), target)
            rejected { repo.list() }; assertArrayEquals(byteArrayOf(1, 2), Files.readAllBytes(target))
        } finally { Files.delete(target) }
    }
    @Test fun wrongClockAndHashCannotOverwrite() = fixture { root ->
        val r = FileNoteRepository(root); val a = r.create("A", "saved", 10)
        rejected { r.update(a.id, a.revision, "B", "wrong time", 9) }
        rejected { r.update(a.id, "0".repeat(64), "B", "wrong version", 11) }
        assertEquals(a, r.get(a.id))
    }
}
