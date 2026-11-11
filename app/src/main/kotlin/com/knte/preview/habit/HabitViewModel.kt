package com.knte.preview.habit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knte.core.habit.HabitAppendPlan
import com.knte.core.habit.HabitCheck
import com.knte.core.habit.HabitCodec
import com.knte.core.habit.HabitIdentity
import com.knte.core.habit.HabitLedgerApi
import com.knte.core.habit.HabitMark
import com.knte.core.habit.HabitProjection
import com.knte.core.habit.HabitSnapshot
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
import java.time.LocalDateTime
import java.util.UUID

/** §9.1 固定 28 天热力图窗口；窗口大小不是可配置项。 */
const val HABIT_HEAT_DAYS = 28

/** 隔离目的地说明：私有隔离库，不是正式 §9.1 check-only 文件，也不是真实数据源。 */
const val HABIT_STORE_LABEL =
    "本 App 私有隔离库 · HabitLedgerStore · db/habits.jsonl（schema knte-habit-v1，追加式）"

private const val PREFIX = "knte.isolated.habit."

enum class HabitDraftKind { CREATE, EDIT, CHECK }

/** 草稿只承载尚未追加的输入；`baseline*` 用于判定 dirty，不参与写入。 */
data class HabitDraft(
    val kind: HabitDraftKind,
    val habitId: String? = null,
    val name: String = "",
    val targetDaysText: String = "",
    val baselineName: String? = null,
    val baselineTargetDays: Int? = null,
    val date: String = "",
    val baselineDate: String = "",
    val done: Boolean = true,
    val baselineDone: Boolean = true,
    val note: String = "",
) {
    val dirty: Boolean
        get() = when (kind) {
            HabitDraftKind.CREATE -> name.isNotBlank() || targetDaysText.isNotBlank()
            HabitDraftKind.EDIT ->
                name != baselineName.orEmpty() ||
                    targetDaysText != baselineTargetDays?.toString().orEmpty()
            HabitDraftKind.CHECK ->
                date != baselineDate || done != baselineDone || note.isNotBlank()
        }
}

/** 核对卡载荷：即将追加哪一条事件，以及 core 签发的不可变计划。 */
data class HabitConfirmation(
    val op: String,
    val plan: HabitAppendPlan,
    val habitId: String,
    val habitName: String,
    val targetDays: Int? = null,
    val archived: Boolean? = null,
    val date: String? = null,
    val done: Boolean? = null,
    val note: String? = null,
)

/** 全部展示值来自同一份真实快照；streak / stage / heat / total 都是投影，从不落库。 */
data class HabitUiState(
    val loaded: Boolean = false,
    val snapshot: HabitSnapshot? = null,
    val selectedId: String? = null,
    val showArchived: Boolean = false,
    val today: String = LocalDate.now().toString(),
    val draft: HabitDraft? = null,
    val busy: Boolean = false,
    val confirmation: HabitConfirmation? = null,
    val error: String? = null,
    val notice: String? = null,
    val reviewRequired: Boolean = false,
) {
    val identities: List<HabitIdentity>
        get() = snapshot?.identities(showArchived).orEmpty()

    val selected: HabitIdentity?
        get() = selectedId?.let { id -> snapshot?.identity(id) }

    /** 同源投影：总完成、当前连续、成长阶段、28 天热力图。 */
    val selectedTotalDone: Int?
        get() = selected?.let { snapshot!!.totalDone(it.id, LocalDate.parse(today)) }

    val selectedStreak: Int?
        get() = selected?.let { snapshot!!.currentStreak(it.id, LocalDate.parse(today)) }

    val selectedStage: String?
        get() = selectedStreak?.let { HabitProjection.stage(it) }

    val selectedStageLevel: Int
        get() = selectedStreak?.let { HabitProjection.stageLevel(it) } ?: 0

    val selectedHeat: List<HabitMark>
        get() = selected?.let { snapshot!!.heat(it.id, LocalDate.parse(today), HABIT_HEAT_DAYS) }
            .orEmpty()

    /** 最近一天一行：同日改答只保留最新一条，历史行仍在盘上。 */
    val selectedHistory: List<HabitCheck>
        get() = selected?.let { snapshot!!.history(it.id).sortedByDescending { row -> row.date } }
            .orEmpty()

    val selectedSameDayCorrections: Int
        get() = snapshot?.sameDayCorrectionCount ?: 0

    val hasUnsavedChanges: Boolean get() = draft?.dirty == true

    val canLeave: Boolean get() = !busy && !hasUnsavedChanges
}

/** 只拥有 App 私有隔离习惯库。写入走 core 两阶段：prepare → 人工核对卡 → confirm + 独立重读；
 *  计划一旦失效必须回执 cancel；写入结果未知置 reviewRequired，不自动重试；错误保留草稿。 */
class HabitViewModel(
    private val store: () -> HabitLedgerApi,
    private val handle: SavedStateHandle = SavedStateHandle(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(HabitUiState())
    val state: StateFlow<HabitUiState> = _state.asStateFlow()

    private var cachedApi: HabitLedgerApi? = null

    private fun api(): HabitLedgerApi = cachedApi ?: store().also { cachedApi = it }

    init {
        restoreDraft()
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

    // ---- read -------------------------------------------------------------

    fun reload() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val snapshot = withContext(ioDispatcher) { api().snapshot() }
                val today = LocalDate.now().toString()
                _state.update { s ->
                    s.copy(
                        loaded = true,
                        busy = false,
                        snapshot = snapshot,
                        today = today,
                        selectedId = s.selectedId?.takeIf { id -> snapshot.identity(id) != null }
                            ?: snapshot.identities(false).firstOrNull()?.id,
                        error = if (s.reviewRequired) s.error else null,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update {
                    it.copy(
                        busy = false,
                        error = if (it.reviewRequired) {
                            "上次写入尚无确认回执，且隔离库当前不可读：${describe(e)}"
                        } else {
                            "隔离库读取失败，未视为空库：${describe(e)}"
                        },
                    )
                }
            }
        }
    }

    fun select(id: String) {
        val s = _state.value
        if (s.busy || s.reviewRequired) return
        if (s.snapshot?.identity(id) == null) {
            _state.update { it.copy(error = "目标习惯不存在，未切换选择。") }
            return
        }
        _state.update { it.copy(selectedId = id, error = null, notice = null) }
    }

    fun showArchivedChanged(value: Boolean) {
        val s = _state.value
        if (s.busy) return
        _state.update { it.copy(showArchived = value) }
    }

    // ---- create / edit ----------------------------------------------------

    fun beginCreate() {
        if (!gate()) return
        val today = LocalDate.now().toString()
        persist(HabitDraft(HabitDraftKind.CREATE, date = today, baselineDate = today))
    }

    fun beginEdit(id: String) {
        val s = _state.value
        if (s.busy || s.reviewRequired || s.draft != null) return
        val identity = s.snapshot?.identity(id)
        if (identity == null) {
            _state.update { it.copy(error = "目标习惯不存在，未开始编辑。") }
            return
        }
        persist(
            HabitDraft(
                kind = HabitDraftKind.EDIT,
                habitId = identity.id,
                name = identity.name,
                targetDaysText = identity.targetDays.toString(),
                baselineName = identity.name,
                baselineTargetDays = identity.targetDays,
            )
        )
    }

    fun nameChanged(value: String) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        if (d.kind == HabitDraftKind.CHECK) return
        if (value.length > 80) return
        invalidateConfirmation()
        persist(d.copy(name = value))
    }

    /** 目标天数只接受严格整数文本；空、符号、小数、前导零一律拒绝，不猜测不补默认值。 */
    fun targetDaysChanged(value: String) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        if (d.kind == HabitDraftKind.CHECK) return
        if (value.length > 4 || !value.all { it in '0'..'9' }) {
            invalidateConfirmation()
            _state.update { it.copy(notice = "目标天数只接受 1..3650 的整数；原输入保留。") }
            return
        }
        invalidateConfirmation()
        persist(d.copy(targetDaysText = value))
    }

    fun prepareIdentity() {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        when (d.kind) {
            HabitDraftKind.CREATE -> {
                val name = d.name.trim()
                if (name.isEmpty()) {
                    failPrepare("习惯名称不能为空；未生成核对计划。")
                    return
                }
                val target = parseTargetDays(d.targetDaysText)
                if (target == null) {
                    failPrepare("目标天数只接受 1..3650 的整数；未生成核对计划。")
                    return
                }
                val habitId = newHabitId()
                buildPlan("create", habitId) { api().planCreate(habitId, name, target, newEventId(), nowStamp()) }
            }
            HabitDraftKind.EDIT -> {
                val id = d.habitId ?: return
                val identity = _state.value.snapshot?.identity(id)
                if (identity == null) {
                    failPrepare("目标习惯不存在，未生成核对计划。")
                    return
                }
                val normalizedName = d.name.trim()
                val parsedTarget = parseTargetDays(d.targetDaysText)
                if (normalizedName.isEmpty() || parsedTarget == null) {
                    failPrepare("名称不能为空，目标天数必须为1..3650整数；原草稿保留。")
                    return
                }
                val name = normalizedName.takeIf { it != identity.name }
                val target = parsedTarget.takeIf { it != identity.targetDays }
                if (name == null && target == null) {
                    failPrepare("名称与目标天数均未变化，未生成核对计划。")
                    return
                }
                buildPlan("edit", id) { api().planEdit(id, name, target, newEventId(), nowStamp()) }
            }
            HabitDraftKind.CHECK -> return
        }
    }

    // ---- daily check ------------------------------------------------------

    fun beginCheck(id: String) {
        val s = _state.value
        if (s.busy || s.reviewRequired || s.draft != null) return
        val identity = s.snapshot?.identity(id)
        if (identity == null) {
            _state.update { it.copy(error = "目标习惯不存在，未开始打卡。") }
            return
        }
        if (identity.archived) {
            _state.update { it.copy(error = "已归档的习惯不能继续打卡；可先恢复再打卡。") }
            return
        }
        val today = LocalDate.now().toString()
        persist(
            HabitDraft(
                kind = HabitDraftKind.CHECK,
                habitId = identity.id,
                date = today,
                baselineDate = today,
                done = true,
                baselineDone = true,
            )
        )
    }

    /** 过去日期可以补记；未来日期一律拒绝（core 的 HABIT_FUTURE_CHECK 合同）。 */
    fun checkDateChanged(value: String) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        if (d.kind != HabitDraftKind.CHECK) return
        if (value.length > 10) return
        invalidateConfirmation()
        persist(d.copy(date = value))
    }

    fun checkDoneChanged(value: Boolean) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        if (d.kind != HabitDraftKind.CHECK) return
        invalidateConfirmation()
        persist(d.copy(done = value))
    }

    fun checkNoteChanged(value: String) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        if (d.kind != HabitDraftKind.CHECK) return
        if (value.length > 200) return
        invalidateConfirmation()
        persist(d.copy(note = value))
    }

    fun prepareCheck() {
        val s = _state.value
        val d = s.draft ?: return
        if (s.busy || s.reviewRequired) return
        if (d.kind != HabitDraftKind.CHECK) return
        val id = d.habitId ?: return
        val identity = s.snapshot?.identity(id)
        if (identity == null) {
            failPrepare("目标习惯不存在，未生成核对计划。")
            return
        }
        if (identity.archived) {
            failPrepare("已归档的习惯不能继续打卡；未生成核对计划。")
            return
        }
        val day = parseDate(d.date)
        if (day == null) {
            failPrepare("日期须为真实日期 YYYY-MM-DD；未生成核对计划。")
            return
        }
        if (day.isAfter(LocalDate.parse(s.today))) {
            failPrepare("不能为未来日期打卡；未生成核对计划。")
            return
        }
        buildPlan("check", id) { api().planCheck(id, d.date, d.done, d.note.ifBlank { null }, newEventId(), nowStamp()) }
    }

    // ---- archive / restore ------------------------------------------------

    fun prepareArchive(id: String, archive: Boolean) {
        val s = _state.value
        if (s.busy || s.reviewRequired) return
        if (s.draft != null) {
            _state.update { it.copy(error = "请先结束当前草稿，再归档或恢复。") }
            return
        }
        val identity = s.snapshot?.identity(id)
        if (identity == null) {
            _state.update { it.copy(error = "目标习惯不存在，未生成核对计划。") }
            return
        }
        if (identity.archived == archive) {
            _state.update { it.copy(error = if (archive) "该习惯已在归档状态。" else "该习惯未归档。") }
            return
        }
        buildPlan(if (archive) "archive" else "restore", id) { api().planArchive(id, archive, newEventId(), nowStamp()) }
    }

    // ---- commit -----------------------------------------------------------

    fun confirmCommit() {
        val s = _state.value
        val c = s.confirmation ?: return
        if (s.busy || s.reviewRequired) return
        handle[PREFIX + "writing"] = true
        _state.update { it.copy(busy = true, confirmation = null) }
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { api().commit(c.plan, c.plan.confirmation) }
                // 独立重读真实库状态，不用 commit 自身返回值自证。
                val reread = withContext(ioDispatcher) { api().snapshot() }
                verify(c, reread)
                handle[PREFIX + "writing"] = false
                clearDraftKeys()
                _state.update {
                    it.copy(
                        busy = false,
                        draft = null,
                        confirmation = null,
                        error = null,
                        loaded = true,
                        snapshot = reread,
                        today = LocalDate.now().toString(),
                        selectedId = c.habitId,
                        notice = noticeFor(c, reread),
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update {
                    it.copy(
                        busy = false,
                        reviewRequired = true,
                        error = "写入结果未确认，请保留草稿并核对隔离库，不会自动重试：${describe(e)}",
                    )
                }
            }
        }
    }

    fun dismissConfirmation() {
        if (_state.value.busy) return
        invalidateConfirmation()
    }

    fun discard() {
        if (_state.value.busy) return
        invalidateConfirmation()
        clearDraftKeys()
        _state.update {
            it.copy(draft = null, confirmation = null, notice = "已放弃当前草稿；未向隔离库追加任何事件。")
        }
    }

    // ---- internals --------------------------------------------------------

    private fun gate(): Boolean {
        val s = _state.value
        if (s.busy) return false
        if (s.reviewRequired) {
            _state.update { it.copy(error = "待人工核对状态下不开始新的输入；不自动重试、不自动清理 pending。") }
            return false
        }
        if (s.draft != null) return false
        return true
    }

    private fun failPrepare(message: String) {
        _state.update { it.copy(busy = false, confirmation = null, error = message) }
    }

    private fun buildPlan(op: String, habitId: String, build: () -> HabitAppendPlan) {
        _state.update { it.copy(busy = true, error = null, confirmation = null) }
        viewModelScope.launch {
            try {
                val plan = withContext(ioDispatcher) { build() }
                val identity = _state.value.snapshot?.identity(habitId)
                val d = _state.value.draft
                val targetDays = parseTargetDays(d?.targetDaysText.orEmpty())
                val c = when (op) {
                    "create" -> HabitConfirmation("create", plan, habitId,
                        identity?.name ?: d?.name.orEmpty(), targetDays, identity?.archived)
                    "edit" -> HabitConfirmation("edit", plan, habitId,
                        d?.name?.trim() ?: identity?.name.orEmpty(), targetDays, identity?.archived)
                    "archive" -> HabitConfirmation("archive", plan, habitId,
                        identity?.name.orEmpty(), identity?.targetDays, identity?.archived)
                    "restore" -> HabitConfirmation("restore", plan, habitId,
                        identity?.name.orEmpty(), identity?.targetDays, identity?.archived)
                    else -> HabitConfirmation("check", plan, habitId,
                        identity?.name.orEmpty(), identity?.targetDays, identity?.archived,
                        d?.date, d?.done, d?.note?.ifBlank { null })
                }
                _state.update { it.copy(busy = false, confirmation = c) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(busy = false, error = "未写入，核对计划失败：${describe(e)}") }
            }
        }
    }

    /** 独立重读后的效果核对：看不到预期效果就不算成功。 */
    private fun verify(c: HabitConfirmation, reread: HabitSnapshot) {
        when (c.op) {
            "create" -> {
                val identity = reread.identity(c.habitId)
                check(identity != null && !identity.archived) { "独立重读未发现新建习惯" }
                check(c.targetDays == null || identity.targetDays == c.targetDays) { "独立重读的目标天数不一致" }
            }
            "edit" -> {
                val identity = reread.identity(c.habitId)
                check(identity != null) { "独立重读未找到该习惯" }
                check(c.habitName.isEmpty() || identity.name == c.habitName) { "独立重读的名称不一致" }
                check(c.targetDays == null || identity.targetDays == c.targetDays) { "独立重读的目标天数不一致" }
            }
            "archive" -> check(reread.identity(c.habitId)?.archived == true) { "独立重读未发现已归档" }
            "restore" -> check(reread.identity(c.habitId)?.archived == false) { "独立重读未发现已恢复" }
            "check" -> {
                val row = reread.check(c.habitId, LocalDate.parse(c.date!!))
                check(row != null && row.done == c.done) { "独立重读未发现该日打卡" }
            }
            else -> error("HABIT_OP_UNSUPPORTED:${c.op}")
        }
    }

    private fun noticeFor(c: HabitConfirmation, reread: HabitSnapshot): String = when (c.op) {
        "create" -> "已创建习惯并独立重读确认；非正式数据源，未接真实数据库。"
        "edit" -> "已更新习惯并独立重读确认；历史打卡一行未改。"
        "archive" -> "已归档并独立重读确认；没有物理删除任何历史记录，归档后不能继续打卡。"
        "restore" -> "已恢复并独立重读确认；历史记录原样保留。"
        "check" -> {
            val corrections = reread.events().count {
                it.op == "check" && it.check?.habitId == c.habitId
            }
            "已追加打卡并独立重读确认；同日改答只追加新行，历史保留（该习惯累计 $corrections 条打卡事件）。"
        }
        else -> "已写入并独立重读确认。"
    }

    /** 计划失效必须回执 cancel，避免旧确认在别处继续生效。 */
    private fun invalidateConfirmation() {
        val plan = _state.value.confirmation?.plan ?: return
        _state.update { it.copy(confirmation = null) }
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { api().cancel(plan) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update {
                    it.copy(notice = "旧核对计划取消失败（${describe(e)}）；该计划已从界面移除，请勿复用旧确认。")
                }
            }
        }
    }

    private fun persist(d: HabitDraft) {
        handle[PREFIX + "draft"] = true
        handle[PREFIX + "kind"] = d.kind.name
        handle[PREFIX + "habitId"] = d.habitId
        handle[PREFIX + "name"] = d.name
        handle[PREFIX + "targetDaysText"] = d.targetDaysText
        handle[PREFIX + "baselineName"] = d.baselineName
        handle[PREFIX + "baselineTargetDays"] = d.baselineTargetDays
        handle[PREFIX + "date"] = d.date
        handle[PREFIX + "baselineDate"] = d.baselineDate
        handle[PREFIX + "done"] = d.done
        handle[PREFIX + "baselineDone"] = d.baselineDone
        handle[PREFIX + "note"] = d.note
        _state.update { it.copy(draft = d, confirmation = null, error = null) }
    }

    private fun restoreDraft() {
        if (handle.get<Boolean>(PREFIX + "draft") != true) return
        val kind = handle.get<String>(PREFIX + "kind")
            ?.let { runCatching { HabitDraftKind.valueOf(it) }.getOrNull() }
            ?: return
        val habitId = handle.get<String>(PREFIX + "habitId")
        val name = handle.get<String>(PREFIX + "name").orEmpty()
        val targetDaysText = handle.get<String>(PREFIX + "targetDaysText").orEmpty()
        val date = handle.get<String>(PREFIX + "date").orEmpty()
        val note = handle.get<String>(PREFIX + "note").orEmpty()
        val done = handle.get<Boolean>(PREFIX + "done") ?: true
        if (name.length > 80 || targetDaysText.length > 4 || date.length > 10 || note.length > 200) return
        _state.update {
            it.copy(
                draft = HabitDraft(
                    kind = kind,
                    habitId = habitId,
                    name = name,
                    targetDaysText = targetDaysText,
                    baselineName = handle.get<String>(PREFIX + "baselineName"),
                    baselineTargetDays = handle.get<Int>(PREFIX + "baselineTargetDays"),
                    date = date,
                    baselineDate = handle.get<String>(PREFIX + "baselineDate").orEmpty(),
                    done = done,
                    baselineDone = handle.get<Boolean>(PREFIX + "baselineDone") ?: true,
                    note = note,
                ),
                notice = "已恢复未追加的草稿输入；核对计划不恢复，确认动作不会自动发生。",
            )
        }
    }

    private fun clearDraftKeys() {
        listOf(
            "draft", "kind", "habitId", "name", "targetDaysText", "baselineName",
            "baselineTargetDays", "date", "baselineDate", "done", "baselineDone", "note",
        ).forEach { handle.remove<Any?>(PREFIX + it) }
    }
}

private fun newHabitId(): String = "h-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)

private fun newEventId(): String = HabitCodec.newEventId()

private fun nowStamp(): String = HabitCodec.stamp(LocalDateTime.now())

/** 严格整数目标天数：仅 0-9 数字、1..3650；不猜、不补默认值、不接受小数与符号。 */
internal fun parseTargetDays(text: String): Int? {
    if (text.isEmpty() || text.length > 4) return null
    if (!text.all { it in '0'..'9' }) return null
    val value = text.toLongOrNull() ?: return null
    if (value !in 1L..3650L) return null
    return value.toInt()
}

/** 严格日期：形状 YYYY-MM-DD 且为真实日历日。 */
internal fun parseDate(text: String): LocalDate? {
    if (!text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) return null
    val day = runCatching { LocalDate.parse(text) }.getOrNull() ?: return null
    return day.takeIf { it.toString() == text }
}

/** core 错误码的可读文案；保留原始码，不吞码。 */
internal fun describe(e: Exception): String {
    val code = e.message ?: e.javaClass.simpleName
    val text = when (code) {
        "HABIT_ID_INVALID" -> "习惯 id 形状无效（h- 加 8 位十六进制）"
        "HABIT_EVENT_ID_INVALID" -> "事件 id 形状无效"
        "HABIT_NAME_CONTROL_CHAR" -> "名称含控制字符"
        "TEXT_TOO_LONG" -> "文本超过上限"
        "TEXT_BLANK" -> "文本为空或全空白"
        "HABIT_TARGET_DAYS_RANGE" -> "目标天数须在 1..3650"
        "HABIT_HEAT_DAYS_RANGE" -> "热力图天数超出范围"
        "HABIT_DAILY_RESERVED" -> "该 id 为日新保留，不能自建"
        "HABIT_CREATE_CONFLICT" -> "同名 id 已存在，不会静默改为编辑"
        "HABIT_CREATE_FIELDS_REQUIRED" -> "创建必须提供名称与目标天数"
        "HABIT_NOT_FOUND" -> "习惯不存在"
        "HABIT_ARCHIVED" -> "已归档的习惯不能打卡"
        "HABIT_NAME_MISMATCH" -> "名称与既有身份不一致"
        "HABIT_CREATED_TS_CHANGED" -> "创建时间被改动，拒绝"
        "HABIT_FUTURE_CHECK" -> "不能为未来日期打卡"
        "HABIT_DATE_INVALID", "DATE_FORMAT_INVALID" -> "日期须为真实日期 YYYY-MM-DD"
        "DATE_NOT_REAL" -> "不是真实日历日"
        "HABIT_TS_INVALID", "TS_FORMAT_INVALID", "TS_NOT_REAL" -> "时间戳无效"
        "HABIT_EVENT_MULTILINE" -> "事件必须是单行"
        "HABIT_FILE_CAPACITY" -> "文件超过 2MiB 上限"
        "HABIT_EVENT_CAPACITY" -> "事件数超过上限"
        "HABIT_EVENT_ID_CONFLICT" -> "事件 id 冲突"
        "HABIT_BASELINE_CHANGED" -> "库内容已变化，计划过期；请重新核对"
        "HABIT_CONFIRMATION_MISMATCH" -> "确认令牌不匹配"
        "HABIT_WRITE_RESULT_UNKNOWN" -> "写入结果未知，需人工核对"
        "HABIT_PENDING_REVIEW_REQUIRED" -> "存在 pending 标记，需人工检查"
        "HABIT_DB_ENTRY_UNEXPECTED" -> "库目录出现意外条目"
        "HABIT_STORE_LINK_REJECTED" -> "库路径含符号链接，拒绝"
        "HABIT_STORE_ROOT_CHANGED" -> "库根路径已变化"
        "PLAN_UNKNOWN" -> "计划不存在（进程重建后单次计划失效）"
        "PLAN_ALREADY_CONFIRMED" -> "计划已确认过，不可重复确认"
        "PLAN_CANCELLED" -> "计划已取消"
        "HABIT_ROOT_MISMATCH" -> "计划与当前库根不匹配"
        else -> null
    }
    return if (text == null) code else "$code（$text）"
}
