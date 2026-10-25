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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** 管理用户授权的本机、TF 卡、USB 或移动硬盘目录，并识别本地媒体所在卷。 */
class TvStorageManager(private val context: Context) {
    enum class TargetStatus { AVAILABLE, READ_ONLY, OFFLINE, PERMISSION_REQUIRED, UNAVAILABLE, WRITE_FAILED }
    enum class WriteCheck { WRITABLE, READ_ONLY, UNAVAILABLE, WRITE_FAILED, CLEANUP_FAILED }

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

    data class TvMountDevice(
        val name: String,
        val rootFile: File,
        val uri: Uri,
        val location: MediaStorageLocation,
        val freeBytes: Long,
        val totalBytes: Long,
        val isReadOnly: Boolean
    ) {
        val formattedFree: String get() = formatSize(freeBytes)
        val formattedTotal: String get() = formatSize(totalBytes)
    }

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
        MediaLibraryStorage(context).forget(uri)
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
            status in setOf(TargetStatus.AVAILABLE, TargetStatus.READ_ONLY, TargetStatus.WRITE_FAILED), status)
    }

    fun getTargetStatus(uri: Uri): TargetStatus = try {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return TargetStatus.UNAVAILABLE)
            when {
                !file.exists() -> TargetStatus.UNAVAILABLE
                !file.canRead() -> TargetStatus.PERMISSION_REQUIRED
                !file.canWrite() -> TargetStatus.READ_ONLY
                prefs.getBoolean("write_check_failed_$uri", false) -> TargetStatus.WRITE_FAILED
                else -> TargetStatus.AVAILABLE
            }
        } else {
            val id = volumeId(uri)
            if (id == null) TargetStatus.UNAVAILABLE
            else if (!id.equals("primary", true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                findStorageVolume(id)?.state !in listOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)) {
                TargetStatus.OFFLINE
            } else if (context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) {
                TargetStatus.PERMISSION_REQUIRED
            } else {
                val root = getDocumentFile(context, uri)
                when {
                    root == null || !root.exists() -> TargetStatus.UNAVAILABLE
                    !root.canRead() -> TargetStatus.PERMISSION_REQUIRED
                    !root.canWrite() -> TargetStatus.READ_ONLY
                    prefs.getBoolean("write_check_failed_$uri", false) -> TargetStatus.WRITE_FAILED
                    else -> TargetStatus.AVAILABLE
                }
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

    data class DetectedDevice(val name: String, val initialUri: Uri, val readOnly: Boolean)

    fun getConnectedDevices(): List<DetectedDevice> {
        val list = mutableListOf<DetectedDevice>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            manager?.storageVolumes?.filter { !it.isPrimary && it.state in
                setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY) }?.forEach { volume ->
                val id = volume.uuid ?: return@forEach
                list.add(
                    DetectedDevice(
                        volume.getDescription(context),
                        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "$id:"),
                        volume.state == Environment.MEDIA_MOUNTED_READ_ONLY
                    )
                )
            }
        }
        return list
    }

    /** 专为电视端适配：无 SAF 授权界面时直接返回当前挂载的真实物理设备。 */
    fun getTvPhysicalStorageDevices(): List<TvMountDevice> {
        val results = mutableListOf<TvMountDevice>()

        // 1. 机身内部存储 (有权限用根目录，无权限用专属私有目录保底)
        val internalFile = Environment.getExternalStorageDirectory()
        val targetInternal = if (internalFile.exists() && internalFile.canRead()) internalFile
                             else context.getExternalFilesDir(null) ?: context.filesDir
        results.add(
            TvMountDevice(
                name = "机身内部存储",
                rootFile = targetInternal,
                uri = Uri.fromFile(targetInternal),
                location = MediaStorageLocation.INTERNAL_STORAGE,
                freeBytes = targetInternal.usableSpace,
                totalBytes = targetInternal.totalSpace,
                isReadOnly = !targetInternal.canWrite()
            )
        )

        // 2. 外部挂载驱动器 (U 盘 / TF 卡 / 移动硬盘)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            manager?.storageVolumes?.filter { !it.isPrimary && it.state in
                setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY) }?.forEach { volume ->
                val id = volume.uuid ?: return@forEach
                val mountDir = File("/storage/$id")
                val extAppDir = context.getExternalFilesDirs(null).firstOrNull { it?.absolutePath?.contains(id, true) == true }
                val targetDir = if (mountDir.exists() && mountDir.canRead()) mountDir else extAppDir
                if (targetDir != null && targetDir.exists()) {
                    val desc = volume.getDescription(context).takeIf { it.isNotBlank() } ?: "外接存储设备 ($id)"
                    results.add(
                        TvMountDevice(
                            name = desc,
                            rootFile = targetDir,
                            uri = Uri.fromFile(targetDir),
                            location = if (desc.contains("tf", true) || desc.contains("sd", true)) MediaStorageLocation.TF_CARD else MediaStorageLocation.USB_DRIVE,
                            freeBytes = targetDir.usableSpace,
                            totalBytes = targetDir.totalSpace,
                            isReadOnly = !targetDir.canWrite() || volume.state == Environment.MEDIA_MOUNTED_READ_ONLY
                        )
                    )
                }
            }
        }

        // 3. Fallback: 检查 /storage 挂载目录
        val storageDir = File("/storage")
        if (storageDir.exists() && storageDir.isDirectory) {
            storageDir.listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead() }
                ?.forEach { extDir ->
                    if (results.none { it.rootFile.absolutePath == extDir.absolutePath }) {
                        results.add(
                            TvMountDevice(
                                name = "U盘 / 移动存储 (${extDir.name})",
                                rootFile = extDir,
                                uri = Uri.fromFile(extDir),
                                location = MediaStorageLocation.USB_DRIVE,
                                freeBytes = extDir.usableSpace,
                                totalBytes = extDir.totalSpace,
                                isReadOnly = !extDir.canWrite()
                            )
                        )
                    }
                }
        }

        return results.distinctBy { it.rootFile.absolutePath }
    }

    fun getStorageTarget(): StorageTarget? {
        val uri = getStorageRoot()?.let(Uri::parse) ?: return null
        val savedLocation = MediaStorageLocation.fromStored(prefs.getString(KEY_STORAGE_LOCATION, null))
        return storageTarget(uri, savedLocation ?: inferLocation(uri))
    }

    fun getAvailableStorageTarget(): StorageTarget? = getStorageTarget()?.takeIf { it.isAvailable }

    fun getAvailableExternalStorageTarget(): StorageTarget? =
        getAvailableStorageTarget()?.takeIf { isStorageTargetWritable(it.uri) }

    fun isExternalStorageTarget(target: StorageTarget): Boolean =
        target.location != MediaStorageLocation.INTERNAL_STORAGE && isExternalStorageTarget(target.uri)

    fun isExternalStorageTarget(uri: Uri): Boolean =
        volumeId(uri)?.equals("primary", ignoreCase = true) == false

    fun getStorageInfo(target: StorageTarget? = getStorageTarget()): StorageInfo {
        if (target == null || !target.isAvailable) return StorageInfo(0L, 0L, "--", "--")
        return storageInfoFor(target.uri)
    }

    fun hasAvailableBytes(target: StorageTarget, requiredBytes: Long): Boolean {
        if (requiredBytes < 0L || !target.isAvailable) return false
        val storageInfo = getStorageInfo(target)
        return storageInfo.totalBytes <= 0L || storageInfo.freeBytes >= requiredBytes
    }

    fun isStorageTargetAvailable(uri: Uri): Boolean = try {
        isStorageTargetReadable(uri)
    } catch (_: Exception) {
        false
    }

    fun isStorageTargetWritable(uri: Uri): Boolean = try {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return false)
            file.exists() && file.canWrite() && !prefs.getBoolean("write_check_failed_$uri", false)
        } else {
            isStorageTargetAvailable(uri) && !prefs.getBoolean("write_check_failed_$uri", false) &&
                getDocumentFile(context, uri)?.canWrite() == true
        }
    } catch (_: Exception) {
        false
    }

    fun checkDirectoryWrite(uri: Uri): WriteCheck {
        if (!isStorageTargetAvailable(uri)) return WriteCheck.UNAVAILABLE
        val root = getDocumentFile(context, uri) ?: return WriteCheck.UNAVAILABLE
        if (!runCatching { root.canWrite() }.getOrDefault(false)) return WriteCheck.READ_ONLY
        var probe: DocumentFile? = null
        var result = WriteCheck.WRITE_FAILED
        try {
            probe = root.createFile("application/octet-stream", ".yuanbao-check-${UUID.randomUUID()}")
                ?: throw IOException("Cannot create probe")
            val bytes = UUID.randomUUID().toString().toByteArray(Charsets.UTF_8)
            val outputStream = if (probe.uri.scheme == "file") {
                FileOutputStream(File(probe.uri.path!!))
            } else {
                context.contentResolver.openOutputStream(probe.uri, "w")
            }
            outputStream?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw IOException("Cannot open probe")

            val inputStream = if (probe.uri.scheme == "file") {
                File(probe.uri.path!!).inputStream()
            } else {
                context.contentResolver.openInputStream(probe.uri)
            }
            val actual = inputStream?.use { input ->
                val buffer = ByteArray(bytes.size + 1)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read <= 0) break
                    count += read
                }
                buffer.copyOf(count)
            }
            if (actual != null && actual.contentEquals(bytes)) result = WriteCheck.WRITABLE
        } catch (_: Exception) {
            result = WriteCheck.WRITE_FAILED
        } finally {
            if (probe != null && !runCatching { probe.delete() }.getOrDefault(false)) {
                result = WriteCheck.CLEANUP_FAILED
            }
        }
        prefs.edit().putBoolean("write_check_failed_$uri", result != WriteCheck.WRITABLE).apply()
        return result
    }

    private fun isStorageTargetReadable(uri: Uri): Boolean {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return false)
            return file.exists() && file.canRead()
        }
        val volumeId = volumeId(uri) ?: return false
        val root = getDocumentFile(context, uri) ?: return false
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
        val root = getDocumentFile(context, treeUri) ?: return null
        return root.createFile("video/*", relativeName)
    }

    fun resolveLocalLocation(uri: Uri): MediaStorageLocation {
        val configured = getStorageTarget()
        if (configured != null && sameVolume(uri, configured.uri)) return configured.location
        return inferLocation(uri)
    }

    private fun inferLocation(uri: Uri): MediaStorageLocation {
        val volumeId = volumeId(uri) ?: return MediaStorageLocation.EXTERNAL_STORAGE
        if (volumeId.equals("primary", ignoreCase = true)) return MediaStorageLocation.INTERNAL_STORAGE

        val description = if (uri.scheme == "file") uri.path.orEmpty().lowercase()
                          else findStorageVolume(volumeId)?.getDescription(context).orEmpty().lowercase()

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

    private fun findStorageVolume(volumeId: String) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        runCatching {
            val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            manager.storageVolumes.firstOrNull { it.uuid.equals(volumeId, ignoreCase = true) }
        }.getOrNull()
    } else {
        null
    }

    private fun storageInfoFor(treeUri: Uri?): StorageInfo {
        if (treeUri?.scheme == "file") {
            val file = File(treeUri.path ?: return StorageInfo(0L, 0L, "--", "--"))
            val total = file.totalSpace
            val free = file.usableSpace
            return StorageInfo(total, free, formatSize(free), formatSize(total))
        }
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

    private fun findExternalFilesDirectory(volumeId: String) = context.getExternalFilesDirs(null).firstOrNull { directory ->
        directory?.absolutePath?.contains(volumeId, ignoreCase = true) == true
    }

    companion object {
        const val KEY_STORAGE_ROOT = "key_storage_root"
        const val KEY_STORAGE_LOCATION = "key_storage_location"
        const val KEY_STORAGE_TARGETS = "key_storage_targets"

        fun getDocumentFile(context: Context, uri: Uri): DocumentFile? = runCatching {
            if (uri.scheme == "file") {
                val path = uri.path ?: return null
                val file = File(path)
                if (file.exists()) DocumentFile.fromFile(file) else null
            } else {
                DocumentFile.fromTreeUri(context, uri)
            }
        }.getOrNull()

        fun volumeId(uri: Uri): String? = runCatching {
            if (uri.scheme == "file") {
                val path = uri.path.orEmpty()
                return when {
                    path.contains("/storage/emulated", true) || path.contains("/sdcard", true) -> "primary"
                    path.contains("/storage/") -> path.substringAfter("/storage/").substringBefore('/')
                    else -> "primary"
                }
            }
            if (uri.authority != "com.android.externalstorage.documents") return null
            val documentId = if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.getTreeDocumentId(uri)
            } else {
                DocumentsContract.getDocumentId(uri)
            }
            documentId.substringBefore(':').takeIf { it.isNotBlank() }
        }.getOrNull()

        fun formatSize(bytes: Long): String {
            val gb = bytes.toDouble() / (1024 * 1024 * 1024)
            return if (gb >= 1.0) String.format("%.1f GB", gb)
            else String.format("%.1f MB", bytes.toDouble() / (1024 * 1024))
        }
    }
}
