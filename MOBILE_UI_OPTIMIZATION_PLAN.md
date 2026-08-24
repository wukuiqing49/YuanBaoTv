# YuanBaoTv 手机端页面优化计划

执行日期：2026-08-25

当前手机端 UI 评分：约 55/100

本轮目标：完成 P0/P1 后达到 75-80 分，为后续商业化 90 分验收建立稳定基线。

## 1. 目标与边界

### 目标

- 手机页面不再呈现为电视页面的等比压缩或横向拉伸。
- 手机、手机横屏、平板/折叠屏和 Android TV 使用匹配各自交互方式的导航与布局。
- 修复系统栏不可见、Material3 按钮错误染色、页面标题重复和固定高度导致的空白问题。
- 保持 NAS、扫描、下载、播放、媒体筛选和分集逻辑不变。
- 建立可重复执行的手机端截图与字体缩放验收矩阵。

### 不在本轮处理

- 不修改 NAS、下载、扫描、播放器的数据与任务逻辑。
- 不修改数据库结构、public API、包名、模块名和已有 XML id。
- 不处理支付和发布签名安全。
- 不进行与手机页面无关的架构重构。

## 2. 已确认的实机基线

测试设备：OPPO PCAM00，Android 11

物理分辨率：1080 x 2340

密度：480 dpi

系统字体缩放：1.0

实测场景：手机横屏，应用窗口约 780dp x 360dp

已确认问题：

- 顶部四个纯文字 Tab 横向铺满，选中胶囊过宽，导航占用过多垂直空间。
- Tab 字号为 13sp，本身没有异常；“特别大”的主要原因是容器比例、等宽分配和重复页面标题。
- 横屏手机仍命中默认 `layout/`，因为现有 `layout-sw600dp/` 按最小宽度判断，无法覆盖普通手机横屏。
- 普通 `Button` 被 Material3 `primary` 绿色 Tint 覆盖，自定义金色/青色 drawable 未按设计显示。
- 深色页面使用 `windowLightStatusBar=true` 和 `windowLightNavigationBar=true`，导致系统栏深色图标不可见。
- 首页、媒体库、下载管理和 NAS 页面存在大面积无意义空白和电视式大卡片。
- NAS 编辑器包含六个输入框但没有滚动容器，横屏加软键盘时存在遮挡风险。
- `ViewPager2.isUserInputEnabled=false` 对电视合理，但手机也被禁止左右滑动。

## 3. 设计与实现原则

1. 手机默认使用底部图标导航；Android TV 保留顶部遥控器导航。
2. 普通手机横屏优先使用紧凑底部导航；窗口宽度足够时可使用窄侧边导航。
3. 使用当前窗口宽度适配，新增 `layout-w600dp/`；不要只依赖 `sw600dp`。
4. 页面只保留一个主标题层级，主导航文字与页面标题不得重复占据首屏。
5. 列表和内容区使用剩余空间或自然滚动，避免无必要的固定高度。
6. 手机使用触控反馈，电视使用焦点环和焦点缩放；焦点动画不得无条件应用到触屏设备。
7. 字号保持可访问性，不通过统一缩小字体掩盖布局问题。
8. 所有用户可见文案进入 `feature_res`，默认、中文和英文资源保持一致。

## 4. 明日执行清单

### P0-1 深色主题与按钮体系

- [ ] 将普通页面系统栏图标调整为浅色，检查 Android 27、29 及默认 Theme。
- [ ] 为手机和电视按钮建立统一 Style，明确背景 Tint、文字色、圆角、按压和焦点状态。
- [ ] 清除普通 `Button` 对自定义 drawable 的 Material3 绿色 Tint。
- [ ] 检查首页主按钮、筛选按钮、下载按钮、NAS 操作按钮和详情页按钮。
- [ ] 不改变播放器全屏隐藏系统栏策略。

涉及文件：

- `app/src/main/res/values/themes.xml`
- `app/src/main/res/values-v27/themes.xml`
- `app/src/main/res/values-v29/themes.xml`
- `app/src/main/res/values/colors.xml`
- `feature/feature_res/src/main/res/values/styles.xml`
- `feature/feature_res/src/main/res/values/colors.xml`
- `feature/feature_res/src/main/res/drawable/bg_*.xml`

验收：

- 状态栏、手势条或三键导航在深色背景上清晰可见。
- “继续播放”显示设计规定的暖金色，不再显示主题绿色。
- 筛选、次要操作、危险操作之间有明确视觉层级。

### P0-2 手机主导航重构

- [ ] 默认 `layout/activity_home_host.xml` 改为手机导航结构。
- [ ] Android TV 继续使用 `layout-television/activity_home_host.xml`。
- [ ] 手机使用四个图标加短标签的底部导航：主页、媒体、下载、NAS。
- [ ] BottomNav 成为 bottom inset 的唯一 owner，内容区增加对应底部安全空间。
- [ ] 删除手机顶部超宽胶囊 Tab，不在子页面重复显示与导航相同的标题。
- [ ] 手机允许 ViewPager2 滑动；电视继续禁用滑动并保留遥控器左右切换。
- [ ] `TvFocusHelper.applyFocusScale` 仅在电视模式启用。

涉及文件：

- `feature/feature_app/src/main/res/layout/activity_home_host.xml`
- `feature/feature_app/src/main/res/layout-television/activity_home_host.xml`
- `feature/feature_app/src/main/java/com/wkq/bao/feature/app/HomeActivity.kt`
- `feature/feature_app/src/main/java/com/wkq/bao/feature/app/utils/TvFocusHelper.kt`
- `feature/feature_res/src/main/res/drawable/` 下的导航图标与状态资源

验收：

- 360dp 宽度下四项导航不截断、不换行。
- 横屏手机导航不再占用页面顶部约 20% 高度。
- 手机点击和滑动均可切页；电视遥控器行为不回归。

### P0-3 响应式资源与 Insets

- [ ] 新增 `layout-w600dp/`，覆盖普通手机横屏和宽窗口。
- [ ] 保留 `layout-sw600dp/` 作为平板/折叠屏整机基线，但清理其中电视专用尺寸和硬编码预览文案。
- [ ] 使用 `WindowInsetsCompat` 明确 Header、内容区和 BottomNav 的 Insets owner。
- [ ] 检查 Android 15/16 强制 Edge-to-edge、显示缺口、三键导航和手势导航。
- [ ] 多窗口缩放时不得依赖启动时固定宽高。

涉及文件：

- `feature/feature_app/src/main/res/layout-w600dp/`
- `feature/feature_app/src/main/res/layout-sw600dp/`
- `feature/feature_app/src/main/res/layout-television/`
- `feature/feature_app/src/main/java/com/wkq/bao/feature/app/HomeActivity.kt`
- `app/src/main/AndroidManifest.xml`

验收：

- 780dp x 360dp 窗口使用宽窗口布局，而不是拉伸竖屏布局。
- 导航栏、状态栏、刘海和圆角区域不遮挡内容。
- 分屏拖动窗口宽度时没有重叠、裁切和固定空白区。

### P1-1 首页

- [ ] 无媒体时使用紧凑空态，提供“添加 NAS”和“扫描本地存储”明确入口。
- [ ] 有媒体时 Hero 保留真实背景图，并在首屏露出后续内容。
- [ ] 横屏限制 Hero 最大宽度/高度，避免空内容占满整屏。
- [ ] 手机卡片使用合理触控尺寸，电视卡片保留遥控焦点尺寸。
- [ ] 继续观看和推荐区域根据数据决定是否显示，不保留空轨道高度。

涉及文件：

- `feature/feature_app/src/main/res/layout/activity_home.xml`
- `feature/feature_app/src/main/res/layout-w600dp/activity_home.xml`
- `feature/feature_app/src/main/java/com/wkq/bao/feature/app/HomeFragment.kt`
- `feature/feature_app/src/main/res/layout/item_continue_watching.xml`
- `feature/feature_app/src/main/res/layout/item_poster_card.xml`

### P1-2 媒体库

- [ ] 移除主导航与页面标题的重复展示。
- [ ] 筛选改为紧凑可横向滚动的选择控件，避免四个大按钮。
- [ ] 根据窗口可用宽度动态计算 GridLayoutManager 列数。
- [ ] 空媒体库显示居中空态和添加来源操作，不显示整屏黑场。
- [ ] 海报卡保持 2:3 比例，标题最多两行或明确单行省略策略。

涉及文件：

- `feature/feature_app/src/main/res/layout/activity_media_library.xml`
- `feature/feature_app/src/main/res/layout-w600dp/activity_media_library.xml`
- `feature/feature_app/src/main/java/com/wkq/bao/feature/app/MediaLibraryFragment.kt`
- `feature/feature_app/src/main/res/layout/item_poster_card.xml`

### P1-3 下载管理

- [ ] 页面改为整体可滚动或稳定的弹性分区，取消下载队列固定 164dp 高度。
- [ ] 存储状态卡在手机上压缩为摘要行，选择位置保持主要操作层级。
- [ ] 空下载队列与下载中列表共用同一容器，不同时占位。
- [ ] 宽窗口可采用“下载任务 + 已下载媒体”双栏布局。
- [ ] 保证失败原因两行、长存储名称和扫描进度不遮挡按钮。

涉及文件：

- `feature/feature_app/src/main/res/layout/activity_downloads.xml`
- `feature/feature_app/src/main/res/layout-w600dp/activity_downloads.xml`
- `feature/feature_app/src/main/res/layout/item_download_task.xml`
- `feature/feature_app/src/main/java/com/wkq/bao/feature/app/DownloadsFragment.kt`

### P1-4 NAS 设置

- [ ] 手机使用紧凑 NAS 来源列表，不使用全宽 184dp/220dp 大卡片。
- [ ] 当前来源、连接状态、扫描状态和操作层级分离。
- [ ] “添加 NAS”改为明确命令入口，不使用巨大的空卡片。
- [ ] NAS 编辑器改为可滚动 Dialog/BottomSheet，处理 IME 和底部系统栏。
- [ ] 字段增加输入类型、错误提示和保存前校验；键盘 Next/Done 顺序正确。

涉及文件：

- `feature/feature_app/src/main/res/layout/activity_nas_settings.xml`
- `feature/feature_app/src/main/res/layout-w600dp/activity_nas_settings.xml`
- `feature/feature_app/src/main/java/com/wkq/bao/feature/app/NasSettingsFragment.kt`
- `feature/feature_res/src/main/res/values*/strings.xml`

### P1-5 电影/电视剧详情

- [ ] 手机竖屏取消依赖负 Margin 的海报叠层。
- [ ] 三个操作按钮允许换行或拆成主按钮加图标操作。
- [ ] 电影隐藏季和分集区域；电视剧显示季选择和分集列表。
- [ ] 手机分集优先使用纵向列表或自适应网格，电视保留横向轨道。
- [ ] 长标题、四行简介和 200% 字体下不覆盖海报与操作按钮。

涉及文件：

- `feature/feature_app/src/main/res/layout/activity_detail.xml`
- `feature/feature_app/src/main/res/layout-w600dp/activity_detail.xml`
- `feature/feature_app/src/main/res/layout/item_episode_card.xml`
- `feature/feature_app/src/main/java/com/wkq/bao/feature/app/DetailActivity.kt`

## 5. 验收矩阵

### 窗口与设备

- [ ] 手机竖屏：360dp x 800dp。
- [ ] 小屏手机竖屏：320dp x 640dp。
- [ ] 手机横屏：780dp x 360dp。
- [ ] 平板/折叠屏：600dp、840dp 宽度。
- [ ] Android 15/16 多窗口，连续拖动窗口宽度。
- [ ] Android TV 1080p，遥控器方向键和 OK/Back。

### 字体与语言

- [ ] `fontScale=1.0`。
- [ ] `fontScale=1.3`。
- [ ] `fontScale=2.0`。
- [ ] 简体中文。
- [ ] 英文长文案。

### 交互

- [ ] 主导航点击、滑动和返回逻辑。
- [ ] 列表最后一项不会被 BottomNav 或系统导航栏遮挡。
- [ ] NAS 编辑器打开键盘后所有字段和保存按钮可达。
- [ ] 下载进度刷新不引发布局跳动。
- [ ] 旋转和多窗口后仍保留当前主页面、筛选项和滚动位置。
- [ ] 电视焦点首次进入、跨区域移动和返回焦点恢复正常。

## 6. 自动验证命令

```powershell
python .agents/skills/android-ui-workflow/scripts/validate_ui_output.py `
  --module-src app/src/main/java --module-src feature/feature_app/src/main/java `
  --module-res app/src/main/res --module-res feature/feature_app/src/main/res `
  --module-res feature/feature_res/src/main/res

python .agents/skills/android-i18n-workflow/scripts/validate_i18n_resources.py `
  --res-dir app/src/main/res --res-dir feature/feature_res/src/main/res

python .agents/skills/android-project-architecture-workflow/scripts/validate_architecture.py --project-root .
python .agents/skills/android-build-workflow/scripts/validate_build_output.py --project-root .

.\gradlew.bat :feature:feature_app:testDebugUnitTest :app:assembleDebug :app:lintDebug
git diff --check
```

## 7. 完成定义

本轮只有同时满足以下条件才算完成：

- P0 全部完成，P1 至少完成首页、媒体库、下载管理和 NAS 设置。
- 手机横屏不再出现顶部超宽文字胶囊导航。
- 系统栏图标清晰，按钮不再被绿色主题 Tint 覆盖。
- 页面没有重复主标题、固定空白区、文字遮挡和不可滚动输入表单。
- 默认、中文和英文资源门禁通过。
- Debug 构建、单测和 Lint 通过。
- 至少保存首页、媒体库、下载管理、NAS、详情页的手机竖屏与横屏截图。
- Android TV 焦点行为完成最小回归。

## 8. 风险与回退策略

- 导航结构修改影响四个 Fragment 的生命周期与状态保留，应先完成宿主导航，再逐页改布局。
- Theme 修改可能影响 AlertDialog、播放器 OSD 和系统栏，应按页面逐一截图确认。
- 不删除现有 `layout-television`；手机改造出现问题时可以按资源目录独立回退。
- 新增 `layout-w600dp` 时保持 XML id 完全一致，避免 ViewBinding 生成接口不一致。
- 不把 `layout-sw600dp` 直接复制为 `layout-w600dp`，应按宽窗口实际内容重新组织。
- 真机无法通过 ADB 切换方向时，使用 Android Studio Emulator 补齐竖屏和字体缩放测试。

## 9. 预计评分

| 阶段 | 手机端 UI 预计评分 | 说明 |
|---|---:|---|
| 当前 | 55 | 功能可用，但仍是电视式横屏结构 |
| 完成 P0 | 68-72 | 导航、主题、系统栏和宽窗口基础正确 |
| 完成 P0 + P1 | 75-80 | 主要页面形成手机信息架构 |
| 完成全设备截图与交互回归 | 85 | 达到可交付 Beta 水平 |
| 补齐真实内容、视觉精修和自动截图回归 | 90 | 商业化 UI 基线 |
