package com.knte.core.reading

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** 父级 open_document 的只读导入门面：调用方交来选中的字节（或文件路径做有界
 * 只读读取）。本层绝不持久化书籍副本、绝不自动生成内容、绝不上传；选择章节
 * 是纯查找。本层只支持 UTF-8 TXT/Markdown。 */
object ReadingEngine {
    fun importBook(bytes: ByteArray, sourceKey: String, originalName: String, format: String): IndexedBook {
        require(format == "txt" || format == "md") { "FORMAT_UNSUPPORTED" }
        val text = BookTextCodec.decode(bytes)
        val identity = BookIdentity.bind(sourceKey, text.sha256, originalName)
        return IndexedBook(identity, format, text, ChapterIndexer.index(text, format))
    }

    /** 有界只读读取：不复制、不移动、不修改源文件。 */
    fun importBookFile(file: File, sourceKey: String, originalName: String, format: String): IndexedBook {
        require(format == "txt" || format == "md") { "FORMAT_UNSUPPORTED" }
        val path: Path = file.toPath().toAbsolutePath().normalize()
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "BOOK_FILE_INVALID" }
        require(Files.size(path) <= BookTextCodec.MAX_BOOK_BYTES) { "BOOK_SIZE_LIMIT" }
        return importBook(readBounded(path), sourceKey, originalName, format)
    }

    private fun readBounded(path: Path): ByteArray {
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val out = ByteArrayOutputStream()
            val block = ByteArray(8192)
            while (true) {
                val n = input.read(block)
                if (n < 0) break
                require(out.size() + n <= BookTextCodec.MAX_BOOK_BYTES) { "BOOK_READ_BOUND" }
                out.write(block, 0, n)
            }
            return out.toByteArray()
        }
    }
}
