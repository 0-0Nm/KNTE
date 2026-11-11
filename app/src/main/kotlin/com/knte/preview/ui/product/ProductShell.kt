package com.knte.preview.ui.product

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

private val ShellStateSaver = listSaver<ShellState, String>(
    save = { listOf(it.route.module.name, it.route.category?.name.orEmpty(),
        it.route.section?.name.orEmpty(), it.overlay.name, it.observeMode.name) },
    restore = { values ->
        runCatching {
            ShellState(ProductRoute(Module.valueOf(values[0]),
                values[1].takeIf { it.isNotEmpty() }?.let { MemoryCategory.valueOf(it) },
                values[2].takeIf { it.isNotEmpty() }?.let { Section.valueOf(it) }), Overlay.valueOf(values[3]),
                values.getOrNull(4)?.let { ObserveMode.valueOf(it) } ?: ObserveMode.OBJECTS)
        }.getOrElse { ShellState() }
    },
)

/**
 * Independent shell. Parent owns repositories, ViewModels and draft lifetime.
 *
 * [diaryContent] must be the isolated Markdown diary, never the private .note tool.
 * With null it is explicitly NotConnected; no fallback to [noteToolContent].
 * [noteToolContent] is mounted only as a standalone development page (no outer Scaffold).
 * Prefer [onOpenPreviewTool] to let the parent mount its tool outside this shell entirely.
 *
 * Draft contract: pass [canLeaveContent] = false while an embedded draft/busy operation
 * cannot leave, or supply [onNavigateAway] to confirm/save and invoke its continuation.
 * If both are supplied, onNavigateAway owns the decision. Cancelling means not invoking it.
 * The continuation is one-shot and ignored after its origin route/tool has changed.
 * Header exits and module/category/section navigation all use the same gate. Parent must
 * retain draft state above this composition; this shell never saves/discards business drafts.
 * Existing child BackHandlers are composed later and may handle editor Back first.
 *
 * Normal shell owns safeDrawing insets. Hosted tool has its own Scaffold/IME handling;
 * the wrapper adds only a consumed top inset. Actual IME/insets behavior needs device review.
 */
@Composable
fun ProductShell(
    noteToolContent: @Composable () -> Unit,
    diaryContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    onOpenPreviewTool: (() -> Unit)? = null,
    canLeaveContent: Boolean = true,
    onNavigateAway: ((continueNavigation: () -> Unit) -> Unit)? = null,
    timeContent: (@Composable () -> Unit)? = null,
    accountingContent: (@Composable (Section) -> Unit)? = null,
) {
    var state by rememberSaveable(stateSaver = ShellStateSaver) { mutableStateOf(ShellState()) }
    var beforeQuiet by rememberSaveable(stateSaver = ShellStateSaver) { mutableStateOf(ShellState()) }
    var toolOpen by rememberSaveable { mutableStateOf(false) }
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    var leaveNotice by rememberSaveable { mutableStateOf(false) }
    val route = state.route
    val isTime = route.module == Module.MEMORY && route.category == MemoryCategory.TIME && route.section == Section.TIME
    val isAccounting = route.module == Module.MEMORY && route.category == MemoryCategory.ACCOUNTING
    val hostsContent = toolOpen || (route.isDiary && diaryContent != null) || (isTime && timeContent != null) || (isAccounting && accountingContent != null)

    fun leave(proceed: () -> Unit) {
        if (!hostsContent) { leaveNotice = false; proceed(); return }
        val gate = onNavigateAway
        if (gate != null) {
            val origin = state.route
            val originTool = toolOpen
            // Close our modal before the parent may present a draft confirmation.
            // The hosted editor remains composed beneath this navigation-only overlay.
            state = state.open(Overlay.NONE)
            settingsPage = null
            var used = false
            gate {
                if (!used && origin == state.route && originTool == toolOpen) {
                    used = true
                    leaveNotice = false
                    proceed()
                }
            }
        } else if (canLeaveContent) {
            leaveNotice = false
            proceed()
        } else {
            state = state.open(Overlay.NONE)
            leaveNotice = true
        }
    }
    fun navigate(target: ProductRoute) {
        if (target == state.route) { state = state.open(Overlay.NONE); return }
        leave {
            if (target.module == Module.QUIET) beforeQuiet = state.open(Overlay.NONE)
            state = state.navigate(target)
            settingsPage = null
        }
    }
    fun closeOverlay() { state = state.open(Overlay.NONE); settingsPage = null }
    fun openTool() {
        leave {
            closeOverlay()
            if (onOpenPreviewTool != null) onOpenPreviewTool() else toolOpen = true
        }
    }

    // Registered before hosted children so their editor-specific Back behavior is retained.
    BackHandler(enabled = toolOpen && state.overlay == Overlay.NONE) { leave { toolOpen = false } }
    BackHandler(enabled = !toolOpen && route.module == Module.QUIET && state.overlay == Overlay.NONE) {
        navigate(beforeQuiet.route)
    }

    ProductTheme {
        if (toolOpen) {
            // No shell Scaffold and no nested product rails around the existing NotesScreen.
            Column(modifier.fillMaxSize().background(ProductInk.Paper).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    PaperAction("返回产品", { leave { toolOpen = false } })
                    Text("独立预览工具", Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
                }
                Text("开发工具 · 私有 .note 记录，非正式日记", Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall, color = ProductInk.Secondary)
                if (leaveNotice) LeaveBlockedNotice()
                Box(Modifier.weight(1f).fillMaxWidth()) { noteToolContent() }
            }
        } else if (route.module == Module.QUIET) {
            Box(modifier.fillMaxSize().background(ProductInk.Dark).safeDrawingPadding()) {
                QuietRoom { navigate(beforeQuiet.route) }
            }
        } else {
            Column(modifier.fillMaxSize().background(ProductInk.Paper).safeDrawingPadding()) {
                BrandHeader { settingsPage = null; state = state.open(Overlay.SETTINGS) }
                if (route.module == Module.MEMORY) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp)) {
                        MemoryCategory.values().forEach { category ->
                            PaperAction(category.label,
                                { navigate(ProductRoute(Module.MEMORY, category,
                                    ProductRoute.sections(Module.MEMORY, category).first())) },
                                isSelected = route.category == category)
                        }
                    }
                }
                if (route.showWorld) {
                    PaperAction("世界 · 尚未连接", { state = state.open(Overlay.WORLD) }, Modifier.fillMaxWidth())
                }
                if (route.showChapter) {
                    Text("章节 · 尚未连接", Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium, color = ProductInk.Secondary)
                }
                if (route.showObserve) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
                        ObserveMode.values().forEach { mode ->
                            PaperAction(mode.label, { state = state.selectObserve(mode) },
                                Modifier.weight(1f), state.observeMode == mode)
                        }
                    }
                }
                NavigationLine(route, { state = state.open(Overlay.MODULES) },
                    { state = state.open(Overlay.SECTIONS) })
                if (leaveNotice) LeaveBlockedNotice()
                if (route.isDiary) {
                    Surface(color = ProductInk.Paper2) {
                        Text(if (diaryContent != null) "隔离验证库，未接真实Vault" else "正式日记尚未接入 · 不使用私有 .note 工具代替",
                            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = ProductInk.Secondary)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().drawBehind {
                    val spacing = 28.dp.toPx()
                    var y = spacing
                    while (y < size.height) {
                        drawLine(ProductInk.Line.copy(alpha = 0.45f), Offset(0f, y), Offset(size.width, y))
                        y += spacing
                    }
                }) {
                    if (isAccounting && accountingContent != null) {
                        accountingContent(requireNotNull(route.section))
                    } else if (isTime && timeContent != null) {
                        timeContent()
                    } else if (availability(route, diaryContent != null) == ProductAvailability.Available) {
                        diaryContent?.invoke()
                    } else {
                        NotConnectedPage(route, state.observeMode) { state = state.open(Overlay.MODULES) }
                    }
                }
            }
        }
        ProductOverlay(state.overlay, route, settingsPage, { settingsPage = it },
            ::closeOverlay, ::navigate, ::openTool)
    }
}

@Composable
private fun LeaveBlockedNotice() {
    Surface(color = ProductInk.Paper2) {
        Text("当前内容暂不能离开。请先在原页面处理草稿或等待操作完成；没有丢弃输入。",
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = ProductInk.Accent2)
    }
}
