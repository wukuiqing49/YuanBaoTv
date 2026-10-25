# 小米电视 NAS 整体测试

日期：2026-10-24。设备：Xiaomi MiTV-MTEQ0，1920×1080，无线 ADB。

## 打包与代码验证

- 已重新生成并覆盖安装 Debug APK，保留应用与 NAS 凭据数据。
- app assembleDebug 通过。
- feature_app 28 项、core/media 53 项、core/nas 10 项单元测试通过，共 91 项。
- UI、国际化、架构检查通过，保留既有 Insets 和 Uri.fromFile 告警。
- 构建策略门禁仍因工作区原有全局明文网络配置失败；本轮没有修改该网络策略，不将 APK 构建成功等同于门禁通过。

## NAS 位置确认

- 原来 SMB 和 WebDAV 都指向 SMBShare 共享，只有四个旧媒体条目，因此先前测试不能代表用户的真实片库。
- 用户确认目标名称为 YuanBaoTv；它不能解析为独立网络主机名。
- 使用原有 NAS 账号，在应用编辑草稿内测试另一个共享名成功，确认 YuanBaoTv 是 NAS 上的共享。
- 已保存 SMB 和 WebDAV 的目标共享。SMB 对共享名大小写不敏感，保存值为 yuanBaoTv；WebDAV 使用精确大小写 YuanBaoTv，空扫描子路径。
- 两种协议均通过连接测试，能读取目标共享的 video、动画片、电视剧目录。
- 两种协议扫描均 SUCCEEDED，各处理/导入 1183 个文件。
- 扫描后数据库总计 959 个媒体条目、1078 集；这些是索引统计，不能直接等同于准确剧集数量或元数据质量。
- 两个来源分别拥有 1183 条远程文件记录；旧路径还有不属于有效来源的历史记录。本轮没有清理用户索引或删除历史数据。

## 真实媒体实测

- 媒体库已展示真实片库，包括小猪佩奇、熊出没等内容。
- 从媒体库进入小猪佩奇详情成功，海报、简介、分类及选集能够展示。
- 从实际 NAS 文件启动视频成功，画面、进度、缓冲正常；第一集约 28 秒时暂停，媒体会话记录缓冲约 127 秒。
- 手动切换下一集成功，播放器标题变为 S01E02，后续暂停时进度约 39 秒、缓冲约 123 秒。
- 返回首页后保留观看历史。播放测试可能按应用原逻辑更新观看进度。
- 没有声称通过长时间播放、跨季结束自动续播、多音轨/字幕、断网重连或所有文件格式兼容验证。

## 下载问题与最小修复

- 电视存在已挂载的可移除存储，但系统没有能处理 ACTION_OPEN_DOCUMENT_TREE 的 Activity。
- 从 NAS 选择文件后点击下载，原实现直接 launch 目录选择器，观察到 NAS 页面退出并返回首页。
- 根因：NasBrowserActivity 缺少 DownloadsFragment 已具备的目录选择器可用性检查及 ActivityNotFoundException 保护。
- 修改文件：feature/feature_app/src/main/java/com/wkq/bao/feature/app/NasBrowserActivity.kt。
- 复用已有 storage_picker_missing 与 storage_permission_failed 三语言资源，补充无处理器/系统占位实现检查、异常处理和等待状态恢复；没有引入新依赖或修改存储授权模型。
- 修复包在同一电视复测：同一进程、同一 NAS 页面保留，不再退出；提示当前设备不支持目录授权。
- 下载落盘、离线播放及附件缓存未通过端到端验证：尚缺可用的用户目录授权，不能将防退出修复等同于下载可用。

## 仍需修正的问题

1. 首页首屏和媒体库网格底部文字仍有裁切；导航遮挡滚动后的主视觉标题。
2. 真实片库大量使用横向剧照，完整放入竖框后空白明显，仍需更合理的卡片布局。
3. 部分“第 N 话”文件显示为独立影片，季号也有不一致：例如标题含 Season 4，而播放目标标签显示 S01，需要继续核验文件名/NFO/目录解析规则及分组。
4. 不能将目前 959 个索引条目视为正确完成分组；中文集号识别和去重应单独回归。

## 采用的规则与产物

- change-build Workflow：打包与构建检查；fix-bug → implement-page：NAS 下载入口异常的最小修复。
- Android build、architecture、UI、i18n Skills；资源保持默认中文、简体中文和英文，无新增 fallback。
- 用户提供的 NAS 媒体为真实测试素材，未替换或生成影视封面；图标沿用 Google Material 资源。本次不涉及商店发布，素材传播权利未评估。
- 构建日志及验收截图在 app/build/outputs/ui/nas-overall/，APK 在 app/build/outputs/apk/debug/app-debug.apk。
