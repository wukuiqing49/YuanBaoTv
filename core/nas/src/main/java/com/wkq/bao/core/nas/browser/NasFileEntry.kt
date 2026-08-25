package com.wkq.bao.core.nas.browser

/** NAS 文件浏览器使用的统一目录项，不携带账号或密码。 */
data class NasFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModifiedAt: Long = 0L
)
