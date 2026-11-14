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
    private val title = label("时记悬浮", 18f)
    private val status = label("尚未加载", 14f)
    private val clock = label("", 28f)
    private val project = EditText(context).apply {
        hint = "项目（最多96字）"
        maxLines = 1
        setSingleLine(true)
        contentDescription = "项目名称"
        minHeight = dp(48)
    }
    private val targetList = LinearLayout(context).apply { orientation = VERTICAL }
    private val details = label("", 13f)
    private val primary = button("开始")
    private val confirm = button("确认")
    private val cancel = button("取消")
    private val expand = button("展开")
    private val hide = button("隐藏浮窗")
    private val dragHandle = label("拖动", 12f)
    private var rendered: TimerOverlayPresentation = TimerOverlayPresentation()
    private var dragMoved = false

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
        val reviewRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        reviewRow.addView(confirm, lp(0, 52, 1f))
        reviewRow.addView(cancel, lp(0, 52, 1f))
        addView(reviewRow, lp(-1, 56))
        val footer = LinearLayout(context).apply { orientation = HORIZONTAL }
        footer.addView(expand, lp(0, 52, 1f))
        footer.addView(hide, lp(0, 52, 1f))
        addView(footer, lp(-1, 56))

        primary.setOnClickListener { onPrimary() }
        confirm.setOnClickListener { currentHandle()?.let { emit(TimerOverlayIntent.Confirm(it)) } }
        cancel.setOnClickListener { currentHandle()?.let { emit(TimerOverlayIntent.Cancel(it)) } }
        expand.setOnClickListener { emit(TimerOverlayIntent.ToggleExpanded) }
        hide.setOnClickListener { emit(TimerOverlayIntent.Hide) }
        dragHandle.setOnTouchListener(DragTouchListener())
        post { installDragTouchDelegate() }
        project.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (rendered.phase is TimerOverlayPhase.Idle) primary.isEnabled = TimerOverlayText.projectInput(s?.toString().orEmpty()).isNotBlank()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        importantA11y()
        render(rendered)
    }

    fun render(next: TimerOverlayPresentation) {
        rendered = next
        status.text = TimerOverlayText.phaseLabel(next.phase)
        clock.text = next.clockText?.let { "估算：$it" } ?: "分钟事实由主机提供"
        project.visibility = if (next.phase is TimerOverlayPhase.PreparingStart || next.phase is TimerOverlayPhase.Idle) VISIBLE else GONE
        targetList.removeAllViews()
        details.text = ""
        val snapshot = snapshotOf(next.phase)
        val resolvedSelection = TimerOverlaySelectionResolver.resolve(next.phase)
        snapshot?.running?.forEach { target ->
            val selected = resolvedSelection?.id == target.id
            val item = button(if (selected) "✓ ${target.projectText} · ${target.id}" else "${target.projectText} · ${target.id}")
            item.contentDescription = "选择目标 ${target.projectText}，${target.id}，开始于 ${target.startedAtText}"
            item.isSelected = selected
            item.setOnClickListener { emit(TimerOverlayIntent.SelectTarget(target.id)) }
            targetList.addView(item, lp(-1, 52))
        }
        when (val phase = next.phase) {
            is TimerOverlayPhase.PreparingStart -> details.text = "项目：${phase.projectText}\n来源：主机待核对"
            is TimerOverlayPhase.PreparingStop -> details.text = TimerOverlayText.targetDetails(phase.target)
            is TimerOverlayPhase.PendingReview -> details.text = phase.message
            is TimerOverlayPhase.Failed -> details.text = phase.message + (phase.lastGood?.let { "\n已保留上次可用投影" } ?: "")
            is TimerOverlayPhase.Confirmed -> details.text = phase.operationText
            is TimerOverlayPhase.Busy -> details.text = phase.operationText
            else -> Unit
        }
        primary.text = when (next.phase) {
            is TimerOverlayPhase.Running -> if (resolvedSelection == null) "先选择目标" else "停止所选"
            else -> "开始"
        }
        primary.isEnabled = (next.phase is TimerOverlayPhase.Idle && TimerOverlayText.projectInput(project.text.toString()).isNotBlank()) ||
            (next.phase is TimerOverlayPhase.Running && resolvedSelection != null)
        confirm.visibility = if (next.phase is TimerOverlayPhase.PreparingStart || next.phase is TimerOverlayPhase.PreparingStop) VISIBLE else GONE
        cancel.visibility = confirm.visibility
        confirm.isEnabled = confirm.visibility == VISIBLE
        cancel.isEnabled = confirm.isEnabled
        expand.text = if (next.expanded) "收起" else "展开"
        val detailVisible = next.expanded || confirm.visibility == VISIBLE || next.phase is TimerOverlayPhase.Failed || next.phase is TimerOverlayPhase.PendingReview
        targetList.visibility = if (next.expanded) VISIBLE else GONE
        details.visibility = if (detailVisible) VISIBLE else GONE
        if (next.requestProjectFocus) project.requestFocus()
    }

    private fun onPrimary() {
        when (val phase = rendered.phase) {
            is TimerOverlayPhase.Idle -> TimerOverlayText.projectInput(project.text.toString()).takeIf { it.isNotBlank() }?.let { emit(TimerOverlayIntent.Start(it)) }
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
        primary.contentDescription = "真实开始或停止时间记录"
        confirm.contentDescription = "确认当前计时操作"
        cancel.contentDescription = "取消当前待确认操作"
    }

    private inner class DragTouchListener : OnTouchListener {
        private var downX = 0f; private var downY = 0f; private var originX = 0f; private var originY = 0f
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        override fun onTouch(v: View, e: MotionEvent): Boolean = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; originX = translationX; originY = translationY; dragMoved = false; true }
            MotionEvent.ACTION_MOVE -> { val dx = e.rawX - downX; val dy = e.rawY - downY; if (!dragMoved && dx * dx + dy * dy > slop * slop) dragMoved = true; if (dragMoved) { translationX = originX + dx; translationY = originY + dy }; true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { val consumed = dragMoved; dragMoved = false; consumed }
            else -> false
        }
    }

    private fun label(text: String, size: Float) = TextView(context).apply { this.text = text; textSize = size; setTextColor(ink); setPadding(dp(2), 0, dp(2), 0) }
    private fun button(text: String) = Button(context).apply { this.text = text; textSize = 14f; setTextColor(ink); minHeight = dp(48); minimumHeight = dp(48); background = rounded(Color.rgb(236, 226, 210), 12); stateListAnimator = null }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), Color.rgb(205, 191, 171)) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LayoutParams(if (w == 0) 0 else if (w == -1) LayoutParams.MATCH_PARENT else w, if (h == -2) LayoutParams.WRAP_CONTENT else dp(h)).apply { this.weight = weight }
}
