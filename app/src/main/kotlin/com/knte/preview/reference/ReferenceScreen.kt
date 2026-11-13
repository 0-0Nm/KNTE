package com.knte.preview.reference

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.knte.core.reference.*
import java.time.LocalDate

@Composable
fun ReferenceScreen(vm:ReferenceViewModel,pool:ReferencePool=ReferencePool.MEMO) {
    val s by vm.state.collectAsStateWithLifecycle()
    var discard by rememberSaveable{mutableStateOf(false)}
    fun leave(){if(!s.busy && !s.reviewRequired){if(s.hasUnsavedChanges)discard=true else vm.discard()}}
    BackHandler(enabled=s.draft!=null){leave()}
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("放弃未保存草稿？")},
        text={Text("不会删除已存备忘或吃一堑。")},
        confirmButton={TextButton(onClick={discard=false;vm.discard()}){Text("放弃草稿")}},
        dismissButton={TextButton(onClick={discard=false}){Text("继续编辑")}})
    s.confirmation?.let{c->
        var details by remember(c.plan.token){mutableStateOf(false)}
        AlertDialog(onDismissRequest=vm::dismissConfirmation,title={Text("核对后写入")},
        text={Column(Modifier.verticalScroll(rememberScrollState())){
            Text(c.label);Text("范围：本App私有隔离引用库，非正式四文件源。取消不提交本次业务变更，但隔离库会记录取消状态。")
            TextButton(onClick={details=!details}){Text(if(details)"收起校验详情" else "校验详情")}
            if(details)Text("计划：${c.plan.token}\n基线：${c.plan.beforeSha256}",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(onClick=vm::confirm,enabled=!s.busy){Text("确认写入")}},
        dismissButton={TextButton(onClick=vm::dismissConfirmation){Text("取消")}})}
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal=22.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        if(s.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(if(pool==ReferencePool.MEMO)"备忘" else "吃一堑",style=MaterialTheme.typography.titleLarge)
            Text("隔离引用库 · 人工管理；未接AI同坑判断或正式多文件源。",style=MaterialTheme.typography.bodySmall)
            s.error?.let{Text(it,color=MaterialTheme.colorScheme.error)};s.notice?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
            if(s.reviewRequired || s.snapshot?.hasPending==true)TextButton(onClick=vm::classifyPending,enabled=!s.busy){Text("只读核对未决状态")}
            val presentation=referencePoolPresentation(s,pool,LocalDate.now())
            if(s.busy)Text(s.activity.message)
            Text(presentation.snapshotLabel,style=MaterialTheme.typography.bodySmall)
            Text("最近引用（本池）· 较早 → 较近",style=MaterialTheme.typography.titleMedium)
            Text(presentation.recentMessage,style=MaterialTheme.typography.bodySmall)
            if(s.snapshot!=null){
                if(presentation.recent.isEmpty())Text(referenceRecentEmptyMessage(s))
                presentation.recent.forEach{Text(it)}
            }
            Text("正式引用历史未连接；近期标记不表示禁止引用。基础权重不是最终抽取概率。",style=MaterialTheme.typography.bodySmall)
            val d=s.draft
            if(d!=null){
                Text("草稿，尚未保存")
                OutlinedTextField(d.title,{vm.change(title=it)},label={Text("标题（最多200字符）")},enabled=!s.busy && !s.reviewRequired,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(d.body,{vm.change(body=it)},label={Text("正文（可空，最多6000字符）")},enabled=!s.busy && !s.reviewRequired,minLines=3,modifier=Modifier.fillMaxWidth())
                if(d.pool==ReferencePool.MEMO)OutlinedTextField(d.due,{vm.change(due=it)},label={Text("期限 YYYY-MM-DD（可空）")},enabled=!s.busy && !s.reviewRequired,modifier=Modifier.fillMaxWidth())
                else OutlinedTextField(d.count,{vm.change(count=it)},label={Text("人工确认次数（1..100000）")},enabled=!s.busy && !s.reviewRequired,modifier=Modifier.fillMaxWidth())
            }else if(s.snapshot!=null){
                val rows=presentation.rows
                if(rows.isEmpty())Text(presentation.emptyRowsMessage)
                rows.forEach{row->Surface(shape=RoundedCornerShape(3.dp),color=MaterialTheme.colorScheme.surfaceVariant){Column(Modifier.padding(12.dp)){
                    Text(row.entry.title,style=MaterialTheme.typography.titleMedium)
                    row.entry.body?.let{Text(it)}
                    Text("基础权重 ${row.weight} · ${if(row.entry.archived)"已归档" else if((row.entry as? ReferenceEntry.Memo)?.done == true)"已完成" else "未归档"}",style=MaterialTheme.typography.bodySmall)
                    Text("原因：${referenceWeightReason(row.reason)}",style=MaterialTheme.typography.bodySmall)
                    if(row.inRecent)Text("近期引用（本池）",style=MaterialTheme.typography.bodySmall)
                    if(!row.entry.archived && !s.reviewRequired){Row{
                        val memo=row.entry as? ReferenceEntry.Memo
                        if(memo!=null && !memo.done)TextButton(onClick={vm.prepareDone(memo)},enabled=!s.busy){Text("完成")}
                        TextButton(onClick={vm.prepareArchive(row.entry)},enabled=!s.busy){Text("归档")}
                    }}
                }}}
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom=8.dp)){
            if(s.draft==null){
                Button(onClick={vm.begin(pool)},enabled=!s.busy && !s.reviewRequired && s.loaded){Text("新建")}
            }else{
                Button(onClick=vm::prepareCreate,enabled=!s.busy && !s.reviewRequired){Text("核对保存")}
                TextButton(onClick={leave()},enabled=!s.busy && !s.reviewRequired){Text("取消")}
            }
            TextButton(onClick=vm::reload,enabled=!s.busy && s.confirmation==null){Text("重读")}
        }
    }
}
