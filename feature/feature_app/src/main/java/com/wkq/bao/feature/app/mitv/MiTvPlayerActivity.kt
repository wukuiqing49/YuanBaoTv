package com.wkq.bao.feature.app.mitv

import com.wkq.bao.feature.app.PlayerActivity

/**
 * 本机小爱以区分大小写的 mitv、Player、Activity 判断前台视频场景。
 * 小写标记放在本应用自己的包内；仅作为固件兼容入口，完整复用原播放器。
 * 原 PlayerActivity 和 MitvPlayerActivity 继续保留，避免破坏已有调用。
 */
class MiTvPlayerActivity : PlayerActivity()
