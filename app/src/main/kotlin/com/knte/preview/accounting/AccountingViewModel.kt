package com.knte.preview.accounting

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knte.core.accounting.AccountingAppendPlan
import com.knte.core.accounting.AccountingCodec
import com.knte.core.accounting.AccountingConfirmResult
import com.knte.core.accounting.AccountingDraft
import com.knte.core.accounting.AccountingIssue
import com.knte.core.accounting.AccountingReports
import com.knte.core.accounting.AccountingEntryKind
import com.knte.core.accounting.AccountingReversalOutcome
import com.knte.core.accounting.AccountingRow
import com.knte.core.accounting.AccountingSnapshot
import com.knte.core.accounting.IsolatedAccountingLedger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.math.BigDecimal

private const val PREFIX = "knte.isolated.accounting."
private const val MAX_AMOUNT_TEXT_CHARS = 12
/** 取消 intent 独立于写入 intent：仅 cancel 成功才清，失败或 scope 销毁时保留，供 init 恢复锁定。 */
private const val CANCEL_INTENT_KEY = PREFIX + "cancelling"
/** reload 失败时把当前读取错误码追加在原不明结果提示之后；用固定分隔符避免重复叠加。 */
private const val READ_ERROR_SEPARATOR = "；当前读取错误："
/** 只读摘要使用 SavedStateHandle 的受限原语；超预算宁可不写摘要，也绝不放松 review lock。 */
private const val SUMMARY_PREFIX = PREFIX + "reviewSummary."
private const val SUMMARY_STATUS_KEY = SUMMARY_PREFIX + "status"
private const val MAX_SUMMARY_UTF16_UNITS = 12000

/** 新流水草稿的表单字段。id 在开始草稿时生成一次；金额保持文本，直到核对时才经
 * BigDecimal 按分精确转换（全程不经 Double）。raw_input 原样保留，不做任何解析。 */
data class AccountingForm(
    val id: String,
    val date: String = LocalDate.now().toString(),
    val income: Boolean = false,
    val amountText: String = "",
    val category: String = AccountingCodec.CATEGORIES.first(),
    val location: String = "",
    val note: String = "",
    val rawInput: String = "",
) {
    companion object {
        fun new(date: String = LocalDate.now().toString()): AccountingForm =
            AccountingForm(id = AccountingCodec.newRecordId(), date = date)
    }
}

/** 报表数值只在真实快照加载成功后出现（读取错误不当 0：金额字段保持 null）。 */
data class AccountingReviewSummary(
    val entryKind: AccountingEntryKind,
    val id: String,
    val ts: String,
    val beforeRevision: String?,
    val afterRevision: String,
    val originalViewKey: String? = null,
    val originalId: String? = null,
    val originalTs: String? = null,
    val originalCents: Long? = null,
    val originalDate: String? = null,
    val originalCategory: String? = null,
    val originalLocation: String? = null,
    val originalNote: String? = null,
    val originalRawInput: String? = null,
    val amountCents: Long? = null,
    val category: String? = null,
    val location: String? = null,
    val note: String? = null,
    val rawInput: String? = null,
)

data class AccountingUiState(
    val loaded: Boolean = false,
    val rows: List<AccountingRow> = emptyList(),
    val findings: List<AccountingIssue> = emptyList(),
    val revision: String? = null,
    val income: Long? = null,
    val expense: Long? = null,
    val net: Long? = null,
    val monthReport: AccountingReports.MonthReport? = null,
    val monthlyTotals: List<AccountingReports.MonthTotal> = emptyList(),
    val snapshotStale: Boolean = false,
    val categoryTotals: List<AccountingReports.CategoryTotal> = emptyList(),
    val reportMonth: String = "",
    val draft: AccountingForm? = null,
    val busy: Boolean = false,
    val confirmation: AccountingAppendPlan? = null,
    val reviewSummary: AccountingReviewSummary? = null,
    val error: String? = null,
    val notice: String? = null,
    val reviewRequired: Boolean = false,
    val reversalOutcome: AccountingReversalOutcome? = null,
) {
    /** 是否存在未保存的草稿内容（父级统一草稿 gate 读取）。 */
    val dirty: Boolean
        get() = draft?.let { d ->
            d.amountText.isNotEmpty() || d.note.isNotEmpty() || d.location.isNotEmpty() ||
                d.rawInput.isNotEmpty() || d.date != LocalDate.now().toString() ||
                d.income || d.category != AccountingCodec.CATEGORIES.first()
        } ?: false
    val hasUnsavedChanges: Boolean get() = dirty
    val canLeave: Boolean get() = !busy && !dirty
}

/** 只拥有 App 私有隔离记账验证库（IsolatedAccountingLedger）：非正式账本，不接真实数据。
 * 写入走 core 两阶段计划（prepareManual → 人工核对卡 → confirm + 独立重读）；计划一旦
 * 因草稿变化/取消/放弃而失效，必须回执 ledger.cancel，避免旧确认继续生效；写入结果
 * 未知时置 reviewRequired，禁止自动重试；TIMESTAMP_BUSY 提示等待重试，不盲改时间。 */
class AccountingViewModel(
    private val store: () -> IsolatedAccountingLedger,
    private val handle: SavedStateHandle = SavedStateHandle(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(AccountingUiState())
    val state: StateFlow<AccountingUiState> = _state.asStateFlow()

    /** 计划状态按实例记录：prepareManual/confirm/cancel 必须落在同一实例上。 */
    private var cachedLedger: IsolatedAccountingLedger? = null
    private var lastSnapshot: AccountingSnapshot? = null

    private fun ledger(): IsolatedAccountingLedger =
        cachedLedger ?: store().also { cachedLedger = it }

    init {
        if (handle.get<Boolean>(PREFIX + "draft") == true) {
            val id = handle.get<String>(PREFIX + "id")?.takeIf { AccountingCodec.validId(it) }
                ?: AccountingCodec.newRecordId()
            val date = handle.get<String>(PREFIX + "date") ?: LocalDate.now().toString()
            val income = handle.get<Boolean>(PREFIX + "income") ?: false
            val amountText = handle.get<String>(PREFIX + "amountText").orEmpty()
            val category = handle.get<String>(PREFIX + "category")
                ?.takeIf { it in AccountingCodec.CATEGORIES } ?: AccountingCodec.CATEGORIES.first()
            val location = handle.get<String>(PREFIX + "location").orEmpty()
            val note = handle.get<String>(PREFIX + "note").orEmpty()
            val rawInput = handle.get<String>(PREFIX + "rawInput").orEmpty()
            if (rawInput.length <= AccountingCodec.MAX_RAW_CHARS &&
                location.length <= AccountingCodec.MAX_LOCATION_CHARS &&
                note.codePointCount(0, note.length) <= AccountingCodec.MAX_NOTE_CODE_POINTS &&
                amountText.length <= MAX_AMOUNT_TEXT_CHARS && date.length <= 10
            ) {
                _state.update {
                    it.copy(
                        draft = AccountingForm(id, date, income, amountText, category, location, note, rawInput),
                        notice = "已恢复草稿字段（有限保护）；核对计划不恢复，确认动作不会自动发生。",
                    )
                }
            } else {
                _state.update { it.copy(notice = "草稿超出可恢复上限，未恢复；已写流水不受影响。") }
            }
        }
        val restoredSummary = restoreReviewSummary()
        if (restoredSummary != null) {
            _state.update {
                it.copy(
                    reviewSummary = restoredSummary,
                    confirmation = null,
                    reviewRequired = true,
                    notice = "已恢复只读核对摘要；旧确认不会恢复，需人工核对隔离库。",
                )
            }
        }
        val writeIntent = handle.get<Boolean>(PREFIX + "writing") == true
        val cancelIntent = handle.get<Boolean>(CANCEL_INTENT_KEY) == true
        if (writeIntent || cancelIntent) {
            // 取消 intent 独立恢复：明确「取消未确认」，不与写入 intent 混淆，也不放松 writing 锁。
            val unclear = when {
                writeIntent && cancelIntent -> "上次写入与核对计划取消均无确认回执"
                cancelIntent -> "上次核对计划取消尚无确认回执"
                else -> "上次写入尚无确认回执"
            }
            _state.update {
                it.copy(
                    reviewRequired = true,
                    error = "$unclear。请保留草稿并核对隔离库；不会自动重试。",
                )
            }
        }
        reload()
    }

    /** 读取失败不当空库：loaded 保持 false、金额保持 null、显式报错。 */
    fun reload() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val snapshot = withContext(ioDispatcher) { ledger().load() }
                lastSnapshot = snapshot
                _state.update { s ->
                    s.copy(
                        loaded = true, busy = false, snapshotStale = false,
                        rows = snapshot.rows, findings = snapshot.findings,
                        revision = snapshot.revision,
                        income = snapshot.income, expense = snapshot.expense, net = snapshot.net,
                        error = if (s.reviewRequired) s.error?.substringBefore(READ_ERROR_SEPARATOR) else null,
                    )
                }
                recomputeReports()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val readCode = describe(e)
                _state.update { s ->
                    val base = if (s.reviewRequired) {
                        s.error?.substringBefore(READ_ERROR_SEPARATOR) ?: "上次结果尚无确认回执"
                    } else null
                    s.copy(
                        busy = false, snapshotStale = lastSnapshot != null,
                        error = if (base != null) "$base$READ_ERROR_SEPARATOR$readCode"
                        else "隔离库读取失败，未视为空库：$readCode",
                    )
                }
            }
        }
    }

    fun newEntry() {
        val s = _state.value
        if (s.busy || s.draft != null || s.reviewRequired) return
        // reversal plan 与 manual draft 互斥：替换前必须收到真实 cancel 回执。
        if (s.confirmation != null) {
            cancelThen { saveDraft(AccountingForm.new()) }
        } else {
            saveDraft(AccountingForm.new())
        }
    }

    fun dateChanged(value: String) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        if (value.length > 10) return
        cancelThen { saveDraft(d.copy(date = value)) }
    }

    fun incomeChanged(value: Boolean) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        if (d.income == value) return
        cancelThen { saveDraft(d.copy(income = value)) }
    }

    fun amountChanged(value: String) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        if (value.length > MAX_AMOUNT_TEXT_CHARS) return
        cancelThen { saveDraft(d.copy(amountText = value)) }
    }

    fun categoryChanged(value: String) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        if (value !in AccountingCodec.CATEGORIES) return
        cancelThen { saveDraft(d.copy(category = value)) }
    }

    fun locationChanged(value: String) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        if (value.length > AccountingCodec.MAX_LOCATION_CHARS) return
        cancelThen { saveDraft(d.copy(location = value)) }
    }

    fun noteChanged(value: String) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        if (value.codePointCount(0, value.length) > AccountingCodec.MAX_NOTE_CODE_POINTS) return
        cancelThen { saveDraft(d.copy(note = value)) }
    }

    fun rawInputChanged(value: String) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        val encodable = runCatching { AccountingCodec.encode(value) }.isSuccess
        if (!encodable || value.length > AccountingCodec.MAX_RAW_CHARS) {
            cancelThen {
                _state.update {
                    it.copy(notice = "输入未接受：原文须为严格UTF-8且不超过${AccountingCodec.MAX_RAW_CHARS}字符；原草稿保留。")
                }
            }
            return
        }
        cancelThen { saveDraft(d.copy(rawInput = value)) }
    }

    /** 报表月份输入：空＝全部月份；输入中的前缀放行，完整且合法才重算。 */
    fun reportMonthChanged(value: String) {
        val s = _state.value
        if (s.busy) return
        if (value.length > 7) return
        if (value.isNotEmpty() && !value.matches(Regex("[0-9]{0,4}(-[0-9]{0,2})?"))) return
        _state.update { it.copy(reportMonth = value) }
        if (value.isEmpty() || value.matches(Regex("[0-9]{4}-(0[1-9]|1[0-2])"))) recomputeReports()
    }

    /** 为当前明细行创建隔离追加式冲销计划；不修改草稿，且与手工计划互斥。 */
    fun prepareReversal(targetKey: String) {
        val s = _state.value
        if (s.busy || s.reviewRequired || s.draft != null || s.confirmation != null) return
        val target = s.rows.firstOrNull { it.viewKey == targetKey }
        if (target == null) {
            _state.update { it.copy(error = "未写入，冲销计划失败：VOID_TARGET_MISSING（目标不存在或已失效，请重新读取）") }
            return
        }
        if (!target.valid || !target.active) {
            _state.update { it.copy(error = "未写入，冲销计划失败：VOID_TARGET_NOT_ACTIVE（目标已不是有效活动记录，不能冲销）") }
            return
        }
        if (target.kind.name == "REVERSAL") {
            _state.update { it.copy(error = "未写入，冲销计划失败：REVERSAL_TARGET_IS_REVERSAL（不能对冲销记录再次冲销）") }
            return
        }
        if (target.rawInput.isEmpty()) {
            _state.update { it.copy(error = "未写入，冲销计划失败：VOID_RAW_INPUT_MISSING（目标缺少原文）") }
            return
        }
        val frozenRevision = s.revision
        val frozenTarget = target
        _state.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                val plan = withContext(ioDispatcher) {
                    ledger().prepareReversal(targetKey = targetKey, sourceRevision = s.revision)
                }
                val binding = plan.reversal
                check(binding != null && binding.sourceRevision == frozenRevision &&
                    binding.originalViewKey == frozenTarget.viewKey && binding.originalId == frozenTarget.id &&
                    binding.originalTs == frozenTarget.ts && binding.originalCents == frozenTarget.cents &&
                    binding.originalDate == frozenTarget.date && binding.originalCategory == frozenTarget.category &&
                    binding.originalLocation == frozenTarget.location && binding.originalRawInput == frozenTarget.rawInput) {
                    "REVERSAL_REVIEW_BINDING_MISMATCH"
                }
                val summary = summaryFor(plan, frozenTarget)
                persistReviewSummary(summary, "CONFIRMABLE")
                _state.update { it.copy(busy = false, confirmation = plan, reviewSummary = summary) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(busy = false, error = "未写入，冲销计划失败：${describe(e)}") }
            }
        }
    }

    fun prepare() {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val plan = withContext(ioDispatcher) {
                    val draft = AccountingDraft.prepare(
                        id = d.id,
                        date = d.date,
                        amountText = d.amountText,
                        income = d.income,
                        category = d.category,
                        location = d.location,
                        note = d.note,
                        rawInput = d.rawInput,
                    )
                    ledger().prepareManual(draft)
                }
                val summary = summaryFor(plan, null, d)
                persistReviewSummary(summary, "CONFIRMABLE")
                _state.update { it.copy(busy = false, confirmation = plan, reviewSummary = summary) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(busy = false, error = "未写入，核对计划失败：${describe(e)}") }
            }
        }
    }

    fun dismissConfirmation() {
        invalidateConfirmation()
    }

    fun commit() {
        val s = _state.value
        val plan = s.confirmation ?: return
        if (s.busy || s.reviewRequired) return
        handle[PREFIX + "writing"] = true
        val frozenSummary = s.reviewSummary ?: summaryFor(plan, s.rows.firstOrNull { it.ts == plan.reversal?.originalTs }, s.draft)
        persistReviewSummary(frozenSummary, "WRITING")
        _state.update { it.copy(busy = true, confirmation = null, reviewSummary = frozenSummary) }
        viewModelScope.launch {
            try {
                val current = ledger()
                val result = withContext(ioDispatcher) { current.confirm(plan, plan.confirmation) }
                val reread = withContext(ioDispatcher) { current.load() }
                if (plan.entryKind == AccountingEntryKind.REVERSAL) {
                    val confirmed = result as? AccountingConfirmResult.Confirmed
                    val outcome = confirmed?.reversal
                    val original = plan.reversal
                    check(outcome != null && original != null && confirmed.appended.ts == plan.ts) { "REVERSAL_READBACK_NOT_CONSUMED" }
                    val originalRow = reread.byTimestamp(outcome.originalTs)
                    val reversalRow = reread.byTimestamp(outcome.reversalTs)
                    check(originalRow != null && !originalRow.active && reversalRow != null &&
                        reversalRow.valid && reversalRow.kind == com.knte.core.accounting.AccountingKind.REVERSAL &&
                        !reversalRow.active && reversalRow.correctedOf == original.originalTs &&
                        reversalRow.id == plan.id && reversalRow.cents == outcome.reversalCents &&
                        original.originalId == originalRow.id && original.originalCents == originalRow.cents &&
                        original.originalDate == originalRow.date && original.originalCategory == originalRow.category &&
                        original.originalLocation == originalRow.location && original.originalRawInput == originalRow.rawInput &&
                        original.sourceRevision == plan.beforeRevision &&
                        outcome.readbackRevision == reread.revision && outcome.receipt.isNotBlank()) {
                        "REVERSAL_READBACK_NOT_CONSUMED"
                    }
                    lastSnapshot = reread
                    clearDraftKeys()
                    clearReviewSummary()
                    handle[PREFIX + "writing"] = false
                    _state.update { it.copy(
                        busy = false, draft = null, reviewSummary = null, error = null, reversalOutcome = outcome,
                        notice = "冲销已追加并由独立重读确认；原记录仍保留，当前视图按冲销链计算。App私有隔离验证库（合成数据），非正式账本。",
                        rows = reread.rows, findings = reread.findings, revision = reread.revision,
                        income = reread.income, expense = reread.expense, net = reread.net,
                    ) }
                } else {
                    val row = when (result) {
                        is AccountingConfirmResult.Confirmed -> {
                            val confirmed = reread.byTimestamp(plan.ts)
                            check(confirmed != null && confirmed.id == plan.id && confirmed.active) {
                                "独立重读未找到该笔有效记录"
                            }
                            confirmed
                        }
                        is AccountingConfirmResult.AlreadyWritten -> {
                            // 幂等回执不能按 plan.ts 猜测身份：只信 core 返回的 existing 行，
                            // 再用独立重读确认稳定 id/viewKey/实际 ts、完整字段和原始字节。
                            val existing = result.existing
                            val expected = frozenSummary
                            val expectedCents = expected.amountCents
                            val expectedDate = s.draft?.date
                            val rereadRow = reread.byTimestamp(existing.ts)
                            check(result.snapshot.revision == reread.revision &&
                                result.snapshot.rawBytes().contentEquals(reread.rawBytes()) &&
                                rereadRow != null && rereadRow.id == existing.id &&
                                rereadRow.viewKey == existing.viewKey && rereadRow.ts == existing.ts &&
                                existing.id == plan.id && existing.viewKey == "ts:${existing.ts}" &&
                                existing.valid && existing.source == AccountingCodec.SOURCE_MANUAL &&
                                existing.active && existing.correctedOf.isEmpty() &&
                                expectedCents != null && existing.cents == expectedCents &&
                                (expectedDate != null && existing.date == expectedDate) &&
                                existing.category == expected.category &&
                                existing.location == expected.location && existing.note == expected.note &&
                                existing.rawInput == expected.rawInput &&
                                existing.rawBytes().contentEquals(rereadRow.rawBytes())) {
                                "MANUAL_ALREADY_WRITTEN_IDENTITY_DRIFT"
                            }
                            rereadRow
                        }
                    }
                    lastSnapshot = reread
                    clearDraftKeys()
                    clearReviewSummary()
                    handle[PREFIX + "writing"] = false
                    val message = when (result) {
                        is AccountingConfirmResult.Confirmed -> "已写入App私有隔离验证库并独立重读确认；非正式账本，未接真实数据。"
                        is AccountingConfirmResult.AlreadyWritten -> "该笔此前已写入（幂等确认），已用core回执身份并独立重读确认，未重复追加。"
                    }
                    _state.update { it.copy(
                        busy = false, draft = null, reviewSummary = null, error = null, notice = message,
                        rows = reread.rows, findings = reread.findings, revision = reread.revision,
                        income = reread.income, expense = reread.expense, net = reread.net,
                    ) }
                }
                recomputeReports()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                persistReviewSummary(_state.value.reviewSummary, "WRITE_UNKNOWN")
                _state.update { it.copy(
                    busy = false, reviewRequired = true,
                    error = "写入结果未确认：${describe(e)}；请保留草稿并核对隔离库，不会自动重试。",
                ) }
            }
        }
    }

    fun discard(afterDiscard: () -> Unit = {}) {
        cancelThen {
            clearDraftKeys()
            _state.update {
                it.copy(
                    draft = null, confirmation = null, reviewSummary = null, error = null,
                    notice = "已放弃当前草稿；不修改或删除已写流水。",
                )
            }
            afterDiscard()
        }
    }

    /** 计划失效必须回执 ledger.cancel，避免旧确认在别处继续生效；不占用调用线程做 IO。 */
    private fun invalidateConfirmation() {
        cancelThen {}
    }

    /** 取消飞行期间 busy=true：新 prepare、字段覆盖、放弃与离页回调都排在 cancel 成功之后；
     *  独立 cancel intent 在 handle 于飞行前落键，仅成功才清；失败或 scope 销毁时保留，init 据此恢复锁定；
     *  失败不执行 next（不 callback、不清草稿键、不放松 writing 锁）。 */
    private fun cancelThen(next: () -> Unit) {
        val s = _state.value
        if (s.busy || s.reviewRequired) return
        val plan = s.confirmation
        if (plan == null) {
            next()
            return
        }
        // cancel 开始前先在 handle 落独立 intent：飞行中 scope 销毁也能持久保留。
        handle[CANCEL_INTENT_KEY] = true
        val frozenSummary = s.reviewSummary ?: summaryFor(plan, s.rows.firstOrNull { it.ts == plan.reversal?.originalTs }, s.draft)
        persistReviewSummary(frozenSummary, "CANCELLING")
        _state.update { it.copy(busy = true, confirmation = null, reviewSummary = frozenSummary) }
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { ledger().cancel(plan) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                persistReviewSummary(_state.value.reviewSummary, "CANCEL_UNKNOWN")
                _state.update {
                    it.copy(
                        busy = false,
                        reviewRequired = true,
                        error = "旧核对计划取消未确认（${describe(e)}）；草稿保留，不复用旧确认，不会自动重试。",
                    )
                }
                return@launch
            }
            // 仅 cancel 真正成功才清 intent；失败与 scope 取消均保留。
            handle[CANCEL_INTENT_KEY] = false
            clearReviewSummary()
            _state.update { it.copy(busy = false, reviewSummary = null) }
            next()
        }
    }

    private fun saveDraft(d: AccountingForm) {
        handle[PREFIX + "draft"] = true
        handle[PREFIX + "id"] = d.id
        handle[PREFIX + "date"] = d.date
        handle[PREFIX + "income"] = d.income
        handle[PREFIX + "amountText"] = d.amountText
        handle[PREFIX + "category"] = d.category
        handle[PREFIX + "location"] = d.location
        handle[PREFIX + "note"] = d.note
        handle[PREFIX + "rawInput"] = d.rawInput
        _state.update { it.copy(draft = d, confirmation = null) }
    }

    private fun summaryFor(plan: AccountingAppendPlan, target: AccountingRow?, draft: AccountingForm? = null): AccountingReviewSummary =
        if (plan.entryKind == AccountingEntryKind.REVERSAL) {
            val r = checkNotNull(plan.reversal)
            AccountingReviewSummary(plan.entryKind, plan.id, plan.ts, plan.beforeRevision, plan.afterRevision,
                r.originalViewKey, r.originalId, r.originalTs, r.originalCents, r.originalDate,
                r.originalCategory, r.originalLocation, target?.note, r.originalRawInput)
        } else {
            AccountingReviewSummary(plan.entryKind, plan.id, plan.ts, plan.beforeRevision, plan.afterRevision,
                amountCents = target?.cents ?: draft?.let { runCatching { AccountingCodec.centsOf(BigDecimal(it.amountText), it.income) }.getOrNull() },
                category = target?.category ?: draft?.category, location = target?.location ?: draft?.location,
                note = target?.note ?: draft?.note, rawInput = target?.rawInput ?: draft?.rawInput)
        }

    private fun persistReviewSummary(summary: AccountingReviewSummary?, status: String) {
        if (summary == null) return
        val values = listOf(
            summary.entryKind.name, summary.id, summary.ts, summary.beforeRevision.orEmpty(), summary.afterRevision,
            summary.originalViewKey.orEmpty(), summary.originalId.orEmpty(), summary.originalTs.orEmpty(),
            summary.originalCents?.toString().orEmpty(), summary.originalDate.orEmpty(), summary.originalCategory.orEmpty(),
            summary.originalLocation.orEmpty(), summary.originalNote.orEmpty(), summary.originalRawInput.orEmpty(),
            summary.amountCents?.toString().orEmpty(), summary.category.orEmpty(), summary.location.orEmpty(),
            summary.note.orEmpty(), summary.rawInput.orEmpty(), status,
        )
        if (values.sumOf { it.length } > MAX_SUMMARY_UTF16_UNITS) {
            // 不清已有锁；缺摘要时恢复仍 reviewRequired=true，避免产生虚假可提交状态。
            return
        }
        handle[SUMMARY_STATUS_KEY] = status
        handle[SUMMARY_PREFIX + "entryKind"] = summary.entryKind.name
        handle[SUMMARY_PREFIX + "id"] = summary.id
        handle[SUMMARY_PREFIX + "ts"] = summary.ts
        handle[SUMMARY_PREFIX + "beforeRevision"] = summary.beforeRevision
        handle[SUMMARY_PREFIX + "afterRevision"] = summary.afterRevision
        handle[SUMMARY_PREFIX + "originalViewKey"] = summary.originalViewKey
        handle[SUMMARY_PREFIX + "originalId"] = summary.originalId
        handle[SUMMARY_PREFIX + "originalTs"] = summary.originalTs
        handle[SUMMARY_PREFIX + "originalCents"] = summary.originalCents
        handle[SUMMARY_PREFIX + "originalDate"] = summary.originalDate
        handle[SUMMARY_PREFIX + "originalCategory"] = summary.originalCategory
        handle[SUMMARY_PREFIX + "originalLocation"] = summary.originalLocation
        handle[SUMMARY_PREFIX + "originalNote"] = summary.originalNote
        handle[SUMMARY_PREFIX + "originalRawInput"] = summary.originalRawInput
        handle[SUMMARY_PREFIX + "amountCents"] = summary.amountCents
        handle[SUMMARY_PREFIX + "category"] = summary.category
        handle[SUMMARY_PREFIX + "location"] = summary.location
        handle[SUMMARY_PREFIX + "note"] = summary.note
        handle[SUMMARY_PREFIX + "rawInput"] = summary.rawInput
    }

    private fun restoreReviewSummary(): AccountingReviewSummary? {
        val kind = handle.get<String>(SUMMARY_PREFIX + "entryKind") ?: return null
        val entryKind = runCatching { AccountingEntryKind.valueOf(kind) }.getOrNull() ?: return null
        val id = handle.get<String>(SUMMARY_PREFIX + "id") ?: return null
        val ts = handle.get<String>(SUMMARY_PREFIX + "ts") ?: return null
        val after = handle.get<String>(SUMMARY_PREFIX + "afterRevision") ?: return null
        return AccountingReviewSummary(
            entryKind = entryKind, id = id, ts = ts,
            beforeRevision = handle.get<String>(SUMMARY_PREFIX + "beforeRevision"), afterRevision = after,
            originalViewKey = handle.get<String>(SUMMARY_PREFIX + "originalViewKey"),
            originalId = handle.get<String>(SUMMARY_PREFIX + "originalId"),
            originalTs = handle.get<String>(SUMMARY_PREFIX + "originalTs"),
            originalCents = handle.get<Long>(SUMMARY_PREFIX + "originalCents"),
            originalDate = handle.get<String>(SUMMARY_PREFIX + "originalDate"),
            originalCategory = handle.get<String>(SUMMARY_PREFIX + "originalCategory"),
            originalLocation = handle.get<String>(SUMMARY_PREFIX + "originalLocation"),
            originalNote = handle.get<String>(SUMMARY_PREFIX + "originalNote"),
            originalRawInput = handle.get<String>(SUMMARY_PREFIX + "originalRawInput"),
            amountCents = handle.get<Long>(SUMMARY_PREFIX + "amountCents"),
            category = handle.get<String>(SUMMARY_PREFIX + "category"),
            location = handle.get<String>(SUMMARY_PREFIX + "location"),
            note = handle.get<String>(SUMMARY_PREFIX + "note"),
            rawInput = handle.get<String>(SUMMARY_PREFIX + "rawInput"),
        )
    }

    private fun clearReviewSummary() {
        listOf("status", "entryKind", "id", "ts", "beforeRevision", "afterRevision", "originalViewKey",
            "originalId", "originalTs", "originalCents", "originalDate", "originalCategory", "originalLocation",
            "originalNote", "originalRawInput", "amountCents", "category", "location", "note", "rawInput")
            .forEach { handle.remove<Any?>(SUMMARY_PREFIX + it) }
    }

    private fun clearDraftKeys() {
        listOf(
            "draft", "id", "date", "income", "amountText", "category",
            "location", "note", "rawInput",
        ).forEach { handle.remove<Any?>(PREFIX + it) }
    }

    /** 报表只从已加载的真实快照生成；月份参数非法时保持上次结果。 */
    private fun recomputeReports() {
        val snapshot = lastSnapshot ?: return
        try {
            val month = _state.value.reportMonth.takeIf { it.isNotEmpty() }
            _state.update {
                it.copy(
                    monthReport = AccountingReports.month(snapshot, month),
                    monthlyTotals = AccountingReports.monthlyTotals(snapshot),
                    categoryTotals = AccountingReports.categoryTotals(snapshot, month),
                )
            }
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(error = "报表参数无效（${e.message}），报表保持上次结果。") }
        }
    }
}

/** core 错误码的完整可读文案（保留原始码，不吞码）。 */
private fun describe(e: Exception): String {
    val code = e.message ?: e.javaClass.simpleName
    val text = when (code) {
        "AMOUNT_TEXT_INVALID" -> "金额须为纯数字（最多9位整数、2位小数），不猜测不四舍五入"
        "AMOUNT_INVALID" -> "金额为零或超出上限 999999999.99"
        "DATE_INVALID" -> "日期须为真实日期 YYYY-MM-DD"
        "ID_INVALID" -> "记录ID无效"
        "CATEGORY_NOT_IN_ENUM" -> "类别不在契约十枚举内"
        "LOCATION_TOO_LONG" -> "地点超过 ${AccountingCodec.MAX_LOCATION_CHARS} 字符"
        "NOTE_TOO_LONG" -> "备注超过 ${AccountingCodec.MAX_NOTE_CODE_POINTS} 码点"
        "RAW_INPUT_REQUIRED" -> "原文必填且不能全为空白"
        "TEXT_NUL_REJECTED" -> "文本含NUL或无法严格UTF-8编码"
        "TIMESTAMP_BUSY" -> "时间戳被占用：请稍候后重新核对；不会自动改时间"
        "WRITE_UNTERMINATED" -> "库文件末行未以换行终止，需人工处理，不自动修复"
        "CAPACITY" -> "行数超过上限 ${AccountingCodec.MAX_ROWS}"
        "SIZE_LIMIT" -> "文件超过 ${AccountingCodec.MAX_FILE_BYTES} 字节上限"
        "TS_INVALID" -> "时间戳无效"
        "DB_CHANGED" -> "库目录结构异常"
        "REVISION_CONFLICT" -> "库内容已变化，计划过期；请重新核对"
        "CREATE_CONFLICT" -> "库文件已被创建，计划过期；请重新核对"
        "PLAN_CANCELLED" -> "计划已取消"
        "PLAN_UNKNOWN" -> "计划不存在（进程重建后单次计划失效）"
        "PLAN_ALREADY_CONFIRMED" -> "计划已确认过，不可重复确认"
        "CONFIRMATION_MISMATCH" -> "确认令牌不匹配"
        "ID_CONFLICT" -> "记录ID冲突"
        "ROOT_MISMATCH" -> "计划与当前库根不匹配"
        "DB_ENTRY_UNEXPECTED" -> "库目录出现意外条目"
        "PENDING_REVIEW_REQUIRED" -> "存在pending标记，需人工检查"
        "PRE_WRITE_VALIDATION_FAILED" -> "写入前校验失败"
        "WRITE_RESULT_UNKNOWN" -> "写入结果未知，需人工核对"
        "VOID_TARGET_MISSING" -> "目标不存在或已失效，请重新读取"
        "VOID_TARGET_NOT_ACTIVE" -> "目标已不是有效活动记录，不能冲销"
        "REVERSAL_TARGET_IS_REVERSAL" -> "不能对冲销记录再次冲销"
        "REVERSAL_TARGET_ALREADY_REVERSED" -> "目标已有冲销记录，请重新读取"
        "REVERSAL_READBACK_NOT_CONSUMED" -> "冲销独立重读未匹配，需人工核对"
        "REVERSAL_NET_MISMATCH" -> "冲销后净额校验不一致，需人工核对"
        else -> null
    }
    return if (text == null) code else "$code（$text）"
}
