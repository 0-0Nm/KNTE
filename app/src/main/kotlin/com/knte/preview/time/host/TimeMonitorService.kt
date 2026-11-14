package com.knte.preview.time.host

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.WindowManager
import com.knte.preview.MainActivity
import com.knte.preview.time.overlay.*
import kotlinx.coroutines.*

class TimeMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val authority by lazy { AppTimeAuthority.get(applicationContext) }
    private var panel: TimerOverlayPanel? = null
    private var window: WindowManager? = null
    override fun onCreate() { super.onCreate(); getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "计时监控", NotificationManager.IMPORTANCE_LOW)); startForeground(NOTIFICATION_ID, notification()); window = getSystemService(WindowManager::class.java) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) { ACTION_MONITOR -> show(); ACTION_SHOW -> show(); ACTION_HIDE -> hide(); ACTION_STOP_MONITOR -> stopSelf() }
        return START_NOT_STICKY
    }
    private fun show() { if (!android.provider.Settings.canDrawOverlays(this)) return; if (panel == null) { panel = TimerOverlayPanel(this) { handle(it) }; window?.addView(panel, layout()) }; authority.setMonitor(true); render() }
    private fun hide() { panel?.let { runCatching { window?.removeView(it) } }; panel = null; authority.setMonitor(false) }
    private fun handle(intent: TimerOverlayIntent) { scope.launch { when (intent) { is TimerOverlayIntent.Start -> authority.prepareStart(intent.projectText); is TimerOverlayIntent.Stop -> authority.prepareStop(intent.targetId); is TimerOverlayIntent.Confirm -> authority.confirm(OpaqueHostHandle(intent.handle.value)); is TimerOverlayIntent.Cancel -> authority.cancel(OpaqueHostHandle(intent.handle.value)); TimerOverlayIntent.Hide -> hide(); TimerOverlayIntent.ToggleExpanded, is TimerOverlayIntent.SelectTarget -> Unit }; render() } }
    private fun render() {
        val state = authority.state.value
        val view = authority.currentView()
        val running = view?.records.orEmpty().filter { it.running() }.map { TimerOverlayTarget(it.id, it.project, "${it.date} ${it.start}", "本地时记") }
        val phase = when (val p = state.phase) {
            is AuthorityPhase.Preparing -> if (p.op == "start") TimerOverlayPhase.PreparingStart(p.handle.project, OpaqueHandle(authority.currentOpaqueHandle()?.value ?: p.handle.nonce)) else TimerOverlayPhase.PreparingStop(TimerOverlayTarget(p.handle.targetId, p.handle.project, "${p.handle.date} ${p.handle.start}", "本地时记"), OpaqueHandle(authority.currentOpaqueHandle()?.value ?: p.handle.nonce))
            is AuthorityPhase.Review -> TimerOverlayPhase.PendingReview(p.message, TimerOverlaySnapshot(running))
            is AuthorityPhase.Failed -> TimerOverlayPhase.Failed(p.message, TimerOverlaySnapshot(running))
            else -> if (running.isEmpty()) TimerOverlayPhase.Idle(TimerOverlaySnapshot()) else TimerOverlayPhase.Running(TimerOverlaySnapshot(running), running.singleOrNull()?.id)
        }
        panel?.render(TimerOverlayPresentation(phase, TimerOverlayMonitor.Running, expanded = true))
    }
    private fun layout() = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
    private fun notification(): Notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("计时监控").setContentText("本地计时控制已启用").setOngoing(true).setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
    override fun onDestroy() { hide(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { const val CHANNEL = "timer-control-low"; const val NOTIFICATION_ID = 4101; const val ACTION_MONITOR = "com.knte.preview.timer.MONITOR"; const val ACTION_SHOW = "com.knte.preview.timer.SHOW"; const val ACTION_HIDE = "com.knte.preview.timer.HIDE"; const val ACTION_STOP_MONITOR = "com.knte.preview.timer.STOP_MONITOR" }
}
