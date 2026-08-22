package com.wkq.bao.core.media.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * USB / 外置存储热插拔广播接收器 (支持 USB 插入、拔出与自动 Fallback 降级)
 */
class UsbStorageReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UsbStorageReceiver"
        const val ACTION_USB_STATE_CHANGED = "com.wkq.bao.core.media.USB_STATE_CHANGED"
        const val EXTRA_IS_MOUNTED = "is_mounted"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Received storage action: $action")

        val isMounted = when (action) {
            Intent.ACTION_MEDIA_MOUNTED -> true
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_BAD_REMOVAL -> false
            else -> null
        }

        if (isMounted != null) {
            val notifyIntent = Intent(ACTION_USB_STATE_CHANGED).apply {
                putExtra(EXTRA_IS_MOUNTED, isMounted)
                setPackage(context.packageName)
            }
            context.sendBroadcast(notifyIntent)
        }
    }
}
