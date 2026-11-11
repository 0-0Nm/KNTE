package com.knte.core.notes

import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** App-private bounded store. Cooperative locks + atomic replace, not a hostile-parent sandbox.
 * Pending files are retained and block reads; no automatic deletion/replay/recovery is attempted. */
class FileNoteRepository(directory: File) : NoteRepository {
    private val root = directory.toPath().toAbsolutePath().normalize()
    private val mutex = locks.computeIfAbsent(root.toString()) { Any() }

    init {
        synchronized(mutex) {
            require(directory.absoluteFile.toPath().normalize() == root)
            var ancestor: Path? = root
            while (ancestor != null) {
                check(!Files.isSymbolicLink(ancestor)) { "STORE_LINK_REJECTED" }
                ancestor = ancestor.parent
            }
            Files.createDirectories(root)
            check(root.toFile().canonicalFile.toPath() == root && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            val lock = root.resolve("store.lock")
            if (!Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createFile(lock) } catch (_: FileAlreadyExistsException) { }
            }
            regular(lock)
        }
    }

    override fun list(): List<Note> = locked {
        inventory().map(::read).sortedWith(compareByDescending<Note> { it.updatedAtMillis }.thenBy { it.id })
    }
    override fun get(id: String): Note? = locked {
        validateId(id); inventory()
        val file = root.resolve("$id.note")
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) read(file) else null
    }
    override fun create(title: String, body: String, nowMillis: Long): Note = locked {
        val files = inventory()
        check(files.size < MAX_RECORDS) { "STORE_CAPACITY" }
        val id = UUID.randomUUID().toString()
        val bytes = encode(id, title, body, nowMillis, nowMillis)
        publish(id, bytes, replace = false)
    }
    override fun update(id: String, expectedRevision: String, title: String, body: String, nowMillis: Long): Note = locked {
        validateId(id); inventory()
        require(expectedRevision.matches(Regex("[0-9a-f]{64}"))) { "REVISION_INVALID" }
        val old = read(root.resolve("$id.note"))
        check(old.revision == expectedRevision) { "REVISION_CONFLICT" }
        require(nowMillis >= old.updatedAtMillis) { "CLOCK_MOVED_BACK" }
        publish(id, encode(id, title, body, old.createdAtMillis, nowMillis), replace = true)
    }

    private fun publish(id: String, bytes: ByteArray, replace: Boolean): Note {
        val destination = root.resolve("$id.note")
        val pending = root.resolve("$id.pending")
        java.nio.channels.FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { out ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) out.write(buffer)
            out.force(true)
        }
        if (replace) {
            regular(destination)
            Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            check(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { "CREATE_CONFLICT" }
            Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE)
        }
        java.nio.channels.FileChannel.open(root, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        val result = read(destination)
        check(result.revision == hash(bytes)) { "WRITE_ACK_UNCONFIRMED" }
        return result
    }

    private fun inventory(): List<Path> {
        check(!Files.isSymbolicLink(root) && root.toFile().canonicalFile.toPath() == root) { "STORE_ROOT_CHANGED" }
        val files = mutableListOf<Path>()
        Files.newDirectoryStream(root).use { entries ->
            for (entry in entries) {
                regular(entry)
                val name = entry.fileName.toString()
                check(!name.endsWith(".pending")) { "PENDING_WRITE_REVIEW_REQUIRED" }
                if (name == "store.lock") { check(Files.size(entry) == 0L); continue }
                check(name.endsWith(".note")) { "UNKNOWN_STORE_ENTRY" }
                validateId(name.removeSuffix(".note"))
                files.add(entry)
                check(files.size <= MAX_RECORDS) { "STORE_CAPACITY" }
            }
        }
        return files
    }
    private fun regular(path: Path) {
        check(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "STORE_SPECIAL_FILE" }
    }
    private fun read(path: Path): Note {
        regular(path)
        val size = Files.size(path)
        check(size in 1..MAX_FILE_BYTES.toLong()) { "RECORD_SIZE_INVALID" }
        val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val out = ByteArrayOutputStream()
            val block = ByteArray(8192)
            while (true) {
                val n = input.read(block)
                if (n < 0) break
                check(n > 0 && out.size() + n <= MAX_FILE_BYTES) { "RECORD_READ_BOUND" }
                out.write(block, 0, n)
            }
            out.toByteArray()
        }
        check(bytes.size.toLong() == size && bytes.size >= 40) { "RECORD_CHANGED" }
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        check(MessageDigest.isEqual(digest(payload), bytes.copyOfRange(bytes.size - 32, bytes.size))) { "RECORD_CHECKSUM" }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            check(input.readInt() == MAGIC && input.readInt() == 1) { "RECORD_VERSION" }
            val id = text(input, 64); validateId(id)
            check(path.fileName.toString() == "$id.note") { "RECORD_IDENTITY" }
            val created = input.readLong(); val updated = input.readLong()
            val title = text(input, 1024); val body = text(input, MAX_BODY_BYTES)
            validateContent(title, body, created, updated)
            check(input.read() == -1) { "RECORD_TRAILING" }
            Note(id, title, body, created, updated, hash(bytes))
        }
    }
    private fun encode(id: String, title: String, body: String, created: Long, updated: Long): ByteArray {
        val normalized = title.trim()
        validateContent(normalized, body, created, updated)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(MAGIC); data.writeInt(1); writeText(data, id)
            data.writeLong(created); data.writeLong(updated); writeText(data, normalized); writeText(data, body)
        }
        val payload = out.toByteArray()
        return payload + digest(payload)
    }
    private fun validateContent(title: String, body: String, created: Long, updated: Long) {
        require(title.isNotBlank() && title == title.trim() && title.codePointCount(0, title.length) <= 120) { "TITLE_INVALID" }
        strictBytes(title); require(strictBytes(body).size <= MAX_BODY_BYTES) { "BODY_LIMIT" }
        require(created >= 0 && updated >= created) { "TIMESTAMP_INVALID" }
    }
    private fun strictBytes(text: String): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }
    private fun writeText(out: DataOutputStream, value: String) { val bytes = strictBytes(value); out.writeInt(bytes.size); out.write(bytes) }
    private fun text(input: DataInputStream, max: Int): String {
        val size = input.readInt(); check(size in 0..max) { "TEXT_LENGTH" }
        val bytes = ByteArray(size); input.readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    private fun validateId(id: String) { require(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) { "ID_INVALID" } }
    private fun <T> locked(block: () -> T): T = synchronized(mutex) {
        regular(root.resolve("store.lock"))
        java.nio.channels.FileChannel.open(root.resolve("store.lock"), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            channel.lock().use { block() }
        }
    }
    companion object {
        private const val MAGIC = 0x4b4e5445
        const val MAX_BODY_BYTES = 256 * 1024
        const val MAX_RECORDS = 1000
        private const val MAX_FILE_BYTES = MAX_BODY_BYTES + 4096
        private val locks = ConcurrentHashMap<String, Any>()
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        private fun hash(bytes: ByteArray) = digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
