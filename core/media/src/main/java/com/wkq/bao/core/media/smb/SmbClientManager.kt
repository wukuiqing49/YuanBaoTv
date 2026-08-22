package com.wkq.bao.core.media.smb

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB 局域网连接与文件管理
 */
object SmbClientManager {

    private val client: SMBClient by lazy {
        val config = SmbConfig.builder()
            .withTimeout(10, TimeUnit.SECONDS)
            .withSoTimeout(15, TimeUnit.SECONDS)
            .build()
        SMBClient(config)
    }

    /**
     * 测试 NAS SMB 连通性
     */
    suspend fun testConnection(
        host: String,
        port: Int = 445,
        username: String = "",
        password: String = "",
        shareName: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        var connection: Connection? = null
        var session: Session? = null
        try {
            connection = client.connect(host, port)
            val authContext = if (username.isEmpty()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(username, password.toCharArray(), "")
            }
            session = connection.authenticate(authContext)
            
            if (shareName.isNotEmpty()) {
                val share = session.connectShare(shareName) as? DiskShare
                    ?: return@withContext Result.failure(Exception("无法挂载共享目录: $shareName"))
                share.close()
            }
            Result.success("SMB 连接成功 (Host: $host, Share: $shareName)")
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                session?.close()
                connection?.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * 递归遍历指定共享目录下的所有文件路径
     */
    suspend fun listFilesRecursive(
        host: String,
        port: Int = 445,
        username: String = "",
        password: String = "",
        shareName: String = "",
        subPath: String = ""
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        var connection: Connection? = null
        var session: Session? = null
        val resultList = mutableListOf<String>()

        try {
            connection = client.connect(host, port)
            val authContext = if (username.isEmpty()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(username, password.toCharArray(), "")
            }
            session = connection.authenticate(authContext)
            val share = session.connectShare(shareName) as? DiskShare
                ?: return@withContext Result.failure(Exception("无法挂载共享目录: $shareName"))

            fun walk(currentPath: String) {
                val items = share.list(currentPath)
                for (item in items) {
                    val name = item.fileName
                    if (name == "." || name == "..") continue

                    val itemFullPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
                    val isDir = (item.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L

                    if (isDir) {
                        walk(itemFullPath)
                    } else {
                        resultList.add(itemFullPath)
                    }
                }
            }

            walk(subPath.trimStart('/'))
            share.close()
            Result.success(resultList)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                session?.close()
                connection?.close()
            } catch (_: Exception) {}
        }
    }
}
