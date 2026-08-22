package com.wkq.bao.core.media.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/** 管理用户授权的 TF 卡或 USB 硬盘目录，并识别本地媒体所在卷。 */
class TvStorageManager(private val context: Context) {

    data class StorageInfo(
        val totalBytes: Long,
        val freeBytes: Long,
        val formattedFree: String,
        val formattedTotal: String
    )

    data class StorageTarget(
        val uri: Uri,
        val location: MediaStorageLocation,
        val isAvailable: Boolean
    )

    private val prefs by lazy { context.getSharedPreferences("tv_storage_prefs", Context.MODE_PRIVATE) }

    fun saveStorageRoot(uri: Uri, location: MediaStorageLocation) {
        prefs.edit()
            .putString(KEY_STORAGE_ROOT, uri.toString())
            .putString(KEY_STORAGE_LOCATION, location.name)
            .apply()
    }

    fun getStorageRoot(): String? = prefs.getString(KEY_STORAGE_ROOT, null)

    fun getStorageTarget(): StorageTarget? {
        val uri = getStorageRoot()?.let(Uri::parse) ?: return null
        val savedLocation = MediaStorageLocation.fromStored(prefs.getString(KEY_STORAGE_LOCATION, null))
        return StorageTarget(uri, savedLocation ?: inferLocation(uri), isStorageTargetAvailable(uri))
    }

    fun getAvailableStorageTarget(): StorageTarget? = getStorageTarget()?.takeIf { it.isAvailable }

    fun getStorageInfo(target: StorageTarget? = getStorageTarget()): StorageInfo {
        if (target == null || !target.isAvailable) return StorageInfo(0L, 0L, "--", "--")
        return storageInfoFor(target.uri)
    }

    /** 仅允许作为下载目标的已挂载可移除卷，避免把内部存储误标成 TF 或 USB。 */
    fun isStorageTargetAvailable(uri: Uri): Boolean {
        val volumeId = volumeId(uri) ?: return false
        if (volumeId.equals("primary", ignoreCase = true)) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val volume = findStorageVolume(volumeId) ?: return false
            if (!volume.isRemovable || volume.state != Environment.MEDIA_MOUNTED) return false
        } else {
            val externalDirectory = findExternalFilesDirectory(volumeId) ?: return false
            if (!Environment.isExternalStorageRemovable(externalDirectory)) return false
        }

        return DocumentFile.fromTreeUri(context, uri)?.exists() == true
    }

    fun createLocalMediaFile(treeUri: Uri, relativeName: String): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        return root.createFile("video/*", relativeName)
    }

    /**
     * 已下载文件优先使用写入时保存的类型；旧记录则通过 SAF 卷 ID 推断。
     */
    fun resolveLocalLocation(uri: Uri): MediaStorageLocation {
        val configured = getStorageTarget()
        if (configured != null && sameVolume(uri, configured.uri)) return configured.location

        return inferLocation(uri)
    }

    private fun inferLocation(uri: Uri): MediaStorageLocation {
        val volumeId = volumeId(uri) ?: return MediaStorageLocation.EXTERNAL_STORAGE
        if (volumeId.equals("primary", ignoreCase = true)) return MediaStorageLocation.EXTERNAL_STORAGE

        val description = findStorageVolume(volumeId)?.getDescription(context).orEmpty().lowercase()

        return if (description.contains("usb") || description.contains("disk") || description.contains("hard") || description.contains("移动")) {
            MediaStorageLocation.USB_DRIVE
        } else {
            MediaStorageLocation.TF_CARD
        }
    }

    private fun sameVolume(first: Uri, second: Uri): Boolean {
        val firstVolume = volumeId(first)
        val secondVolume = volumeId(second)
        return firstVolume != null && firstVolume.equals(secondVolume, ignoreCase = true)
    }

    private fun volumeId(uri: Uri): String? = runCatching {
        val documentId = if (DocumentsContract.isTreeUri(uri)) {
            DocumentsContract.getTreeDocumentId(uri)
        } else {
            DocumentsContract.getDocumentId(uri)
        }
        documentId.substringBefore(':').takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun findStorageVolume(volumeId: String) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        runCatching {
            val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            manager.storageVolumes.firstOrNull { it.uuid.equals(volumeId, ignoreCase = true) }
        }.getOrNull()
    } else {
        null
    }

    private fun storageInfoFor(treeUri: Uri?): StorageInfo {
        val volumeId = treeUri?.let(::volumeId)
        val fileDir = when {
            volumeId.isNullOrBlank() || volumeId.equals("primary", ignoreCase = true) -> {
                context.getExternalFilesDir(null) ?: context.filesDir
            }
            else -> findExternalFilesDirectory(volumeId)
        }
        if (fileDir == null) return StorageInfo(0L, 0L, "--", "--")
        val total = fileDir.totalSpace
        val free = fileDir.freeSpace
        return StorageInfo(total, free, formatSize(free), formatSize(total))
    }

    private fun formatSize(bytes: Long): String {
        val gb = bytes.toDouble() / (1024 * 1024 * 1024)
        return if (gb >= 1.0) String.format("%.1f GB", gb)
        else String.format("%.1f MB", bytes.toDouble() / (1024 * 1024))
    }

    private fun findExternalFilesDirectory(volumeId: String) = context.getExternalFilesDirs(null).firstOrNull { directory ->
        directory.absolutePath.contains(volumeId, ignoreCase = true)
    }

    private companion object {
        const val KEY_STORAGE_ROOT = "key_storage_root"
        const val KEY_STORAGE_LOCATION = "key_storage_location"
    }
}
