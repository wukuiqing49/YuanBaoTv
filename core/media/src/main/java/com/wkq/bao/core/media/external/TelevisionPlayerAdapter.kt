package com.wkq.bao.core.media.external

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import org.json.JSONObject

/** 厂商适配只选择已验证的播放器；未适配设备继续使用内置播放器。 */
object TelevisionPlayerAdapter {
    val xiaomiComponent = ComponentName("com.mitv.mivideoplayer", "com.mitv.videoplayer.VideoPlayerActivity")

    internal fun supportsXiaomi(television: Boolean, manufacturer: String, brand: String, api: Int): Boolean =
        television && api >= 26 && listOf(manufacturer, brand).any {
            it.equals("xiaomi", true) || it.equals("redmi", true)
        }

    fun resolve(context: Context): ComponentName? {
        val tv = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
        if (!supportsXiaomi(tv, Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty(), Build.VERSION.SDK_INT)) return null
        return runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getActivityInfo(xiaomiComponent, 0)
            xiaomiComponent.takeIf { info.enabled && info.exported && info.applicationInfo.enabled }
        }.getOrNull()
    }

    fun createIntent(component: ComponentName, uri: android.net.Uri, mimeType: String, title: String = ""): Intent =
        Intent("tv.intent.action.external.mitv.mivideoplayer.PLAY_URL").apply {
            this.component = component
            setDataAndType(uri, mimeType)
            // 已核对本机 buildLocalPlayInfo 的入参；不伪造在线内容、会员或广告配置。
            putExtra("playInfo", JSONObject().put("title", title).put("url", uri.toString())
                .put("index", 0).put("rootPath", "").put("sendby", "com.wkq.bao").toString())
            clipData = android.content.ClipData.newRawUri("video", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
