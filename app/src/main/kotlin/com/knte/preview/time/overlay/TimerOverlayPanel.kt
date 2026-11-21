package com.knte.preview.time.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.TouchDelegate
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Native Android panel only: renders a projection and emits bounded intents. */
class TimerOverlayPanel(
    context: Context,
    private val emit: (TimerOverlayIntent) -> Unit,
) : LinearLayout(context) {
    private val ink = Color.rgb(38, 34, 29)
    private val paper = Color.rgb(248, 243, 232)
    private val title = label("时记", 18f)
    private val status = label("尚未加载", 14f)
    private val clock = label("", 28f)
    private val project = EditText(context).apply {
        hint = "要做什么？输入项目名称"
        maxLines = 1
        setSingleLine(true)
        contentDescription = "新计时项目名称"
        minHeight = dp(48)
    }
    private val targetList = LinearLayout(context).apply { orientation = VERTICAL }
    private val details = label("", 13f)
    private val primary = button("开始计时")
    private val alternate = button("另起项目")
    private val confirm = button("确认")
    private val cancel = button("取消")
    private val expand = button("展开")
    private val hide = button("隐藏浮窗")
    private val dragHandle = label("拖动", 12f)
    private var rendered: TimerOverlayPresentation = TimerOverlayPresentation()
    private var windowCallbacks: TimerOverlayWindowCallbacks? = null
    private val dragTouchListener = DragTouchListener()

    /** Optional host integration, not a WindowManager implementation. */
    fun setWindowCallbacks(callbacks: TimerOverlayWindowCallbacks?) {
        dragTouchListener.cancel()
        windowCallbacks = callbacks
        dragHandle.text = if (callbacks == null) "拖动（主机未接入）" else "拖动"
        callbacks?.onProjectFocusChanged(project.hasFocus() && project.isEnabled)
    }

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(12))
        background = rounded(paper, 18)
        elevation = dp(8).toFloat()
        addView(dragHandle, lp(-1, 30))
        addView(title, lp(-1, 30))
        addView(status, lp(-1, 30))
        addView(clock, lp(-1, 46))
        addView(project, lp(-1, 52))
        addView(targetList, lp(-1, -2))
        addView(details, lp(-1, -2))
        addView(primary, lp(-1, 52))
        addView(alternate, lp(-1, 52))
        val reviewRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        reviewRow.addView(confirm, lp(0, 52, 1f))
        reviewRow.addView(cancel, lp(0, 52, 1f))
        addView(reviewRow, lp(-1, 56))
        val footer = LinearLayout(context).apply { orientation = HORIZONTAL }
        footer.addView(expand, lp(0, 52, 1f))
        footer.addView(hide, lp(0, 52, 1f))
        addView(footer, lp(-1, 56))

        primary.setOnClickListener { onPrimary() }
        alternate.setOnClickListener {
            when (rendered.phase) {
                is TimerOverlayPhase.Running -> emit(TimerOverlayIntent.NewProject)
                is TimerOverlayPhase.StartingNew -> emit(TimerOverlayIntent.CancelNewProject)
                else -> Unit
            }
        }
        confirm.setOnClickListener { currentHandle()?.let { emit(TimerOverlayIntent.Confirm(it)) } }
        cancel.setOnClickListener { currentHandle()?.let { emit(TimerOverlayIntent.Cancel(it)) } }
        expand.setOnClickListener { emit(TimerOverlayIntent.ToggleExpanded) }
        hide.setOnClickListener { emit(TimerOverlayIntent.Hide) }
        dragHandle.setOnTouchListener(dragTouchListener)
        post { installDragTouchDelegate() }
        project.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (TimerOverlayText.projectEditable(rendered.phase)) {
                    project.error = TimerOverlayText.projectInputError(s?.toString().orEmpty())
                    primary.isEnabled = project.error == null
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        project.setOnFocusChangeListener { _, focused ->
            windowCallbacks?.onProjectFocusChanged(focused && project.isEnabled)
        }
        setWindowCallbacks(null)
        importantA11y()
        render(rendered)
    }

    fun render(next: TimerOverlayPresentation) {
        val previousPhase = rendered.phase
        rendered = next
        status.text = TimerOverlayText.phaseLabel(next.phase)
        val snapshot = snapshotOf(next.phase)
        val resolvedSelection = TimerOverlaySelectionResolver.resolve(next.phase)
        val showClock = next.phase is TimerOverlayPhase.Running || next.phase is TimerOverlayPhase.StartingNew
        clock.visibility = if (showClock) VISIBLE else GONE
        clock.text = next.clockText ?: "耗时待读取"
        project.visibility = if (next.phase is TimerOverlayPhase.PreparingStart ||
            next.phase is TimerOverlayPhase.Idle || next.phase is TimerOverlayPhase.StartingNew) VISIBLE else GONE
        val editable = TimerOverlayText.projectEditable(next.phase)
        project.isEnabled = editable
        project.isFocusable = editable
        project.isFocusableInTouchMode = editable
        if (!editable) project.clearFocus()
        if (next.phase is TimerOverlayPhase.StartingNew && previousPhase !is TimerOverlayPhase.StartingNew) {
            project.setText("")
        }
        if (next.phase is TimerOverlayPhase.PreparingStart && project.text.toString() != next.phase.projectText) {
            project.setText(next.phase.projectText)
        }
        project.error = if (editable && project.text.isNotEmpty()) TimerOverlayText.projectInputError(project.text.toString()) else null
        targetList.removeAllViews()
        details.text = ""
        snapshot?.running?.forEach { target ->
            val selected = resolvedSelection?.id == target.id
            val shortId = target.id.takeLast(6)
            val item = button(if (selected) "✓ ${target.projectText} · ${target.startedAtText} · $shortId"
                else "${target.projectText} · ${target.startedAtText} · $shortId")
            item.contentDescription = "选择停止目标 ${target.projectText}，开始于 ${target.startedAtText}，记录编号 ${target.id}"
            item.isSelected = selected
            item.isEnabled = next.phase !is TimerOverlayPhase.StartingNew
            item.setOnClickListener { emit(TimerOverlayIntent.SelectTarget(target.id)) }
            targetList.addView(item, lp(-1, 52))
        }
        when (val phase = next.phase) {
            is TimerOverlayPhase.PreparingStart, is TimerOverlayPhase.PreparingStop ->
                details.text = TimerOverlayText.preparingDetails(phase)
            is TimerOverlayPhase.StartingNew ->
                details.text = "开始新项目不会停止现有 ${phase.snapshot.running.size} 项计时。"
            is TimerOverlayPhase.Running -> details.text = when {
                resolvedSelection != null && next.expanded -> TimerOverlayText.targetDetails(resolvedSelection)
                phase.snapshot.running.size > 1 -> "有 ${phase.snapshot.running.size} 项同时进行。展开后点选要停止的项目；另起项目不会影响它们。"
                else -> ""
            }
            is TimerOverlayPhase.PendingReview -> details.text = phase.message
            is TimerOverlayPhase.Failed -> details.text = phase.message + (phase.lastGood?.let { "\n已保留上次可用投影" } ?: "")
            is TimerOverlayPhase.Confirmed -> details.text = phase.operationText
            is TimerOverlayPhase.Busy -> details.text = phase.operationText
            else -> Unit
        }
        primary.text = when (next.phase) {
            is TimerOverlayPhase.Idle -> "开始计时"
            is TimerOverlayPhase.StartingNew -> "开始新项目"
            is TimerOverlayPhase.Running -> when {
                resolvedSelection != null -> if (next.phase.snapshot.running.size == 1) "停止此项" else "停止所选项目"
                next.phase.snapshot.running.size > 1 -> "先选择要停止的项目"
                else -> "请重新选择当前项目"
            }
            else -> "开始计时"
        }
        primary.contentDescription = primary.text
        primary.isEnabled = ((next.phase is TimerOverlayPhase.Idle || next.phase is TimerOverlayPhase.StartingNew) &&
            TimerOverlayText.projectInput(project.text.toString()).isNotBlank()) ||
            (next.phase is TimerOverlayPhase.Running && resolvedSelection != null)
        alternate.visibility = if (next.phase is TimerOverlayPhase.Running || next.phase is TimerOverlayPhase.StartingNew) VISIBLE else GONE
        alternate.text = if (next.phase is TimerOverlayPhase.StartingNew) "返回当前计时" else "另起项目"
        confirm.visibility = if (next.phase is TimerOverlayPhase.PreparingStart || next.phase is TimerOverlayPhase.PreparingStop) VISIBLE else GONE
        cancel.visibility = confirm.visibility
        confirm.text = if (next.phase is TimerOverlayPhase.PreparingStop) "确认停止" else "确认开始"
        confirm.isEnabled = confirm.visibility == VISIBLE
        cancel.isEnabled = confirm.isEnabled
        expand.text = if (next.expanded) "收起" else "展开"
        val detailVisible = next.expanded || confirm.visibility == VISIBLE || next.phase is TimerOverlayPhase.StartingNew ||
            next.phase is TimerOverlayPhase.Failed || next.phase is TimerOverlayPhase.PendingReview
        targetList.visibility = if (next.expanded) VISIBLE else GONE
        details.visibility = if (detailVisible && details.text.isNotEmpty()) VISIBLE else GONE
        if (next.requestProjectFocus && editable) project.requestFocus()
    }

    private fun onPrimary() {
        when (val phase = rendered.phase) {
            is TimerOverlayPhase.Idle, is TimerOverlayPhase.StartingNew ->
                TimerOverlayText.projectInput(project.text.toString()).takeIf { it.isNotBlank() }?.let { emit(TimerOverlayIntent.Start(it)) }
            is TimerOverlayPhase.Running -> TimerOverlaySelectionResolver.resolve(phase)?.let {
                emit(TimerOverlayIntent.Stop(it.id))
            }
            else -> Unit
        }
    }

    private fun currentHandle(): OpaqueHandle? = when (val phase = rendered.phase) {
        is TimerOverlayPhase.PreparingStart -> phase.handle
        is TimerOverlayPhase.PreparingStop -> phase.handle
        else -> null
    }

    private fun snapshotOf(phase: TimerOverlayPhase): TimerOverlaySnapshot? = when (phase) {
        is TimerOverlayPhase.Idle -> phase.snapshot
        is TimerOverlayPhase.StartingNew -> phase.snapshot
        is TimerOverlayPhase.Running -> phase.snapshot
        is TimerOverlayPhase.Failed -> phase.lastGood
        is TimerOverlayPhase.PendingReview -> phase.lastGood
        else -> null
    }

    private fun installDragTouchDelegate() {
        val bounds = Rect()
        dragHandle.getHitRect(bounds)
        val minimum = dp(48)
        val extra = ((minimum - bounds.height()).coerceAtLeast(0) + 1) / 2
        bounds.top -= extra
        bounds.bottom += extra
        touchDelegate = TouchDelegate(bounds, dragHandle)
    }

    private fun importantA11y() {
        dragHandle.contentDescription = "拖动悬浮窗，不会触发计时操作"
        hide.contentDescription = "隐藏浮窗，不停止计时"
        primary.contentDescription = "开始或停止时间记录"
        confirm.contentDescription = "确认当前计时操作"
        cancel.contentDescription = "取消当前待确认操作"
    }

    private inner class DragTouchListener : OnTouchListener {
        private val gesture = TimerOverlayDragGesture(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
        fun cancel() { gesture.end() }
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            if (windowCallbacks == null) return false
            return when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { gesture.begin(e.rawX, e.rawY); true }
                MotionEvent.ACTION_MOVE -> {
                    gesture.move(e.rawX, e.rawY)?.let { windowCallbacks?.onDragBy(it.x, it.y) }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!gesture.end()) v.performClick(); true }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { gesture.end(); true }
                else -> false
            }
        }
    }

    private fun label(text: String, size: Float) = TextView(context).apply { this.text = text; textSize = size; setTextColor(ink); setPadding(dp(2), 0, dp(2), 0) }
    private fun button(text: String) = Button(context).apply { this.text = text; textSize = 14f; setTextColor(ink); minHeight = dp(48); minimumHeight = dp(48); background = rounded(Color.rgb(236, 226, 210), 12); stateListAnimator = null }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), Color.rgb(205, 191, 171)) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LayoutParams(if (w == 0) 0 else if (w == -1) LayoutParams.MATCH_PARENT else w, if (h == -2) LayoutParams.WRAP_CONTENT else dp(h)).apply { this.weight = weight }
}
