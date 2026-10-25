package com.wkq.bao.core.media.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.UUID

/** 用户授权目录中的可搬迁媒体库；所有文件操作须在 IO 线程执行。 */
class MediaLibraryStorage(private val context: Context) {
    data class Library(val id: String, val root: DocumentFile)

    fun read(tree: Uri): Library? {
        val parent = TvStorageManager.getDocumentFile(context, tree) ?: return null
        val root = if (parent.name == DIRECTORY) parent else parent.findFile(DIRECTORY) ?: return null
        val marker = root.findFile(".yuanbao")?.findFile("library.json") ?: return null
        val bytes = openInput(marker.uri)?.use { input ->
            val buffer = ByteArray(4097)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read <= 0) break
                count += read
            }
            require(count <= 4096) { "Invalid library marker" }
            buffer.copyOf(count)
        } ?: throw IOException("Cannot read library marker")
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.getInt("version") == 1) { "Unsupported library version" }
        val id = UUID.fromString(json.getString("id")).toString()
        return Library(id, root)
    }

    fun initialize(tree: Uri): Library = synchronized(lock) {
        read(tree)?.let { existing ->
            check(expectedId(tree).let { it.isBlank() || it == existing.id }) { "Library identity changed" }
            ensureFolders(existing.root)
            remember(tree, existing.id)
            return@synchronized existing
        }
        check(expectedId(tree).isBlank()) { "Library marker is missing" }
        check(TvStorageManager(context).checkDirectoryWrite(tree) == TvStorageManager.WriteCheck.WRITABLE) {
            "Storage is not writable"
        }
        val parent = TvStorageManager.getDocumentFile(context, tree) ?: throw IOException("Missing storage")
        val root = if (parent.name == DIRECTORY) parent else directory(parent, DIRECTORY)
        ensureFolders(root)
        val metadata = directory(root, ".yuanbao")
        val id = UUID.randomUUID().toString()
        val marker = metadata.createFile("application/json", "library-$id.pending")
            ?: throw IOException("Cannot create library marker")
        try {
            openOutput(marker.uri)?.use {
                it.write(JSONObject().put("version", 1).put("id", id).toString().toByteArray(Charsets.UTF_8))
            } ?: throw IOException("Cannot write library marker")
            check(marker.renameTo("library.json")) { "Cannot finalize library marker" }
            val library = read(tree) ?: throw IOException("Cannot verify library marker")
            remember(tree, library.id)
            library
        } catch (error: Exception) {
            runCatching { marker.delete() }
            throw error
        }
    }

    fun expectedId(tree: Uri): String = preferences.getString("id_$tree", "").orEmpty()

    fun forget(tree: Uri) { preferences.edit().remove("id_$tree").remove("volume_$tree").apply() }

    /** 卷标识与库编号同时绑定，复制到另一设备的标识文件不会自动接管原下载。 */
    fun matches(tree: Uri, id: String): Boolean = runCatching {
        id.isNotBlank() && expectedId(tree) == id && read(tree)?.id == id &&
            preferences.getString("volume_$tree", "") == (TvStorageManager.volumeId(tree) ?: "")
    }.getOrDefault(false)

    private val preferences get() = context.getSharedPreferences("media_library_identity", Context.MODE_PRIVATE)
        private fun remember(tree: Uri, id: String) {
        val previous = expectedId(tree)
        check(previous.isBlank() || previous == id) { "Library identity changed" }
        val volId = TvStorageManager.volumeId(tree) ?: if (tree.scheme == "file") "primary" else runCatching {
            DocumentsContract.getTreeDocumentId(tree).substringBefore(':')
        }.getOrDefault("")
        preferences.edit().putString("id_$tree", id)
            .putString("volume_$tree", volId).apply()
    }

    private fun openInput(uri: Uri): InputStream? {
        return if (uri.scheme == "file") {
            val path = uri.path ?: return null
            FileInputStream(File(path))
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }

    private fun openOutput(uri: Uri): OutputStream? {
        return if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val file = File(path)
            file.parentFile?.mkdirs()
            FileOutputStream(file)
        } else {
            context.contentResolver.openOutputStream(uri, "w")
        }
    }

    private fun ensureFolders(root: DocumentFile) {
        listOf("Movies", "TVShows", "Downloads", ".yuanbao").forEach { directory(root, it) }
    }

    companion object {
        const val DIRECTORY = "YuanBaoTV"
        private val lock = Any()
        fun directory(parent: DocumentFile, name: String): DocumentFile {
            val child = parent.findFile(name) ?: parent.createDirectory(name) ?: throw IOException("Cannot create directory")
            check(child.isDirectory) { "Directory name is occupied by a file" }
            return child
        }
    }
}
