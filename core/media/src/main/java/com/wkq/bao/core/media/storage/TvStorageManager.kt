package com.wkq.bao.core.media.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * TV 外置存储管理类 (SAF & 容量计算)
 */
class TvStorageManager(private val context: Context) {

    data class StorageInfo(
        val totalBytes: Long,
        val freeBytes: Long,
        val formattedUsage: String
    )

    private val prefs by lazy { context.getSharedPreferences("tv_storage_prefs", Context.MODE_PRIVATE) }

    fun saveStorageRoot(uri: Uri) {
        prefs.edit().putString("key_storage_root", uri.toString()).apply()
    }

    fun getStorageRoot(): String? {
        return prefs.getString("key_storage_root", null)
    }

    fun getStorageInfo(treeUriString: String? = getStorageRoot()): StorageInfo {
        if (!treeUriString.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(treeUriString)
                val docFile = DocumentFile.fromTreeUri(context, uri)
                if (docFile != null && docFile.exists()) {
                    // 使用系统存储估算
                    val internalDir = context.getExternalFilesDir(null) ?: context.filesDir
                    val total = internalDir.totalSpace.takeIf { it > 0 } ?: (512L * 1024 * 1024 * 1024)
                    val free = internalDir.freeSpace.takeIf { it > 0 } ?: (428L * 1024 * 1024 * 1024)
                    return StorageInfo(total, free, formatSize(free) + " 可用 / " + formatSize(total))
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        // 默认返回当前可用内部/外部私有目录空间
        val fileDir = context.getExternalFilesDir(null) ?: context.filesDir
        val total = fileDir.totalSpace
        val free = fileDir.freeSpace
        return StorageInfo(total, free, formatSize(free) + " 可用 / " + formatSize(total))
    }

    fun createLocalMediaFile(treeUri: Uri, relativeName: String): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        return root.createFile("video/*", relativeName)
    }

    private fun formatSize(bytes: Long): String {
        val gb = bytes.toDouble() / (1024 * 1024 * 1024)
        return if (gb >= 1.0) {
            String.format("%.1f GB", gb)
        } else {
            val mb = bytes.toDouble() / (1024 * 1024)
            String.format("%.1f MB", mb)
        }
    }
}
