package com.wkq.bao.core.media.webdav

import android.net.Uri
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
        .filter { source ->
            uri.scheme.equals("https", true) && uri.host.equals(sourceHost(source), true) &&
                uri.port == source.port && isUnderSourcePath(uri, source)
        }
        .maxByOrNull { source -> sourceBasePath(source).length }

    private fun sourceHost(source: NasSourceEntity): String? {
        val input = source.host.trim().let { if (it.contains("://")) it else "https://$it" }
        return Uri.parse(input).host
    }

    private fun isUnderSourcePath(uri: Uri, source: NasSourceEntity): Boolean {
        val basePath = sourceBasePath(source)
        if (basePath.isBlank()) return true
        val requestPath = uri.path.orEmpty().trim('/')
        return requestPath.equals(basePath, true) || requestPath.startsWith("$basePath/", true)
    }

    private fun sourceBasePath(source: NasSourceEntity): String =
        listOf(source.shareName, source.rootPath)
            .joinToString("/")
            .trim('/')
}
