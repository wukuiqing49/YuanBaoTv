# Figma 页面任务卡：圆宝TV - 首页

## 基本信息

- 页面名称：圆宝TV - 首页 (优化版)
- Figma 文件：圆宝TV
- Figma 链接：https://www.figma.com/design/... (本地 Dev Mode 连通)
- node id：`1:2`
- Frame 宽度：1280
- Frame 高度：1730.25
- Figma 逻辑屏幕宽度：1280
- Figma 逻辑屏幕高度：720 (标准 720P/1080P TV 基准)
- 是否高清放大稿：否
- 高清放大倍率：1.0
- Android 基准宽度：1280dp
- 缩放比例：1.0
- 页面类型：TV 流媒体主页 (Activity + RecyclerViews)
- 所属模块：`feature:feature_app`
- 公共资源模块：`feature:feature_res`
- 页面路由：`com.wkq.bao.feature.app.HomeActivity`
- Launcher Activity：`com.wkq.bao.feature.app.SplashActivity`
- Host Activity / Fragment：`HomeActivity`
- 目标 Feature 页面：`HomeActivity`
- 创建 / 路由入口：`SplashActivity -> HomeActivity`
- 是否替换旧实现：是
- 旧实现替换范围：全面重构 TV 首页布局与遥控器交互
- 负责人：Antigravity
- 日期：2026-08-22

## 设计资源

- 截图：`figma_screenshot_1_2.png`
- JSON：`figma_nodes_1_2.json`
- 图层报告：`figma_layer_report.md`
- 规范化报告：`.ai-work/figma/output/figma_normalize_report.md`
- 图片目录：`feature/feature_res/src/main/res/drawable/`
- 资源清单：`.ai-work/figma/output/asset_manifest_home.md`

## 页面分析

- 页面结构：
  1. **TopNavBar (顶部导航栏)**：品牌 Logo + 首页/动画/全部媒体/下载/设置 按钮组。
  2. **Hero Section (焦点推荐 Banner)**：大背景图 + 标题 + 简介 + 立即播放 & 详情操作按钮。
  3. **Section 1: Continue Watching (继续观看)**：16:9 横版卡片流，带播放进度条。
  4. **Section 2: Cartoons (热门动画)**：2:3 纵向海报流，带季数与分类信息。
- 关键图层：
  - `1:3` Top Navigation
  - `1:29` Section - Hero Content
  - `1:49` Section - Row 1: Continue Watching (16:9)
  - `1:93` Section - Row 2: Cartoons (2:3 Posters)
- 可复用组件：
  - `bg_tv_focus_ring.xml`（卡片获焦外发光边框）
  - `bg_glass_card.xml`（毛玻璃半透明容器）
  - `TvFocusHelper`（统一 1.08x 放大与 Elevation 抬升）
- 列表 / Adapter：
  - `ContinueWatchingAdapter` (承载 `WatchHistoryEntity`)
  - `PosterCardAdapter` (承载 `MediaSeriesEntity`)
- 交互说明：
  - D-Pad 遥控器上下左右流畅导航。
  - 聚焦元素自动放大并显示呼吸发光环。

## 项目基类 / 组件审计

- 已扫描模块：`core:core_base`、`core:core_utils`、`feature:feature_res`、`feature:feature_app`
- Gradle / AAR / 源码依赖审计：依赖 `com.github.wukuiqing49:AndroidCoreBase:v1.3.0`
- Activity 基类：`com.wkq.base.activity.BaseActivity<VB: ViewBinding>`
- Fragment 基类：`com.wkq.base.frame.fragment.MvpFragment`
- ViewModel 基类：`androidx.lifecycle.ViewModel`
- Adapter 基类：`androidx.recyclerview.widget.RecyclerView.Adapter`
- Insets / Edge-to-edge 工具：`androidx.activity.enableEdgeToEdge()`
- 最终采用结论：全面采用 `BaseActivity<ActivityHomeBinding>`，重写 `initView()` 与 `initData()`。

## 页面入口链路合同

| 层级 | Android 落点 | 职责 | 是否新增/替换 | 说明 |
| --- | --- | --- | --- | --- |
| Launcher | `SplashActivity` | 开屏欢迎、品牌动效、初始化 | 新增 | 应用全局启动入口 |
| Home Host | `HomeActivity` | 承载首页导航、Hero Banner、海报流 | 替换/新建 | 核心主界面 |
| Detail | `DetailActivity` | 剧集详情、选集、播放与下载 | 新增 | 点击卡片跳转目标 |
| Library | `MediaLibraryActivity` | 分类媒体库海报墙 | 新增 | 导航栏跳转目标 |
| Settings | `NasSettingsActivity` | NAS 源管理与扫描 | 新增 | 导航栏跳转目标 |
| Downloads | `DownloadsActivity` | 存储与离线下载管理 | 新增 | 导航栏跳转目标 |

## 屏幕适配方案

- Figma Frame：1280 × 1730.25
- Figma 逻辑屏幕：1280 × 720 (TV 标准 16:9)
- Android 基准宽度：1280dp
- 换算公式：`dp = px * 1.0`
- 换算比例：1.0
- 宽度基准与高度填充：宽度采用 `0dp` 约束拉伸，纵向采用 `NestedScrollView` 支撑长内容滚动，RecyclerView 水平滚动。
- 根布局自适应策略：`NestedScrollView` + `ConstraintLayout` (match_parent)。
- 列表列宽 / 间距策略：横向 `paddingStart/End = 48dp`, 卡片右间距 `16dp`。
- 图片比例策略：继续观看 `16:9`，海报墙 `2:3`。

## 宽度基准与高度填充合同

| 项 | 合同 |
| --- | --- |
| 宽度基准 | 1280dp (TV 标准) |
| 固定视觉块 | TopNavBar (64dp), Hero Banner (220dp), 16:9 卡片 (280x158dp), 2:3 卡片 (160x240dp) |
| 弹性内容区 | 列表项水平无限滚动，页面整体垂直自适应 |
| Figma 高度用途 | 仅作为各 Section 的排列参考，不写死 Android 根布局高度 |
| Insets 对高度的影响 | TV 页面边距设为 48dp 安全区，内层 clipToPadding="false" 避免焦点截断 |

## 关键图层 Bounds 表

| 用途 | Figma 图层 | Node ID | x | y | width | height | Android 归属 |
| --- | --- | --- | ---: | ---: | ---: | ---: | --- |
| 页面根 | 圆宝TV - 首页 (优化版) | `1:2` | 0 | 0 | 1280 | 1730 | `activity_home.xml` root |
| 顶部导航栏 | Top Navigation | `1:3` | 0 | 0 | 1280 | 204 | `activity_home.xml#top_nav_bar` |
| Hero 焦点区 | Section - Hero Content | `1:29` | 80 | 144 | 896 | 290 | `activity_home.xml#layout_hero` |
| 继续观看行 | Section - Row 1: Continue Watching | `1:49` | 80 | 498 | 1120 | 368 | `activity_home.xml#rv_continue_watching` |
| 热门动画行 | Section - Row 2: Cartoons | `1:93` | 80 | 914 | 1120 | 468 | `activity_home.xml#rv_cartoons` |

## 关键尺寸换算表

| 用途 | Figma px | 逻辑 px | Android dp/sp | dimen/style 名 | 固定尺寸例外 | 说明 |
| --- | ---: | ---: | ---: | --- | --- | --- |
| 页面左右外边距 | 80 | 48 | 48dp | `tv_screen_padding` | 否 | TV 安全区边距 |
| 导航栏高度 | 82 | 64 | 64dp | `tv_nav_bar_height` | 否 | 顶部导航容器高度 |
| Hero Banner 高度 | 290 | 220 | 220dp | `tv_hero_height` | 否 | 焦点推荐卡片高度 |
| 继续观看卡片宽 | 420 | 280 | 280dp | `tv_continue_card_width` | 否 | 16:9 横版比例宽 |
| 继续观看卡片高 | 236 | 158 | 158dp | `tv_continue_card_height` | 否 | 16:9 横版比例高 |
| 动画海报卡片宽 | 224 | 160 | 160dp | `tv_poster_card_width` | 否 | 2:3 纵向比例宽 |
| 动画海报卡片高 | 336 | 240 | 240dp | `tv_poster_card_height` | 否 | 2:3 纵向比例高 |
| 标题主字号 | 32 | 26 | 26sp | `tv_text_title` | 否 | 大屏一级标题字号 |

## 图层到 Android 映射

| Figma 图层 | Node ID | Android 文件 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| Top Navigation | `1:3` | `activity_home.xml#top_nav_bar` | XML Layout | 顶部导航容器 |
| 圆宝TV Logo | `1:5` | `activity_home.xml#tv_logo` | TextView | 品牌标题文字 |
| 立即播放 Button | `1:38` | `activity_home.xml#btn_hero_play` | Button | 焦点播放主按钮 |
| 查看详情 Button | `1:43` | `activity_home.xml#btn_hero_detail` | Button | 详情操作次按钮 |
| Continue Card 1 | `1:53` | `item_continue_watching.xml` | XML Item | 16:9 横版单项卡片 |
| Poster Card 1 | `1:97` | `item_poster_card.xml` | XML Item | 2:3 纵向海报单项卡片 |

## 门禁记录

- 架构门禁是否通过：是
- 项目基类 / 组件审计是否通过：是（采用 WKQ `BaseActivity<VB>`）
- 页面入口链路合同是否通过：是（`SplashActivity -> HomeActivity -> DetailActivity`）
- 规范化报告是否已读取：是
- 资源门禁是否通过：是（全量 Shape / Selector / String 已就绪）
