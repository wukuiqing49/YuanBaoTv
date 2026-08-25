package com.wkq.bao.core.media.smb

import android.content.Context
import android.net.Uri
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.NasSourceEntity
import java.util.concurrent.ConcurrentHashMap

/** 在进程内保存已经由 Room 读取的 NAS 配置，避免把凭据写入播放 URI。 */
object SmbCredentialRegistry {
    private val sources = ConcurrentHashMap<String, NasSourceEntity>()

    fun register(source: NasSourceEntity) {
        sources[key(source.host, source.shareName)] = source
    }

    fun resolve(uri: Uri): NasSourceEntity? {
        val location = SmbClientManager.parseLocation(uri) ?: return null
        return sources[key(location.host, location.shareName)]
    }

    /**
     * MediaSessionService 可能在应用进程被系统回收后单独重建，
     * 此时内存缓存为空，需从本地数据库安全恢复 NAS 来源。
     * 调用方必须在 Media3 的加载线程执行，避免阻塞主线程。
     */
    fun resolve(context: Context, uri: Uri): NasSourceEntity? {
        resolve(uri)?.let { return it }
        val location = SmbClientManager.parseLocation(uri) ?: return null
        return AppDatabase.getInstance(context.applicationContext)
            .nasDao()
            .getEnabledSmbSourceByAddress(location.host, location.shareName)
            ?.also(::register)
    }

    private fun key(host: String, shareName: String): String = "${host.lowercase()}|${shareName.trim('/').lowercase()}"
}
