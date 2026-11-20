package com.knte.preview.time.host

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.WindowManager
import com.knte.preview.time.overlay.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TimeMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val authority by lazy { AppTimeAuthority.get(applicationContext) }
    private val operations = Mutex()
    private var owned: OpaqueHostHandle? = null
    private var panel: TimerOverlayPanel? = null
    private val window by lazy { getSystemService(WindowManager::class.java) }
    private var selected: String? = null
    private var expanded = true
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MONITOR, ACTION_SHOW -> {
                if (!monitorAccess(this).allowed) {
                    _status.value = MonitorStatus.Failed
                    stopSelf()
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
                        _status.value = MonitorStatus.Failed
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    scope.launch { authority.state.collect { render() } }
                    scope.launch {
                        while (isActive && monitorAccess(this@TimeMonitorService).allowed) delay(1000)
                        operations.withLock {
                            detach()
                            cancelOwn()
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
                _status.value = MonitorStatus.Failed
                stopSelf()
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

    private suspend fun cancelOwn() {
        val handle = owned ?: return
        owned = null
        if (authority.currentOpaqueHandle() == handle) {
            try { authority.cancel(handle) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* No retries or implicit acknowledgement. */ }
        }
    }

    private suspend fun hide() {
        detach()
        cancelOwn()
        _status.value = if (started) MonitorStatus.Hidden else MonitorStatus.Off
    }

    private fun handle(intent: TimerOverlayIntent) {
        scope.launch {
            operations.withLock {
                if (panel == null || !monitorAccess(this@TimeMonitorService).allowed) return@withLock
                try {
                    when (intent) {
                        is TimerOverlayIntent.Start -> if (authority.state.value.phase is AuthorityPhase.Ready) owned = authority.prepareStart(intent.projectText)
                        is TimerOverlayIntent.Stop -> if (authority.state.value.phase is AuthorityPhase.Ready) owned = authority.prepareStop(intent.targetId)
                        is TimerOverlayIntent.Confirm -> if (owned?.value == intent.handle.value && authority.currentOpaqueHandle() == owned) {
                            val handle = checkNotNull(owned)
                            owned = null
                            authority.confirm(handle)
                        }
                        is TimerOverlayIntent.Cancel -> if (owned?.value == intent.handle.value) cancelOwn()
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

    private fun render() {
        val targetPanel = panel ?: return
        val phase = when (val p = authority.state.value.phase) {
            AuthorityPhase.Starting -> TimerOverlayPhase.Unloaded
            is AuthorityPhase.Busy -> TimerOverlayPhase.Busy(p.message)
            is AuthorityPhase.Preparing -> {
                val handle = owned?.takeIf { authority.currentOpaqueHandle() == it }?.toPanelHandle()
                when {
                    handle == null -> TimerOverlayPhase.Busy("请在发起操作的界面核对")
                    p.op == "start" -> TimerOverlayPhase.PreparingStart(p.handle.project, handle)
                    else -> TimerOverlayPhase.PreparingStop(
                        TimerOverlayTarget(p.handle.targetId, p.handle.project, "${p.handle.date} ${p.handle.start}", "本地时记"), handle
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
                val target = TimerOverlaySelectionResolver.resolve(snapshot, selected)?.id ?: running.singleOrNull()?.id
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
        internal val status = _status.asStateFlow()
    }
}

internal enum class MonitorStatus(val label: String, val active: Boolean = false, val visible: Boolean = false) {
    Off("未开启"), Visible("浮窗已显示", true, true), Hidden("运行中，浮窗已隐藏", true),
    Failed("未开启：请检查悬浮窗和通知设置")
}
