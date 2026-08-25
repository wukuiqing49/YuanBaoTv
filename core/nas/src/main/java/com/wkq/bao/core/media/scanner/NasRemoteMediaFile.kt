package com.wkq.bao.core.media.scanner

/** 扫描器跨 SMB/WebDAV 的统一远端媒体元数据。 */
data class NasRemoteMediaFile(
    val path: String,
    val length: Long,
    val lastModifiedAt: Long,
    val posterUri: String = "",
    val backdropUri: String = "",
    val thumbnailUri: String = ""
)
