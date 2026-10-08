package com.wkq.bao.core.media.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

/** 管理用户授权的本机、TF 卡、USB 或移动硬盘目录，并识别本地媒体所在卷。 */
class TvStorageManager(private val context: Context) {
    enum class TargetStatus { AVAILABLE, READ_ONLY, OFFLINE, PERMISSION_REQUIRED, UNAVAILABLE }

    data class StorageInfo(
        val totalBytes: Long,
        val freeBytes: Long,
        val formattedFree: String,
        val formattedTotal: String
    )

    data class StorageTarget(
        val uri: Uri,
        val location: MediaStorageLocation,
        val isAvailable: Boolean,
        val status: TargetStatus = if (isAvailable) TargetStatus.AVAILABLE else TargetStatus.UNAVAILABLE
    )

    private val prefs by lazy { context.getSharedPreferences("tv_storage_prefs", Context.MODE_PRIVATE) }

    fun saveStorageRoot(uri: Uri, location: MediaStorageLocation) {
        val targets = storedTargets().filterNot { it.first == uri } + (uri to location)
        val stored = JSONArray()
        targets.forEach { (targetUri, targetLocation) ->
            stored.put(JSONObject().put("uri", targetUri.toString()).put("location", targetLocation.name))
        }
        prefs.edit()
            .putString(KEY_STORAGE_ROOT, uri.toString())
            .putString(KEY_STORAGE_LOCATION, location.name)
            .putString(KEY_STORAGE_TARGETS, stored.toString())
            .apply()
    }

    /** 保存已授权目录列表，当前目录仍沿用原来的配置键。 */
    fun getStorageTargets(): List<StorageTarget> = storedTargets().map { (uri, location) ->
        storageTarget(uri, location)
    }

    /** 仅移除目录配置；下载任务和媒体记录可能仍依赖其授权，不能同时撤销。 */
    fun removeStorageRoot(uri: Uri) {
        val remaining = storedTargets().filterNot { it.first == uri }
        val stored = JSONArray()
        remaining.forEach { (targetUri, location) ->
            stored.put(JSONObject().put("uri", targetUri.toString()).put("location", location.name))
        }
        val editor = prefs.edit().putString(KEY_STORAGE_TARGETS, stored.toString())
        if (getStorageRoot() == uri.toString()) {
            val next = remaining.firstOrNull()
            if (next == null) editor.remove(KEY_STORAGE_ROOT).remove(KEY_STORAGE_LOCATION)
            else editor.putString(KEY_STORAGE_ROOT, next.first.toString())
                .putString(KEY_STORAGE_LOCATION, next.second.name)
        }
        editor.apply()
    }

    private fun storageTarget(uri: Uri, location: MediaStorageLocation): StorageTarget {
        val status = getTargetStatus(uri)
        return StorageTarget(uri, location,
            status == TargetStatus.AVAILABLE || status == TargetStatus.READ_ONLY, status)
    }

    fun getTargetStatus(uri: Uri): TargetStatus = try {
        val id = volumeId(uri)
        if (id == null) TargetStatus.UNAVAILABLE
        else if (!id.equals("primary", true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            findStorageVolume(id)?.state !in listOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)) {
            TargetStatus.OFFLINE
        } else if (context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) {
            TargetStatus.PERMISSION_REQUIRED
        } else {
            val root = DocumentFile.fromTreeUri(context, uri)
            when {
                root == null || !root.exists() -> TargetStatus.UNAVAILABLE
                !root.canRead() -> TargetStatus.PERMISSION_REQUIRED
                !root.canWrite() -> TargetStatus.READ_ONLY
                else -> TargetStatus.AVAILABLE
            }
        }
    } catch (_: SecurityException) {
        TargetStatus.PERMISSION_REQUIRED
    } catch (_: Exception) {
        TargetStatus.UNAVAILABLE
    }

    private fun storedTargets(): List<Pair<Uri, MediaStorageLocation>> {
        val stored = runCatching { JSONArray(prefs.getString(KEY_STORAGE_TARGETS, "[]")) }.getOrElse { JSONArray() }
        val targets = (0 until stored.length()).mapNotNull { index ->
            val target = stored.optJSONObject(index) ?: return@mapNotNull null
            val uri = target.optString("uri").takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return@mapNotNull null
            val location = MediaStorageLocation.fromStored(target.optString("location")) ?: return@mapNotNull null
            uri to location
        }.toMutableList()
        getStorageRoot()?.let(Uri::parse)?.let { current ->
            if (targets.none { it.first == current }) {
                targets += current to (MediaStorageLocation.fromStored(prefs.getString(KEY_STORAGE_LOCATION, null))
                    ?: inferLocation(current))
            }
        }
        return targets.distinctBy { it.first }
    }

    fun getStorageRoot(): String? = prefs.getString(KEY_STORAGE_ROOT, null)

    fun getStorageTarget(): StorageTarget? {
        val uri = getStorageRoot()?.let(Uri::parse) ?: return null
        val savedLocation = MediaStorageLocation.fromStored(prefs.getString(KEY_STORAGE_LOCATION, null))
        return storageTarget(uri, savedLocation ?: inferLocation(uri))
    }

    fun getAvailableStorageTarget(): StorageTarget? = getStorageTarget()?.takeIf { it.isAvailable }

    /** NAS 下载只允许写入可移除介质，避免占用设备内部存储。 */
    fun getAvailableExternalStorageTarget(): StorageTarget? =
        getAvailableStorageTarget()?.takeIf(::isExternalStorageTarget)
            ?.takeIf { isStorageTargetWritable(it.uri) }

    fun isExternalStorageTarget(target: StorageTarget): Boolean =
        target.location != MediaStorageLocation.INTERNAL_STORAGE && isExternalStorageTarget(target.uri)

    fun isExternalStorageTarget(uri: Uri): Boolean =
        volumeId(uri)?.equals("primary", ignoreCase = true) == false

    fun getStorageInfo(target: StorageTarget? = getStorageTarget()): StorageInfo {
        if (target == null || !target.isAvailable) return StorageInfo(0L, 0L, "--", "--")
        return storageInfoFor(target.uri)
    }

    /** 可识别卷空间不足时提前拒绝下载；无法识别容量的文档提供方保留实际写入校验。 */
    fun hasAvailableBytes(target: StorageTarget, requiredBytes: Long): Boolean {
        if (requiredBytes < 0L || !target.isAvailable) return false
        val storageInfo = getStorageInfo(target)
        return storageInfo.totalBytes <= 0L || storageInfo.freeBytes >= requiredBytes
    }

    /** 允许系统文件选择器授予的本机或已挂载外接卷，拒绝无法识别的文档提供方。 */
    fun isStorageTargetAvailable(uri: Uri): Boolean = try {
        isStorageTargetReadable(uri)
    } catch (_: Exception) {
        false
    }

    fun isStorageTargetWritable(uri: Uri): Boolean = try {
        isStorageTargetAvailable(uri) && DocumentFile.fromTreeUri(context, uri)?.canWrite() == true
    } catch (_: Exception) {
        false
    }

    private fun isStorageTargetReadable(uri: Uri): Boolean {
        val volumeId = volumeId(uri) ?: return false
        val root = DocumentFile.fromTreeUri(context, uri) ?: return false
        if (!root.exists() || !root.canRead()) return false
        if (volumeId.equals("primary", ignoreCase = true)) return true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val volume = findStorageVolume(volumeId) ?: return false
            if (volume.state != Environment.MEDIA_MOUNTED && volume.state != Environment.MEDIA_MOUNTED_READ_ONLY) return false
        } else {
            val externalDirectory = findExternalFilesDirectory(volumeId) ?: return false
            if (externalDirectory.exists().not()) return false
        }

        return true
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
        if (volumeId.equals("primary", ignoreCase = true)) return MediaStorageLocation.INTERNAL_STORAGE

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
        if (uri.authority != "com.android.externalstorage.documents") return null
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
        val free = fileDir.usableSpace
        return StorageInfo(total, free, formatSize(free), formatSize(total))
    }

    private fun formatSize(bytes: Long): String {
        val gb = bytes.toDouble() / (1024 * 1024 * 1024)
        return if (gb >= 1.0) String.format("%.1f GB", gb)
        else String.format("%.1f MB", bytes.toDouble() / (1024 * 1024))
    }

    private fun findExternalFilesDirectory(volumeId: String) = context.getExternalFilesDirs(null).firstOrNull { directory ->
        directory?.absolutePath?.contains(volumeId, ignoreCase = true) == true
    }

    private companion object {
        const val KEY_STORAGE_ROOT = "key_storage_root"
        const val KEY_STORAGE_LOCATION = "key_storage_location"
        const val KEY_STORAGE_TARGETS = "key_storage_targets"
    }
}
