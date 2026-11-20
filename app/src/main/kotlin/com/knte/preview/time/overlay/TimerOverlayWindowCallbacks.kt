package com.knte.preview.time.overlay

/** Optional UI-thread callbacks. They never emit timer intents or mutate View translations.
 * The host owns WindowManager.LayoutParams, attachment/lifecycle checks, screen-edge/inset
 * clamping, gravity conversion, IME flags and updateViewLayout. Null/unset means no dragging.
 */
interface TimerOverlayWindowCallbacks {
    /** Incremental physical screen pixels (+x right, +y down), not dp or absolute positions. */
    fun onDragBy(deltaX: Float, deltaY: Float)

    /** Advisory focus signal, not proof an IME is visible. Host may manage focus flags/IME.
     * On install, the panel reports current focus; prepared/non-editable phases clear focus.
     */
    fun onProjectFocusChanged(focused: Boolean)
}

/** Pure touch-slop bookkeeping; no Android/window/timer effects. */
internal class TimerOverlayDragGesture(private val slop: Float) {
    init { require(slop.isFinite() && slop >= 0f) }
    data class Delta(val x: Float, val y: Float)
    private var active = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    fun begin(x: Float, y: Float) {
        end()
        if (!x.isFinite() || !y.isFinite()) return
        active = true
        downX = x; downY = y; lastX = x; lastY = y
    }

    fun move(x: Float, y: Float): Delta? {
        if (!active) return null
        if (!x.isFinite() || !y.isFinite()) { end(); return null }
        val dx = x - downX
        val dy = y - downY
        if (!moved && dx * dx + dy * dy <= slop * slop) return null
        moved = true
        val delta = Delta(x - lastX, y - lastY)
        lastX = x; lastY = y
        return delta
    }

    fun end(): Boolean {
        val wasMoved = moved
        active = false; moved = false
        return wasMoved
    }
}
