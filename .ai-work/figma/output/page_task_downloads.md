# Figma 页面任务卡：圆宝TV - 存储与离线下载管理 (DownloadsActivity)

## 一、基本信息

- **页面名称**：圆宝TV - 存储与离线下载管理 (Downloads)
- **Figma 文件**：圆宝TV
- **Figma Frame Node ID**：`1:400`
- **Frame 尺寸**：1280 × 720 (标准 TV 16:9 全屏)
- **Figma 逻辑屏幕宽度**：1280px
- **Android 基准宽度**：1280dp
- **换算公式**：`dp = px * 1.0` (比例 1:1)
- **所属模块**：`feature:feature_app`
- **公共资源模块**：`feature:feature_res`
- **页面路由**：`com.wkq.bao.feature.app.DownloadsActivity`
- **Activity 基类**：`com.wkq.base.activity.BaseActivity<ActivityDownloadsBinding>`
- **屏幕方向**：`sensorLandscape` 强制横屏

---

## 二、页面图层与结构分解 (Hierarchy)

```text
根容器: ConstraintLayout (全屏暗色背景, paddingStart/End=48dp, paddingTop=24dp)
├── [Layer 0] 顶部头栏与外置存储状态卡: layout_header_storage
│     ├── 左侧: tv_title (26sp 粗体, "下载与存储管理")
│     └── 右侧: card_storage_info (毛玻璃大卡片, 380dp x 72dp)
│           ├── tv_storage_label ("USB 外置存储", 13sp Bold)
│           ├── tv_storage_capacity ("428.5 GB 可用 / 512 GB", 11sp)
│           ├── btn_select_storage ("设置存储目录" 按钮, 调起系统 SAF 树授权)
│           └── pb_storage (细长青色存储用量进度条)
├── [Layer 1] 正在下载任务区: layout_downloading
│     ├── tv_label_downloading (18sp 粗体, "正在下载")
│     └── card_download_task (大任务卡片, 100dp 高度, 获焦呼吸光晕)
│           ├── tv_task_title ("汪汪队立大功 S03E05 • 超级狗狗大冲刺.mkv", 15sp Bold)
│           ├── tv_task_speed ("18.6 MB/s • 450 MB / 1.2 GB", 青色 12sp)
│           ├── pb_task (细长双色进度条, max=100)
│           └── 按钮组: btn_task_pause ("暂停") + btn_task_cancel ("取消")
└── [Layer 2] 离线媒体库画廊 (已下载内容): layout_downloaded_gallery
      ├── tv_label_downloaded (18sp 粗体, "离线媒体库 (NAS 关闭仍可播放)")
      └── rv_downloaded (水平海报流, 2:3 黄金比例卡片, PosterCardAdapter)
```

---

## 三、关键图层到 Android XML 映射表

| Figma 图层名 | Node ID | Android 控件 ID | 控件类型 | 尺寸 (dp) | 样式规范 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Storage Card | `1:405` | `card_storage_info` | `ConstraintLayout`| `380dp x 72dp` | `@drawable/bg_glass_card` |
| Storage Label | `1:408` | `tv_storage_label` | `TextView` | `wrap_content` | 13sp Bold, `#FFFFFF` |
| Storage Size | `1:410` | `tv_storage_capacity`| `TextView` | `wrap_content` | 11sp, `@color/tv_text_secondary` |
| Set Dir Button | `1:415` | `btn_select_storage`| `Button` | `wrap_content x 32dp` | `@drawable/bg_tv_button_focus` |
| Task Card | `1:420` | `card_download_task`| `ConstraintLayout`| `match_parent x 100dp`| `@drawable/bg_tv_focus_ring` |
| Task Title | `1:425` | `tv_task_title` | `TextView` | `wrap_content` | 15sp Bold, `#FFFFFF` |
| Task Speed | `1:428` | `tv_task_speed` | `TextView` | `wrap_content` | 12sp, `@color/tv_accent_cyan` |
| Pause Button | `1:435` | `btn_task_pause` | `Button` | `80dp x 36dp` | `@drawable/bg_tv_button_focus` |
| Cancel Button | `1:440` | `btn_task_cancel` | `Button` | `80dp x 36dp` | `@drawable/bg_tv_button_focus` |
| Downloaded List| `1:450` | `rv_downloaded` | `RecyclerView` | `match_parent x 0dp` | 水平海报流, `clipToPadding="false"` |

---

## 四、SAF 外置存储与断网闭环合同

1. **SAF 授权链路**：用户点击 `btn_select_storage` -> 调起 `ACTION_OPEN_DOCUMENT_TREE` -> `takePersistableUriPermission` 持久化 URI -> 自动刷新已用容量与剩余空间。
2. **离线独立播放闭环**：离线库卡片点击直通 `PlayerActivity`，由 `MediaResolver` 命中 `PlaybackSource.Local`，**彻底脱离 NAS 局域网**。

---

## 五、门禁审计结论

- [x] **架构审计**：采用 `BaseActivity<ActivityDownloadsBinding>`
- [x] **屏幕方向**：`sensorLandscape` 全局强制横屏
- [x] **当前状态**：**分析完成（figma-process 完成），已输出施工图任务卡。当前已停止修改代码，等待用户确认！**
