package com.knte.preview.time.overlay

/** Immutable, host-provided projection. It contains no repository plan or Android lifecycle object. */
data class TimerOverlayPresentation(
    val phase: TimerOverlayPhase = TimerOverlayPhase.Unloaded,
    val monitor: TimerOverlayMonitor = TimerOverlayMonitor.Stopped,
    val expanded: Boolean = false,
    /** Host-owned display text; never treated as a persisted duration. */
    val clockText: String? = null,
    val requestProjectFocus: Boolean = false,
)

data class TimerOverlayTarget(
    val id: String,
    val projectText: String,
    val startedAtText: String,
    val sourceText: String,
    val minuteFactText: String? = null,
) {
    init {
        require(id.isNotBlank()) { "target id must not be blank" }
        require(projectText.isNotBlank()) { "project text must not be blank" }
    }
}

data class TimerOverlaySnapshot(
    val running: List<TimerOverlayTarget> = emptyList(),
    val sourceText: String = "本地时记",
    val revisionText: String = "未核对",
) {
    init { require(running.none { it.id.isBlank() }) }
}

/** Pure fail-closed lookup for actions that target the current running snapshot. */
object TimerOverlaySelectionResolver {
    fun resolve(phase: TimerOverlayPhase): TimerOverlayTarget? = when (phase) {
        is TimerOverlayPhase.Running -> resolve(phase.snapshot, phase.selectedTargetId)
        else -> null
    }

    fun resolve(snapshot: TimerOverlaySnapshot, selectedTargetId: String?): TimerOverlayTarget? {
        val id = selectedTargetId?.takeIf { it.isNotBlank() } ?: return null
        return snapshot.running.singleOrNull { it.id == id }
    }
}

sealed interface TimerOverlayPhase {
    data object Unloaded : TimerOverlayPhase
    data class Idle(val snapshot: TimerOverlaySnapshot) : TimerOverlayPhase
    /** selectedTargetId is null when multiple targets require an explicit choice. */
    data class Running(
        val snapshot: TimerOverlaySnapshot,
        val selectedTargetId: String? = null,
    ) : TimerOverlayPhase
    data class PreparingStart(
        val projectText: String,
        val handle: OpaqueHandle,
        val eventTimes: TimerOverlayEventTimes? = null,
    ) : TimerOverlayPhase
    data class PreparingStop(
        val target: TimerOverlayTarget,
        val handle: OpaqueHandle,
        val eventTimes: TimerOverlayEventTimes? = null,
    ) : TimerOverlayPhase
    data class Busy(val operationText: String) : TimerOverlayPhase
    data class PendingReview(val message: String, val lastGood: TimerOverlaySnapshot? = null) : TimerOverlayPhase
    data class Failed(val message: String, val lastGood: TimerOverlaySnapshot? = null) : TimerOverlayPhase
    data class Confirmed(val operationText: String, val targetId: String? = null) : TimerOverlayPhase
}

/** Display-only values from the SAME immutable prepared handle, never the current clock.
 * Null means unavailable; the panel explicitly says so instead of inventing a time.
 * The host includes the date (including a cross-day end date) in each supplied value.
 */
data class TimerOverlayEventTimes(val startText: String? = null, val endText: String? = null) {
    init {
        require(startText == null || startText.isNotBlank())
        require(endText == null || endText.isNotBlank())
    }
}

enum class TimerOverlayMonitor { Running, Stopped }

/** Opaque by contract: only the host that issued it may interpret its value. */
@JvmInline
value class OpaqueHandle(val value: String) {
    init { require(value.isNotBlank()) { "opaque handle must not be blank" } }
}

sealed interface TimerOverlayIntent {
    data class Start(val projectText: String) : TimerOverlayIntent {
        init { require(TimerOverlayText.projectInputError(projectText) == null) { "invalid project input" } }
    }
    data class Stop(val targetId: String) : TimerOverlayIntent {
        init { require(targetId.isNotBlank()) }
    }
    data class Confirm(val handle: OpaqueHandle) : TimerOverlayIntent
    data class Cancel(val handle: OpaqueHandle) : TimerOverlayIntent
    data class SelectTarget(val targetId: String) : TimerOverlayIntent {
        init { require(targetId.isNotBlank()) }
    }
    data object ToggleExpanded : TimerOverlayIntent
    data object Hide : TimerOverlayIntent
}

object TimerOverlayPresentationMapper {
    fun idle(snapshot: TimerOverlaySnapshot): TimerOverlayPresentation =
        TimerOverlayPresentation(phase = TimerOverlayPhase.Idle(snapshot))

    fun running(
        snapshot: TimerOverlaySnapshot,
        selectedTargetId: String? = null,
        clockText: String? = null,
    ): TimerOverlayPresentation = TimerOverlayPresentation(
        phase = TimerOverlayPhase.Running(snapshot, selectedTargetId),
        clockText = clockText,
    )

    fun failed(message: String, lastGood: TimerOverlaySnapshot?): TimerOverlayPresentation =
        TimerOverlayPresentation(phase = TimerOverlayPhase.Failed(message, lastGood))

    fun pending(message: String, lastGood: TimerOverlaySnapshot?): TimerOverlayPresentation =
        TimerOverlayPresentation(phase = TimerOverlayPhase.PendingReview(message, lastGood))
}

object TimerOverlayText {
    const val maxProjectLength = 96

    /** Source-compatible adapter: valid input is returned VERBATIM; invalid input is refused.
     * No trimming, normalization or UTF-16 substring truncation is allowed at this boundary.
     */
    fun projectInput(raw: String): String = if (projectInputError(raw) == null) raw else ""

    /** Limit counts Unicode code points, not graphemes or UTF-16 code units. */
    fun projectInputError(raw: String): String? {
        var index = 0
        var count = 0
        while (index < raw.length) {
            val ch = raw[index++]
            if (Character.isHighSurrogate(ch)) {
                if (index == raw.length || !Character.isLowSurrogate(raw[index])) return "项目包含不完整的 Unicode 字符"
                index++
            } else if (Character.isLowSurrogate(ch)) return "项目包含不完整的 Unicode 字符"
            count++
        }
        return when {
            raw.isBlank() -> "请输入项目名称"
            count > maxProjectLength -> "项目最多 $maxProjectLength 个 Unicode 字符（当前 $count）"
            else -> null
        }
    }

    fun projectEditable(phase: TimerOverlayPhase): Boolean = phase is TimerOverlayPhase.Idle

    fun preparingDetails(phase: TimerOverlayPhase): String = when (phase) {
        is TimerOverlayPhase.PreparingStart ->
            "项目：${phase.projectText}\n来源：主机待核对\n计划开始：${phase.eventTimes?.startText ?: "主机未提供"}"
        is TimerOverlayPhase.PreparingStop -> targetDetails(phase.target) +
            "\n计划开始：${phase.eventTimes?.startText ?: "主机未提供"}" +
            "\n计划停止：${phase.eventTimes?.endText ?: "主机未提供"}"
        else -> ""
    }

    fun phaseLabel(phase: TimerOverlayPhase): String = when (phase) {
        TimerOverlayPhase.Unloaded -> "尚未加载"
        is TimerOverlayPhase.Idle -> "空闲"
        is TimerOverlayPhase.Running -> if (phase.snapshot.running.size > 1) "运行中 · 请选择目标" else "运行中"
        is TimerOverlayPhase.PreparingStart -> "准备核对 · 开始"
        is TimerOverlayPhase.PreparingStop -> "准备核对 · 停止"
        is TimerOverlayPhase.Busy -> "处理中"
        is TimerOverlayPhase.PendingReview -> "待人工核对"
        is TimerOverlayPhase.Failed -> "读取失败 · 显示上次可用"
        is TimerOverlayPhase.Confirmed -> "已确认"
    }

    fun targetDetails(target: TimerOverlayTarget): String = buildString {
        append("项目：").append(target.projectText)
        append("\n目标：").append(target.id)
        append("\n开始：").append(target.startedAtText)
        append("\n来源：").append(target.sourceText)
        target.minuteFactText?.let { append("\n分钟事实：").append(it) }
    }
}
