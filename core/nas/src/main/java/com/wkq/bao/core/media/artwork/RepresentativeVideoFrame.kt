package com.wkq.bao.core.media.artwork

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.IOException

/** NAS 与本地视频共用取帧顺序；返回的 Bitmap 由调用方回收。 */
internal fun MediaMetadataRetriever.representativeFrame(): Bitmap {
    val duration = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    for (timeUs in VideoFramePolicy.candidateTimesUs(duration)) {
        val frame = getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
        val pixels = IntArray(24 * 14) { index ->
            frame.getPixel((index % 24 * frame.width / 24).coerceAtMost(frame.width - 1),
                (index / 24 * frame.height / 14).coerceAtMost(frame.height - 1))
        }
        if (VideoFramePolicy.isUseful(pixels)) return frame
        frame.recycle()
    }
    throw IOException("视频中没有适合作为封面的画面")
}
