package com.knte.preview.ui.product

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Exactly one modal window, selected by one Overlay value; no timer or auto-hide. */
@Composable
internal fun ProductOverlay(
    overlay: Overlay,
    route: ProductRoute,
    settingsPage: String?,
    onSettingsPage: (String?) -> Unit,
    onClose: () -> Unit,
    onRoute: (ProductRoute) -> Unit,
    onOpenPreviewTool: () -> Unit,
) {
    if (overlay == Overlay.NONE) return
    val back = {
        if (overlay == Overlay.SETTINGS && settingsPage != null) onSettingsPage(null) else onClose()
    }
    Dialog(onDismissRequest = back,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            Surface(
                Modifier.align(when (overlay) {
                    Overlay.MODULES -> Alignment.CenterStart
                    Overlay.SECTIONS -> Alignment.CenterEnd
                    else -> Alignment.Center
                }).widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 720.dp),
                color = ProductInk.Paper, border = BorderStroke(1.dp, ProductInk.Ink),
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 6.dp,
                    bottomStart = 6.dp, bottomEnd = 10.dp),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(17.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    PaperAction(if (overlay == Overlay.SETTINGS && settingsPage != null) "返回设置" else "关闭面板", back)
                    when (overlay) {
                        Overlay.MODULES -> {
                            OverlayTitle("模块")
                            Module.values().forEach { module ->
                                PaperAction(module.label, { onRoute(ProductRoute.initial(module)) },
                                    Modifier.fillMaxWidth(), route.module == module, fontSize = 19.sp)
                            }
                        }
                        Overlay.SECTIONS -> {
                            OverlayTitle(route.category?.label ?: route.module.label)
                            ProductRoute.sections(route.module, route.category).forEach { section ->
                                PaperAction(section.label, { onRoute(route.copy(section = section)) },
                                    Modifier.fillMaxWidth(), route.section == section, fontSize = 16.sp)
                            }
                        }
                        Overlay.WORLD -> {
                            OverlayTitle("世界与章节 · 尚未连接")
                            Text("需要真实世界目录、选择状态与章节索引。当前不预填世界、书籍或人物；没有可选项不等于目录为空。")
                            Text("世界作用域随路由派生。师长是全局入口，不显示世界行；只有可问显示章节行。",
                                style = MaterialTheme.typography.bodySmall, color = ProductInk.Secondary)
                        }
                        Overlay.SETTINGS -> SettingsContent(settingsPage, onSettingsPage, onOpenPreviewTool)
                        Overlay.NONE -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

/** Deliberate settings reconstruction, not HTML's five groups of nonfunctional controls. */
@Composable
private fun SettingsContent(page: String?, onPage: (String?) -> Unit, openTool: () -> Unit) {
    when (page) {
        "about" -> {
            OverlayTitle("关于知温")
            Text("HTML 驱动的 Compose 产品壳切片")
            Text("静室 / 记念 / 共读 / 知温。当前提供导航和数据边界展示，不代表完整功能迁移。")
            Text("外观使用本地 HTML 默认纸色与系统衬线回退；未安装原型字体。没有可持久化的主题、模型或磁吸偏好。",
                style = MaterialTheme.typography.bodySmall)
        }
        "data" -> {
            OverlayTitle("数据边界")
            PaperCard {
                Text("正式日记入口", style = MaterialTheme.typography.titleMedium)
                Text("仅在父级传入隔离 Markdown 日记内容后启用。隔离验证库，未接真实Vault。")
            }
            PaperCard {
                Text("预览工具不是正式日记", style = MaterialTheme.typography.titleMedium)
                Text("现有 .note 记录是 App 私有预览数据。此壳不读取、迁移、合并或替换真实 Vault。")
            }
            Text("共读和记念中的珠玑应查询同一真实源，由路由决定作用域；本切片没有创建业务数据源。")
            Text("未连接与空数据分离。没有仓库回执时不展示保存成功；没有服务连接时不展示 AI、天气、录音或金额。")
        }
        else -> {
            OverlayTitle("设置")
            PaperAction("关于", { onPage("about") }, Modifier.fillMaxWidth())
            PaperAction("数据边界", { onPage("data") }, Modifier.fillMaxWidth())
            PaperCard {
                Text("开发工具", style = MaterialTheme.typography.titleMedium)
                Text("仅用于 App 私有 .note 记录验证，不是正式日记。", style = MaterialTheme.typography.bodySmall)
                PaperAction("打开独立预览工具", openTool, Modifier.fillMaxWidth())
            }
        }
    }
}
