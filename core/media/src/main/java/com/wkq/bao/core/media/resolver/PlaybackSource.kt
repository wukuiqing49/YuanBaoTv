package com.wkq.bao.core.media.resolver

import android.net.Uri

/**
 * 解析后的播放媒体源
 */
sealed class PlaybackSource {
    /** 本地外置存储 (USB / SSD / 本地沙盒) */
    data class Local(val uri: Uri, val title: String) : PlaybackSource()

    /** 局域网 NAS 串流 */
    data class NasStream(val uri: Uri, val title: String, val nasSourceId: Long) : PlaybackSource()

    /** 当前不可用 (NAS 离线且未下载) */
    data class Unavailable(val reason: String, val episodeId: Long) : PlaybackSource()
}
