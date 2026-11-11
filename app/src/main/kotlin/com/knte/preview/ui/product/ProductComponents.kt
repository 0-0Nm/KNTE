package com.knte.preview.ui.product

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A laid-out 48dp minimum target, never an overlapping invisible expansion. */
@Composable
internal fun PaperAction(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    isSelected: Boolean? = null, fontSize: TextUnit = 13.5.sp,
) {
    val accent = ProductInk.Accent
    val chosen = isSelected == true
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
        .semantics { isSelected?.let { selected = it } }
        .clickable(role = Role.Button, onClick = onClick)
        .drawBehind { if (chosen) drawLine(accent, Offset(0f, size.height - 2.dp.toPx()),
            Offset(size.width, size.height - 2.dp.toPx()), 2.dp.toPx()) }
        .padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = fontSize, fontFamily = FontFamily.Serif,
            color = if (chosen) accent else ProductInk.Ink,
            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
internal fun BrandHeader(openSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        .heightIn(min = 56.dp).drawBehind {
            drawLine(ProductInk.Ink, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx())
        }, verticalAlignment = Alignment.CenterVertically) {
        Text("知温", Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.headlineLarge)
        PaperAction("设置", openSettings, fontSize = 11.sp)
    }
}

@Composable
private fun RailStub(label: String, visual: String, onClick: () -> Unit) {
    Box(Modifier.width(48.dp).heightIn(min = 48.dp)
        .semantics { contentDescription = label }
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(topStart = 8.dp, topEnd = 3.dp,
            bottomStart = 3.dp, bottomEnd = 8.dp), color = ProductInk.Paper2,
            border = BorderStroke(1.dp, ProductInk.Line)) {
            Text(visual, Modifier.width(26.dp).padding(vertical = 6.dp)
                .clearAndSetSemantics { }, fontSize = 13.sp, fontFamily = FontFamily.Serif,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** Stubs occupy navigation-row slots, not editor hit areas. Full content stays full width. */
@Composable
internal fun NavigationLine(route: ProductRoute, modules: () -> Unit, sections: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RailStub("打开模块导航", "模\n块", modules)
        Text(route.breadcrumb, Modifier.weight(1f).padding(horizontal = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = ProductInk.Secondary)
        if (ProductRoute.sections(route.module, route.category).isNotEmpty()) {
            RailStub("打开分区导航", "分\n区", sections)
        } else Spacer(Modifier.width(48.dp))
    }
}

@Composable
internal fun PaperCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = ProductInk.Paper2,
        shape = RoundedCornerShape(3.dp), border = BorderStroke(1.dp, ProductInk.Line)) {
        Column(Modifier.padding(horizontal = 17.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
internal fun NotConnectedPage(route: ProductRoute, observeMode: ObserveMode, onBack: () -> Unit) {
    var expanded by rememberSaveable(route.breadcrumb, observeMode.name) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text(if (route.showObserve) observeMode.label else route.section?.label ?: route.module.label,
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.semantics { heading() })
        PaperCard {
            Text("尚未连接", style = MaterialTheme.typography.bodyLarge)
            Text(if (route.showObserve) observeMode.unavailableMessage else when (route.module) {
                Module.ZHIWEN -> "对话服务尚未连接，暂时无法发起对话。"
                Module.READING -> "阅读内容尚未连接，暂时无法查看当前分区。"
                else -> "${route.section?.label ?: route.module.label}内容尚未连接，暂时无法读取记录。"
            }, style = MaterialTheme.typography.bodyMedium)
            PaperAction(if (expanded) "收起连接详情" else "连接详情", { expanded = !expanded })
            if (expanded) {
                Text("状态：NotConnected；未连接不代表没有记录。", style = MaterialTheme.typography.bodySmall)
                Text(route.dependency, style = MaterialTheme.typography.bodyMedium)
                Text("没有读取真实 Vault，没有生成、发送、录音或保存操作。连接失败不能显示为零条记录。",
                    style = MaterialTheme.typography.bodySmall, color = ProductInk.Secondary)
                if (route.section == Section.MEMORY_GEMS || route.section == Section.READING_GEMS) {
                    Text("两个珠玑入口是同一真实珠玑源的不同作用域视图；本壳不新建独立珠玑库。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        PaperAction("返回模块目录", onBack)
    }
}

@Composable
internal fun QuietRoom(onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(ProductInk.Dark)) {
        Canvas(Modifier.fillMaxSize()) {
            // Deterministic decoration, not recording or a generated data stream.
            repeat(37) { index ->
                val x = ((index * 73 + 19) % 997) / 997f * size.width
                val y = ((index * 137 + 41) % 991) / 991f * size.height
                drawCircle(ProductInk.DarkInk.copy(alpha = 0.12f), 1.dp.toPx(), Offset(x, y))
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Surface(color = ProductInk.Paper, shape = RoundedCornerShape(3.dp)) {
                PaperAction("退出静室", onExit)
            }
            Spacer(Modifier.height(32.dp))
            Text("静室", style = MaterialTheme.typography.titleLarge, color = ProductInk.DarkInk,
                modifier = Modifier.semantics { heading() })
            Text("停一停，留一点空白。", color = ProductInk.DarkInk,
                fontSize = 13.5.sp, lineHeight = 25.65.sp, fontFamily = FontFamily.Serif)
            Text("保存与录音尚未连接，此刻只留一处安静的空白。",
                color = ProductInk.DarkInk, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
