package com.knte.core.diary

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class DiaryEntry(val name: String, val document: DiaryDocument)
class DiaryWritePlan internal constructor(internal val rootId: String, val name: String,
    val expectedRevision: String?, document: DiaryDocument) {
    private val proposed = document.bytes()
    val proposedRevision: String = DiaryCodec.hash(proposed)
    val confirmation: String = DiaryCodec.hash(DiaryCodec.encode(
        "$rootId\n$name\n${expectedRevision ?: "CREATE"}\n$proposedRevision"))
    internal fun bytes(): ByteArray = proposed.clone()
}
/** LOCAL ISOLATED validation store, not SAF, not an authority bridge or external-writer CAS.
 * Same-process callers cooperate on a lock. Pending receipts fail closed; no automatic recovery.
 * Journal contains original/proposed user bytes: keep private, never log/export implicitly. */
class IsolatedDiaryRepository internal constructor(directory: File,
    private val phaseProbe: (String) -> Unit) {
    constructor(directory: File) : this(directory, {})
    private val root = directory.toPath().toAbsolutePath().normalize()
    private val mutex = locks.computeIfAbsent(root.toString()) { Any() }
    init { synchronized(mutex) {
        safeRoot(allowMissing = true); Files.createDirectories(root); safeRoot()
    } }
    fun list(): List<DiaryEntry> = synchronized(mutex) {
        inventory().sortedByDescending { it.fileName.toString() }.map { DiaryEntry(it.fileName.toString(), read(it)) }
    }
    fun get(name: String): DiaryEntry? = synchronized(mutex) {
        validName(name); inventory()
        val p = root.resolve(name)
        if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) DiaryEntry(name, read(p)) else null
    }
    fun planCreate(document: DiaryDocument): DiaryWritePlan = synchronized(mutex) {
        require(document.editable && document.metadata?.date != null) { "CREATE_METADATA_REQUIRED" }
        inventory()
        val name = document.metadata!!.date + ".md"
        validName(name)
        check(!Files.exists(root.resolve(name), LinkOption.NOFOLLOW_LINKS)) { "CREATE_CONFLICT" }
        DiaryWritePlan(root.toString(), name, null, document)
    }
    fun planReplaceBody(name: String, expectedRevision: String, body: String): DiaryWritePlan = synchronized(mutex) {
        validName(name); inventory()
        val old = read(root.resolve(name))
        check(old.revision == expectedRevision) { "REVISION_CONFLICT" }
        DiaryWritePlan(root.toString(), name, expectedRevision, DiaryCodec.replaceBody(old, body))
    }
    fun commit(plan: DiaryWritePlan, confirmation: String): DiaryEntry = synchronized(mutex) {
        require(plan.rootId == root.toString() && confirmation == plan.confirmation) { "CONFIRMATION_MISMATCH" }
        validName(plan.name); inventory()
        val target = root.resolve(plan.name)
        fun baseline(): ByteArray? {
            if (plan.expectedRevision == null) {
                check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "CREATE_CONFLICT" }; return null
            }
            val bytes = read(target).bytes()
            check(DiaryCodec.hash(bytes) == plan.expectedRevision) { "REVISION_CONFLICT" }
            return bytes
        }
        val before = baseline()
        val proposed = plan.bytes()
        check(DiaryCodec.hash(proposed) == plan.proposedRevision) { "PLAN_CHANGED" }
        val journal = root.resolve(".journal")
        if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(journal)
        check(Files.isDirectory(journal, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(journal))
        val attempt = journal.resolve(UUID.randomUUID().toString())
        Files.createDirectory(attempt)
        // A durable pending marker exists BEFORE any target write; failures retain it.
        val pending = root.resolve("${plan.name}.pending")
        writeNew(pending, DiaryCodec.encode(attempt.fileName.toString()))
        forceDir(root)
        phaseProbe("pending-durable")
        if (before != null) {
            writeNew(attempt.resolve("before.md"), before)
            check(readBounded(attempt.resolve("before.md")).contentEquals(before)) { "BEFORE_IMAGE_UNCONFIRMED" }
        }
        writeNew(attempt.resolve("proposed.md"), proposed)
        check(readBounded(attempt.resolve("proposed.md")).contentEquals(proposed)) { "PROPOSAL_UNCONFIRMED" }
        forceDir(attempt); forceDir(journal)
        baseline() // Last check: not an external-process compare-and-swap.
        if (before == null) {
            // CREATE_NEW cannot replace an existing destination. Partial failure stays pending.
            writeNew(target, proposed)
        } else {
            val staged = attempt.resolve("publish.md")
            writeNew(staged, proposed)
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        forceDir(root)
        phaseProbe("target-published")
        val actual = read(target)
        check(actual.revision == plan.proposedRevision && actual.bytes().contentEquals(proposed)) { "WRITE_RESULT_UNKNOWN" }
        writeNew(attempt.resolve("receipt.txt"), DiaryCodec.encode("verified\n${plan.name}\n${plan.proposedRevision}\n"))
        forceDir(attempt)
        phaseProbe("receipt-durable")
        Files.delete(pending) // Only our marker after full readback+receipt; no stale pending cleanup.
        forceDir(root)
        DiaryEntry(plan.name, actual)
    }
    private fun inventory(): List<Path> {
        safeRoot(); val result = mutableListOf<Path>(); var totalBytes = 0L
        Files.newDirectoryStream(root).use { stream ->
            for (p in stream) {
                val n = p.fileName.toString()
                check(!n.endsWith(".pending")) { "PENDING_REVIEW_REQUIRED" }
                if (n == ".journal") {
                    check(!Files.isSymbolicLink(p) && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) { "JOURNAL_CHANGED" }
                    continue
                }
                validName(n); regular(p)
                totalBytes += Files.size(p)
                check(totalBytes <= 16L * 1024 * 1024) { "DIARY_STORE_READ_BUDGET" }
                result.add(p)
                check(result.size <= 1000) { "DIARY_CAPACITY" }
            }
        }
        return result
    }
    private fun safeRoot(allowMissing: Boolean = false) {
        var p: Path? = root
        while (p != null) { check(!Files.isSymbolicLink(p)) { "ROOT_LINK_REJECTED" }; p = p.parent }
        if (!allowMissing || Files.exists(root, LinkOption.NOFOLLOW_LINKS))
            check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && root.toFile().canonicalFile.toPath() == root) { "ROOT_CHANGED" }
    }
    private fun validName(name: String) {
        require(name.endsWith(".md") && DiaryCodec.validDate(name.removeSuffix(".md"))) { "DIARY_NAME_INVALID" }
    }
    private fun regular(p: Path) {
        check(!Files.isSymbolicLink(p) && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) { "DIARY_SPECIAL_FILE" }
    }
    private fun read(p: Path): DiaryDocument = DiaryCodec.parse(readBounded(p))
    private fun readBounded(p: Path): ByteArray {
        regular(p)
        check(Files.size(p) <= DiaryCodec.MAX_FILE_BYTES) { "DIARY_SIZE_LIMIT" }
        return Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS).use { input ->
            val out = ByteArrayOutputStream(); val block = ByteArray(8192)
            while (true) {
                val n = input.read(block); if (n < 0) break
                check(n > 0 && out.size() + n <= DiaryCodec.MAX_FILE_BYTES) { "DIARY_READ_BOUND" }
                out.write(block, 0, n)
            }
            out.toByteArray()
        }
    }
    private fun writeNew(p: Path, bytes: ByteArray) {
        FileChannel.open(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }
    private fun forceDir(p: Path) {
        FileChannel.open(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    }
    companion object { private val locks = ConcurrentHashMap<String, Any>() }
}
