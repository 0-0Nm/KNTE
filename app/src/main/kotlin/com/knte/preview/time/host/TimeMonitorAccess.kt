package com.knte.preview.time.host

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

internal data class MonitorAccess(val overlay: Boolean, val notifications: Boolean) {
    /** Overlay is the only monitor gate. Notification denial hides/demotes system notice
     *  visibility, but does not disable the user-visible floating timer or page timer. */
    val allowed: Boolean get() = overlay
}

internal fun monitorAccess(context: Context) = MonitorAccess(
    overlay = Settings.canDrawOverlays(context),
    notifications = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED),
)
