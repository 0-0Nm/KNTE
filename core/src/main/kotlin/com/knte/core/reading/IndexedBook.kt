package com.knte.core.reading

/** 目录条目：只指向真实存在的原文范围。 */
data class TocEntry(val chapterId: String, val ordinal: Int, val title: String,
    val depth: Int, val line: Int, val offset: Int)

/** 一章的精确原文范围与指纹；范围是原文的连续切片，绝无生成内容。 */
data class ChapterRef(val chapterId: String, val ordinal: Int, val title: String, val depth: Int,
    val line: Int, val startUtf16: Int, val endUtf16: Int, val startByte: Int, val endByte: Int,
    val fingerprint: String, val preamble: Boolean, val wholeTextSingleChapter: Boolean)

/** 章节原文：revision/章节/精确范围/字节指纹全部绑定，可核验。 */
class ChapterText(val revision: String, val chapter: ChapterRef, val slice: TextSlice) {
    init {
        require(slice.sha256 == chapter.fingerprint && slice.startUtf16 == chapter.startUtf16 &&
            slice.endUtf16 == chapter.endUtf16) { "CHAPTER_BINDING_MISMATCH" }
    }
    val text: String get() = slice.text
}

/** 原文切片：revision + chapterId + 精确范围绑定，可对照原字节核验。 */
class ChapterSlice(val revision: String, val chapterId: String, val slice: TextSlice) {
    val text: String get() = slice.text
}

/** 只读导入的书：身份 + 原文 + 章节索引。读章节/切片是纯查找，绝不自动生成、
 * 上传或持久化任何东西；选择章节不触发任何副作用。 */
class IndexedBook internal constructor(val identity: BookIdentity, val format: String,
    val text: BookText, val index: ChapterIndex) {
    val revision: String get() = identity.revision
    val toc: List<TocEntry> = index.headings.map {
        TocEntry(it.chapterId, it.ordinal, it.title, it.depth, it.line, it.offset)
    }

    fun chapter(chapterId: String): ChapterRef {
        val h = index.heading(chapterId)
        val end = index.headings.getOrNull(h.ordinal)?.offset ?: text.sourceChars
        val slice = text.slice(h.offset, end)
        return ChapterRef(h.chapterId, h.ordinal, h.title, h.depth, h.line,
            h.offset, end, slice.startByte, slice.endByte,
            ReadingHash.sha256(slice.bytesCopy()), h.preamble,
            index.singleWholeTextChapter && h.ordinal == 1)
    }

    fun readChapter(chapterId: String): ChapterText {
        val ref = chapter(chapterId)
        return ChapterText(revision, ref, text.slice(ref.startUtf16, ref.endUtf16))
    }

    fun readSlice(chapterId: String, startUtf16: Int, endUtf16: Int): ChapterSlice {
        val ref = chapter(chapterId)
        require(startUtf16 in ref.startUtf16..ref.endUtf16) { "SLICE_RANGE_INVALID" }
        require(endUtf16 in startUtf16..ref.endUtf16) { "SLICE_RANGE_INVALID" }
        return ChapterSlice(revision, chapterId, text.slice(startUtf16, endUtf16))
    }
}
