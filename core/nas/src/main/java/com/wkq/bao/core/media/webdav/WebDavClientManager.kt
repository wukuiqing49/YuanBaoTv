package com.wkq.bao.core.media.webdav

import android.net.Uri
import android.util.Base64
import com.wkq.bao.core.database.entity.NasSourceEntity
import com.wkq.bao.core.nas.security.NasCredentialVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** HTTPS WebDAV 的连接、递归扫描与 Range 读取实现。 */
object WebDavClientManager {
    data class RemoteFileInfo(val length: Long, val lastModifiedAt: Long)

    data class RemoteMediaFile(
        val path: String,
        val length: Long,
        val lastModifiedAt: Long
    )

    private data class DavEntry(
        val relativePath: String,
        val isDirectory: Boolean,
        val length: Long,
        val lastModifiedAt: Long
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun isWebDav(source: NasSourceEntity): Boolean = source.type.equals("WEBDAV", ignoreCase = true)

    fun buildUri(source: NasSourceEntity, path: String, cacheVersion: Long = 0L): String {
        val builder = endpoint(source, path).newBuilder()
        if (cacheVersion > 0L) builder.addQueryParameter("v", cacheVersion.toString())
        return builder.build().toString()
    }

    fun authorizationHeader(source: NasSourceEntity): String? {
        if (source.username.isBlank()) return null
        val password = NasCredentialVault.decrypt(source.passwordEncrypted)
        val token = Base64.encodeToString("${source.username}:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $token"
    }

    suspend fun testConnection(source: NasSourceEntity): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            executePropFind(source, "", depth = 0).use { response ->
                check(response.code == 207 || response.isSuccessful) { "WebDAV 连接失败: HTTP ${response.code}" }
            }
            "WebDAV 连接成功"
        }
    }

    suspend fun scanFilesRecursive(
        source: NasSourceEntity,
        resumeAfterPath: String? = null,
        onFile: suspend (RemoteMediaFile) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val directories = ArrayDeque<String>()
            directories += ""
            var visited = 0
            var checkpointReached = resumeAfterPath.isNullOrBlank()
            while (directories.isNotEmpty()) {
                coroutineContext.ensureActive()
                val directory = directories.removeFirst()
                listDirectory(source, directory)
                    .sortedBy { it.relativePath.lowercase(Locale.ROOT) }
                    .forEach { entry ->
                        coroutineContext.ensureActive()
                        if (entry.isDirectory) {
                            directories += entry.relativePath
                        } else {
                            if (!checkpointReached) {
                                checkpointReached = entry.relativePath == resumeAfterPath
                                return@forEach
                            }
                            onFile(RemoteMediaFile(entry.relativePath, entry.length, entry.lastModifiedAt))
                            visited++
                        }
                    }
            }
            check(checkpointReached) { "扫描检查点已失效" }
            visited
        }.onFailure { if (it is CancellationException) throw it }
    }

    suspend fun getRemoteFileInfo(source: NasSourceEntity, uri: Uri): RemoteFileInfo = withContext(Dispatchers.IO) {
        val request = request(source, uri.toString()).head().build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) return@withContext RemoteFileInfo(
                response.header("Content-Length")?.toLongOrNull() ?: error("WebDAV 未返回文件大小"),
                parseHttpDate(response.header("Last-Modified"))
            )
        }
        // 部分 WebDAV 服务不允许 HEAD，使用 0-0 的 Range GET 回退。
        rangeResponse(source, uri.toString(), 0L, 1L).use { response ->
            val range = response.header("Content-Range").orEmpty()
            val length = range.substringAfter('/').toLongOrNull() ?: error("WebDAV 未返回文件大小")
            RemoteFileInfo(length, parseHttpDate(response.header("Last-Modified")))
        }
    }

    suspend fun copyRangeTo(
        source: NasSourceEntity,
        uri: Uri,
        output: OutputStream,
        offset: Long,
        byteCount: Long,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit
    ): String = withContext(Dispatchers.IO) {
        require(offset >= 0L && byteCount > 0L) { "WebDAV 分块范围无效" }
        rangeResponse(source, uri.toString(), offset, byteCount).use { response ->
            check(response.code == 206) { "WebDAV 服务不支持 Range 下载: HTTP ${response.code}" }
            val stream = checkNotNull(response.body).byteStream()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            var copied = 0L
            onProgress(0L, byteCount)
            stream.use {
                while (copied < byteCount) {
                    coroutineContext.ensureActive()
                    val expected = minOf(buffer.size.toLong(), byteCount - copied).toInt()
                    val read = it.read(buffer, 0, expected)
                    if (read <= 0) error("WebDAV 分块读取提前结束")
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    copied += read
                    onProgress(copied, byteCount)
                }
            }
            output.flush()
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    fun isRetryable(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it is IOException }

    private fun listDirectory(source: NasSourceEntity, directory: String): List<DavEntry> {
        executePropFind(source, directory, depth = 1).use { response ->
            check(response.code == 207) { "WebDAV 目录读取失败: HTTP ${response.code}" }
            return parseEntries(source, checkNotNull(response.body).byteStream())
                .filter { it.relativePath.isNotBlank() && !it.relativePath.equals(directory.trim('/'), true) }
        }
    }

    private fun executePropFind(source: NasSourceEntity, path: String, depth: Int) = client.newCall(
        request(source, endpoint(source, path).toString())
            .header("Depth", depth.toString())
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            .build()
    ).execute()

    private fun rangeResponse(source: NasSourceEntity, url: String, offset: Long, byteCount: Long) = client.newCall(
        request(source, url)
            .header("Range", "bytes=$offset-${offset + byteCount - 1}")
            .get()
            .build()
    ).execute()

    private fun request(source: NasSourceEntity, url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "*/*")
        .apply { authorizationHeader(source)?.let { header("Authorization", it) } }

    private fun endpoint(source: NasSourceEntity, path: String) = sourceBaseUrl(source).newBuilder().apply {
        (baseSegments(source) + path.trim('/').split('/'))
            .filter(String::isNotBlank)
            .forEach(::addPathSegment)
    }.build()

    private fun sourceBaseUrl(source: NasSourceEntity) = run {
        val host = source.host.trim().removeSuffix("/")
        val input = if (host.contains("://")) host else "https://$host"
        val parsed = input.toHttpUrl()
        parsed.newBuilder()
            .scheme("https")
            .port(source.port.takeIf { it in 1..65535 } ?: DEFAULT_PORT)
            .encodedPath("/")
            .build()
    }

    private fun baseSegments(source: NasSourceEntity): List<String> =
        listOf(source.shareName, source.rootPath)
            .flatMap { it.trim('/').split('/') }
            .filter(String::isNotBlank)

    private fun parseEntries(source: NasSourceEntity, stream: java.io.InputStream): List<DavEntry> {
        val entries = mutableListOf<DavEntry>()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(stream, "UTF-8") }
        var href = ""
        var length = 0L
        var modified = 0L
        var isDirectory = false
        var inResponse = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':').lowercase(Locale.ROOT)) {
                    "response" -> { inResponse = true; href = ""; length = 0L; modified = 0L; isDirectory = false }
                    "collection" -> if (inResponse) isDirectory = true
                    "href" -> if (inResponse) href = parser.nextText()
                    "getcontentlength" -> if (inResponse) length = parser.nextText().toLongOrNull() ?: 0L
                    "getlastmodified" -> if (inResponse) modified = parseHttpDate(parser.nextText())
                }
                XmlPullParser.END_TAG -> if (parser.name.substringAfter(':').equals("response", true) && inResponse) {
                    relativePath(source, href)?.let { entries += DavEntry(it, isDirectory, length, modified) }
                    inResponse = false
                }
            }
            parser.next()
        }
        return entries
    }

    private fun relativePath(source: NasSourceEntity, href: String): String? {
        val responsePath = Uri.decode(Uri.parse(href).path.orEmpty()).trim('/')
        val basePath = baseSegments(source).joinToString("/")
        return when {
            basePath.isBlank() -> responsePath
            responsePath.equals(basePath, true) -> ""
            responsePath.startsWith("$basePath/", true) -> responsePath.removePrefix(basePath).trim('/')
            else -> null
        }
    }

    private fun parseHttpDate(value: String?): Long = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
            .parse(value.orEmpty())?.time ?: 0L
    }.getOrDefault(0L)

    private val XML_MEDIA_TYPE = "text/xml; charset=utf-8".toMediaType()
    private val PROPFIND_BODY = """<?xml version="1.0"?><propfind xmlns="DAV:"><prop><resourcetype/><getcontentlength/><getlastmodified/></prop></propfind>"""
    private const val DEFAULT_PORT = 5006
    private const val COPY_BUFFER_SIZE = 256 * 1024
}
