package com.knte.preview.ui.product

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
        val target = overlayBackTarget(overlay, settingsPage)
        if (target == Overlay.SETTINGS) onSettingsPage(null) else onClose()
    }
    Dialog(onDismissRequest = back,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        if (overlay == Overlay.MODULES || overlay == Overlay.SECTIONS) {
            EdgeNavigation(overlay, route, onClose, onRoute)
            return@Dialog
        }
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            Surface(
                Modifier.align(Alignment.Center)
                    .widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 720.dp),
                color = ProductInk.Paper, border = BorderStroke(1.dp, ProductInk.Ink),
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 6.dp,
                    bottomStart = 6.dp, bottomEnd = 10.dp),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(17.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    PaperAction(if (overlay == Overlay.SETTINGS && settingsPage != null) "返回设置" else "关闭面板", back)
                    when (overlay) {
                        Overlay.MODULES, Overlay.SECTIONS -> Unit // edge panel above
                        Overlay.WORLD -> {
                            OverlayTitle("世界与章节")
                            Text("尚未连接阅读目录，暂时无法选择世界或章节。")
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

/** The dialog is only the modal host. Its surface is a narrow edge rail, never a sheet.
 * 48dp rows own their real space; large fonts / short windows scroll instead of overlapping.
 * Full-size dismissal layer is behind the surface, leaving editor width unchanged on close.
 */
@Composable
private fun EdgeNavigation(
    overlay: Overlay, route: ProductRoute, onClose: () -> Unit,
    onRoute: (ProductRoute) -> Unit,
) {
    val left = overlay == Overlay.MODULES
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(Modifier.matchParentSize()
            .semantics { contentDescription = "关闭导航面板" }
            .clickable(interactionSource = remember { MutableInteractionSource() },
                indication = null, role = Role.Button, onClick = onClose))
        // Match the header's 56dp slot, not an absolute system-status-bar offset.
        Box(Modifier.fillMaxSize().padding(top = 56.dp, bottom = 8.dp)) {
            Surface(
                Modifier.align(if (left) Alignment.TopStart else Alignment.TopEnd)
                    .width(if (left) 96.dp else 132.dp).heightIn(max = 600.dp),
                color = ProductInk.Paper2, border = BorderStroke(1.dp, ProductInk.Line),
                shape = if (left) RoundedCornerShape(topEnd = 3.dp, bottomEnd = 6.dp)
                    else RoundedCornerShape(topStart = 3.dp, bottomStart = 6.dp),
                shadowElevation = 3.dp,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                    PaperAction("收起", onClose, Modifier.fillMaxWidth(), fontSize = 11.sp)
                    if (left) {
                        Module.values().forEach { module ->
                            PaperAction(module.label, { onRoute(ProductRoute.initial(module)) },
                                Modifier.fillMaxWidth(), route.module == module, fontSize = 19.sp)
                        }
                    } else {
                        ProductRoute.sections(route.module, route.category).forEach { section ->
                            PaperAction(section.label, { onRoute(route.copy(section = section)) },
                                Modifier.fillMaxWidth(), route.section == section, fontSize = 16.sp)
                        }
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
            Text("知温 · 记念与共读")
            Text("静室 / 记念 / 共读 / 知温。部分服务尚未连接，可用范围见数据边界。")
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
