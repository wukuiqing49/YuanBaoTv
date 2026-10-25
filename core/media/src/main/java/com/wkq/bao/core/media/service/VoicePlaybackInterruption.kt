package com.wkq.bao.core.media.service

/** 语音唤醒的临时暂停不能覆盖用户暂停，也不能恢复另一部影片。 */
internal class VoicePlaybackInterruption {
    private var interruptedMediaId: String? = null

    fun pause(mediaId: String?, playWhenReady: Boolean) {
        if (interruptedMediaId == null && playWhenReady && !mediaId.isNullOrEmpty()) {
            interruptedMediaId = mediaId
        }
    }

    fun resume(mediaId: String?): Boolean {
        val shouldResume = interruptedMediaId != null && interruptedMediaId == mediaId
        clear()
        return shouldResume
    }

    fun clear() { interruptedMediaId = null }
}

/** 仅处理本机小爱与电视播控协议中已经确认的操作；不把未知消息猜成播放指令。 */
internal enum class MiTvPlaybackOperation {
    PAUSE, RESUME, SEEK, EPISODE, PLAYRATE, FAST_FORWARD, REWIND, REPLAY, STOP;

    companion object {
        fun parse(operate: String?): MiTvPlaybackOperation? = when (operate?.lowercase()) {
            "pause" -> PAUSE
            "resume" -> RESUME
            "seek" -> SEEK
            "episode" -> EPISODE
            "playrate" -> PLAYRATE
            "fastforward", "fast_forward", "forward" -> FAST_FORWARD
            "rewind", "backward", "back" -> REWIND
            "replay", "restart" -> REPLAY
            "stop" -> STOP
            else -> null
        }
    }
}

object PlaybackSessionCommands {
    const val UI_ACTIVE = "com.wkq.bao.playback.UI_ACTIVE"
    const val NEXT_EPISODE = "com.wkq.bao.playback.NEXT_EPISODE"
    const val EXTRA_ACTIVE = "active"
}
