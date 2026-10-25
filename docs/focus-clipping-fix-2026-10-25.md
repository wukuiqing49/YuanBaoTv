# 焦点边框裁剪修复（2026-10-25）

## 根因
前轮只验收状态色，没有完整验收 scale=1.04 后的绘制范围。导航、首页按钮组、普通 LinearLayout 默认裁剪子视图；分季 HorizontalScrollView 高度只有 44dp，缺少缩放留白；部分列表首末项紧贴视口边界，放大后的白边被切成直线。

## 修改文件与方案
- feature_app/.../utils/TvFocusHelper.kt：焦点进入时，仅释放当前控件到最近视口之间普通布局容器的 clipChildren/clipToPadding；不穿透 RecyclerView、ScrollView、HorizontalScrollView、NestedScrollView 和 ViewPager2。对滚动/列表视口设置独立 clipBounds，防止父布局释放裁剪后，列表内容盖住页头或弹窗操作栏。
- 三个 layout*television/activity_detail.xml：分季视口 72dp，四周 12dp 留白；选集列表增加左右内部留白，保护首项和末项。
- 四个 feature_res values*television/dimens.xml：电视 ui_focus_padding 由 8dp 调整为 12dp。
- 三个 layout*television/activity_media_library.xml：网格两侧增加内部焦点留白。
- 三个 layout*television/activity_downloads.xml、layout/activity_nas_browser.xml：补齐列表边缘留白，NAS 文件列表增加顶部安全空间。
- layout/popup_cache_selection.xml：缓存选集列表增加四周焦点留白。

保留原缩放幅度与边框厚度，不通过隐藏描边或吞掉焦点消除表象；分页和真实滚动边界继续保留。

## 验证
- Debug APK 构建及 feature_app 30 项单元测试通过。UI 门禁通过（保留既有 Insets owner 警告），i18n 与 git diff --check 通过。
- Google TV API 36 模拟器 1920×1080 / 320dpi：截图确认顶部导航、主要按钮、分季按钮、第一张选集卡及缓存弹窗可见焦点项的四角描边完整。
- 实际截图过程中发现首张选集左边仍被切边，补充 RecyclerView 内部横向留白后重新截图确认；也发现弹窗列表越过操作栏，补充独立视口裁剪后再次截图确认。
- 最终截图：app/build/outputs/ui/focus-nav-fixed.png、focus-season-fixed.png、focus-episode-fixed.png、focus-popup-fixed.png；focus-primary-fixed.png 保留阶段性主要按钮检查图。崩溃缓冲没有新增异常。

## 边界与采用规则
按 implement-page Workflow 与 Android UI / 架构 / 国际化 Skill 执行。没有新增依赖、改变媒体数据或下载文件。用户文案和默认/简中/英文 fallback 不变，未生成新宣传素材。
小米电视仍离线，本轮未做实机安装。下载与 NAS 文件列表已补留白但未逐项取得真实内容焦点截图；所有列表末项、所有弹窗、大字体及其他分辨率还需补充覆盖，不声称已全量视觉验收。既有发布网络安全门禁问题不在本轮变更范围。
