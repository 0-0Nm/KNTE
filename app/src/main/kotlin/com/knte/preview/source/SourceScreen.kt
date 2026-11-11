package com.knte.preview.source

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SourceScreen(viewModel: SourceViewModel, onChooseSource: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.opened != null) { viewModel.closeDocument() }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("日记目录 · 只读", style = MaterialTheme.typography.titleLarge)
            Text("只读取你主动选择目录中的直接子 Markdown 文档。不会修改、迁移或删除原文件。",
                style = MaterialTheme.typography.bodySmall)
            state.rootLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val doc = state.opened
            if (doc != null) {
                TextButton(onClick = viewModel::closeDocument, enabled = !state.busy) { Text("返回目录") }
                Text(doc.displayName, style = MaterialTheme.typography.titleMedium)
                doc.metadata?.date?.let { Text("日期：$it") }
                if (doc.problems.isNotEmpty()) Text("原文格式提示：${doc.problems.joinToString()}；保持只读")
                SelectionContainer {
                    Text(if (doc.problems.contains("UNCLOSED_FRONTMATTER"))
                        doc.document.bytes().toString(Charsets.UTF_8) else doc.body)
                }
            } else {
                when (val s = state.status) {
                    SourceStatus.NotSelected -> Text("尚未选择日记目录，不是空目录。")
                    SourceStatus.PermissionLost -> Text("目录授权已不可用。旧内容已清除，请重新选择。", color = MaterialTheme.colorScheme.error)
                    SourceStatus.Loading -> Text("正在读取来源…")
                    SourceStatus.ReadyEmpty -> Text("目录读取成功，没有直接子 Markdown 文档。不会搜索子目录。")
                    is SourceStatus.Ready -> Text("已读取 ${s.entries.size} 份文档")
                    is SourceStatus.Error -> Text("读取失败：${s.message} [${s.code}]。显示的旧列表仅供参考，并非本次读取成功。", color = MaterialTheme.colorScheme.error)
                }
                state.entries.forEach { entry ->
                    OutlinedButton(onClick = { viewModel.open(entry) }, enabled = !state.busy,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(entry.displayName)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onChooseSource, enabled = !state.busy) { Text("选择日记目录") }
            if (state.hasRoot) {
                TextButton(onClick = viewModel::reload, enabled = !state.busy) { Text("重读") }
                TextButton(onClick = viewModel::forget, enabled = !state.busy) { Text("断开") }
            }
        }
    }
}
