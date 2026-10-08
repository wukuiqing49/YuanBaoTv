package com.wkq.bao.core.media.subtitle

import android.content.SharedPreferences
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/** 保留 Media3 的格式解析，仅规范化文本编码并调整提示时间。 */
internal class PlaybackSubtitleParserFactory(private val preferences: SharedPreferences) : SubtitleParser.Factory {
    private val delegate = DefaultSubtitleParserFactory()
    override fun supportsFormat(format: Format) = delegate.supportsFormat(format)
    override fun getCueReplacementBehavior(format: Format) = delegate.getCueReplacementBehavior(format)
    override fun create(format: Format): SubtitleParser {
        val parser = delegate.create(format)
        val shiftUs = preferences.getInt("subtitle_offset_ms", 0).coerceIn(-10000, 10000) * 1000L
        val text = format.sampleMimeType in setOf("application/x-subrip", "text/vtt", "text/x-ssa")
        return object : SubtitleParser {
            override fun getCueReplacementBehavior() = parser.cueReplacementBehavior
            override fun reset() = parser.reset()
            override fun parse(data: ByteArray, offset: Int, length: Int,
                outputOptions: SubtitleParser.OutputOptions, output: Consumer<CuesWithTiming>) {
                val adjustedOptions = when {
                    outputOptions.startTimeUs == C.TIME_UNSET -> SubtitleParser.OutputOptions.allCues()
                    outputOptions.outputAllCues -> SubtitleParser.OutputOptions.cuesAfterThenRemainingCuesBefore(outputOptions.startTimeUs - shiftUs)
                    else -> SubtitleParser.OutputOptions.onlyCuesAfter(outputOptions.startTimeUs - shiftUs)
                }
                val consumer = Consumer<CuesWithTiming> { cues ->
                    output.accept(CuesWithTiming(cues.cues,
                        if (cues.startTimeUs == C.TIME_UNSET) C.TIME_UNSET else cues.startTimeUs + shiftUs,
                        cues.durationUs))
                }
                if (text) {
                    try {
                        val normalized = SubtitleTextEncoding.utf8(data, offset, length)
                        parser.parse(normalized, 0, normalized.size, adjustedOptions, consumer)
                    } catch (error: RuntimeException) {
                        // 损坏的文本字幕不能中断视频，也不在日志中输出文件内容。
                        Log.w("PlaybackSubtitles", "Invalid text subtitle: ${error.javaClass.simpleName}")
                        output.accept(CuesWithTiming(emptyList(), 0, C.TIME_UNSET))
                    }
                } else parser.parse(data, offset, length, adjustedOptions, consumer)
            }
        }
    }
}
