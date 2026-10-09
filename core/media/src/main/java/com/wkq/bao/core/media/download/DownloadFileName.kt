package com.wkq.bao.core.media.download

import java.security.MessageDigest

internal object DownloadFileName {
    fun sanitize(name: String): String {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (safe.length <= 160 && safe.toByteArray(Charsets.UTF_8).size <= 240) return safe
        val extension = safe.substringAfterLast('.', "").takeIf { it.length in 1..20 }
            ?.let { ".$it" }.orEmpty()
        val hash = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val suffix = "_$hash$extension"
        var stem = safe.removeSuffix(extension).take(160 - suffix.length)
            .dropLastWhile { Character.isHighSurrogate(it) }
        // 外部文件系统常按 UTF-8 字节限制文件名，中文与 emoji 不能只按字符数截断。
        while ((stem + suffix).toByteArray(Charsets.UTF_8).size > 240) {
            stem = stem.dropLast(1).dropLastWhile { Character.isHighSurrogate(it) }
        }
        return stem + suffix
    }
}
