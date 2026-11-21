package com.knte.preview.time.host

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    // Returning from a system screen refreshes both grants. A callback never starts a service.
    LaunchedEffect(context) {
        while (true) {
            access = monitorAccess(context)
            delay(1000)
        }
    }
    fun request(block: () -> Unit) {
        error = null
        try { block() } catch (_: RuntimeException) {
            error = "操作未完成。请检查系统设置；已有计时没有改变。"
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("悬浮计时", style = MaterialTheme.typography.titleMedium)
            Text("当前状态：${status.label}")
            Text("悬浮窗权限：${if (access.overlay) "已允许" else "未允许"} · 系统通知：${if (access.notifications) "已允许" else "未允许"}")
            Text("隐藏只收起小窗；退出监控也不会结束正在计时的项目。", style = MaterialTheme.typography.bodySmall)
            if (!access.notifications) {
                Text("通知未允许也可使用悬浮窗，但系统通知可能不可见。", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!access.overlay) {
                OutlinedButton(onClick = {
                    request {
                        settings.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    }
                }) { Text("去系统设置悬浮窗权限") }
            }
            if (!access.notifications) {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    OutlinedButton(onClick = { request { notification.launch(Manifest.permission.POST_NOTIFICATIONS) } }) {
                        Text("允许通知（可选）")
                    }
                }
                OutlinedButton(onClick = { request {
                    settings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                } }) { Text("管理系统通知") }
            }
            if (!status.visible) {
                Button(
                    enabled = access.allowed && (status.active || canStart),
                    onClick = {
                        request {
                            access = monitorAccess(context)
                            if (access.allowed) context.startForegroundService(
                                Intent(context, TimeMonitorService::class.java).setAction(TimeMonitorService.ACTION_MONITOR)
                            )
                        }
                    },
                ) { Text(if (status.active) "重新显示悬浮窗" else "开启悬浮计时") }
            }
            if (status.active) {
                OutlinedButton(onClick = {
                    request { context.startService(Intent(context, TimeMonitorService::class.java).setAction(TimeMonitorService.ACTION_STOP_MONITOR)) }
                }) { Text("退出悬浮监控（不停止计时）") }
            }
        }
    }
}
