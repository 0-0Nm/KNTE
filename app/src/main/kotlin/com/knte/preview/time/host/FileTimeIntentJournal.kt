package com.knte.preview.time.host

import com.knte.core.time.control.TimeIntentJournal
import com.knte.core.time.control.TimePendingIntent
import com.knte.core.time.format.TimeJson
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Stable TIME_JOURNAL_* failure. The message always starts with the code so the controller's
 *  generic wrap() and any reviewer see the same stable signal; the original cause is kept. */
class TimeIntentJournalException(
    val code: String,
    message: String,
    cause: Throwable? = null
) : RuntimeException("$code: $message", cause)

/**
 * App-owned durable [TimeIntentJournal]: one strictly bounded strict-JSON file per pending
 * intent, in a caller-injected, app-private directory.
 *
 * ROOT CONTRACT (host duty, verified defensively here):
 * - The host injects the app-private SIBLING leaf `filesDir.canonicalFile/isolated-time-intents-v1`.
 *   The journal root is NEVER inside the time ledger root: the ledger inventory rejects foreign
 *   entries, and a journal directory must never share or nest into that root.
 * - [com.knte.core.time.control.TimePendingIntent] carries no sourceLabel: the root identity is a
 *   deployment binding made by the single app authority that wires the host. It is NOT an
 *   authentication boundary and MUST NOT be used as one; different journal roots must never be
 *   mixed, copied or replayed across stores.
 * - Path rules mirror the project store rules: absolute normalized path, no symlink in any
 *   ancestor, canonical-path equality, dedicated directory (any foreign entry fails closed).
 *
 * PERSISTENCE MODEL (POSIX, honestly scoped):
 * - save: validate -> refuse on leftover temp/foreign entries -> write a uniquely named temp file
 *   (CREATE_NEW) -> fsync file (force(true)) -> bounded read-back byte comparison -> atomic
 *   rename (ATOMIC_MOVE) -> fsync directory. Crash between temp write and rename leaves the temp
 *   file in place: it is NEVER auto-deleted by name, and its presence fails every later
 *   loadAll/save closed until a human removes it. This is crash-recovery-by-refusal within one
 *   filesystem; no absolute power-loss guarantee is claimed (fsync semantics belong to the OS).
 * - Same nonce + identical full content is an idempotent no-op; same nonce + ANY different field
 *   is refused (TIME_JOURNAL_NONCE_CONFLICT), never an overwrite.
 * - clear(nonce) deletes exactly the one nonce-bound file after re-validating it (a corrupt or
 *   foreign-bound target is refused, not deleted); a missing entry is an idempotent no-op. It
 *   never touches the ledger, never batch-clears.
 * - loadAll fails closed on: symlink/foreign entry, leftover temp, oversize file, non-UTF-8
 *   bytes, JSON violations, unknown op, missing/extra keys, null/blank required fields,
 *   op/shape mismatch (start must carry null end/endDate; stop must carry non-blank end),
 *   nonce/filename mismatch, count above budget. Nothing is skipped, nothing silently deleted.
 * - Budgets: at most [MAX_INTENTS] entries, at most [MAX_ENTRY_BYTES] bytes per entry, string
 *   fields length-bounded (project at most 100 chars per project rule). Over-budget is refused,
 *   never trimmed. eventId/token/recordId are accepted as opaque non-blank strings (the real
 *   interface allows legacy non-UUID ids); only the nonce is constrained because it is bound to
 *   the file name ([A-Za-z0-9_-]{1,128}, no dot: no traversal, no hidden names).
 * - Locking is a cooperative SAME-PROCESS lock per canonical root (mirroring the repository);
 *   no cross-process CAS is claimed or invented.
 */
class FileTimeIntentJournal private constructor(
    private val root: Path,
    private val maxIntents: Int,
    private val maxEntryBytes: Int
) : TimeIntentJournal {

    constructor(directory: File) : this(openRoot(directory), MAX_INTENTS, MAX_ENTRY_BYTES)

    /** Test-only budget override so capacity behaviour is exercised without 64 real entries. */
    internal constructor(directory: File, maxIntents: Int, maxEntryBytes: Int) :
        this(openRoot(directory), maxIntents, maxEntryBytes)

    companion object {
        const val MAX_INTENTS = 64
        const val MAX_ENTRY_BYTES = 16 * 1024
        const val MAX_FIELD_CHARS = 256
        const val MAX_PROJECT_CHARS = 100
        private const val PREFIX = "intent-"
        private const val SUFFIX = ".json"
        private const val TMP_MARK = ".json.tmp-"
        private val NONCE = Regex("[A-Za-z0-9_-]{1,128}")
        private val ENTRY_NAME = Regex("intent-[A-Za-z0-9_-]{1,128}\\.json")
        private val locks = ConcurrentHashMap<String, Any>()

        private fun fail(code: String, message: String, cause: Throwable? = null): Nothing =
            throw TimeIntentJournalException(code, message, cause)

        /** Same validation family as the isolated store: normalized absolute path, no symlink in
         *  the ancestor chain, canonical equality, dedicated real directory. */
        private fun openRoot(directory: File): Path {
            val path = directory.toPath().toAbsolutePath().normalize()
            if (path.toString() != directory.absoluteFile.toPath().normalize().toString()) {
                fail("TIME_JOURNAL_PATH_INVALID", "journal根路径不一致: $directory")
            }
            var ancestor: Path? = path
            while (ancestor != null) {
                if (Files.isSymbolicLink(ancestor)) {
                    fail("TIME_JOURNAL_LINK_REJECTED", "journal根祖先链存在符号链接: $ancestor")
                }
                ancestor = ancestor.parent
            }
            Files.createDirectories(path)
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                fail("TIME_JOURNAL_NOT_DIRECTORY", "journal根不是目录: $path")
            }
            if (path.toFile().canonicalFile.toPath() != path) {
                fail("TIME_JOURNAL_ROOT_CHANGED", "journal根canonical路径漂移: $path")
            }
            return path
        }
    }

    private val mutex = locks.computeIfAbsent(root.toString()) { Any() }

    /** Stable identity of this journal's root (the single-app-authority binding; display only). */
    val rootLabel: String = root.toString()

    // ---- TimeIntentJournal --------------------------------------------------

    override fun save(intent: TimePendingIntent): Unit = synchronized(mutex) {
        validateIntent(intent)
        checkRoot()
        val target = fileFor(intent.nonce)
        val present = inventoryNames() // fails closed on foreign entries and leftover temps
        // Read-and-validate EVERY existing entry before writing: a journal that cannot be read
        // blocks writes too (fail closed) — never "write around" a corrupt entry.
        val existingByNonce = present.associateWith { readEntry(fileFor(it)) }
        val current = existingByNonce[intent.nonce]
        if (current != null) {
            if (current == intent) return@synchronized // idempotent same-content replay
            fail("TIME_JOURNAL_NONCE_CONFLICT", "同nonce不同内容拒绝覆盖: ${intent.nonce}")
        }
        if (present.size >= maxIntents) {
            fail("TIME_JOURNAL_CAPACITY", "意图数量超过上限$maxIntents，拒绝而非裁剪")
        }
        val bytes = encodeEntry(intent)
        val tmp = root.resolve("$PREFIX${intent.nonce}$TMP_MARK${UUID.randomUUID()}")
        writeNewForced(tmp, bytes)
        val back = readBounded(tmp, maxEntryBytes)
        if (!back.contentEquals(bytes)) {
            deleteOwnTemp(tmp)
            fail("TIME_JOURNAL_READBACK_MISMATCH", "临时文件回读不一致，未发布: ${intent.nonce}")
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            deleteOwnTemp(tmp)
            fail("TIME_JOURNAL_PUBLISH_FAILED", "原子发布失败，未声称已持久: ${e.message}", e)
        }
        forceDir(root)
    }

    override fun clear(nonce: String): Unit = synchronized(mutex) {
        validateNonce(nonce)
        checkRoot()
        val target = fileFor(nonce)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return@synchronized // documented idempotent: clearing an absent intent is a no-op
        }
        readEntry(target) // only a well-formed, filename-bound entry may be deleted
        Files.delete(target)
        forceDir(root)
    }

    override fun loadAll(): List<TimePendingIntent> = synchronized(mutex) {
        checkRoot()
        val names = inventoryNames()
        if (names.size > maxIntents) {
            fail("TIME_JOURNAL_CAPACITY", "磁盘上意图数量${names.size}超过上限$maxIntents，拒读不裁剪")
        }
        names.sorted().map { readEntry(fileFor(it)) }
    }

    // ---- internals ----------------------------------------------------------

    private fun fileFor(nonce: String): Path = root.resolve("$PREFIX$nonce$SUFFIX")

    /** Re-validate the root itself (same inventory family as the store: symlink/canonical). */
    private fun checkRoot() {
        if (Files.isSymbolicLink(root) || root.toFile().canonicalFile.toPath() != root) {
            fail("TIME_JOURNAL_ROOT_CHANGED", "journal根在运行期间被替换/漂移: $root")
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            fail("TIME_JOURNAL_NOT_DIRECTORY", "journal根不是目录: $root")
        }
    }

    /** Lists the nonces of all real entries. Any symlink, foreign name or leftover crash temp
     *  fails closed; the temp is preserved for a human, never deleted by name. */
    private fun inventoryNames(): List<String> {
        val nonces = mutableListOf<String>()
        Files.newDirectoryStream(root).use { stream ->
            for (entry in stream) {
                if (Files.isSymbolicLink(entry)) {
                    fail("TIME_JOURNAL_LINK_REJECTED", "journal内存在符号链接: ${entry.fileName}")
                }
                val name = entry.fileName.toString()
                if (ENTRY_NAME.matches(name)) {
                    if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        fail("TIME_JOURNAL_SPECIAL_FILE", "意图条目不是普通文件: $name")
                    }
                    nonces += name.removePrefix(PREFIX).removeSuffix(SUFFIX)
                    continue
                }
                if (name.startsWith(PREFIX) && name.contains(TMP_MARK)) {
                    fail("TIME_JOURNAL_LEFTOVER_TEMP",
                        "发现崩溃残留临时文件，保留待人工处理并封锁读写: $name")
                }
                fail("TIME_JOURNAL_FOREIGN_ENTRY", "journal目录存在外来条目: $name")
            }
        }
        return nonces
    }

    private fun validateNonce(nonce: String) {
        if (!NONCE.matches(nonce)) {
            fail("TIME_JOURNAL_NONCE_INVALID", "nonce不满足文件名绑定键空间[A-Za-z0-9_-]{1,128}: 长度${nonce.length}")
        }
    }

    /** Full shape/size validation before any byte is written. ids/tokens are opaque non-blank
     *  strings (legacy non-UUID ids stay legal); only the nonce is filename-bound. */
    private fun validateIntent(i: TimePendingIntent) {
        validateNonce(i.nonce)
        if (i.op != "start" && i.op != "stop") {
            fail("TIME_JOURNAL_UNKNOWN_OP", "未知op拒写: ${i.op}")
        }
        field(i.eventId, "eventId"); field(i.token, "token"); field(i.recordId, "recordId")
        field(i.date, "date"); field(i.start, "start")
        field(i.baselineRevision, "baselineRevision"); field(i.createdAt, "createdAt")
        if (i.project.isBlank() || i.project.codePointCount(0, i.project.length) > MAX_PROJECT_CHARS) {
            fail("TIME_JOURNAL_FIELD_INVALID", "project为空或超过$MAX_PROJECT_CHARS 字符")
        }
        if (i.op == "start") {
            if (i.end != null || i.endDate != null) {
                fail("TIME_JOURNAL_FIELD_INVALID", "start意图必须携带空end/endDate")
            }
        } else {
            val end = i.end
            if (end == null || end.isBlank() || end.length > MAX_FIELD_CHARS) {
                fail("TIME_JOURNAL_FIELD_INVALID", "stop意图end缺失/越界")
            }
            if (i.endDate != null && (i.endDate.isBlank() || i.endDate.length > MAX_FIELD_CHARS)) {
                fail("TIME_JOURNAL_FIELD_INVALID", "stop意图endDate越界")
            }
        }
    }

    private fun field(value: String, name: String) {
        if (value.isBlank() || value.length > MAX_FIELD_CHARS) {
            fail("TIME_JOURNAL_FIELD_INVALID", "字段$name 为空或超过$MAX_FIELD_CHARS 字符")
        }
    }

    /** Canonical strict-JSON encoding: exactly the 12 TimePendingIntent fields in fixed order,
     *  nullable end/endDate as JSON null; strict UTF-8; size budget enforced before writing. */
    private fun encodeEntry(i: TimePendingIntent): ByteArray {
        fun str(s: String) = TimeJson.Value.Str(s)
        fun opt(s: String?): TimeJson.Value = if (s == null) TimeJson.Value.Null else TimeJson.Value.Str(s)
        val obj = TimeJson.Value.Obj(
            listOf(
                "nonce" to str(i.nonce),
                "op" to str(i.op),
                "eventId" to str(i.eventId),
                "token" to str(i.token),
                "recordId" to str(i.recordId),
                "project" to str(i.project),
                "date" to str(i.date),
                "start" to str(i.start),
                "end" to opt(i.end),
                "endDate" to opt(i.endDate),
                "baselineRevision" to str(i.baselineRevision),
                "createdAt" to str(i.createdAt)
            )
        )
        val bytes = TimeJson.encodeUtf8Strict(TimeJson.encode(obj) + "\n")
        if (bytes.size > maxEntryBytes) {
            fail("TIME_JOURNAL_ENTRY_TOO_LARGE", "单条意图${bytes.size}字节超过上限$maxEntryBytes，拒写不裁剪")
        }
        return bytes
    }

    /** Strict bounded read + parse + full validation. ANY violation fails closed. */
    private fun readEntry(file: Path): TimePendingIntent {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            fail("TIME_JOURNAL_SPECIAL_FILE", "意图条目不是普通文件: ${file.fileName}")
        }
        val name = file.fileName.toString()
        if (!ENTRY_NAME.matches(name)) {
            fail("TIME_JOURNAL_FOREIGN_ENTRY", "意图文件名不符绑定规则: $name")
        }
        val bytes = try {
            readBounded(file, maxEntryBytes)
        } catch (e: TimeIntentJournalException) {
            throw e
        } catch (e: Exception) {
            fail("TIME_JOURNAL_CORRUPT", "条目读取失败: $name", e)
        }
        val text = try {
            TimeJson.decodeUtf8Strict(bytes)
        } catch (e: Exception) {
            fail("TIME_JOURNAL_CORRUPT", "条目非严格UTF-8: $name", e)
        }
        val obj = try {
            TimeJson.parse(text)
        } catch (e: Exception) {
            fail("TIME_JOURNAL_CORRUPT", "条目JSON不合法: $name", e)
        }
        if (obj !is TimeJson.Value.Obj) fail("TIME_JOURNAL_CORRUPT", "条目不是JSON对象: $name")
        val expected = setOf(
            "nonce", "op", "eventId", "token", "recordId", "project", "date", "start",
            "end", "endDate", "baselineRevision", "createdAt"
        )
        val keys = obj.entries.map { it.first }.toSet()
        if (keys != expected || obj.entries.size != expected.size) {
            fail("TIME_JOURNAL_CORRUPT", "条目键集不完整/含外来键: $name")
        }
        val map = obj.entries.toMap()
        fun req(key: String): String {
            val v = map.getValue(key)
            if (v !is TimeJson.Value.Str) fail("TIME_JOURNAL_CORRUPT", "字段$key 缺失或类型错误: $name")
            return v.text
        }
        fun opt(key: String): String? = when (val v = map.getValue(key)) {
            is TimeJson.Value.Str -> v.text
            TimeJson.Value.Null -> null
            else -> fail("TIME_JOURNAL_CORRUPT", "字段$key 类型错误: $name")
        }
        val intent = try {
            TimePendingIntent(
                nonce = req("nonce"), op = req("op"), eventId = req("eventId"), token = req("token"),
                recordId = req("recordId"), project = req("project"), date = req("date"),
                start = req("start"), end = opt("end"), endDate = opt("endDate"),
                baselineRevision = req("baselineRevision"), createdAt = req("createdAt")
            )
        } catch (e: TimeIntentJournalException) {
            throw e
        } catch (e: Exception) {
            fail("TIME_JOURNAL_CORRUPT", "条目字段读取失败: $name", e)
        }
        val boundNonce = name.removePrefix(PREFIX).removeSuffix(SUFFIX)
        if (intent.nonce != boundNonce) {
            fail("TIME_JOURNAL_NONCE_MISMATCH", "条目nonce与文件名不绑定: $name")
        }
        try {
            validateIntent(intent)
        } catch (e: TimeIntentJournalException) {
            fail("TIME_JOURNAL_CORRUPT", "条目字段语义不合法: $name (${e.code})", e)
        }
        return intent
    }

    private fun readBounded(path: Path, max: Int): ByteArray {
        if (Files.size(path) > max) {
            fail("TIME_JOURNAL_ENTRY_TOO_LARGE", "条目超过$max 字节: ${path.fileName}")
        }
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val block = ByteArray(8192)
            while (true) {
                val n = input.read(block)
                if (n < 0) break
                if (n == 0 || out.size() + n > max) {
                    fail("TIME_JOURNAL_ENTRY_TOO_LARGE", "条目读取越界: ${path.fileName}")
                }
                out.write(block, 0, n)
            }
            return out.toByteArray()
        }
    }

    private fun writeNewForced(path: Path, bytes: ByteArray) {
        try {
            FileChannel.open(
                path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        } catch (e: Exception) {
            fail("TIME_JOURNAL_WRITE_FAILED", "临时条目写入/落盘失败: ${e.message}", e)
        }
    }

    /** Only ever deletes the unique temp file THIS call created on a controlled failure path.
     *  Crash leftovers from other runs are never touched (they fail closed instead). */
    private fun deleteOwnTemp(tmp: Path) {
        try {
            Files.deleteIfExists(tmp)
        } catch (_: Exception) {
            // leave it: the next loadAll/save fails closed and names it for a human
        }
    }

    private fun forceDir(path: Path) {
        try {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
                it.force(true)
            }
        } catch (e: Exception) {
            fail("TIME_JOURNAL_FSYNC_FAILED", "目录落盘失败，不能声称已持久: ${e.message}", e)
        }
    }
}
