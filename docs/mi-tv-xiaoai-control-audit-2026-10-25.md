# 小米电视内置小爱播控排查与接入缺口

## 结论
本机小爱没有把用户的暂停/继续播放请求分发到 YuanBaoTv。仅接通 Android MediaSession 或接收小爱唤醒广播，不等于获得小爱语义播控支持。
用户在新版已安装、视频实际播放的稳定窗口再次测试，仍听到“当前没有可操作的资源”，因此真实语音链路仍失败，不能标记为已修复。

## 设备与版本
- 小米电视：MiTV-MTEQ0，Android API 29。
- 无线调试：192.168.5.5:5555；旧地址 192.168.5.17:5555 离线。
- 内置小爱主包 com.xiaomi.voicecontrol：5.12.0.7，versionCode 5120007。
- 应用包名 com.wkq.bao，versionName 1.0.1。
- 本轮通过安装后 SHA-256 对照，确认电视实际安装包与当前构建 APK 相同。校验记录在忽略目录 `app/build/outputs/ui/xiaoai-validation.json`。

## 区分三条链
1. Android 标准媒体控制：MediaSession 已激活并被系统选为媒体按键目标；播放器有 READY/PLAYING 状态且无播放器错误。此前具名平台控制器的播放/暂停通路曾通过。
2. 小爱唤醒临时打断：实测日志收到 operate=pause/resume、cause=voicecontrol。应用暂停并在对话结束后恢复。这是降低录音干扰的临时打断，不是用户说“暂停”的操作。
3. 小爱语义播控：用户真实测试失败；没有收到对应 cause=user 的暂停/恢复，也没有对应标准会话控制请求。系统本地弹出/播报“当前没有可操作的资源”。

## 根因证据
静态检查本机已安装的小爱 APK，仅分析相关控制协议，不修改系统应用，不复制其实现到项目。
- 视频上下文 `UtilsMaster.isVideoPlayActivityForeground` 使用区分大小写的 Player、Activity、mitv/miui 名称条件。
- 原来的 `com.wkq.bao.feature.app.MitvPlayerActivity` 不包含小写 mitv。该问题存在，但不是完整播控失败的唯一原因。
- 真正的本地应用播控在 `AppAgentProcessor.handleOperate` 中取得前台 ComponentName 的 packageName，再从注入的 mAppAgentProcessorMap 按包名查找代理。未命中返回未处理，继续后续处理器。
- 本机 Dagger 注入表包含 11 个包名：com.gitvdemo.video、com.ktcp.video、com.cibn.tv、com.mitv.tvhome、com.xiaomi.voicecontrol、com.mitv.videoplayer、com.xiaomi.mitv.mediaexplorer、com.duokan.videodaily、com.mitv.mivideoplayer、com.ixigua.android.tv.wasu、com.hunantv.license。没有 com.wkq.bao。
- `FeedbackAgentProcessor.handleOperate` 的兜底使用本地 string/no_res_available（0x7f1302ca），实际字符串就是用户听到的“当前没有可操作的资源”。
- 后续音频处理器使用小爱自身 AudioPlayerManager；本次没有发现未命中应用代理时向任意 Android MediaSession 转发视频语义指令的通用路径。
这些静态路径与真实测试结果一致。结论限定于本机当前固件/小爱版本；不能据此推断所有小米电视都相同。

## 本轮代码修改
- `feature/feature_app/.../mitv/MiTvPlayerActivity.kt`：新增自有包内的小写 mitv 播放入口，复用原 PlayerActivity，不冒用系统包名。
- `PlayerActivity.resolvePlayerClass`：仅在小米/Redmi 电视选择新入口；普通手机及其他设备沿用原入口。
- `app/src/main/AndroidManifest.xml`：注册新入口，exported=false；原有两个入口和外部调用保持不变。
- `MiTvPlaybackEntryTest.kt`：两项回归测试核对本机名称判断条件，防止大小写再次失配。
- 新入口已经在电视前台组件中确认生效，实际播放正常。但它只修复视频上下文的一个条件，不能向小爱的内部包名表注册本应用。
- 本轮没有继续猜测新广播、修改系统小爱、冒用其他播放器包名，或用永久忽略音频焦点来伪造成功。

## 验证
- `:app:assembleDebug` 通过。
- `:feature:feature_app:testDebugUnitTest`：32 项通过。
- `:core:media:testDebugUnitTest`：66 项通过。
- UI 门禁通过，保留既有 Insets owner 提示。
- 架构门禁通过，保留既有 Uri.fromFile 提示。
- diff whitespace 检查通过（按仓库 CRLF 兼容选项）。
- 本轮没有新增文案，默认/英语/简体中文资源与 fallback 不变，无新增外部素材。
- 原有网络安全门禁问题不是本轮改动：全局 cleartext 配置仍需另行处理，不作为本轮构建成功的替代说明。

## 真正接通仍需什么
需向小米确认当前电视固件的第三方播控接入方式：本包名对应的应用代理/SDK/配置、控制指令协议，以及是否提供对标准 MediaSession 的通用语音转发。
应用侧可提供 com.wkq.bao 包名、播放入口、标准 MediaSession、现有小米播控广播处理和实测日志，作为适配材料。
本次未找到可由普通应用自行调用、向本机代理表注册新包名的已确认接口。不能承诺再加一个 Manifest 权限、换图标或改 Activity 名就能完成。
不需要提供 NAS 密码、私有媒体地址、签名密码或小米账号凭据。

## 使用的项目流程
主 Workflow：fix-bug；联动 change-architecture、implement-page、change-build。
采用现有 Android 项目架构、UI、构建 Skills；没有新增依赖或升级 SDK/Media3。

## 剩余风险
真实小爱语义暂停、继续、快进、下一集仍未通过。系统语音路由未接通前，应用内单测和广播注入成功均不能替代该验收。
新入口是固件上下文兼容措施，不应被对外宣传为小爱完整支持。后续其他固件升级可能改变非公开的名称识别与代理路由。
