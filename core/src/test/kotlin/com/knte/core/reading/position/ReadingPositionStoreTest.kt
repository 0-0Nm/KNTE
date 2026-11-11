package com.knte.core.reading.position

import com.knte.core.reading.ReadingHash
import com.knte.core.reading.ReadingPosition
import com.knte.core.reading.ReadingScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ReadingPositionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fails(block: () -> Unit) {
        try { block(); fail("expected refusal") } catch (e: Exception) { }
    }
    private fun position(bookId: String = "b-01234567", offset: Int = 5) =
        ReadingPosition("素问", bookId, "a".repeat(64), "c-0001", "f".repeat(64), offset, "2026-11-11T22:01:07")

    @Test fun inMemorySaveLoadList() {
        val s = InMemoryReadingPositionStore()
        val p = position()
        s.save(p)
        assertEquals(p, s.load(ReadingScope("素问"), p.bookId))
        assertNull(s.load(ReadingScope("w-other"), p.bookId))
        assertEquals(listOf(p), s.list(ReadingScope("素问")))
        assertTrue(s.list(ReadingScope("w-other")).isEmpty())
    }

    @Test fun fileStoreSaveReadbackAndNoBookContent() {
        val dir = tmp.newFolder()
        FileReadingPositionStore(dir).save(position())
        val reloaded = FileReadingPositionStore(dir).load(ReadingScope("素问"), "b-01234567")
        assertEquals(position(), reloaded)
        val file = dir.listFiles()!!.single()
        val text = String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        assertTrue(text.contains("\"schema\":1"))
        assertFalse(text.contains("正文")) // 只存指纹/offset，不存书内容
        assertTrue(file.length() <= FileReadingPositionStore.MAX_FILE_BYTES)
    }

    @Test fun fileStoreRejectsDuplicateBookIds() {
        val dir = tmp.newFolder()
        val scope = ReadingScope("素问")
        val name = "positions-" +
            ReadingHash.sha256("素问".toByteArray(Charsets.UTF_8)).substring(0, 12) + ".json"
        val rev = "a".repeat(64)
        val fp = "f".repeat(64)
        val row = "{\"world_ref\":\"素问\",\"book_id\":\"b-01234567\",\"revision\":\"$rev\"," +
            "\"chapter_id\":\"c-0001\",\"chapter_fingerprint\":\"$fp\",\"offset\":5," +
            "\"at\":\"2026-11-11T22:01:07\"}"
        File(dir, name).writeText("{\"schema\":1,\"scope\":\"素问\",\"positions\":[$row,$row]}")
        fails { FileReadingPositionStore(dir).list(scope) }
    }

    @Test fun fileStoreRejectsBadSchemaAndScope() {
        val dir = tmp.newFolder()
        val scope = ReadingScope("素问")
        val name = "positions-" +
            ReadingHash.sha256("素问".toByteArray(Charsets.UTF_8)).substring(0, 12) + ".json"
        val f = File(dir, name)
        f.writeText("{\"schema\":2,\"scope\":\"素问\",\"positions\":[]}")
        fails { FileReadingPositionStore(dir).list(scope) }
        f.writeText("{\"schema\":1,\"scope\":\"别的\",\"positions\":[]}")
        fails { FileReadingPositionStore(dir).list(scope) }
    }

    @Test fun fileStoreBookCountLimit() {
        val dir = tmp.newFolder()
        val s = FileReadingPositionStore(dir)
        for (i in 1..500) s.save(position(bookId = "b-" + i.toString().padStart(8, '0')))
        assertEquals(500, s.list(ReadingScope("素问")).size)
        fails { s.save(position(bookId = "b-" + 501.toString().padStart(8, '0'))) }
    }

    @Test fun scopeIsolation() {
        val dir = tmp.newFolder()
        val s = FileReadingPositionStore(dir)
        val a = position()
        val other = position(bookId = "b-abcdef90").copy(worldRef = "w-other")
        s.save(a)
        s.save(other)
        assertEquals(listOf(a), s.list(ReadingScope("素问")))
        assertEquals(listOf(other), s.list(ReadingScope("w-other")))
        assertEquals(2, dir.listFiles()!!.size) // 每作用域一个小文件
    }
}
