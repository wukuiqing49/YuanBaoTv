# 电视播放器自动适配

## 后续优化：退出、播放方式和遥控器

以下更新覆盖下文初版的“返回页”行为：

- 外部播放器正常结束或用户返回后，释放授权并结束 ExternalPlayerActivity，直接回到原来的首页/详情页，不再显示额外结束页。
- 准备/启动失败才显示重试、内置播放和返回按钮；使用显式 AppCompatButton，清除平台和支持库背景 tint，避免 Material3 将背景染白后与浅色字失去对比。
- 详情页五套布局均增加“播放方式”。默认自动适配不变；用户可以保存为内置播放器，恢复原有连续播放、字幕设置与进度同步，再切回自动。
- 使用 XPopup 自定义 CenterPopupView 和 ViewBinding。默认聚焦当前选择，方向键切换、确认保存，取消不更改；显式支持触摸模式下的焦点恢复。已真机验证自动选项初始聚焦、向下切到内置、确认后实际进入内置播放；随后恢复自动并确认进入小米系统播放器。
- 小米启动改用本机已核对的 PLAY_URL / playInfo 本地播放协议，传入实际标题与授权内容地址，避免仅显示通用 video.mp4。未填充在线 CP、会员或广告字段。
- 最终 APK 已覆盖安装。正常短片结束回首页、系统播放手动退出回原详情页均已实测。没有读取失败时硬切下一集的逻辑。

### 仍不能声称完成的项目

- `PlayStatusManager.notifyPlayEnd` 发送 PLAY_VIDEO_END，但本机 VideoFragment.onPause 也调用它，因此不能当作自然播放完成。当前没有可靠完成事件，系统播放的自动下一集仍未接通。原内置播放器功能保留，并能在播放前选择。
- 小米“更多功能”等菜单属于独立播放器；未发现经本地播放入口关闭全部菜单的可靠参数，未修改系统 APK。
- 标准 16:9 合成片在同一系统播放器中能铺满屏幕，实机菜单为自适应/拉伸两种。实际片段的中央小画面尚未证实是素材内嵌黑边还是比例问题，没有强制拉伸或虚构全屏参数。媒体检查没有取得可用的源帧，不能据此声称已定位素材问题。
- 系统播放器的进度回传、外挂字幕、可靠下一集仍受限；当前播放方式选择是明确的功能取舍，不是这些能力已经对接成功。

本轮构建及 103 项相关单测通过；架构/UI/i18n 门禁通过并保留原有提示。UI 真机检查覆盖焦点、布局、可读性和两种播放器路由，未全面覆盖其他品牌、放大字体与所有片源。沿用 fix-bug 主 Workflow，联动架构、UI、构建和 i18n Skills；默认/简体中文/英语资源同步，无新增外部素材。

## 行为

- 小米/Redmi 电视、API 26 及以上，且新版系统播放器已安装、启用并允许外部启动：默认交给 `com.mitv.mivideoplayer/com.mitv.videoplayer.VideoPlayerActivity`。
- 其他品牌电视、手机、旧系统、系统播放器缺失或禁用：继续原内置播放器。不猜测其他厂商私有协议；新增厂商时扩展 TelevisionPlayerAdapter 的选择与 Intent 适配。
- 保留原 PlayerActivity、MitvPlayerActivity、mitv.MiTvPlayerActivity。外部播放返回页可重试系统播放器、切换内置播放器或返回媒体库。准备失败时明确显示失败并提供内置播放器入口。

## 文件与职责

- `core/media/.../external/TelevisionPlayerAdapter.kt`：设备/版本/组件能力判断，显式 Intent 和只读 URI 授权。
- `core/media/.../external/ExternalPlaybackProvider.kt`：不导出的只读 ContentProvider，随机授权 URI、大小查询、可随机读取的代理文件描述符；原始媒体地址与凭据留在 App 内。读取复用 SchemeDataSource 的本地、SMB、WebDAV 能力，无新服务和依赖。
- `core/media/.../external/PlaybackByteReader.kt`：处理连续读、短读、EOF 和偏移跳转；在关闭和错误时释放底层连接。
- `feature/feature_app/.../ExternalPlayerActivity.kt` 及 `activity_external_player.xml`：准备/启动/返回页，授权回收、生命周期取消和内置播放器入口；根布局唯一消费 Insets。
- `PlayerActivity.start`：统一入口选择外部或原播放器；`startInternal` 明确绕过外部路由，避免回退循环。
- 两处 Manifest：声明 provider/Activity 和精确包可见性。
- `feature_res` 默认、简体中文、英语 strings：准备状态、失败、返回提示与按钮。

## 验证

- `:app:assembleDebug`、`:core:media:testDebugUnitTest`、`:feature:feature_app:testDebugUnitTest` 通过；core/media 71 项、feature_app 32 项，0 失败。
- 新增测试覆盖厂商与 API 选择、短读合并、连续读连接复用、后退跳转、EOF 和释放。
- 架构、UI、i18n 门禁通过。架构保留既有两个 Uri.fromFile 警告；UI 脚本跨不同 Activity 提示多个 Insets 消费者，新增页面自身仅由根布局消费一次。
- 构建配置门禁仍报既有全局 cleartext 错误和用户证书提示，本次未改变网络安全策略；不以 assemble 成功代替该门禁。
- 已覆盖安装到小米电视；从首页实际媒体入口自动进入新版系统播放器并显示视频。
- 系统播放器返回到新返回页，再选择内置播放器，前台切换原 mitv.MiTvPlayerActivity；媒体会话 READY/PLAYING，error=null。
- 未授予权限的 shell 直接查询媒体 provider，被系统以 not exported 拒绝。
- 此前系统播放器本地测试片的真实小爱暂停/继续，用户明确反馈成功。新版本 App 交接实际媒体后的语音验收单独记录，不能用前次结果冒充全部媒体通过。

## 限制

系统播放器的进度回传、原 App 自动下一集、外挂字幕及偏好同步未接通，不写入猜测的观看进度。需要这些能力时使用保留的内置播放器。系统播放器出错可能只显示自身错误页，返回后可切内置播放器。

代理读取要求源大小已知且可随机读取；大小未知或源不可访问时准备失败，提供内置播放。授权在返回/页面结束时撤销，异常进程恢复后可重新准备；URI 不持久授权。代理描述符关闭时释放线程与连接。

其他品牌真机、API 23–25、Android 15/16 的外部厂商播放器、长片/大文件、跨进程恢复、完整字幕/快进和长期稳定性尚未全面实测。没有宣称 Sony/其他厂商已完成专有小爱类语音接入。

采用 change-architecture 主 Workflow，联动 implement-page、change-build、localize-content，使用 Android 架构、UI、构建、i18n Skills；默认/简体中文/英语完整覆盖，无新增图像素材。
