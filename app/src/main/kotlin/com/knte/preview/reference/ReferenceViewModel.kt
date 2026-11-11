package com.knte.preview.reference

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knte.core.reference.*
import com.knte.core.reference.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val PREFIX="knte.isolated.reference."
data class ReferenceDraft(val pool:ReferencePool,val id:String,val title:String="",
    val body:String="",val due:String="",val count:String="1") {
    val dirty:Boolean get()=title.isNotEmpty() || body.isNotEmpty() || due.isNotEmpty() || count!="1"
}
data class ReferenceConfirmation(val plan:ReferenceStorePlan,val id:String,val label:String)
data class ReferenceUiState(val loaded:Boolean=false,val snapshot:ReferenceStoreSnapshot?=null,
    val draft:ReferenceDraft?=null,val confirmation:ReferenceConfirmation?=null,val busy:Boolean=false,
    val reviewRequired:Boolean=false,val error:String?=null,val notice:String?=null) {
    val hasUnsavedChanges:Boolean get()=draft?.dirty==true
    val canLeave:Boolean get()=!busy && !hasUnsavedChanges
}
/** Explicit app-private validation aggregate. No formal-source import, AI merging or recovery. */
class ReferenceViewModel(private val factory:()->ReferenceStoreApi,
    private val handle:SavedStateHandle=SavedStateHandle(),
    private val ioDispatcher:CoroutineDispatcher=Dispatchers.IO):ViewModel() {
    private val _state=MutableStateFlow(ReferenceUiState());val state=_state.asStateFlow()
    private var api:ReferenceStoreApi?=null
    private fun store():ReferenceStoreApi=api ?: factory().also{api=it}
    init {
        if(handle.get<Boolean>(PREFIX+"draft")==true) {
            val pool=runCatching{ReferencePool.valueOf(handle.get<String>(PREFIX+"pool").orEmpty())}.getOrNull()
            val id=handle.get<String>(PREFIX+"id").orEmpty();val title=handle.get<String>(PREFIX+"title").orEmpty()
            val body=handle.get<String>(PREFIX+"body").orEmpty();val due=handle.get<String>(PREFIX+"due").orEmpty()
            val count=handle.get<String>(PREFIX+"count") ?: "1"
            if(pool!=null && id.matches(Regex("[mp]-[0-9a-f]{8}")) && title.length<=200 && body.length<=6000 && due.length<=10 && count.length<=6)
                _state.value=_state.value.copy(draft=ReferenceDraft(pool,id,title,body,due,count))
        }
        if(handle.get<Boolean>(PREFIX+"writing")==true) _state.value=_state.value.copy(reviewRequired=true,
            error="上次写入结果未确认；不会自动重试。可只读核对未决状态。")
        reload()
    }
    fun reload() {
        if(_state.value.busy || _state.value.confirmation!=null)return
        _state.update{it.copy(busy=true)}
        viewModelScope.launch {
            try {
                val pair=withContext(ioDispatcher){val fresh=factory();fresh to fresh.snapshot()}
                api=pair.first
                _state.update{it.copy(loaded=true,busy=false,snapshot=pair.second,
                    reviewRequired=it.reviewRequired || pair.second.hasPending,
                    error=if(it.reviewRequired || pair.second.hasPending) "存在待核对写入，禁止新写入。" else null)}
            }catch(e:Exception){fail(e,"读取失败，不视为空库")}
        }
    }
    fun begin(pool:ReferencePool) {
        if(_state.value.busy || _state.value.reviewRequired || _state.value.draft!=null)return
        persist(ReferenceDraft(pool,(if(pool==ReferencePool.MEMO)"m-" else "p-")+UUID.randomUUID().toString().replace("-","").take(8)))
    }
    fun change(title:String?=null,body:String?=null,due:String?=null,count:String?=null) {
        val s=_state.value;val d=s.draft ?: return
        if(s.busy || s.reviewRequired)return
        val next=d.copy(title=title ?: d.title,body=body ?: d.body,due=due ?: d.due,count=count ?: d.count)
        if(next.title.length>200 || next.body.length>6000 || next.due.length>10 || next.count.length>6) {
            cancelThen { _state.update{it.copy(notice="输入超出上限，原草稿保留。") } };return
        }
        cancelThen { persist(next) }
    }
    private fun persist(d:ReferenceDraft) {
        handle[PREFIX+"draft"]=true;handle[PREFIX+"pool"]=d.pool.name;handle[PREFIX+"id"]=d.id
        handle[PREFIX+"title"]=d.title;handle[PREFIX+"body"]=d.body;handle[PREFIX+"due"]=d.due;handle[PREFIX+"count"]=d.count
        _state.update{it.copy(draft=d,confirmation=null,error=null,notice=null)}
    }
    fun prepareCreate() {
        val d=_state.value.draft ?: return
        if(_state.value.busy || _state.value.reviewRequired || _state.value.confirmation!=null)return
        prepare(d.id,"创建${if(d.pool==ReferencePool.MEMO)"备忘" else "吃一堑"}：${d.title}") {
            val ts=stamp()
            if(d.pool==ReferencePool.MEMO) store().planCreateMemo(d.id,d.title,ts,due=d.due.ifEmpty{null},body=d.body.ifEmpty{null})
            else {
                require(d.count.matches(Regex("[1-9][0-9]{0,5}"))) { "次数须为1..100000整数" }
                val count=d.count.toInt();require(count in 1..100000)
                store().planCreatePitfall(d.id,d.title,ts,count,body=d.body.ifEmpty{null})
            }
        }
    }
    fun prepareDone(entry:ReferenceEntry.Memo)=prepare(entry.id,"完成备忘：${entry.title}"){store().planMemoDone(entry.id,stamp())}
    fun prepareArchive(entry:ReferenceEntry)=prepare(entry.id,"归档：${entry.title}（不删除历史，目前不支持恢复）"){store().planArchive(entry.id,stamp())}
    private fun prepare(id:String,label:String,build:()->ReferenceStorePlan) {
        val s=_state.value;if(s.busy || s.reviewRequired || s.confirmation!=null)return
        _state.update{it.copy(busy=true,error=null)}
        viewModelScope.launch {
            try {
                val p=withContext(ioDispatcher){build()}
                _state.update{it.copy(busy=false,confirmation=ReferenceConfirmation(p,id,label))}
            }catch(e:Exception){fail(e,"未写入，核对计划失败")}
        }
    }
    fun confirm() {
        val c=_state.value.confirmation ?: return
        if(_state.value.busy || _state.value.reviewRequired)return
        handle[PREFIX+"writing"]=true;_state.update{it.copy(busy=true,confirmation=null)}
        viewModelScope.launch {
            try {
                val pair=withContext(ioDispatcher){
                    store().confirm(c.plan,c.plan.confirmation)
                    val fresh=factory();val snapshot=fresh.snapshot()
                    check(!snapshot.hasPending && snapshot.head==c.plan.afterHash){"独立重读未确认"}
                    check(snapshot.entries.any{it.id==c.id}){"目标缺失"}
                    fresh to snapshot
                }
                api=pair.first;clearDraft();handle[PREFIX+"writing"]=false
                _state.update{it.copy(busy=false,loaded=true,draft=null,snapshot=pair.second,error=null,
                    notice="隔离引用库已写入并独立重读；不代表正式四文件源已接通。")}
            }catch(e:Exception){
                if(e is CancellationException)throw e
                _state.update{it.copy(busy=false,reviewRequired=true,error="写入结果未确认；草稿保留，不自动重试。")}
            }
        }
    }
    fun dismissConfirmation()=cancelThen {}
    fun discard()=cancelThen {clearDraft();_state.update{it.copy(draft=null,notice="草稿已放弃，已存记录未删除。")}}
    private fun cancelThen(next:()->Unit) {
        if(_state.value.busy)return
        val c=_state.value.confirmation
        if(c==null){next();return}
        _state.update{it.copy(busy=true,confirmation=null)}
        viewModelScope.launch {
            try {withContext(ioDispatcher){store().cancel(c.plan)};_state.update{it.copy(busy=false)};next()}
            catch(e:Exception){fail(e,"旧计划取消未确认；请先核对，不复用旧确认");_state.update{it.copy(reviewRequired=true)}}
        }
    }
    fun classifyPending() {
        if(_state.value.busy)return
        _state.update{it.copy(busy=true)}
        viewModelScope.launch {
            try {
                val result=withContext(ioDispatcher){store().classifyPending()}
                _state.update{it.copy(busy=false,notice="只读核对：${result.state}。未重发、未解锁、未删除未决标记。")}
            }catch(e:Exception){fail(e,"未决状态无法确认；不执行恢复")}
        }
    }
    private fun clearDraft(){listOf("draft","pool","id","title","body","due","count").forEach{handle.remove<Any?>(PREFIX+it)}}
    private fun fail(e:Exception,label:String){if(e is CancellationException)throw e;_state.update{it.copy(busy=false,error="$label；请检查输入或来源状态。")}}
    private fun stamp()=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
}
