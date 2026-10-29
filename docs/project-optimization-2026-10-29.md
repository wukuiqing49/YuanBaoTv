# 项目优化实施与验收（2026-10-29）

本次针对现有 Android TV NAS 媒体库的安全、播放稳定性、浏览性能和交互问题实施优化。保留 Kotlin/XML/ViewBinding、MVVM、Room 与 Media3 架构，不引入新依赖，不改变数据库版本。

## 修改范围与原因

| 领域 | 主要文件 | 修复结果 |
| --- | --- | --- |
| WebDAV | WebDavEndpoint、WebDavClientManager、WebDavCredentialRegistry、WebDavDataSource、MediaResolver、TvDownloadEngine、NasArtworkLoader | HTTP/HTTPS 在扫描、播放和下载链路统一；校验协议、主机、端口和目录边界；删除信任所有证书及全局 HTTPS 修改；禁止自动重定向携带凭据；校验 Range 响应。 |
| 播放恢复 | TvPlayerController、PlayerActivity、PlaybackFailure | 控制器统一处理失败换源，排除已失败的本地 URI 和 NAS 来源；移除 Activity 竞争重试；按证书、鉴权、网络、格式给出操作提示；手动换源成功清除旧错误；菜单键打开设置。 |
| 媒体库 | MediaLibraryQuery、MediaDao、MediaBrowseRepository、MediaLibraryViewModel | 生产查询在数据库中筛选、排序、计数并限制返回条数，避免先加载全库再截断；保留筛选条件和浏览数量。 |
| 首页 | HomeViewModel、HomeFragment、MediaArtwork | 各分类独立展示；按实体 ID 去重，避免同名影片消失；修正透明但仍可聚焦的区域；卡片行高按实际布局测量；竖海报不作为横幅强行裁切。 |
| 设备与权限 | TvFocusHelper、SplashActivity、HomeActivity、DownloadsFragment、DownloadNotificationPermission、DetailActivity | 修复手机被识别成电视；移除固定启动延时和启动权限弹窗；本地入口直接选择存储；通知权限在下载场景请求；存储弹窗复用国际化文案。 |
| 性能记录 | AppDiagnostics、YuanBaoTvApplication、HomeFragment、PlayerActivity | 添加首页和播放首帧耗时，仅记录固定事件和耗时，不记录媒体标题、地址或凭据。 |
| 工程规则 | build.md、android-build-workflow/SKILL.md、validate_build_output.py、network-security-exceptions.json | 默认仍禁止全局明文；仅允许有原因、有审核文档的精确路径例外；例外不允许 TLS 绕过；本地门禁与 CI 触发策略分离。 |
| 测试配置 | core/database/build.gradle | 测试 APK targetSdk 跟随项目版本，解决 Android 16 拒绝安装旧 targetSdk 测试 APK。 |

详细网络边界见 [network-security-policy.md](network-security-policy.md)。其他项目应复用规则与验证器，不应直接复制本项目的 NAS 明文例外。

## 已执行验证

- NAS、媒体、页面模块 JVM 单元测试：121 项通过，0 失败。
- 网络安全门禁测试：4 项通过，覆盖默认拒绝、例外路径隔离、缺少审核文档以及 TLS 绕过拒绝。
- Android 工作流门禁：agent-structure/build/architecture/ui/i18n 全部通过。
- Debug APK 构建、Android Lint：通过；后续小修改复验结果见末尾记录。
- 独立 Google TV API 36 模拟器：数据库仪器测试 19 项通过，含迁移、下载恢复、数据清理及新增分页测试。
- 新增 10,000 条合成媒体数据测试：首批 60 条查询单次耗时 25ms；同时验证总数、120 条加载、类型/收藏筛选、英文大小写及 `%_` 字面量检索。
- TV 启动与遥控操作：首次启动没有权限弹窗；方向下键加确认键可从首页本地入口直接打开存储选择。
- 本次模拟器首页首次内容绘制记录 3947ms。包含新模拟器和同时构建的环境影响，没有优化前对照，不作为性能提升百分比或真机 SLA。
- 本次模拟器已关闭，未操作连接的两台真机及其他项目模拟器。

验证日志位于 `artifacts/optimization-*.log`；首页截图为 `artifacts/optimization-tv-home.png`。这些是本地验收产物，不作为产品资源。

## 规则、语言和素材

主 Workflow 为 `fix-bug.md`，按领域联动 `change-architecture.md`、`implement-page.md`、`change-build.md`、`localize-content.md`。遵循 execution/android/architecture/build/ui/i18n 规则，使用对应四项 Android Skill；门禁调整使用 skill-creator，设备准备参考 android-cli 并使用本机 SDK 工具。

新增用户文案覆盖默认中文、zh-CN 和英文；其他语言沿用 Android 默认资源 fallback。没有新增营销承诺或外部图片，截图来自实际运行的项目。

## 已知限制与待实机验收

- HTTP Basic Auth 仍为明文，仅适用于可信网络；需要保密时使用 HTTPS。用户 CA 支持是私有 NAS 的有意选择，未实测所有厂商证书配置。
- 尚未完成真实 SMB/WebDAV NAS、USB/TF 热插拔、长时间播放、多种编码、电视遥控器品牌及手机/平板视觉矩阵测试。
- 数据库检索使用 SQLite `lower`，英文大小写已验证；非 ASCII 字母的大小写折叠不保证与 Kotlin 完全一致。
- 分页按已显示数量扩大 LIMIT，首页依然订阅可播放媒体集合；极大库的进一步增量分页及首页 SQL 限流可在取得真机基线后评估。
- 多行标题/放大字体的卡片布局已修改，尚未完成所有设备尺寸的截图验收。
- 静态门禁仍保留网络业务例外、内部 Uri.fromFile 和跨 Activity Insets 扫描警告；通过不等于这些场景已全部实机认证。
- Release 首次构建因主机 native memory 不足退出；关闭专用模拟器后使用单 worker 重试，最终结果记录如下。

## 最终复验

- 单 worker 重试成功：`:app:assembleDebug :app:lintDebug :app:assembleRelease :feature:feature_app:testDebugUnitTest`，耗时 7m 7s；含 Release R8、资源压缩和 lintVitalRelease。
- 最后两项交互细节修改（手动换源清除错误、存储选择弹窗国际化）已通过最终编译/Lint/页面单元测试，未重新启动模拟器截图。
- 全部 Android 工作流门禁再次通过；`git diff --check` 无错误。
- Debug 产物：`app/build/outputs/apk/debug/app-debug.apk`；Release 产物：`app/build/outputs/apk/release/app-release.apk`。仅完成本地构建，没有发布、提交或推送。
