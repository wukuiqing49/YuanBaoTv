package com.wkq.bao.core.base.diagnostics

import android.content.Context
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 仅保存在设备本地的轻量诊断记录。
 * 事件调用方不得传入 NAS 地址、用户名、文件名、Uri、凭据或媒体标题。
 */
object AppDiagnostics {
    private const val PREFERENCES = "app_diagnostics"
    private const val KEY_EVENTS = "events"
    private const val MAX_EVENTS = 200
    private val lock = Any()

    fun record(context: Context, category: String, event: String) {
        val line = "${timestamp()}|${category.sanitize()}|${event.sanitize()}"
        synchronized(lock) {
            val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val events = preferences.getString(KEY_EVENTS, "").orEmpty()
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()
                .takeLast(MAX_EVENTS - 1)
                .toMutableList()
            events += line
            preferences.edit().putString(KEY_EVENTS, events.joinToString("\n")).apply()
        }
    }

    fun createShareIntent(context: Context): Intent {
        val appContext = context.applicationContext
        val directory = File(appContext.cacheDir, "shared").apply { mkdirs() }
        val report = File(directory, "yuanbao-tv-diagnostics.txt")
        report.writeText(createReport(appContext), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", report)
        return Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("diagnostics", uri) }
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_EVENTS)
            .apply()
    }

    private fun createReport(context: Context): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val events = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_EVENTS, "")
            .orEmpty()
            .ifBlank { "(no events)" }
        return buildString {
            appendLine("YuanBao TV diagnostics")
            appendLine("generated_at=${timestamp()}")
            appendLine("app_version=${packageInfo.versionName ?: "unknown"}")
            appendLine("android_sdk=${android.os.Build.VERSION.SDK_INT}")
            appendLine("events:")
            append(events)
        }
    }

    private fun String.sanitize(): String = replace(Regex("[^A-Za-z0-9_.-]"), "_").take(48)

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
}
