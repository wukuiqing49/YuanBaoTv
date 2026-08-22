# Figma 页面任务卡：圆宝TV - 剧集详情页 (DetailActivity)

## 一、基本信息

- **页面名称**：圆宝TV - 剧集详情页 (Detail)
- **Figma 文件**：圆宝TV
- **Figma Frame Node ID**：`1:200`
- **Frame 尺寸**：1280 × 720 (标准 TV 16:9)
- **Figma 逻辑屏幕宽度**：1280px
- **Android 基准宽度**：1280dp
- **换算公式**：`dp = px * 1.0` (比例 1:1)
- **所属模块**：`feature:feature_app`
- **公共资源模块**：`feature:feature_res`
- **页面路由**：`com.wkq.bao.feature.app.DetailActivity`
- **Activity 基类**：`com.wkq.base.activity.BaseActivity<ActivityDetailBinding>`
- **生命周期规范**：`initView()` 绑定视图与 D-Pad 焦点，`initData()` 观察 Room Flow 数据

---

## 二、页面图层与结构分解 (Hierarchy)

```text
根容器: ConstraintLayout (1280dp x 720dp)
├── [Layer 0] iv_backdrop (铺满底图, 16:9 Backdrop 大图, ScaleType=centerCrop)
├── [Layer 1] v_dark_overlay (全屏暗角遮罩, #E60A0C10, 确保文字与卡片反差)
├── [Layer 2] layout_top_content (主信息区, 顶部左对齐, marginStart=64dp, marginTop=48dp)
│     ├── 左侧立牌: iv_series_poster (2:3 纵向海报封面, 180dp x 270dp, 圆角 12dp, 带 focus_ring)
│     └── 右侧文字与操作区: layout_meta_info (width=720dp, marginStart=32dp)
│           ├── tv_nas_badge (NAS 来源状态角标, "NAS 局域网在线 / 已离线")
│           ├── tv_title (主标题, 32sp Bold, 如 "汪汪队立大功")
│           ├── tv_meta_tags (年代 • 类型 • 季数, 14sp, 如 "2023 • 少儿 / 益智 • 7 季")
│           ├── tv_desc (剧情简介, 13sp, 3 行折叠, 行距 3dp)
│           └── layout_action_buttons (水平按钮组, marginTop=20dp)
│                 ├── btn_play (立即播放主按钮, 140dp x 46dp, Cyan 渐变实心高亮)
│                 ├── btn_download_season (下载整季, 130dp x 46dp, 半透明毛玻璃)
│                 └── btn_favorite (收藏, 100dp x 46dp, 半透明毛玻璃)
└── [Layer 3] layout_episodes_section (底部选集流, marginStart=64dp, marginEnd=64dp, marginTop=28dp)
      ├── ll_season_tabs (动态分季药丸 Tab 栏, height=40dp, 水平滚动)
      └── rv_episodes (水平选集列表, height=120dp, clipToPadding=false)
            └── item_episode_card.xml (16:9 单集卡片, 160dp x 90dp, 集数 + 标题 + "已下载/NAS" 角标)
```

---

## 三、关键图层到 Android XML 映射表

| Figma 图层名 | Node ID | Android 控件 ID | 控件类型 | 尺寸 (dp) | 视觉/样式规范 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Background Image | `1:201` | `iv_backdrop` | `ImageView` | `0dp x 0dp` (全屏) | `centerCrop`, Coil 加载 `backdropUri` |
| Overlay Mask | `1:202` | `v_mask` | `View` | `0dp x 0dp` (全屏) | `#E60A0C10` 90% 黑色暗幕 |
| Poster Card | `1:205` | `iv_series_poster`| `ImageView` | `180dp x 270dp` | 2:3 比例，`@drawable/bg_tv_focus_ring` |
| NAS Status Badge | `1:208` | `tv_nas_badge` | `TextView` | `wrap_content` | `@drawable/bg_badge_nas`, 12sp Bold |
| Series Title | `1:210` | `tv_title` | `TextView` | `wrap_content` | 32sp, `#FFFFFF`, Bold |
| Meta Tags | `1:212` | `tv_meta_tags` | `TextView` | `wrap_content` | 14sp, `@color/tv_text_secondary` |
| Description | `1:215` | `tv_desc` | `TextView` | `match_parent` | 13sp, 3 行截断, `#94A3B8` |
| Play Button | `1:220` | `btn_play` | `Button` | `140dp x 46dp` | `@drawable/bg_hero_play_btn`, 14sp Bold |
| Download Season | `1:224` | `btn_download_season`| `Button`| `130dp x 46dp` | `@drawable/bg_tv_button_focus` |
| Favorite Button | `1:228` | `btn_favorite` | `Button` | `100dp x 46dp` | `@drawable/bg_tv_button_focus` |
| Season Tab Container| `1:235`| `ll_season_tabs` | `LinearLayout`| `wrap_content x 40dp`| 动态生成 Season 按钮 |
| Episode RecyclerView| `1:240`| `rv_episodes` | `RecyclerView`| `match_parent x 120dp`| 水平滚动, `clipToPadding="false"` |

---

## 四、单集卡片（`item_episode_card.xml`）图层映射

```text
item_episode_card.xml (180dp x 100dp)
├── iv_episode_thumb (16:9 剧照背景图)
├── v_episode_mask (底部暗角)
├── tv_episode_number ("第 01 集", 13sp Bold)
├── tv_episode_title ("狗狗拯救海象", 11sp)
└── tv_download_status_badge ("已下载 / NAS", 右上角小角标, 9sp)
```

---

## 五、宽度基准与 Insets 合同

1. **宽度基准**：固定 1280dp 逻辑宽度，横屏充满屏幕。
2. **安全区 Insets**：
   - 顶部和左右外边距保留 64dp 安全区（避免老式 TV 电视过扫描/Overscan 截断）。
   - Insets 由宿主 Activity 处理，内层 RecyclerView 声明 `android:clipToPadding="false"`，保证 1.08x 焦点放大时不被边缘裁剪。
3. **触控与遥控器焦点**：
   - 所有可点击项（按钮、海报、选集卡片、Tab）统一设置 `android:focusable="true"`。
   - 挂载 `TvFocusHelper.applyFocusScale()` 获得焦点时 1.08x 放大 + 阴影抬升。

---

## 六、页面级资源清单 (`asset_manifest_detail.md`)

| 资源名称 | 类型 | 目标目录 | 状态 | 规范要求 |
| :--- | :--- | :--- | :--- | :--- |
| `bg_badge_nas.xml` | Shape | `feature/feature_res/drawable/` | 已就绪 | 蓝青色半透明胶囊形状 |
| `bg_badge_local.xml` | Shape | `feature/feature_res/drawable/` | 已就绪 | 绿色半透明胶囊形状 |
| `bg_tv_focus_ring.xml`| Selector | `feature/feature_res/drawable/` | 已就绪 | 聚焦态青色呼吸发光外框 |
| `bg_hero_play_btn.xml`| Selector | `feature/feature_res/drawable/` | 已就绪 | 实心青色渐变大圆角播放按钮 |
| `bg_tv_button_focus.xml`| Selector| `feature/feature_res/drawable/` | 已就绪 | 半透明毛玻璃聚焦高亮样式 |
| `ic_tv_play.xml` | Vector | `feature/feature_res/drawable/` | 已就绪 | 标准播放三角形矢量 |
| `ic_tv_download.xml` | Vector | `feature/feature_res/drawable/` | 已就绪 | 标准下载箭头矢量 |
| `ic_tv_favorite.xml` | Vector | `feature/feature_res/drawable/` | 已就绪 | 标准五角星收藏矢量 |

---

## 七、门禁审计结论与阻塞项

- [x] **架构门禁**：采用 `com.wkq.base.activity.BaseActivity<ActivityDetailBinding>`，符合 WKQ 框架标准。
- [x] **模块归属**：页面逻辑放 `feature:feature_app`，通用资源放 `feature:feature_res`。
- [x] **适配原则**：支持 1280dp TV 与手机横屏，严格分离“固定视觉块”与“弹性自适应区”。
- [x] **当前状态**：**分析完成（figma-process 完成），已生成完整施工图任务卡。当前已停止修改代码，等待用户确认！**
