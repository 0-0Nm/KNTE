package com.knte.preview.reference

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knte.core.reference.*
import com.knte.core.reference.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val PREFIX="knte.isolated.reference."
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
        _state.update{it.copy(busy=true,activity=ReferenceActivity.READING,notice=null)}
        viewModelScope.launch {
            try {
                val pair=withContext(ioDispatcher){val fresh=factory();fresh to fresh.snapshot()}
                api=pair.first
                _state.update{it.copy(loaded=true,busy=false,activity=ReferenceActivity.IDLE,readFailed=false,snapshot=pair.second,
                    reviewRequired=it.reviewRequired || pair.second.hasPending,
                    error=if(it.reviewRequired || pair.second.hasPending) "存在待核对写入，禁止新写入。" else null)}
            }catch(e:Exception){
                if(e is CancellationException)throw e
                _state.update{it.copy(busy=false,activity=ReferenceActivity.IDLE,readFailed=true,
                    error="读取失败；未取得新的快照，不视为空库。可重读，草稿不变。")}
            }
        }
    }
    fun begin(pool:ReferencePool) {
        if(!_state.value.loaded || _state.value.busy || _state.value.reviewRequired || _state.value.draft!=null)return
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
        val summary = buildString {
            append("创建").append(if(d.pool==ReferencePool.MEMO) "备忘" else "吃一堑")
            append("：").append(d.title)
            if(d.pool==ReferencePool.MEMO) append("\n期限：").append(d.due.ifEmpty { "无期限" })
            else append("\n人工确认次数：").append(d.count)
            append("\n正文：\n").append(d.body.ifEmpty { "（空正文）" })
        }
        prepare(d.id,summary) {
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
        _state.update{it.copy(busy=true,activity=ReferenceActivity.PREPARING,error=null,notice=null)}
        viewModelScope.launch {
            try {
                val p=withContext(ioDispatcher){build()}
                _state.update{it.copy(busy=false,activity=ReferenceActivity.IDLE,confirmation=ReferenceConfirmation(p,id,label))}
            }catch(e:Exception){fail(e,"准备失败；尚未发起业务提交，草稿保留。请检查输入或重读来源")}
        }
    }
    fun confirm() {
        val c=_state.value.confirmation ?: return
        if(_state.value.busy || _state.value.reviewRequired)return
        handle[PREFIX+"writing"]=true;_state.update{it.copy(busy=true,activity=ReferenceActivity.CONFIRMING,confirmation=null,error=null,notice=null)}
        viewModelScope.launch {
            var checkingReadback=false
            try {
                val pair=withContext(ioDispatcher){
                    store().confirm(c.plan,c.plan.confirmation)
                    checkingReadback=true
                    val fresh=factory();val snapshot=fresh.snapshot()
                    check(!snapshot.hasPending && snapshot.head==c.plan.afterHash){"独立重读未确认"}
                    check(snapshot.entries.any{it.id==c.id}){"目标缺失"}
                    fresh to snapshot
                }
                api=pair.first;clearDraft();handle[PREFIX+"writing"]=false
                _state.update{it.copy(busy=false,activity=ReferenceActivity.IDLE,readFailed=false,loaded=true,draft=null,snapshot=pair.second,error=null,
                    notice="隔离引用库已写入并独立重读；不代表正式四文件源已接通。")}
            }catch(e:Exception){
                if(e is CancellationException)throw e
                _state.update{it.copy(busy=false,activity=ReferenceActivity.IDLE,reviewRequired=true,
                    error=if(checkingReadback) "提交后的独立读取核对失败；结果仍未确认，草稿与安全锁保留，不自动重试。"
                    else "确认失败；写入结果未知，草稿与安全锁保留，不自动重试。")}
            }
        }
    }
    fun dismissConfirmation()=cancelThen {}
    /** Navigation continuation runs only after any durable cancellation succeeds. */
    fun discard(afterDiscard: () -> Unit = {}) {
        if(_state.value.reviewRequired)return
        cancelThen {
            clearDraft()
            _state.update{it.copy(draft=null,notice="草稿已放弃，已存记录未删除。")}
            afterDiscard()
        }
    }
    private fun cancelThen(next:()->Unit) {
        if(_state.value.busy || _state.value.reviewRequired)return
        val c=_state.value.confirmation
        if(c==null){next();return}
        handle[PREFIX+"writing"]=true
        _state.update{it.copy(busy=true,activity=ReferenceActivity.CANCELLING,confirmation=null,error=null,notice=null)}
        viewModelScope.launch {
            try {
                withContext(ioDispatcher){store().cancel(c.plan)}
                handle[PREFIX+"writing"]=false
                _state.update{it.copy(busy=false,activity=ReferenceActivity.IDLE,
                    notice="已取消本次计划：不提交业务变更，隔离库已记录取消状态。草稿保留。")}
                next()
            }catch(e:Exception){
                if(e is CancellationException)throw e
                handle[PREFIX+"writing"]=true
                _state.update{it.copy(busy=false,activity=ReferenceActivity.IDLE,reviewRequired=true,
                    error="取消失败；取消状态未确认，草稿与安全锁保留，不继续导航或复用旧确认。")}
            }
        }
    }
    fun classifyPending() {
        if(_state.value.busy)return
        _state.update{it.copy(busy=true,activity=ReferenceActivity.CLASSIFYING,error=null,notice=null)}
        viewModelScope.launch {
            try {
                val result=withContext(ioDispatcher){store().classifyPending()}
                _state.update{it.copy(busy=false,activity=ReferenceActivity.IDLE,notice=referencePendingMessage(result.state))}
            }catch(e:Exception){fail(e,"只读核对失败；结果仍未知，不执行恢复，保留安全锁")}
        }
    }
    private fun clearDraft(){listOf("draft","pool","id","title","body","due","count").forEach{handle.remove<Any?>(PREFIX+it)}}
    private fun fail(e:Exception,label:String){if(e is CancellationException)throw e;_state.update{it.copy(busy=false,activity=ReferenceActivity.IDLE,error="$label。")}}
    private fun stamp()=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
}
