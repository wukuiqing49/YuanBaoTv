# 功能与交互优化（2026-10-29）

## 范围与取舍

针对本轮功能/UI 审查，调整导航契约、本地媒体与下载组织、首次接入流程、来源管理入口、播放器设置和中英文一致性。保留现有播放器、媒体库、下载和 NAS 数据模型，不新增第三方依赖。

NAS 保留独立浏览页面，顶部明确显示为“媒体来源 ›”操作，与三个内容页签区别；退出后恢复发起入口的焦点。这样不必把独立浏览器迁入宿主，也不会把动作伪装成第四个内容 Tab。

## 修改清单

| 范围 | 文件 | 改动及原因 |
| --- | --- | --- |
| 导航 | HomeActivity、各 activity_home_host 布局 | 来源入口独立按钮样式及语义说明；恢复返回焦点；下载完成入队导航支持直接显示任务分区。 |
| 首页首次使用 | HomeFragment、strings | 使用家庭影院与本地视频/家庭服务器两条路径，说明扫描后的结果；空态简介允许三行。 |
| 本地与下载 | DownloadsFragment、六套 activity_downloads 布局 | 本地影片和下载任务同时可见，独立选中状态和数量；移除点击标题轮换模式；扫描成为显式按钮，存储卡片只显示状态，目录管理单独入口。 |
| NAS 浏览 | NasBrowserActivity、activity_nas_browser | 修复扫描按钮 0×0 不可见；显示扫描进度、后台运行说明、失败处理建议及查看媒体库按钮；工具栏支持横向滚动；返回按钮与系统返回均按目录层级导航。 |
| 来源管理 | NasBrowserActivity | 显式来源管理菜单提供测试连接、启用/停用、诊断、本地数据说明和删除确认；删除失败保留可读反馈。清空来源时不展示旧文件结果。 |
| 播放设置 | PlayerSettingsDrawerPopup、PlayerActivity、popup_player_settings_drawer | 按播放、声音与字幕、来源与画面分组；使用共享正文字号、弹性行高与三行文本；显示当前轨道与本地来源名称；弹窗宽度限制在屏幕内；修复已有设置抽屉阻止音轨/字幕选择的问题。 |
| 文案 | values、values-zh-rCN、values-en，NasSettingsFragment | 同步默认中文、简体中文和英文；移除 NAS 下载路径中的硬编码中文；复用存储状态资源。 |

## 采用的项目约束

主 Workflow：implement-page；领域规则：execution、android、architecture、ui、i18n；Skills：android-ui-workflow、android-project-architecture-workflow、android-i18n-workflow。设计沿用项目中性深色、白色焦点和独立选中标记，未引入外部图片或营销素材。

默认资源为中文，明确提供英文与简中；其他语言沿用 Android 默认资源 fallback。自动门禁用于辅助检查，不能替代设备截图和遥控操作。

## 验证记录

- 初版 APK 已在专用 Google TV API 36 模拟器安装运行；检查首页与本地/下载双分区，截图位于 artifacts/ux-home-en.png、artifacts/ux-downloads-en.png（中间版本，部分文案随后更新）。
- 聚合工作流检查通过；XML 可解析。
- 常规构建在 Lint 阶段受到主机 native memory 不足影响；关闭本次模拟器，改用单 worker、1GB Java heap、两个处理器及 Kotlin 进程内编译重试。该限制仅通过命令行传入，没有改变项目默认构建参数。
- 最终受限内存构建成功（5m 10s）：`:app:assembleDebug :app:lintDebug :feature:feature_app:testDebugUnitTest`。页面模块 32 项测试全部通过。
- 全部工作流门禁再次通过；`git diff --check` 无错误；六套本地/下载布局均包含相同的切换项和扫描动作。
- 最终版 APK 已在专用 TV 模拟器安装运行；首页、媒体来源入口及返回、本地/下载分区点击与选中状态完成检查。最终截图：artifacts/ux-final-home.png、artifacts/ux-final-sources-empty.png、artifacts/ux-final-downloads.png。
- 来源页截图揭示空态覆盖层依赖已 GONE 列表的宽度，已改为 INVISIBLE 保留布局边界；无来源时隐藏重复的编辑/添加按钮。此小修复随后重新构建验证，截图尚未重拍。
- 加入本地测试影片后，设备复验期间模拟器退出；日志未提供明确原因。本轮不将有片库页面、实际播放、音轨/字幕抽屉联动、大字体和小屏运行结果记为通过。
- 最后一轮空态修复的 Debug 构建、Lint 和页面测试全部通过（3m 5s），日志：artifacts/ux-final-check-20261029.log。最终安装包：app/build/outputs/apk/debug/app-debug.apk。本轮没有重新构建 Release，也没有提交或推送代码。

## 验收边界

未用真实 NAS 凭据、用户媒体或连接中的真机做破坏性操作。真实服务器断线、USB 热插拔、长时间播放、厂商外部播放器以及完整设备/字体矩阵仍需对应硬件验收。测试截图是本地验收产物，不作为产品资源。
