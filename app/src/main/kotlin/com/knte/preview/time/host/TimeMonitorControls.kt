package com.knte.preview.time.host

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@Composable
internal fun TimeMonitorControls(canStart: Boolean) {
    val context = LocalContext.current
    val status by TimeMonitorService.status.collectAsStateWithLifecycle()
    var access by remember(context) { mutableStateOf(monitorAccess(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        access = monitorAccess(context)
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        access = monitorAccess(context)
    }
    // Refresh only; callbacks and permission changes never start the service.
    LaunchedEffect(context) {
        while (true) {
            access = monitorAccess(context)
            delay(1000)
        }
    }
    fun request(block: () -> Unit) {
        error = null
        try { block() } catch (_: RuntimeException) { error = "操作未完成，请检查系统设置后再试。" }
    }
    Column {
        Text("计时监控：${status.label}")
        Text("悬浮窗：${if (access.overlay) "已允许" else "未允许"} · 通知：${if (access.notifications) "已允许" else "未允许"}")
        Text("隐藏浮窗或退出监控都不会停止已有计时。")
        error?.let { Text(it) }
        if (!access.overlay) OutlinedButton(onClick = {
            request { settings.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
        }) { Text("打开悬浮窗权限设置") }
        if (!access.notifications) {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                OutlinedButton(onClick = { request { notification.launch(Manifest.permission.POST_NOTIFICATIONS) } }) { Text("请求通知权限") }
            }
            OutlinedButton(onClick = { request {
                settings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            } }) { Text("打开通知设置") }
        }
        if (!status.visible) OutlinedButton(enabled = canStart && access.allowed, onClick = {
            request {
                access = monitorAccess(context)
                if (access.allowed) context.startForegroundService(Intent(context, TimeMonitorService::class.java).setAction(TimeMonitorService.ACTION_MONITOR))
            }
        }) { Text(if (status.active) "显示浮窗" else "开启计时监控") }
        if (status.active) OutlinedButton(onClick = {
            request { context.startService(Intent(context, TimeMonitorService::class.java).setAction(TimeMonitorService.ACTION_STOP_MONITOR)) }
        }) { Text("退出计时监控") }
    }
}
