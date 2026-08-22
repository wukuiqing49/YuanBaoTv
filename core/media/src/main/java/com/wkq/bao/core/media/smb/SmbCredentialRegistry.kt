package com.wkq.bao.core.media.smb

import android.net.Uri
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

    private fun key(host: String, shareName: String): String = "${host.lowercase()}|${shareName.trim('/').lowercase()}"
}
