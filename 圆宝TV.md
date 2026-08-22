# Android TV NAS Media Library 开发文档

## 1. 项目定位

开发一款运行在 Android TV / 小米电视上的本地媒体库应用。

核心目标：

- 从 NAS 扫描动画片、电视剧、电影资源
- 在电视上以海报墙、季、集的形式展示
- 支持直接从 NAS 串流播放
- 支持将 NAS 资源下载到 USB 移动硬盘、SSD、U 盘或 TF 卡
- NAS 关闭后，已经下载到外置存储的资源仍然可以正常播放
- 支持电视遥控器完整操作
- 使用 Media3 播放视频
- 支持播放进度、继续观看、自动下一集
- 为以后小爱同学、手机遥控、AI 搜索等能力预留接口

第一版不依赖服务器，不要求账号系统。

---

# 2. 使用场景

典型场景：

```text
NAS
 │
 │ SMB / WebDAV / HTTP
 ↓
Android TV
 │
 ├── 直接在线播放
 │
 └── 下载
       ↓
USB HDD / SSD / TF Card
       ↓
NAS 可以关闭
       ↓
本地继续播放
```

例如：

NAS 中存在：

```text
/cartoon/
    Paw Patrol/
        Season 01/
            S01E01.mp4
            S01E02.mp4
        Season 02/

    Peppa Pig/
        Season 01/
        Season 02/
```

App 扫描后不能以普通文件管理器形式展示。

应转换成：

```text
动画片

[汪汪队立大功]
7 Seasons

[小猪佩奇]
9 Seasons

[海底小纵队]
5 Seasons
```

进入电视剧/动画详情页：

```text
汪汪队立大功

▶ 继续播放
↓ 下载整季

Season 1
Season 2
Season 3

E01
E02
E03
...
```

---

# 3. 第一版 MVP

第一版必须完成以下核心链路：

```text
添加 NAS
    ↓
扫描 NAS 文件
    ↓
建立本地媒体索引
    ↓
电视海报墙展示
    ↓
进入影片 / 动画详情
    ↓
选择某一集
    ↓
直接从 NAS 播放
    ↓
下载到 USB 外置存储
    ↓
关闭 NAS
    ↓
仍然能够从本地播放
```

第一版只要这条链路完整跑通，产品即可使用。

---

# 4. 功能模块

## 4.1 首页

采用电视端流媒体样式。

推荐布局：

```text
┌─────────────────────────────────────────────┐
│ Media Home                           设置   │
│                                             │
│ 继续观看                                    │
│ [封面] [封面] [封面] [封面]                │
│                                             │
│ 动画片                                      │
│ [封面] [封面] [封面] [封面]                │
│                                             │
│ 最近添加                                    │
│ [封面] [封面] [封面] [封面]                │
│                                             │
│ 已下载                                      │
│ [封面] [封面] [封面] [封面]                │
└─────────────────────────────────────────────┘
```

首页栏目：

- 继续观看
- 动画片
- 电视剧
- 电影
- 最近添加
- 已下载
- 收藏
- NAS 内容

第一版可以先做：

- 继续观看
- 动画片
- 最近添加
- 已下载

---

# 5. TV 遥控器设计

电视端必须以遥控器为第一交互方式。

支持：

```text
↑
↓
←
→
OK / Enter
Back
Play
Pause
Fast Forward
Rewind
Menu
```

焦点必须明显。

海报获得焦点时：

- 卡片放大
- 阴影增强
- 边框高亮
- 标题显示
- 可显示观看进度

建议：

```text
普通状态：
scale = 1.0

Focus：
scale = 1.08 ~ 1.12
```

切换焦点动画：

```text
120ms ~ 180ms
```

不要使用手机式点击 UI。

---

# 6. NAS 支持

第一版建议优先：

```text
SMB
```

后续：

```text
WebDAV
HTTP
HTTPS
FTP
SFTP
```

NAS Source 数据：

```text
NasSource

id
name
type
host
port
username
password
rootPath
enabled
createdAt
lastScanAt
```

示例：

```text
name:
Home NAS

type:
SMB

host:
192.168.1.100

share:
media

path:
/cartoon
```

密码不能明文裸存。

建议：

- Android Keystore
- EncryptedSharedPreferences

---

# 7. NAS 扫描

扫描流程：

```text
NAS
 ↓
递归扫描目录
 ↓
识别媒体文件
 ↓
解析文件名
 ↓
生成媒体索引
 ↓
写入 Room
```

支持的视频扩展名：

```text
.mp4
.mkv
.avi
.mov
.ts
.m2ts
.webm
.m4v
```

字幕：

```text
.srt
.ass
.ssa
.vtt
```

图片：

```text
.jpg
.jpeg
.png
.webp
```

---

# 8. 文件名解析

第一版不做 AI。

优先通过规则识别：

```text
S01E01
S1E1
Season 01 Episode 01
01x01
第1季第1集
```

例如：

```text
Paw.Patrol.S03E04.1080p.mkv
```

解析成：

```text
Series:
Paw Patrol

Season:
3

Episode:
4
```

支持中文：

```text
汪汪队立大功 第3季 第4集.mp4
```

---

# 9. 媒体数据结构

## MediaSeries

```text
id
title
originalTitle
type
posterUri
backdropUri
description
year
genre
createdAt
updatedAt
```

type：

```text
MOVIE
TV
CARTOON
```

---

## Season

```text
id
seriesId
seasonNumber
title
posterUri
episodeCount
```

---

## Episode

```text
id
seriesId
seasonId
episodeNumber
title
description
duration
thumbnailUri
airDate
```

---

## MediaFile

这是核心表。

```text
id
episodeId
seriesId

nasSourceId
nasUri

localUri

fileName
fileSize
mimeType

checksum

downloadStatus

createdAt
updatedAt
```

一个资源同时保存：

```text
nasUri
localUri
```

例如：

```text
nasUri:
smb://192.168.1.100/cartoon/paw/S01E01.mkv
```

本地：

```text
localUri:
content://com.android.externalstorage.documents/...
```

---

# 10. 播放来源选择

播放时：

```text
localUri 是否存在？
        ↓
     YES
        ↓
从 USB / HDD 播放
```

否则：

```text
NAS 是否在线？
        ↓
     YES
        ↓
NAS 串流
```

否则：

```text
资源当前不可用
```

伪代码：

```kotlin
when {
    media.localUri != null && localFileExists(media.localUri) -> {
        playLocal(media.localUri)
    }

    nasAvailable(media.nasSourceId) -> {
        playNas(media.nasUri)
    }

    else -> {
        showUnavailable()
    }
}
```

原则：

```text
LOCAL FIRST
NAS SECOND
```

---

# 11. Media3 播放器

播放器使用：

```text
AndroidX Media3
ExoPlayer
MediaSession
MediaSessionService
```

播放器必须支持：

- Play
- Pause
- Seek
- Fast Forward
- Rewind
- Next Episode
- Previous Episode
- Auto Next
- Resume
- Subtitle
- Audio Track
- Playback Speed

建议播放器层：

```text
PlaybackService
      ↓
ExoPlayer
      ↓
MediaSession
      ↓
UI
```

不要把 ExoPlayer 生命周期完全绑定 Activity。

---

# 12. MediaSession

必须接入。

原因：

- 遥控器媒体按键
- 系统媒体控制
- 小米电视系统
- 小爱同学可能调用
- 后续手机遥控
- 后续外部控制器

支持 Commands：

```text
PLAY
PAUSE
STOP
SEEK
NEXT
PREVIOUS
FAST_FORWARD
REWIND
```

后续预留：

```text
SEARCH
PLAY_FROM_SEARCH
CONTINUE
```

---

# 13. 播放进度

保存：

```text
WatchHistory

mediaId
positionMs
durationMs
completed
lastPlayedAt
```

每隔：

```text
10 ~ 30 seconds
```

保存一次。

播放结束：

```text
completed = true
position = 0
```

继续观看：

```text
progress >= 2 min
AND
progress < 90%
```

展示在首页：

```text
继续观看
```

---

# 14. 自动下一集

当前 Episode 播放完成：

```text
E01
 ↓
找到 E02
 ↓
本地有 E02？
 ↓ YES
播放 E02
```

没有本地：

```text
NAS 在线？
 ↓
YES
在线播放 E02
```

NAS 也不在线：

```text
提示：
下一集尚未下载
```

---

# 15. 外置存储

支持：

- USB HDD
- USB SSD
- U 盘
- USB 读卡器
- TF / SD Card

使用 Android：

```text
Storage Access Framework
```

第一次：

```text
设置
 ↓
下载位置
 ↓
选择目录
 ↓
ACTION_OPEN_DOCUMENT_TREE
```

保存权限：

```kotlin
takePersistableUriPermission()
```

保存：

```text
downloadRootUri
```

不要依赖：

```text
/storage/XXXX-XXXX/
```

这样的绝对路径。

---

# 16. 外置存储目录

建议：

```text
TVMedia/
│
├── Cartoon/
├── TV/
├── Movies/
├── Downloads/
├── Posters/
├── Subtitles/
└── Temp/
```

例如：

```text
TVMedia/
└── Cartoon/
    └── Paw Patrol/
        └── Season 01/
            ├── S01E01.mkv
            ├── S01E02.mkv
            └── S01E03.mkv
```

---

# 17. 下载功能

支持：

```text
下载当前集
下载整季
下载整部
```

下载任务：

```text
DownloadTask

id
mediaId
sourceUri
targetUri

totalBytes
downloadedBytes

status

createdAt
finishedAt
```

Status：

```text
WAITING
DOWNLOADING
PAUSED
SUCCESS
FAILED
```

---

# 18. 下载安全

必须采用：

```text
临时文件
 ↓
下载完成
 ↓
校验
 ↓
正式文件
```

例如：

```text
S01E01.mkv.download
```

下载完成后：

```text
S01E01.mkv
```

避免出现：

```text
下载一半
 ↓
App 崩溃
 ↓
播放器把坏文件当完整视频播放
```

---

# 19. 下载校验

至少检查：

```text
文件大小
```

后续支持：

```text
MD5
SHA-256
```

如果 NAS 能提供 hash：

```text
NAS hash
==
local hash
```

则认为下载成功。

---

# 20. NAS 离线

NAS 关闭时：

App 不应该一直报错。

策略：

```text
NAS Offline
 ↓
继续展示媒体库
 ↓
已经下载的资源继续可播放
 ↓
NAS-only 资源显示：
Unavailable / NAS Offline
```

不要因为 NAS 不在线清空媒体库。

Room 保存完整索引。

---

# 21. NAS 在线检测

后台轻量检测：

```text
ping 不推荐作为唯一判断
```

建议：

```text
连接 NAS 协议
 ↓
请求 root
 ↓
成功 = ONLINE
```

状态：

```text
ONLINE
OFFLINE
CONNECTING
AUTH_FAILED
```

---

# 22. NAS 同步

用户可以：

```text
设置
 ↓
扫描 NAS
```

或者：

```text
Refresh
```

同步策略：

```text
扫描 NAS
 ↓
与 Room 对比
 ↓
新增
更新
删除
```

不要每次启动全量重建数据库。

---

# 23. 海报墙

海报比例建议：

```text
2:3
```

例如：

```text
300 × 450
```

背景图：

```text
16:9
```

电视焦点：

```text
Poster focused
 ↓
scale 1.1
 ↓
显示标题
 ↓
显示进度
```

---

# 24. 详情页

布局：

```text
┌─────────────────────────────────────┐
│               Backdrop              │
│                                     │
│ 汪汪队立大功                         │
│ 2013 · Cartoon · 7 Seasons          │
│                                     │
│ ▶ Continue                          │
│ ↓ Download Season                   │
│                                     │
│ Season 1 Season 2 Season 3          │
│                                     │
│ E01  ✓ Downloaded                   │
│ E02  NAS                            │
│ E03  NAS                            │
│ E04  ✓ Downloaded                   │
└─────────────────────────────────────┘
```

---

# 25. 播放器 UI

电视播放页：

```text
┌──────────────────────────────────────┐
│                                      │
│                VIDEO                 │
│                                      │
│                                      │
│ 汪汪队 S01E03                        │
│                                      │
│ ◀◀   ▶/⏸   ▶▶                       │
│ ─────────────●────────────           │
│ 12:34                       23:40     │
│                                      │
│ 字幕     音轨     下一集             │
└──────────────────────────────────────┘
```

遥控器：

```text
OK
→ 显示控制条

Left
→ 快退

Right
→ 快进

Up / Down
→ 控件导航

Back
→ 隐藏控制栏 / 退出
```

---

# 26. 小米电视兼容

目标第一阶段：

```text
小米电视 6
Android TV / MIUI TV
```

必须考虑：

- 电视 RAM 较小
- 不要长期持有大量 Bitmap
- RecyclerView 控制缓存
- 图片加载压缩
- 后台任务限制
- USB 挂载变化
- 电视休眠
- 系统杀后台

推荐：

```text
Coil
```

加载海报。

避免加载超大原图。

---

# 27. 开机恢复

如果以后用于长期媒体播放：

支持：

```text
BOOT_COMPLETED
```

启动后：

```text
读取最后播放记录
 ↓
恢复媒体库状态
```

是否自动播放可在设置中：

```text
Auto Resume on Boot
```

默认关闭。

---

# 28. 小爱同学预留

第一版不强依赖小爱同学。

但是必须把播放器接入：

```text
MediaSession
```

这样未来可以支持：

```text
暂停
继续
下一集
上一集
```

同时预留 Deep Link。

---

# 29. Deep Link

设计统一 Command Router。

例如：

```text
mytv://play/episode/123

mytv://series/456

mytv://search?q=汪汪队

mytv://continue

mytv://next

mytv://previous
```

所有入口最终转成：

```text
AppCommand
```

例如：

```kotlin
sealed class AppCommand {

    data class PlayEpisode(
        val episodeId: Long
    ) : AppCommand()

    data class OpenSeries(
        val seriesId: Long
    ) : AppCommand()

    data class Search(
        val query: String
    ) : AppCommand()

    object ContinueWatching : AppCommand()

    object Next : AppCommand()

    object Previous : AppCommand()
}
```

未来可供：

- 小爱同学
- 手机遥控
- Web 控制
- Android TV 搜索
- AI

统一调用。

---

# 30. 设置页

第一版：

```text
NAS Sources

Storage

Playback

Downloads

Library

About
```

NAS：

```text
Add NAS
Edit NAS
Test Connection
Scan
```

Storage：

```text
Internal Storage
USB HDD
USB SSD
TF Card

Available Space
```

Playback：

```text
Auto Next Episode
Resume Playback
Subtitle Default
Preferred Audio
```

Downloads：

```text
Download Location
Concurrent Downloads
Wi-Fi Only
Delete Downloads
```

Library：

```text
Rescan
Clear Metadata
Rebuild Library
```

---

# 31. Room 数据库

建议：

```text
NasSourceEntity
MediaSeriesEntity
SeasonEntity
EpisodeEntity
MediaFileEntity
WatchHistoryEntity
DownloadTaskEntity
FavoriteEntity
```

Repository：

```text
NasRepository
LibraryRepository
PlaybackRepository
DownloadRepository
StorageRepository
```

---

# 32. 推荐模块结构

```text
app/

core/
    database/
    network/
    storage/
    media/
    common/

feature/
    home/
    library/
    series/
    player/
    downloads/
    nas/
    settings/

data/
    nas/
    media/
    download/
    storage/

domain/
    model/
    repository/
    usecase/
```

---

# 33. Media Playback 层

```text
PlaybackService
PlaybackController
PlaybackRepository
MediaResolver
```

MediaResolver：

```text
MediaFile
 ↓
本地存在？
 ↓
localUri

否则
 ↓
nasUri
```

---

# 34. 下载层

```text
DownloadManager

enqueue()

pause()

resume()

cancel()

delete()
```

下载不要绑定 Activity 生命周期。

推荐：

```text
Foreground Service
```

或：

```text
WorkManager
```

大文件连续下载更推荐 Foreground Service。

---

# 35. 网络恢复

下载过程中 NAS 掉线：

```text
DOWNLOADING
 ↓
NETWORK ERROR
 ↓
PAUSED / RETRY
```

NAS 恢复：

```text
Resume
```

后续支持断点续传。

第一版可以：

```text
失败后重新下载
```

---

# 36. 外置硬盘异常

需要处理：

```text
USB 拔出
USB 掉线
USB 重新插入
空间不足
权限丢失
只读存储
```

如果当前播放文件来自 USB：

```text
USB removed
 ↓
停止播放
 ↓
如果 NAS 在线
 ↓
自动切 NAS 播放同一资源
```

这是很有价值的 fallback。

---

# 37. 空间管理

下载前：

```text
requiredSize
<
availableSize
```

否则：

```text
Not enough storage
```

支持显示：

```text
USB SSD
428 GB Free / 931 GB
```

后续：

```text
自动清理已看完资源
```

第一版不做自动删除。

---

# 38. 数据缓存

海报：

```text
Coil Disk Cache
```

Metadata：

```text
Room
```

视频：

```text
USB / NAS
```

避免把大量视频放：

```text
internal storage
```

---

# 39. 第一版明确不做

第一版暂不做：

```text
AI
语音识别
小爱自定义技能
用户账号
云同步
Plex
Jellyfin
Emby
DLNA
Chromecast
投屏
手机遥控
Web 遥控
边下边播
复杂在线刮削
多用户 Profile
儿童锁
远程 NAS
公网串流
服务器
```

---

# 40. 第二阶段

MVP 完成后可以增加：

## Metadata 刮削

支持：

```text
TMDB
TVDB
Douban
Bangumi
NFO
```

自动获取：

- 海报
- 背景图
- 简介
- 演员
- 年份
- 分类
- Season
- Episode

---

# 41. 第三阶段

手机局域网遥控：

```text
手机浏览器
 ↓
电视局域网 Web Server
 ↓
播放 / 暂停 / 搜索
```

例如：

```text
http://192.168.1.30:8080
```

支持：

```text
搜索动画
播放
下载
暂停
下一集
```

---

# 42. 第四阶段

小爱同学：

```text
打开 App
暂停
继续
下一集
```

如果小米开放第三方内容搜索：

```text
播放汪汪队
播放小猪佩奇第三季
```

---

# 43. 第五阶段

离线 AI：

```text
中文语义搜索
自动分类
智能标签
自然语言媒体查询
```

例如：

```text
找一个已经下载的恐龙动画
```

但 AI 不属于 MVP。

---

# 44. 开发优先级

## Phase 1

```text
TV 项目框架
遥控器焦点
Media3
本地文件播放
```

## Phase 2

```text
NAS 配置
SMB 连接
NAS 文件扫描
NAS 在线播放
```

## Phase 3

```text
Room 媒体库
Series
Season
Episode
海报墙
详情页
```

## Phase 4

```text
SAF 外置存储
下载 NAS 文件
USB 播放
```

## Phase 5

```text
观看历史
继续播放
自动下一集
下载管理
```

---

# 45. MVP 验收标准

必须完成：

### NAS

- 能添加 NAS
- 能测试 NAS
- 能扫描目录
- 能读取视频

### 媒体库

- 能显示动画片列表
- 能显示季
- 能显示集

### 播放

- NAS 能直接播放
- Media3 控制正常
- 遥控器可操作
- 可快进快退
- 自动下一集

### 下载

- 可选择 USB HDD
- 可下载 NAS 文件
- 可显示进度
- 下载完成可播放

### 离线

关闭 NAS 后：

- 媒体库仍然存在
- 已下载资源仍然可播放
- 未下载资源标记 NAS Offline

### 观看历史

- 自动保存进度
- 首页显示继续观看
- 可从上次位置继续

---

# 46. 最终第一版产品形态

最终用户体验：

```text
打开电视
 ↓
进入媒体库
 ↓
看到动画海报墙
 ↓
选择《汪汪队》
 ↓
选择 Season 3
 ↓
选择 Episode 5
 ↓
如果本地有资源
    ↓
直接 USB 播放

如果没有
    ↓
NAS 在线
    ↓
NAS 串流
```

用户还可以：

```text
下载 Season 3
 ↓
NAS → USB HDD
 ↓
下载完成
 ↓
关闭 NAS
 ↓
以后继续正常观看
```

产品核心不是：

```text
NAS 文件管理器
```

而是：

> 一个以 NAS 为媒体源、以 USB 外置存储为离线媒体库的 Android TV 私人流媒体播放器。