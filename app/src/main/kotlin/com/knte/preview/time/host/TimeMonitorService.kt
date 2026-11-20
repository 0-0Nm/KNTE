package com.knte.preview.time.host

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.WindowManager
import com.knte.preview.time.overlay.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TimeMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val authority by lazy { AppTimeAuthority.get(applicationContext) }
    private val owner = "monitor-${java.util.UUID.randomUUID()}"
    private val operations = Mutex()
    private val window by lazy { getSystemService(WindowManager::class.java) }
    private var panel: TimerOverlayPanel? = null
    private var owned: OpaqueHostHandle? = null
    private var started = false

    /** Explicit selection: null == no explicit choice yet. A stale selection that no longer
     *  matches the live running snapshot is invalidated instead of implicitly falling back
     *  to another running record (regression fix: removing A must never let stop act on B). */
    private var selected: String? = null
    private var expanded = true

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MONITOR, ACTION_SHOW -> {
                if (!monitorAccess(this).allowed) {
                    failNow()
                    return START_NOT_STICKY
                }
                if (!started) {
                    try {
                        getSystemService(NotificationManager::class.java).createNotificationChannel(
                            NotificationChannel(CHANNEL, "计时监控", NotificationManager.IMPORTANCE_LOW)
                        )
                        startForeground(NOTIFICATION_ID, notification())
                        started = true
                    } catch (_: RuntimeException) {
                        failNow()
                        return START_NOT_STICKY
                    }
                    scope.launch {
                        authority.openOwner(owner)
                        runCatching { authority.initialize() }
                        runCatching { authority.reload() }
                    }
                    scope.launch { authority.state.collect { render() } }
                    scope.launch {
                        while (isActive && monitorAccess(this@TimeMonitorService).allowed) delay(1000)
                        operations.withLock {
                            detach()
                            closeOwnerScoped()
                            _status.value = MonitorStatus.Failed
                            stopSelf()
                        }
                    }
                }
                if (started) show()
            }
            ACTION_HIDE -> scope.launch { operations.withLock { hide() } }
            ACTION_STOP_MONITOR -> scope.launch {
                operations.withLock { hide(); stopSelf() }
            }
            else -> if (!started) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun failNow() {
        _status.value = MonitorStatus.Failed
        stopSelf()
    }

    private fun show() {
        if (panel == null) {
            val next = TimerOverlayPanel(this, ::handle)
            try {
                window.addView(next, WindowManager.LayoutParams(
                    dp(320), -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
                ))
                panel = next
            } catch (_: RuntimeException) {
                failNow()
                return
            }
        }
        _status.value = MonitorStatus.Visible
        render()
    }

    private fun detach() {
        panel?.let { runCatching { window.removeViewImmediate(it) } }
        panel = null
        selected = null
    }

    private suspend fun closeOwnerScoped() {
        try { authority.closeOwner(owner, "monitor-stopped") }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* issued handle stays discoverable; no retry/ack/clear */ }
    }

    /** Display-only: the monitor owner stays open and any active prepare of this surface is
     *  preserved — hiding the window never cancels or hides business state. */
    private suspend fun hide() {
        detach()
        _status.value = if (started) MonitorStatus.Hidden else MonitorStatus.Off
    }

    private fun handle(intent: TimerOverlayIntent) {
        scope.launch {
            operations.withLock {
                if (panel == null || !monitorAccess(this@TimeMonitorService).allowed) return@withLock
                try {
                    when (intent) {
                        is TimerOverlayIntent.Start -> if (authority.state.value.phase is AuthorityPhase.Ready) {
                            owned = authority.prepareStart(intent.projectText, owner)
                        }
                        is TimerOverlayIntent.Stop -> if (authority.state.value.phase is AuthorityPhase.Ready) {
                            // Stop acts only on the explicitly selected target: never infer.
                            val target = resolveSelectedForAction()
                            if (target == intent.targetId) owned = authority.prepareStop(intent.targetId, owner)
                        }
                        is TimerOverlayIntent.Confirm -> {
                            val mine = ownedHandleSnapshot()
                            if (mine?.value == intent.handle.value && authority.ownedHandle(owner) == mine) {
                                authority.confirm(mine, owner)
                                owned = null
                            }
                        }
                        is TimerOverlayIntent.Cancel -> if (ownedHandleSnapshot()?.value == intent.handle.value) cancelOwn()
                        TimerOverlayIntent.Hide -> hide()
                        TimerOverlayIntent.ToggleExpanded -> expanded = !expanded
                        is TimerOverlayIntent.SelectTarget -> selected = intent.targetId
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Authority publishes business failure; no automatic retry. */ }
                render()
            }
        }
    }

    private fun ownedHandleSnapshot(): OpaqueHostHandle? = authority.ownedHandle(owner)

    private suspend fun cancelOwn() {
        val handle = owned ?: return
        owned = null
        if (authority.ownedHandle(owner) == handle) {
            try { authority.cancel(handle, owner) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* No retries or implicit acknowledgement. */ }
        }
    }

    /** Explicit-selection policy: an expired selection is dropped; without an explicit
     *  selection there is NO action fallback — the panel shows "请选择目标". */
    private fun resolveSelectedForAction(): String? {
        val v = authority.currentView() ?: return null
        return MonitorSelectionPolicy.explicitActionTarget(selected, v.records.filter { it.running() }.map { it.id })
    }

    private fun render() {
        val targetPanel = panel ?: return
        val phase = when (val p = authority.state.value.phase) {
            AuthorityPhase.Starting -> TimerOverlayPhase.Unloaded
            is AuthorityPhase.Busy -> TimerOverlayPhase.Busy(p.message)
            is AuthorityPhase.Preparing -> {
                val handle = ownedHandleSnapshot()?.takeIf { authority.currentOpaqueHandle() == it }?.toPanelHandle()
                when {
                    handle == null -> TimerOverlayPhase.Busy("请在发起操作的界面核对")
                    p.op == "start" -> TimerOverlayPhase.PreparingStart(p.handle.project, handle, TimerOverlayEventTimes("${p.handle.date} ${p.handle.start}"))
                    else -> TimerOverlayPhase.PreparingStop(
                        TimerOverlayTarget(p.handle.targetId, p.handle.project, "${p.handle.date} ${p.handle.start}", "本地时记"), handle, TimerOverlayEventTimes("${p.handle.date} ${p.handle.start}", p.handle.end?.let { "${p.handle.endDate ?: p.handle.date} $it" })
                    )
                }
            }
            is AuthorityPhase.Review -> TimerOverlayPhase.PendingReview(p.message)
            is AuthorityPhase.Failed -> TimerOverlayPhase.Failed(p.message)
            is AuthorityPhase.Ready -> {
                val running = p.view.records.filter { it.running() }.map {
                    TimerOverlayTarget(it.id, it.project, "${it.date} ${it.start}", "本地时记")
                }
                val snapshot = TimerOverlaySnapshot(running)
                val target: String? = TimerOverlaySelectionResolver.resolve(snapshot, selected)?.id
                when {
                    p.view.reviewRequired || p.view.readError != null -> TimerOverlayPhase.PendingReview("待人工核对")
                    running.isEmpty() -> TimerOverlayPhase.Idle(snapshot)
                    else -> TimerOverlayPhase.Running(snapshot, target)
                }
            }
        }
        targetPanel.render(TimerOverlayPresentation(phase, TimerOverlayMonitor.Running, expanded = expanded))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun notification(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_popup_sync)
        .setContentTitle("计时监控").setContentText("本地计时控制已启用").setOngoing(true)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .apply { packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            setContentIntent(PendingIntent.getActivity(this@TimeMonitorService, 1, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } }
        .build()

    override fun onDestroy() {
        detach()
        authority.closeOwnerDetached(owner, "monitor-destroyed")
        if (_status.value != MonitorStatus.Failed) _status.value = MonitorStatus.Off
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "timer-control-low"
        const val NOTIFICATION_ID = 4101
        const val ACTION_MONITOR = "com.knte.preview.timer.MONITOR"
        const val ACTION_SHOW = "com.knte.preview.timer.SHOW"
        const val ACTION_HIDE = "com.knte.preview.timer.HIDE"
        const val ACTION_STOP_MONITOR = "com.knte.preview.timer.STOP_MONITOR"
        private val _status = MutableStateFlow(MonitorStatus.Off)
        internal val status: StateFlow<MonitorStatus> = _status.asStateFlow()
    }
}

internal enum class MonitorStatus(val label: String, val active: Boolean = false, val visible: Boolean = false) {
    Off("未开启"), Visible("浮窗已显示", true, true), Hidden("运行中，浮窗已隐藏", true),
    Failed("未开启：请检查悬浮窗和通知设置")
}

/** Pure, testable action-target policy. Display may show the sole running record, but a
 *  stop action requires an EXPLICIT selection that still matches the live snapshot:
 *  no implicit fallback (removing A must never silently aim stop at B). */
internal object MonitorSelectionPolicy {
    fun explicitActionTarget(selected: String?, runningIds: List<String>): String? =
        selected?.takeIf { it.isNotBlank() && runningIds.contains(it) }
}
