package com.wkq.bao.feature.app

/**
 * 小米电视专有播放入口：
 * 包含 Mitv 与 Player 标识，适配小米电视系统（MIUI TV / PatchWall）和小爱同学
 * 的前台播放状态识别协议，完整复用原 PlayerActivity 的全部能力。
 */
class MitvPlayerActivity : PlayerActivity()
