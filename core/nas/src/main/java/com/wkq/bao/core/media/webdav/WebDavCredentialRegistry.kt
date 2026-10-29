package com.wkq.bao.core.media.webdav

import android.content.Context
import android.net.Uri
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.NasSourceEntity
import java.util.concurrent.ConcurrentHashMap

/** 让播放器按 URL 找回对应 WebDAV 凭据，凭据本身仍只保留在加密配置中。 */
object WebDavCredentialRegistry {
    private val sources = ConcurrentHashMap<Long, NasSourceEntity>()

    fun register(source: NasSourceEntity) {
        if (WebDavClientManager.isWebDav(source)) sources[source.id] = source
    }

    fun resolve(uri: Uri): NasSourceEntity? = sources.values
        .asSequence()
        .filter { source -> WebDavEndpoint.matches(source, uri.toString()) }
        .maxByOrNull { source -> WebDavEndpoint.baseSegments(source).size }

    suspend fun resolve(context: Context, uri: Uri): NasSourceEntity? {
        resolve(uri)?.let { return it }
        return AppDatabase.getInstance(context.applicationContext)
            .nasDao()
            .getEnabledSources()
            .asSequence()
            .filter(WebDavClientManager::isWebDav)
            .firstOrNull { source -> WebDavEndpoint.matches(source, uri.toString()) }
            ?.also(::register)
    }

}
