package com.wkq.bao.core.media.artwork

/** 只判断画面是否过于空白，不把亮度启发式当作片头或二维码识别。 */
internal object VideoFramePolicy {
    fun candidateTimesUs(durationMs: Long?): List<Long> {
        if (durationMs == null || durationMs <= 0) return listOf(60_000_000L, 30_000_000L, 10_000_000L, 0L)
        return listOf(0.35, 0.55, 0.7).map { (durationMs * it).toLong() * 1000L }.distinct()
    }

    fun isUseful(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return false
        var dark = 0
        var white = 0
        var sum = 0.0
        var squareSum = 0.0
        for (pixel in pixels) {
            val r = pixel shr 16 and 255
            val g = pixel shr 8 and 255
            val b = pixel and 255
            val luminance = (r * 299 + g * 587 + b * 114) / 1000.0
            if (luminance < 22) dark++
            if (luminance > 238) white++
            sum += luminance
            squareSum += luminance * luminance
        }
        val count = pixels.size.toDouble()
        val variance = squareSum / count - (sum / count) * (sum / count)
        return dark / count < 0.85 && white / count < 0.85 && variance > 64
    }
}
