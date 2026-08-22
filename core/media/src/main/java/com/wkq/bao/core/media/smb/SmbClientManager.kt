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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.Closeable
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** SMB 连接、扫描与按偏移读取能力。播放器和下载器共用该实现。 */
object SmbClientManager {

    data class Location(val host: String, val shareName: String, val path: String)

    class RemoteFileHandle internal constructor(
        val file: File,
        val length: Long,
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

    fun parseLocation(uri: Uri): Location? {
        if (uri.scheme != "smb") return null
        val host = uri.host ?: return null
        val segments = uri.pathSegments
        val shareName = segments.firstOrNull().orEmpty()
        if (shareName.isEmpty()) return null
        return Location(host, shareName, segments.drop(1).joinToString("/"))
    }

    fun buildUri(source: NasSourceEntity, path: String): String {
        return "smb://${source.host}/${source.shareName.trim('/')}/${path.trim('/')}"
    }

    fun <T> withRemoteFile(source: NasSourceEntity, uri: Uri, action: (File, Long) -> T): T {
        return openRemoteFile(source, uri).use { action(it.file, it.length) }
    }

    fun openRemoteFile(source: NasSourceEntity, uri: Uri): RemoteFileHandle {
        val location = parseLocation(uri) ?: error("不是有效的 SMB 地址: $uri")
        require(location.host.equals(source.host, ignoreCase = true)) { "SMB 地址与 NAS 配置不匹配" }
        require(location.shareName.equals(source.shareName.trim('/'), ignoreCase = true)) { "SMB 共享目录不匹配" }

        val client = newClient()
        try {
            val connection = client.connect(source.host, source.port)
            val auth = if (source.username.isBlank()) AuthenticationContext.anonymous()
            else AuthenticationContext(source.username, source.passwordEncrypted.toCharArray(), "")
            val session = connection.authenticate(auth)
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
            val length = remoteFile.fileInformation.standardInformation.endOfFile
            return RemoteFileHandle(remoteFile, length, client, connection, session, share)
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
                val auth = if (source.username.isBlank()) AuthenticationContext.anonymous()
                else AuthenticationContext(source.username, source.passwordEncrypted.toCharArray(), "")
                session = connection.authenticate(auth)
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

    suspend fun listFilesRecursive(source: NasSourceEntity): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val client = newClient()
            var connection: Connection? = null
            var session: Session? = null
            var share: DiskShare? = null
            try {
                connection = client.connect(source.host, source.port)
                val auth = if (source.username.isBlank()) AuthenticationContext.anonymous()
                else AuthenticationContext(source.username, source.passwordEncrypted.toCharArray(), "")
                session = connection.authenticate(auth)
                share = session.connectShare(source.shareName.trim('/')) as? DiskShare
                    ?: error("无法挂载共享目录: ${source.shareName}")
                val files = mutableListOf<String>()
                fun walk(path: String) {
                    share.list(path).forEach { item ->
                        if (item.fileName == "." || item.fileName == "..") return@forEach
                        val childPath = listOf(path.trim('/'), item.fileName).filter { it.isNotEmpty() }.joinToString("/")
                        val isDirectory = (item.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                        if (isDirectory) walk(childPath) else files += childPath
                    }
                }
                walk(source.rootPath.trim('/'))
                files
            } finally {
                runCatching { share?.close() }
                runCatching { session?.close() }
                runCatching { connection?.close() }
                runCatching { client.close() }
            }
        }
    }

    suspend fun copyTo(
        source: NasSourceEntity,
        uri: Uri,
        output: OutputStream,
        offset: Long = 0L,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        withRemoteFile(source, uri) { remoteFile, total ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var position = offset
            while (position < total) {
                val size = minOf(buffer.size.toLong(), total - position).toInt()
                val read = remoteFile.read(buffer, position, 0, size)
                if (read <= 0) error("SMB 文件读取提前结束")
                output.write(buffer, 0, read)
                position += read
                kotlinx.coroutines.runBlocking { onProgress(position, total) }
            }
            output.flush()
        }
    }
}
