package com.knte.preview.reading

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ReadingScreen(viewModel:ReadingViewModel,chooseBook:()->Unit) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var toc by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled=toc){toc=false}
    if(toc && s.book!=null) AlertDialog(onDismissRequest={toc=false},title={Text("目录 · 原文定位")},
        text={ LazyColumn(Modifier.heightIn(max=440.dp)) {
            items(s.book!!.toc,key={it.chapterId}) { item ->
                TextButton(onClick={viewModel.chapter(item.chapterId);toc=false},enabled=!s.busy,
                    modifier=Modifier.fillMaxWidth()) { Text("${item.ordinal}. ${item.title}") }
            }
        } },confirmButton={TextButton(onClick={toc=false}){Text("关闭")}})
    Column(Modifier.fillMaxSize().padding(horizontal=22.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("本机阅读",style=MaterialTheme.typography.titleLarge)
        Text("仅 TXT / Markdown；不上传、不修改原书，尚未接入师长与 AI 教学。",style=MaterialTheme.typography.bodySmall)
        if(s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        s.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        s.notice?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
        val book=s.book
        if(book==null) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("尚未选择书籍。选择器只打开你指定的文件，不扫描书库。")
            }
        } else {
            Text(book.identity.originalName,style=MaterialTheme.typography.titleMedium)
            Row {
                TextButton(onClick={toc=true},enabled=!s.busy){Text("目录 ${book.toc.size} 章")}
                TextButton(onClick=viewModel::saveChapterPosition,enabled=!s.busy){Text("记住本章")}
            }
            if(book.index.duplicateTitles.isNotEmpty()) Text("存在同名章题，按独立章节定位。",style=MaterialTheme.typography.bodySmall)
            val chapter=s.selectedChapter
            val scroll=key(book.revision,chapter) { rememberScrollState() }
            SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
                Text(s.chapterText.orEmpty(),style=MaterialTheme.typography.bodyLarge)
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom=8.dp)) {
            TextButton(onClick=chooseBook,enabled=!s.busy){Text(if(book==null)"选择书籍" else "换一本")}
            if(book!=null)TextButton(onClick=viewModel::closeBook,enabled=!s.busy){Text("关闭书籍")}
        }
    }
}
