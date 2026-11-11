package com.knte.preview.reading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knte.core.reading.*
import com.knte.core.reading.position.ReadingPositionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Byte input is transient; no original book content is written into app storage. */
class BookInput(val bytes: ByteArray, val sourceKey: String, val name: String, val format: String)
fun interface BookInputLoader { fun load(reference: String): BookInput }
data class ReadingUiState(val book: IndexedBook? = null, val selectedChapter: String? = null,
    val chapterText: String? = null, val busy: Boolean = false,
    val error: String? = null, val notice: String? = null, val positionSaved: Boolean = false)

/** Local reading is a separate explicit scope, not a fabricated teaching world. */
class ReadingViewModel(private val loader: BookInputLoader,
    private val positions: () -> ReadingPositionStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val _state = MutableStateFlow(ReadingUiState())
    val state = _state.asStateFlow()
    private val scope = ReadingScope("local-device-reading")
    private var generation = 0L
    private val lane = Mutex()
    fun select(reference: String) {
        val gen = ++generation
        _state.value = ReadingUiState(busy = true)
        viewModelScope.launch {
            try {
                lane.withLock {
                    if (gen != generation) return@withLock
                    val loaded = withContext(ioDispatcher) {
                        val input = loader.load(reference)
                        val book = ReadingEngine.importBook(input.bytes,input.sourceKey,input.name,input.format)
                        val resolution = ReadingPositions.resolve(book,scope,positions().load(scope,book.identity.bookId))
                        book to resolution
                    }
                    if (gen != generation) return@withLock
                    val book=loaded.first
                    val selected=when(val r=loaded.second) {
                        is PositionResolution.Restored -> r.chapter.chapterId
                        else -> book.toc.first().chapterId
                    }
                    val notice=when(loaded.second) {
                        is PositionResolution.Restored -> "已恢复上次显式记录的章节；不代表读完或学完。"
                        is PositionResolution.RelocationRequired -> "书籍内容或身份已变化，请重新定位；未套用旧偏移。"
                        else -> "本机阅读：不上传、不生成、不自动复制原书。"
                    }
                    _state.value=ReadingUiState(book,selected,book.readChapter(selected).text,notice=notice)
                }
            } catch(e:Exception) {
                if(e is CancellationException) throw e
                if(gen==generation) _state.value=ReadingUiState(error="书籍打开失败。请确认是支持的 UTF-8 TXT/Markdown，且未超过8MiB。")
            }
        }
    }
    fun chapter(id:String) {
        val s=_state.value;val book=s.book ?: return
        if(s.busy) return
        try {
            val text=book.readChapter(id).text
            _state.value=s.copy(selectedChapter=id,chapterText=text,error=null,positionSaved=false)
        } catch(_:Exception) { _state.value=s.copy(error="章节范围无法验证，没有显示其他章节作为替代。") }
    }
    /** Explicit position save stores IDs/hash/offset only, never the book text. */
    fun saveChapterPosition() {
        val s=_state.value;val book=s.book ?: return;val id=s.selectedChapter ?: return
        if(s.busy) return
        val gen=generation
        _state.value=s.copy(busy=true,error=null)
        viewModelScope.launch {
            try {
                lane.withLock {
                    if(gen!=generation) return@withLock
                    withContext(ioDispatcher) {
                        val at=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
                        val p=ReadingPositions.record(book,scope,id,book.chapter(id).startUtf16,at)
                        positions().save(p)
                        check(positions().load(scope,book.identity.bookId)==p) { "POSITION_READBACK_UNCONFIRMED" }
                    }
                    if(gen==generation) _state.value=_state.value.copy(busy=false,positionSaved=true,
                        notice="本机章节位置已保存并重读；只保存位置，不写原书。")
                }
            } catch(e:Exception) {
                if(e is CancellationException) throw e
                if(gen==generation) _state.value=_state.value.copy(busy=false,error="位置保存未确认；请保留当前章节并重试核对。")
            }
        }
    }
    fun closeBook() { generation++;_state.value=ReadingUiState(notice="已关闭本机书籍，没有删除或修改原书。") }
    fun selectionFailed() { _state.value=_state.value.copy(notice="选书已取消或读取授权不可用；未打开其他文件。") }
}
