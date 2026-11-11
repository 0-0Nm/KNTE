package com.knte.core.reading.position

import com.knte.core.reading.ReadingHash
import com.knte.core.reading.ReadingPosition
import com.knte.core.reading.ReadingScope
import com.knte.core.time.format.TimeJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/** 私有阅读位置仓储：只存小型非敏感字段（ID、hash 指纹、offset、时间戳），
 * 绝不写入书籍内容。写入必须读回确认；旧漂移由 ReadingPositions.resolve 拒绝
 * 套用（RelocationRequired），本仓储不做静默修复。 */
interface ReadingPositionStore {
    fun load(scope: ReadingScope, bookId: String): ReadingPosition?
    fun save(position: ReadingPosition)
    fun list(scope: ReadingScope): List<ReadingPosition>
}

/** 进程内实现：测试与无文件环境。 */
class InMemoryReadingPositionStore : ReadingPositionStore {
    private val map = ConcurrentHashMap<String, ReadingPosition>()
    override fun load(scope: ReadingScope, bookId: String): ReadingPosition? = map[key(scope, bookId)]
    override fun save(position: ReadingPosition) { map[keyOf(position)] = position }
    override fun list(scope: ReadingScope): List<ReadingPosition> =
        map.values.filter { it.worldRef == scope.worldRef }.sortedBy { it.bookId }
    private fun key(scope: ReadingScope, bookId: String): String = scope.worldRef + "\n" + bookId
    private fun keyOf(position: ReadingPosition): String = position.worldRef + "\n" + position.bookId
}

/** 文件实现：每个世界作用域一个小 JSON 文件（schema 1，沿用旧 ReadingPositionIndex
 * 限额：≤500 本、≤512KiB）。原子写（临时文件+改名）后全量读回比对确认。 */
class FileReadingPositionStore(directory: File) : ReadingPositionStore {
    private val root: Path = directory.toPath().toAbsolutePath().normalize()
    private val mutex = locks.computeIfAbsent(root.toString()) { Any() }

    init {
        synchronized(mutex) {
            var p: Path? = root
            while (p != null) {
                check(!Files.isSymbolicLink(p)) { "STORE_ROOT_LINK_REJECTED" }
                p = p.parent
            }
            Files.createDirectories(root)
        }
    }

    override fun load(scope: ReadingScope, bookId: String): ReadingPosition? =
        synchronized(mutex) { positions(fileFor(scope), scope).firstOrNull { it.bookId == bookId } }

    override fun list(scope: ReadingScope): List<ReadingPosition> =
        synchronized(mutex) { positions(fileFor(scope), scope).sortedBy { it.bookId } }

    override fun save(position: ReadingPosition): Unit = synchronized(mutex) {
        val scope = ReadingScope(position.worldRef)
        val file = fileFor(scope)
        val merged = (positions(file, scope).filter { it.bookId != position.bookId } + position)
            .sortedBy { it.bookId }
        require(merged.size <= MAX_BOOKS) { "POSITION_COUNT_LIMIT" }
        val bytes = TimeJson.encode(encode(scope, merged)).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "POSITION_FILE_LIMIT" }
        writeAtomically(file, bytes)
        val readBack = positions(file, scope)
        if (readBack != merged) throw IllegalStateException("STORE_READBACK_MISMATCH")
    }

    private fun fileFor(scope: ReadingScope): Path = root.resolve(
        "positions-" + ReadingHash.sha256(scope.worldRef.toByteArray(Charsets.UTF_8)).substring(0, 12) + ".json")

    private fun positions(file: Path, scope: ReadingScope): List<ReadingPosition> {
        check(!Files.isSymbolicLink(root) && root.toFile().canonicalFile.toPath() == root) { "STORE_ROOT_CHANGED" }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        check(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { "STORE_FILE_INVALID" }
        require(Files.size(file) <= MAX_FILE_BYTES) { "POSITION_FILE_LIMIT" }
        val bytes = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
            val out = ByteArrayOutputStream()
            val block = ByteArray(8192)
            while (true) {
                val n = input.read(block)
                if (n < 0) break
                require(n > 0 && out.size() + n <= MAX_FILE_BYTES) { "POSITION_FILE_LIMIT" }
                out.write(block, 0, n)
            }
            out.toByteArray()
        }
        require(bytes.isNotEmpty()) { "POSITION_FILE_EMPTY" }
        val text = try {
            TimeJson.decodeUtf8Strict(bytes)
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("POSITION_FILE_UTF8", e)
        }
        val obj = TimeJson.parse(text) as? TimeJson.Value.Obj
            ?: throw IllegalArgumentException("POSITION_FILE_NOT_OBJECT")
        val schema = obj.entries.firstOrNull { it.first == "schema" }?.second as? TimeJson.Value.Num
            ?: throw IllegalArgumentException("POSITION_SCHEMA_UNSUPPORTED")
        require(schema.literal == "1") { "POSITION_SCHEMA_UNSUPPORTED" }
        val scopeField = obj.entries.firstOrNull { it.first == "scope" }?.second as? TimeJson.Value.Str
            ?: throw IllegalArgumentException("POSITION_SCOPE_MISMATCH")
        require(scopeField.text == scope.worldRef) { "POSITION_SCOPE_MISMATCH" }
        val rows = obj.entries.firstOrNull { it.first == "positions" }?.second as? TimeJson.Value.Arr
            ?: throw IllegalArgumentException("POSITION_FIELD_INVALID")
        require(rows.items.size <= MAX_BOOKS) { "POSITION_COUNT_LIMIT" }
        val result = mutableListOf<ReadingPosition>()
        val seen = HashSet<String>()
        for (row in rows.items) {
            val parsed = parsePosition(row)
            require(parsed.worldRef == scope.worldRef) { "POSITION_SCOPE_MISMATCH" }
            require(seen.add(parsed.bookId)) { "POSITION_DUPLICATE_BOOK" }
            result += parsed
        }
        return result
    }

    private fun parsePosition(row: TimeJson.Value): ReadingPosition {
        val obj = row as? TimeJson.Value.Obj ?: throw IllegalArgumentException("POSITION_ROW_NOT_OBJECT")
        fun field(key: String): TimeJson.Value = obj.entries.firstOrNull { it.first == key }?.second
            ?: throw IllegalArgumentException("POSITION_FIELD_INVALID")
        fun text(key: String): String = (field(key) as? TimeJson.Value.Str)?.text
            ?: throw IllegalArgumentException("POSITION_FIELD_INVALID")
        fun int(key: String): Int {
            val n = field(key) as? TimeJson.Value.Num
                ?: throw IllegalArgumentException("POSITION_FIELD_INVALID")
            require(n.literal.matches(NUMBER)) { "POSITION_FIELD_INVALID" }
            return n.literal.toInt()
        }
        return ReadingPosition(text("world_ref"), text("book_id"), text("revision"),
            text("chapter_id"), text("chapter_fingerprint"), int("offset"), text("at"))
    }

    private fun encode(scope: ReadingScope, positions: List<ReadingPosition>): TimeJson.Value =
        TimeJson.Value.Obj(listOf(
            "schema" to TimeJson.Value.Num("1"),
            "scope" to TimeJson.Value.Str(scope.worldRef),
            "positions" to TimeJson.Value.Arr(positions.map { p ->
                TimeJson.Value.Obj(listOf(
                    "world_ref" to TimeJson.Value.Str(p.worldRef),
                    "book_id" to TimeJson.Value.Str(p.bookId),
                    "revision" to TimeJson.Value.Str(p.revision),
                    "chapter_id" to TimeJson.Value.Str(p.chapterId),
                    "chapter_fingerprint" to TimeJson.Value.Str(p.chapterFingerprint),
                    "offset" to TimeJson.Value.Num(p.offset.toString()),
                    "at" to TimeJson.Value.Str(p.at)))
            })))

    private fun writeAtomically(file: Path, bytes: ByteArray) {
        val temp = Files.createTempFile(root, "positions-", ".tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(temp) }
            throw e
        }
    }

    companion object {
        /** 旧 ReadingPositionIndex.MAX_BOOKS。 */
        const val MAX_BOOKS = 500
        /** 旧 ReadingPositionIndex.MAX_BYTES。 */
        const val MAX_FILE_BYTES = 524288
        private val locks = ConcurrentHashMap<String, Any>()
        private val NUMBER = Regex("[0-9]+")
    }
}
