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

private const val PREFIX = "knte.isolated.accounting."
private const val MAX_AMOUNT_TEXT_CHARS = 12

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
    val categoryTotals: List<AccountingReports.CategoryTotal> = emptyList(),
    val reportMonth: String = "",
    val draft: AccountingForm? = null,
    val busy: Boolean = false,
    val confirmation: AccountingAppendPlan? = null,
    val error: String? = null,
    val notice: String? = null,
    val reviewRequired: Boolean = false,
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
        if (handle.get<Boolean>(PREFIX + "writing") == true) {
            _state.update {
                it.copy(
                    reviewRequired = true,
                    error = "上次写入尚无确认回执。请保留草稿并核对隔离库；不会自动重试。",
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
                        loaded = true, busy = false,
                        rows = snapshot.rows, findings = snapshot.findings,
                        revision = snapshot.revision,
                        income = snapshot.income, expense = snapshot.expense, net = snapshot.net,
                        error = if (s.reviewRequired) s.error else null,
                    )
                }
                recomputeReports()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(busy = false, error = "隔离库读取失败，未视为空库：${describe(e)}") }
            }
        }
    }

    fun newEntry() {
        val s = _state.value
        if (s.busy || s.draft != null || s.reviewRequired) return
        saveDraft(AccountingForm.new())
    }

    fun dateChanged(value: String) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        if (value.length > 10) return
        invalidateConfirmation()
        saveDraft(d.copy(date = value))
    }

    fun incomeChanged(value: Boolean) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        if (d.income == value) return
        invalidateConfirmation()
        saveDraft(d.copy(income = value))
    }

    fun amountChanged(value: String) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        if (value.length > MAX_AMOUNT_TEXT_CHARS) return
        invalidateConfirmation()
        saveDraft(d.copy(amountText = value))
    }

    fun categoryChanged(value: String) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        if (value !in AccountingCodec.CATEGORIES) return
        invalidateConfirmation()
        saveDraft(d.copy(category = value))
    }

    fun locationChanged(value: String) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        if (value.length > AccountingCodec.MAX_LOCATION_CHARS) return
        invalidateConfirmation()
        saveDraft(d.copy(location = value))
    }

    fun noteChanged(value: String) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        if (value.codePointCount(0, value.length) > AccountingCodec.MAX_NOTE_CODE_POINTS) return
        invalidateConfirmation()
        saveDraft(d.copy(note = value))
    }

    fun rawInputChanged(value: String) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        val encodable = runCatching { AccountingCodec.encode(value) }.isSuccess
        if (!encodable || value.length > AccountingCodec.MAX_RAW_CHARS) {
            invalidateConfirmation()
            _state.update {
                it.copy(notice = "输入未接受：原文须为严格UTF-8且不超过${AccountingCodec.MAX_RAW_CHARS}字符；原草稿保留。")
            }
            return
        }
        invalidateConfirmation()
        saveDraft(d.copy(rawInput = value))
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
                _state.update { it.copy(busy = false, confirmation = plan) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(busy = false, error = "未写入，核对计划失败：${describe(e)}") }
            }
        }
    }

    fun dismissConfirmation() {
        if (_state.value.busy) return
        invalidateConfirmation()
    }

    fun commit() {
        val s = _state.value
        val plan = s.confirmation ?: return
        if (s.busy || s.reviewRequired) return
        handle[PREFIX + "writing"] = true
        _state.update { it.copy(busy = true, confirmation = null) }
        viewModelScope.launch {
            try {
                val current = ledger()
                val result = withContext(ioDispatcher) { current.confirm(plan, plan.confirmation) }
                val reread = withContext(ioDispatcher) { current.load() }
                val row = reread.byTimestamp(plan.ts)
                check(row != null && row.id == plan.id && row.active) { "独立重读未找到该笔有效记录" }
                lastSnapshot = reread
                clearDraftKeys()
                handle[PREFIX + "writing"] = false
                val message = when (result) {
                    is AccountingConfirmResult.Confirmed ->
                        "已写入App私有隔离验证库并独立重读确认；非正式账本，未接真实数据。"
                    is AccountingConfirmResult.AlreadyWritten ->
                        "该笔此前已写入（幂等确认），已独立重读确认，未重复追加。"
                }
                _state.update {
                    it.copy(
                        busy = false, draft = null, error = null, notice = message,
                        rows = reread.rows, findings = reread.findings, revision = reread.revision,
                        income = reread.income, expense = reread.expense, net = reread.net,
                    )
                }
                recomputeReports()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update {
                    it.copy(
                        busy = false, reviewRequired = true,
                        error = "写入结果未确认：${describe(e)}；请保留草稿并核对隔离库，不会自动重试。",
                    )
                }
            }
        }
    }

    fun discard() {
        if (_state.value.busy) return
        invalidateConfirmation()
        clearDraftKeys()
        _state.update {
            it.copy(draft = null, confirmation = null, notice = "已放弃当前草稿；不修改或删除已写流水。")
        }
    }

    /** 计划失效必须回执 ledger.cancel，避免旧确认在别处继续生效。 */
    private fun invalidateConfirmation() {
        val plan = _state.value.confirmation ?: return
        _state.update { it.copy(confirmation = null) }
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { ledger().cancel(plan) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update {
                    it.copy(notice = "旧核对计划取消失败（${describe(e)}）；该计划已从界面移除，请勿复用旧确认。")
                }
            }
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
        else -> null
    }
    return if (text == null) code else "$code（$text）"
}
