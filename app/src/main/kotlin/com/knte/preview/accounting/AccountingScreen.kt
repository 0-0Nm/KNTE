package com.knte.preview.accounting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.knte.core.accounting.AccountingAppendPlan
import com.knte.core.accounting.AccountingCodec
import com.knte.core.accounting.AccountingIssue
import com.knte.core.accounting.AccountingRow
import java.math.BigDecimal

/** 三个只读分区；路由与构造由父级接入（LEDGER＝明细+录入，MONTH＝当月收支，CATEGORY＝分类合计）。 */
enum class AccountingSection { LEDGER, MONTH, CATEGORY }

/** 记账隔离验证流水页：草稿 → 核对卡 → 确认真实写 → 独立重读 → 明细/报表。
 * 继承父级纸墨 MaterialTheme；只读写 App 私有隔离验证库（合成数据，非正式账本），
 * 不预造金额；冲销写入未开放、无AI解析，均不给可点的假按钮。 */
@Composable
fun AccountingScreen(
    viewModel: AccountingViewModel,
    section: AccountingSection = AccountingSection.LEDGER,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var discardConfirm by rememberSaveable { mutableStateOf(false) }
    var showHashDetails by rememberSaveable { mutableStateOf(false) }
    var showFindings by rememberSaveable { mutableStateOf(false) }
    var expandedRow by rememberSaveable { mutableStateOf<String?>(null) }

    fun requestLeave() {
        val s = state
        if (s.busy) return
        if (s.dirty) discardConfirm = true else viewModel.discard()
    }
    BackHandler(enabled = state.draft != null) { requestLeave() }

    if (discardConfirm && state.draft != null) AlertDialog(
        onDismissRequest = { discardConfirm = false },
        title = { Text("放弃记账草稿？") },
        text = { Text("不会修改或删除已写入的流水；已写历史保持追加不变。") },
        confirmButton = {
            TextButton(onClick = { discardConfirm = false; viewModel.discard() }, shape = RoundedCornerShape(3.dp)) { Text("放弃草稿") }
        },
        dismissButton = { TextButton(onClick = { discardConfirm = false }, shape = RoundedCornerShape(3.dp)) { Text("继续编辑") } },
    )

    state.confirmation?.let { plan ->
        ConfirmCard(plan, state, viewModel, showHashDetails) { showHashDetails = !showHashDetails }
    }

    Column(
        Modifier.fillMaxSize().imePadding().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("记账 · 隔离验证流水", style = MaterialTheme.typography.titleLarge)
            Text(
                "本页只读写App私有隔离验证库（合成数据），非正式账本；无真实库权限、无付费。人工录入，raw_input 原样保留，无AI解析。冲销仅追加一条记录，不删除历史。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.snapshotStale) {
                Text("上次成功快照（本次读取失败，以下数据可能已过期；未装载新结果）", color = MaterialTheme.colorScheme.error)
            }
            (state.reportInputError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.reviewSummary?.let { ReviewSummaryCard(it, state.reviewRequired) }
            state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.reversalOutcome?.let { outcome ->
                Text("冲销结果：原记录 ${outcome.originalTs} 已由 ${outcome.reversalTs} 消耗；净额 ${AccountingCodec.money(outcome.netBefore)} → ${AccountingCodec.money(outcome.netAfter)}，回执与修订号已独立核对。", style = MaterialTheme.typography.bodySmall)
            }
            if (state.reviewRequired) {
                Text("待人工核对：写入结果未知，禁止再次写入与自动重试。", color = MaterialTheme.colorScheme.error)
            }
            FindingsDisclosure(state.findings, showFindings) { showFindings = !showFindings }
            when (section) {
                AccountingSection.LEDGER -> LedgerContent(state, viewModel, expandedRow) { key ->
                    expandedRow = if (expandedRow == key) null else key
                }
                AccountingSection.MONTH -> MonthContent(state, viewModel)
                AccountingSection.CATEGORY -> CategoryContent(state, viewModel)
            }
            if (section != AccountingSection.LEDGER && state.draft != null) {
                Text("有未保存草稿；Back受dirty确认保护，切回流水分区继续编辑。", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (section == AccountingSection.LEDGER && state.draft != null) {
                Button(
                    onClick = viewModel::prepare,
                    enabled = !state.busy && !state.reviewRequired,
                    shape = RoundedCornerShape(3.dp),
                ) { Text("核对写入") }
                TextButton(
                    onClick = { requestLeave() },
                    enabled = !state.busy,
                    shape = RoundedCornerShape(3.dp),
                ) { Text(if (state.dirty) "取消编辑" else "收起草稿") }
            } else {
                if (section == AccountingSection.LEDGER) {
                    Button(
                        onClick = viewModel::newEntry,
                        enabled = !state.busy && !state.reviewRequired && state.draft == null,
                        shape = RoundedCornerShape(3.dp),
                    ) { Text("新记一笔") }
                }
                TextButton(
                    onClick = viewModel::reload,
                    enabled = !state.busy,
                    shape = RoundedCornerShape(3.dp),
                ) { Text("重新读取") }
            }
        }
    }
}

/** 人工录入表单与明细列表；金额文本按分精确入账，从不经Double。 */
@Composable
private fun LedgerContent(
    state: AccountingUiState,
    viewModel: AccountingViewModel,
    expandedRow: String?,
    toggle: (String) -> Unit,
) {
    val d = state.draft
    if (d != null) {
        Text(
            if (state.dirty) "草稿未保存" else "草稿无改动",
            style = MaterialTheme.typography.labelMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !d.income,
                onClick = { viewModel.incomeChanged(false) },
                enabled = !state.busy && !state.reviewRequired,
                label = { Text("支出") },
            )
            FilterChip(
                selected = d.income,
                onClick = { viewModel.incomeChanged(true) },
                enabled = !state.busy && !state.reviewRequired,
                label = { Text("收入") },
            )
        }
        OutlinedTextField(
            value = d.amountText,
            onValueChange = viewModel::amountChanged,
            singleLine = true,
            enabled = !state.busy && !state.reviewRequired,
            label = { Text("金额（纯数字）") },
            isError = d.amountText.isNotEmpty() && !AccountingCodec.validAmountText(d.amountText),
            supportingText = { Text(amountHint(d.amountText)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = d.date,
            onValueChange = viewModel::dateChanged,
            singleLine = true,
            enabled = !state.busy && !state.reviewRequired,
            label = { Text("业务日期 YYYY-MM-DD") },
            isError = d.date.isNotEmpty() && !AccountingCodec.validDate(d.date),
            supportingText = { Text(dateHint(d.date)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("类别（契约十枚举，仅可选）", style = MaterialTheme.typography.labelMedium)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AccountingCodec.CATEGORIES.chunked(3).forEach { chunk ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chunk.forEach { c ->
                        FilterChip(
                            selected = d.category == c,
                            onClick = { viewModel.categoryChanged(c) },
                            enabled = !state.busy && !state.reviewRequired,
                            label = { Text(c) },
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = d.location,
            onValueChange = viewModel::locationChanged,
            singleLine = true,
            enabled = !state.busy && !state.reviewRequired,
            label = { Text("地点（可空）") },
            supportingText = { Text("${d.location.length} / ${AccountingCodec.MAX_LOCATION_CHARS} 字符") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = d.note,
            onValueChange = viewModel::noteChanged,
            singleLine = true,
            enabled = !state.busy && !state.reviewRequired,
            label = { Text("备注（可空）") },
            supportingText = { Text("${d.note.codePointCount(0, d.note.length)} / ${AccountingCodec.MAX_NOTE_CODE_POINTS} 码点") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = d.rawInput,
            onValueChange = viewModel::rawInputChanged,
            minLines = 3,
            enabled = !state.busy && !state.reviewRequired,
            label = { Text("原文 raw_input（必填）") },
            supportingText = { Text(rawHint(d.rawInput)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "金额文本按分精确入账，从不经Double；无AI解析、无后台识别，原文原样落库。",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        if (!state.loaded) {
            Text("库尚未成功读取（读取失败不当空库处理）。", style = MaterialTheme.typography.bodySmall)
        }
        if (state.loaded && state.rows.isEmpty() && state.error == null) {
            Text("隔离验证库读取成功，暂无流水。")
        }
        state.rows.forEach { row ->
            OutlinedButton(
                onClick = { toggle(row.viewKey) },
                shape = RoundedCornerShape(3.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${row.date} · ${row.category} · ${AccountingCodec.money(row.cents)}")
                    if (row.note.isNotEmpty()) Text(row.note, style = MaterialTheme.typography.bodyMedium)
                    if (!row.valid) {
                        Text("无效行（不进合计，异常披露不省略）", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (expandedRow == row.viewKey) {
                        Text("ts：${row.ts}　id：${row.id}　来源：${row.source}", style = MaterialTheme.typography.bodySmall)
                        Text("raw_input：${row.rawInput}", style = MaterialTheme.typography.bodySmall)
                        if (row.valid && row.active && row.kind.name != "REVERSAL" && row.rawInput.isNotEmpty()) {
                            Text("原记录可创建冲销计划；CORRECTION 只要有效且活动中同样允许。", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(
                                onClick = { viewModel.prepareReversal(row.viewKey) },
                                enabled = !state.busy && !state.reviewRequired && state.draft == null,
                                shape = RoundedCornerShape(3.dp),
                            ) { Text("创建冲销计划") }
                        }
                    }
                }
            }
        }
    }
}

/** 当月收支：只从真实快照生成，读取失败不显示0。 */
@Composable
private fun MonthContent(state: AccountingUiState, viewModel: AccountingViewModel) {
    Text("当月收支（只读报表）", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = state.reportMonth,
        onValueChange = viewModel::reportMonthChanged,
        singleLine = true,
        enabled = !state.busy,
        label = { Text("月份 YYYY-MM") },
        supportingText = { Text(monthHint(state.reportMonth)) },
        modifier = Modifier.fillMaxWidth(),
    )
    val mr = state.monthReport
    if (mr == null) {
        Text(
            if (state.loaded) "尚无快照。" else "库未成功读取；读取错误不当0，不生成报表。",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    Text(
        "收入 ${AccountingCodec.money(mr.income)} · 支出 ${AccountingCodec.money(mr.expense)} · 结余 ${AccountingCodec.money(mr.net)}（分值精确合计）",
    )
    Text(
        "范围内有效行 ${mr.rows.size}；被排除无效行 ${mr.excludedInRange} 条。",
        style = MaterialTheme.typography.bodySmall,
    )
    val chart = AccountingChartModel.from(
        report = mr,
        sourceRevision = state.reportRevision,
        loaded = state.loaded,
        stale = state.snapshotStale || state.reportsStale,
        busy = state.busy,
        reviewRequired = state.reviewRequired,
    )
    AccountingMoneyChart(chart, AccountingCodec::money)
    val activeDays = mr.days.filter { it.income != 0L || it.expense != 0L }
    if (activeDays.isEmpty()) Text("该范围内暂无有效流水。")
    activeDays.forEach { day ->
        Text(
            "${day.date}：收 ${AccountingCodec.money(day.income)} / 支 ${AccountingCodec.money(day.expense)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (state.monthlyTotals.isNotEmpty()) {
        Text("分月合计", style = MaterialTheme.typography.titleSmall)
        state.monthlyTotals.forEach { t ->
            Text(
                "${t.month}：收 ${AccountingCodec.money(t.income)} 支 ${AccountingCodec.money(t.expense)} 结余 ${AccountingCodec.money(t.net)}（${t.count}笔）",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 分类合计：契约十枚举全列，枚举外类别仍披露。 */
@Composable
private fun CategoryContent(state: AccountingUiState, viewModel: AccountingViewModel) {
    Text("分类合计（只读报表）", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = state.reportMonth,
        onValueChange = viewModel::reportMonthChanged,
        singleLine = true,
        enabled = !state.busy,
        label = { Text("月份 YYYY-MM") },
        supportingText = { Text(monthHint(state.reportMonth)) },
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.reportRevision == null) {
        Text(
            if (state.loaded) "尚无成功报表。" else "库未成功读取，不生成分类报表。",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    Text(
        "上次成功报表范围：${state.reportScope.ifEmpty { "全量" }} · revision ${state.reportRevision.take(12)}${if (state.reportsStale) " · 旧快照/结果可能已过期" else ""}",
        style = MaterialTheme.typography.bodySmall,
    )
    if (state.categoryTotals.isEmpty()) {
        Text(
            if (state.reportsStale) "上次成功报表范围内暂无有效流水；当前输入未生成新范围。" else "该成功报表范围内暂无有效流水。",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    state.categoryTotals.forEach { c ->
        Text(
            "${if (c.inEnum) "" else "（枚举外）"}${c.category}：收 ${AccountingCodec.money(c.income)} / 支 ${AccountingCodec.money(c.expense)} / 结余 ${AccountingCodec.money(c.net)} / ${c.count}笔",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 非静默异常披露：不省略，条数与逐条内容都可见。 */
@Composable
private fun FindingsDisclosure(findings: List<AccountingIssue>, expanded: Boolean, onToggle: () -> Unit) {
    if (findings.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "库内非静默异常 ${findings.size} 条（无效行不进合计；披露不省略）",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelMedium,
        )
        TextButton(onClick = onToggle, shape = RoundedCornerShape(3.dp)) {
            Text(if (expanded) "收起异常明细" else "查看异常明细")
        }
        if (expanded) {
            findings.take(100).forEach { f ->
                Text(
                    "第${f.lineNumber}行 ${f.code}${if (f.detail.isNotEmpty()) "：${f.detail}" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ReviewSummaryCard(summary: AccountingReviewSummary, locked: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (locked) "只读冻结核对摘要（授权已失效）" else "当前核对摘要", style = MaterialTheme.typography.titleSmall)
        Text("${if (summary.entryKind.name == "REVERSAL") "冲销" else "手工流水"} · id=${summary.id} · ts=${summary.ts}")
        if (summary.entryKind.name == "REVERSAL") {
            Text("原记录：${summary.originalId} / ${summary.originalTs} / ${summary.originalCents?.let { AccountingCodec.money(it) } ?: "未知"}")
            Text("业务日期：${summary.originalDate} · 分类：${summary.originalCategory}")
            Text("地点：${summary.originalLocation.orEmpty()} · 原备注：${summary.originalNote.orEmpty()}")
            Text("原文 raw_input：${summary.originalRawInput.orEmpty()}")
            Text("绑定 sourceRevision：${summary.beforeRevision ?: "未知"}")
        } else {
            Text("金额：${summary.amountCents?.let { AccountingCodec.money(it) } ?: "未解析"} · 分类：${summary.category.orEmpty()}")
            Text("地点：${summary.location.orEmpty()} · 备注：${summary.note.orEmpty()}")
            Text("原文 raw_input：${summary.rawInput.orEmpty()}")
        }
        Text("写入时间戳：${summary.ts} · 写入前修订：${summary.beforeRevision ?: "新库"}", style = MaterialTheme.typography.bodySmall)
    }
}

/** 核对卡：确认前完整展示将写入的内容；技术hash收在详情里。 */
@Composable
private fun ConfirmCard(
    plan: AccountingAppendPlan,
    state: AccountingUiState,
    viewModel: AccountingViewModel,
    showHash: Boolean,
    onToggleHash: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = viewModel::dismissConfirmation,
        title = { Text(if (plan.entryKind.name == "REVERSAL") "核对后追加冲销记录" else "核对后写入隔离流水") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val d = state.draft
                val summary = state.reviewSummary
                val r = plan.reversal
                Text("范围：本App私有隔离验证库（合成数据），非正式账本")
                if (r != null) {
                    Text("原记录身份：viewKey=${r.originalViewKey}\nid=${r.originalId}\nts=${r.originalTs}")
                    Text("业务日期：${r.originalDate}\n金额：${AccountingCodec.money(r.originalCents)}（${if (r.originalCents >= 0) "收入" else "支出"}）\n分类：${r.originalCategory}")
                    Text("地点：${r.originalLocation}\n原备注：${summary?.originalNote.orEmpty()}")
                    Text("原文 raw_input：${r.originalRawInput}")
                    Text("反向金额：${AccountingCodec.money(-r.originalCents)}\n将追加一条关联冲销记录，原记录保留；不删除、不覆盖历史。")
                    Text("绑定：sourceRevision=${r.sourceRevision}\n基线净额=${AccountingCodec.money(r.baselineNet)}，基线有效行=${r.baselineEffectiveRows}", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("操作：追加一行；append-only，不覆盖不删除历史")
                    Text("记录ID：${plan.id}\n业务日期：${d?.date}\n类别：${d?.category}")
                    val amount = d?.let { runCatching { AccountingCodec.money(AccountingCodec.centsOf(BigDecimal(it.amountText), it.income)) }.getOrDefault(it.amountText) } ?: ""
                    Text("金额：$amount（${if (d?.income == true) "收入" else "支出"}；分值精确，不经Double）")
                    if (!d?.location.isNullOrEmpty()) Text("地点：${d?.location}")
                    Text("原备注：${summary?.note.orEmpty()}")
                    Text("原文 raw_input：${d?.rawInput ?: ""}")
                }
                Text("写入时间戳：${plan.ts}\n写入前行数：${plan.rowCountBefore}")
                Text("取消只取消计划，不承诺全局零字节；确认后本计划一次性失效；请勿同时从其他途径写同一库。")
                TextButton(onClick = onToggleHash, shape = RoundedCornerShape(3.dp)) {
                    Text(if (showHash) "收起技术详情" else "查看技术详情")
                }
                if (showHash) {
                    Text("数据集：${plan.dataset}", style = MaterialTheme.typography.bodySmall)
                    Text("写入前修订：${plan.beforeRevision ?: "（库文件不存在，将新建）"}", style = MaterialTheme.typography.bodySmall)
                    Text("写入后修订：${plan.afterRevision}", style = MaterialTheme.typography.bodySmall)
                    Text("确认令牌：${plan.confirmation}", style = MaterialTheme.typography.bodySmall)
                    Text("仅本进程合作式锁；不承诺多应用外部并发安全。", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::commit, enabled = !state.busy, shape = RoundedCornerShape(3.dp)) { Text("确认写入") }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissConfirmation, shape = RoundedCornerShape(3.dp)) { Text("取消") }
        },
    )
}

private fun amountHint(text: String): String = when {
    text.isEmpty() -> "纯数字；最多9位整数、2位小数；不经Double"
    AccountingCodec.validAmountText(text) -> "格式有效；将按分精确入账"
    else -> "当前格式无效：须为纯数字（至多两位小数）"
}

private fun dateHint(text: String): String = when {
    text.isEmpty() -> "默认今天；须为真实日期"
    text.length < 10 -> "输入中（YYYY-MM-DD）"
    AccountingCodec.validDate(text) -> "日期有效"
    else -> "日期无效：须为真实日期 YYYY-MM-DD"
}

private fun rawHint(text: String): String {
    val bytes = runCatching { AccountingCodec.encode(text).size }.getOrDefault(-1)
    return if (bytes < 0) {
        "含无法编码字符，将无法保存"
    } else {
        "${text.length} / ${AccountingCodec.MAX_RAW_CHARS} 字符（严格UTF-8 ${bytes} 字节）；必填，原样保留不解析"
    }
}

private fun monthHint(value: String): String = when {
    value.isEmpty() -> "留空＝全部月份"
    value.length < 7 -> "输入中（YYYY-MM）"
    value.matches(Regex("[0-9]{4}-(0[1-9]|1[0-2])")) -> "仅统计该月"
    else -> "格式无效：报表保持上次结果"
}
