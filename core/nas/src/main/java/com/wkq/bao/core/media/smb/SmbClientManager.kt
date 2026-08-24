package com.wkq.bao.core.media.smb

import android.net.Uri
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.wkq.bao.core.database.entity.NasSourceEntity
import com.wkq.bao.core.nas.security.NasCredentialVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.Closeable
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** SMB 连接、扫描与按偏移读取能力。播放器和下载器共用该实现。 */
object SmbClientManager {

    data class Location(val host: String, val shareName: String, val path: String)
    data class RemoteFileInfo(val length: Long, val lastModifiedAt: Long)
    data class RemoteMediaFile(val path: String, val length: Long, val lastModifiedAt: Long)

    class RemoteFileHandle internal constructor(
        val file: File,
        val length: Long,
        val lastModifiedAt: Long,
        private val client: SMBClient,
        private val connection: Connection,
        private val session: Session,
        private val share: DiskShare
    ) : Closeable {
        override fun close() {
            runCatching { file.close() }
            runCatching { share.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
        }
    }

    private fun newClient(): SMBClient {
        val config = SmbConfig.builder()
            .withTimeout(10, TimeUnit.SECONDS)
            .withSoTimeout(15, TimeUnit.SECONDS)
            .build()
        return SMBClient(config)
    }

    private fun authentication(source: NasSourceEntity): AuthenticationContext =
        if (source.username.isBlank()) AuthenticationContext.anonymous()
        else AuthenticationContext(source.username, NasCredentialVault.decrypt(source.passwordEncrypted).toCharArray(), "")

    fun parseLocation(uri: Uri): Location? {
        if (uri.scheme != "smb") return null
        val host = uri.host ?: return null
        val segments = uri.pathSegments
        val shareName = segments.firstOrNull().orEmpty()
        if (shareName.isEmpty()) return null
        return Location(host, shareName, segments.drop(1).joinToString("/"))
    }

    fun buildUri(source: NasSourceEntity, path: String): String {
        return Uri.Builder()
            .scheme("smb")
            .authority(source.host)
            .apply {
                (listOf(source.shareName) + path.split('/'))
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach(::appendPath)
            }
            .build()
            .toString()
    }

    fun <T> withRemoteFile(source: NasSourceEntity, uri: Uri, action: (File, Long) -> T): T {
        return openRemoteFile(source, uri).use { action(it.file, it.length) }
    }

    fun getRemoteFileLength(source: NasSourceEntity, uri: Uri): Long =
        openRemoteFile(source, uri).use { it.length }

    fun getRemoteFileInfo(source: NasSourceEntity, uri: Uri): RemoteFileInfo =
        openRemoteFile(source, uri).use { RemoteFileInfo(it.length, it.lastModifiedAt) }

    fun openRemoteFile(source: NasSourceEntity, uri: Uri): RemoteFileHandle {
        val location = parseLocation(uri) ?: error("不是有效的 SMB 地址: $uri")
        require(location.host.equals(source.host, ignoreCase = true)) { "SMB 地址与 NAS 配置不匹配" }
        require(location.shareName.equals(source.shareName.trim('/'), ignoreCase = true)) { "SMB 共享目录不匹配" }

        val client = newClient()
        try {
            val connection = client.connect(source.host, source.port)
            val session = connection.authenticate(authentication(source))
            val share = session.connectShare(location.shareName) as? DiskShare
                ?: error("无法挂载共享目录: ${location.shareName}")
            val remoteFile = share.openFile(
                location.path,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            val fileInformation = remoteFile.fileInformation
            val length = fileInformation.standardInformation.endOfFile
            val lastModifiedAt = fileInformation.basicInformation.lastWriteTime.toEpochMillis()
            return RemoteFileHandle(remoteFile, length, lastModifiedAt, client, connection, session, share)
        } catch (error: Throwable) {
            runCatching { client.close() }
            throw error
        }
    }

    suspend fun testConnection(source: NasSourceEntity): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val client = newClient()
            var connection: Connection? = null
            var session: Session? = null
            var share: DiskShare? = null
            try {
                connection = client.connect(source.host, source.port)
                session = connection.authenticate(authentication(source))
                share = session.connectShare(source.shareName.trim('/')) as? DiskShare
                    ?: error("无法挂载共享目录: ${source.shareName}")
                "SMB 连接成功"
            } finally {
                runCatching { share?.close() }
                runCatching { session?.close() }
                runCatching { connection?.close() }
                runCatching { client.close() }
            }
        }
    }

    /**
     * 以显式目录队列流式遍历 NAS，内存只保留未访问目录，不会随媒体库文件总数线性增长。
     */
    suspend fun scanFilesRecursive(
        source: NasSourceEntity,
        resumeAfterPath: String? = null,
        onFile: suspend (RemoteMediaFile) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val client = newClient()
            var connection: Connection? = null
            var session: Session? = null
            var share: DiskShare? = null
            try {
                connection = client.connect(source.host, source.port)
                session = connection.authenticate(authentication(source))
                share = session.connectShare(source.shareName.trim('/')) as? DiskShare
                    ?: error("无法挂载共享目录: ${source.shareName}")
                val directories = ArrayDeque<String>()
                directories.add(source.rootPath.trim('/'))
                var visitedFileCount = 0
                var checkpointReached = resumeAfterPath.isNullOrBlank()
                while (directories.isNotEmpty()) {
                    coroutineContext.ensureActive()
                    val path = directories.removeFirst()
                    share.list(path).sortedBy { it.fileName.lowercase() }.forEach { item ->
                        coroutineContext.ensureActive()
                        if (item.fileName == "." || item.fileName == "..") return@forEach
                        val childPath = listOf(path.trim('/'), item.fileName).filter { it.isNotEmpty() }.joinToString("/")
                        val isDirectory = (item.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                        if (isDirectory) {
                            directories.addLast(childPath)
                        } else {
                            if (!checkpointReached) {
                                checkpointReached = childPath == resumeAfterPath
                                return@forEach
                            }
                            onFile(
                                RemoteMediaFile(
                                    path = childPath,
                                    length = item.endOfFile,
                                    lastModifiedAt = item.lastWriteTime.toEpochMillis()
                                )
                            )
                            visitedFileCount++
                        }
                    }
                }
                check(checkpointReached) { "扫描检查点已失效" }
                visitedFileCount
            } finally {
                runCatching { share?.close() }
                runCatching { session?.close() }
                runCatching { connection?.close() }
                runCatching { client.close() }
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    suspend fun copyTo(
        source: NasSourceEntity,
        uri: Uri,
        output: OutputStream,
        offset: Long = 0L,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        openRemoteFile(source, uri).use { handle ->
            val remoteFile = handle.file
            val total = handle.length
            require(offset in 0..total) { "恢复位置超出 NAS 文件长度" }
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            var position = offset
            onProgress(position, total)
            while (position < total) {
                coroutineContext.ensureActive()
                val size = minOf(buffer.size.toLong(), total - position).toInt()
                val read = remoteFile.read(buffer, position, 0, size)
                if (read <= 0) error("SMB 文件读取提前结束")
                output.write(buffer, 0, read)
                position += read
                onProgress(position, total)
            }
            output.flush()
        }
    }

    /** 读取一个确定范围并返回该范围内容的 SHA-256，用于持久化分块下载。 */
    suspend fun copyRangeTo(
        source: NasSourceEntity,
        uri: Uri,
        output: OutputStream,
        offset: Long,
        byteCount: Long,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit
    ): String = withContext(Dispatchers.IO) {
        openRemoteFile(source, uri).use { handle ->
            copyRangeTo(handle, output, offset, byteCount, onProgress)
        }
    }

    /** 使用既有 SMB 会话读取范围，供大文件下载避免每个分块重复认证。调用方负责关闭句柄。 */
    suspend fun copyRangeTo(
        handle: RemoteFileHandle,
        output: OutputStream,
        offset: Long,
        byteCount: Long,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit
    ): String {
        require(offset >= 0L && byteCount >= 0L && offset <= handle.length - byteCount) {
            "下载分块范围超出 NAS 文件长度"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var position = offset
        val endExclusive = offset + byteCount
        onProgress(0L, byteCount)
        while (position < endExclusive) {
            coroutineContext.ensureActive()
            val size = minOf(buffer.size.toLong(), endExclusive - position).toInt()
            val read = handle.file.read(buffer, position, 0, size)
            if (read <= 0) error("SMB 分块读取提前结束")
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            position += read
            onProgress(position - offset, byteCount)
        }
        output.flush()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 仅重试传输层 I/O 异常，避免把存储权限和参数错误误判为网络波动。 */
    fun isRetryable(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it is java.io.IOException }

    private const val COPY_BUFFER_SIZE = 256 * 1024
}
