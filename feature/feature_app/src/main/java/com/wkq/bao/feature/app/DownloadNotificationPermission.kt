package com.wkq.bao.feature.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build

/** 仅在用户已创建下载后申请；拒绝不影响下载，也不在启动时反复打扰。 */
internal object DownloadNotificationPermission {
    fun requestIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33 || activity.isFinishing ||
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val preferences = activity.getSharedPreferences("download_permissions", android.content.Context.MODE_PRIVATE)
        if (preferences.getBoolean("notification_requested", false)) return
        preferences.edit().putBoolean("notification_requested", true).apply()
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }
}
