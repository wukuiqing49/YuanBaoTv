package com.wkq.bao.core.media.webdav

import com.wkq.bao.core.database.entity.NasSourceEntity
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 扫描、鉴权、播放与下载共用的来源边界，路径区分大小写。 */
object WebDavEndpoint {
    fun supports(scheme: String?): Boolean = scheme.equals("http", true) || scheme.equals("https", true)

    private fun configuredUrl(source: NasSourceEntity): HttpUrl {
        val host = source.host.trim().trimEnd('/')
        val scheme = if (source.port == 80 || source.port == 5005) "http" else "https"
        val url = (if (host.contains("://")) host else "$scheme://$host").toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty()) { "Credentials must use the NAS account fields" }
        return url.newBuilder().port(source.port.takeIf { it in 1..65535 } ?: url.port).build()
    }

    internal fun baseUrl(source: NasSourceEntity): HttpUrl = configuredUrl(source).newBuilder()
        .encodedPath("/").query(null).fragment(null).build()

    internal fun baseSegments(source: NasSourceEntity): List<String> =
        configuredUrl(source).pathSegments.filter(String::isNotBlank) +
            listOf(source.shareName, source.rootPath).flatMap { it.trim('/').split('/') }.filter(String::isNotBlank)

    fun matches(source: NasSourceEntity, address: String): Boolean = runCatching {
        val requested = address.toHttpUrl()
        val expected = baseUrl(source)
        val base = baseSegments(source)
        requested.username.isEmpty() && requested.password.isEmpty() &&
            requested.scheme == expected.scheme && requested.host == expected.host && requested.port == expected.port &&
            requested.pathSegments.take(base.size) == base &&
            requested.pathSegments.none { it == "." || it == ".." || '/' in it || '\\' in it }
    }.getOrDefault(false)
}
