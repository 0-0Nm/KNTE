package com.knte.core.reading

/** One indexed chapter heading. [offset] is the legacy-compatible UTF-16 position of
 * the heading line start; [byteOffset] maps it to the original bytes. */
data class ChapterHeading(val ordinal: Int, val chapterId: String, val title: String,
    val depth: Int, val line: Int, val offset: Int, val byteOffset: Int, val preamble: Boolean)

/** Derived, read-only chapter index: real ranges of the source text, never generated
 * content. [sha256] is the book revision this index belongs to. */
class ChapterIndex internal constructor(val headings: List<ChapterHeading>, val sha256: String,
    val sourceChars: Int, val singleWholeTextChapter: Boolean, val duplicateTitles: List<String>) {
    val byId: Map<String, ChapterHeading> = headings.associateBy { it.chapterId }
    fun heading(chapterId: String): ChapterHeading =
        byId[chapterId] ?: throw IllegalArgumentException("CHAPTER_UNKNOWN")
}

/** Port of the legacy Java candidate BookChapters rules (ATX/setext headings, the
 * Chinese chapter-line pattern for TXT and MD, code-fence and YAML front-matter
 * skipping, the 160-char title bound), with two explicit divergences required by
 * the reading contract: an over-long title is REJECTED as a title (never shortened),
 * and more than MAX_HEADINGS chapters REJECTS the import (never a truncated index).
 * Duplicate titles are kept as distinct real chapters and disclosed in
 * [ChapterIndex.duplicateTitles] — never merged, never renamed. */
object ChapterIndexer {
    /** 旧 BookChapters.MAX_HEADINGS。 */
    const val MAX_HEADINGS = 500
    /** 旧 shorten() 上限；新规则：超限即不是章题，绝不截断。 */
    const val MAX_TITLE_CHARS = 160
    /** 旧 ReadingPosition.chapterAt 的默认实际标签。 */
    const val PREAMBLE_TITLE = "正文起点"

    private val atx = Regex("""^ {0,3}(#{1,6})[ \t]+(.+?)\s*$""")
    private val atxClosing = Regex("""[ \t]+#+[ \t]*$""")
    private val fenceOpen = Regex("""^ {0,3}(`{3,}|~{3,}).*$""")
    private val setextUnderline = Regex(""" {0,3}(?:=+|-+)[ \t]*""")
    private val chapterLine = Regex(
        """^(?:第[零一二三四五六七八九十百千〇两0-9]+[章节回卷部篇].*|(?:chapter|part)[ \t]+[0-9ivxlcdm]+(?:[ .:：\-].*)?)$""",
        RegexOption.IGNORE_CASE)

    fun index(book: BookText, format: String): ChapterIndex {
        require(format == "txt" || format == "md") { "FORMAT_UNSUPPORTED" }
        val text = book.text
        val scanned = scan(text, format)
        val bomLen = if (book.hasBom) 1 else 0
        val chapters = mutableListOf<ChapterHeading>()
        if (scanned.isEmpty()) {
            // TXT 无章题：全文 1 章标实际（旧默认标签），绝不虚构章题。
            chapters += ChapterHeading(1, chapterId(1), PREAMBLE_TITLE, 1, 1, 0, book.byteOffsetOf(0), true)
        } else {
            val first = scanned.first()
            val preambleBody = text.substring(minOf(bomLen, first.offset), first.offset)
            if (preambleBody.trim { it <= ' ' }.isNotEmpty()) {
                // 首个章题之前的实际内容按旧 chapterAt 语义归入「正文起点」章。
                chapters += ChapterHeading(1, chapterId(1), PREAMBLE_TITLE, 1, 1, 0, book.byteOffsetOf(0), true)
            }
            for (h in scanned) {
                chapters += ChapterHeading(chapters.size + 1, chapterId(chapters.size + 1),
                    h.title, h.depth, h.line, h.offset, book.byteOffsetOf(h.offset), false)
            }
        }
        val duplicates = chapters.groupingBy { it.title }.eachCount()
            .filterValues { it > 1 }.keys.toList()
        return ChapterIndex(chapters.toList(), book.sha256, book.sourceChars,
            scanned.isEmpty(), duplicates)
    }

    private fun chapterId(ordinal: Int): String = "c-" + ordinal.toString().padStart(4, '0')

    private class ScanHeading(val title: String, val depth: Int, val line: Int, val offset: Int)

    private fun scan(text: String, format: String): List<ScanHeading> {
        val result = mutableListOf<ScanHeading>()
        var begin = 0
        var lineNo = 0
        var prevOffset = 0
        var previous = ""
        var yaml = false
        var fence = ' '
        var fenceLen = 0
        while (begin <= text.length) {
            var i = begin
            while (i < text.length && text[i] != '\r' && text[i] != '\n') i++
            val found = i < text.length
            val end = i
            val line = text.substring(begin, end)
            var display = line
            if (lineNo == 0 && display.startsWith('\uFEFF')) display = display.substring(1)
            lineNo++
            val trim = display.trim { it <= ' ' }
            var title = ""
            var depth = 1
            var offset = begin
            var headingLine = lineNo
            if (format == "md" && lineNo == 1 && trim == "---") {
                yaml = true
                previous = ""
            } else if (yaml) {
                if (trim == "---" || trim == "...") yaml = false
                previous = ""
            } else if (format == "md" && fence != ' ') {
                var count = 0
                while (count < trim.length && trim[count] == fence) count++
                if (count >= fenceLen && trim.substring(count).trim { it <= ' ' }.isEmpty()) fence = ' '
                previous = ""
            } else {
                val open = if (format == "md") fenceOpen.matchEntire(display) else null
                if (open != null) {
                    fence = open.groupValues[1][0]
                    fenceLen = open.groupValues[1].length
                    previous = ""
                } else {
                    val heading = if (format == "md") atx.matchEntire(display) else null
                    if (heading != null) {
                        title = heading.groupValues[2].replaceFirst(atxClosing, "").trim { it <= ' ' }
                        depth = heading.groupValues[1].length
                        if (title.length > MAX_TITLE_CHARS) title = "" // 拒绝不截断
                    } else if (format == "md" && previous.isNotEmpty() &&
                        setextUnderline.matchEntire(display) != null) {
                        title = previous
                        depth = if (trim.isNotEmpty() && trim[0] == '=') 1 else 2
                        offset = prevOffset
                        headingLine = lineNo - 1
                    } else if (chapterLine.matchEntire(trim) != null && trim.length <= MAX_TITLE_CHARS) {
                        title = trim
                        depth = 1
                    }
                    if (title.isNotEmpty()) {
                        if (result.size >= MAX_HEADINGS) throw IllegalArgumentException("TOO_MANY_CHAPTERS")
                        result += ScanHeading(title, depth, headingLine, offset)
                        previous = ""
                    } else {
                        previous = if (trim.isNotEmpty() && display.length <= MAX_TITLE_CHARS &&
                            !display.startsWith("    ") && !display.startsWith("\t")) trim else ""
                        prevOffset = begin
                    }
                }
            }
            if (!found) break
            begin = end + (if (text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n') 2 else 1)
        }
        return result
    }
}
