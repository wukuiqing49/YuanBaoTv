# Figma 屏幕规范化报告 (圆宝TV 首页)

## 1. 画板与屏幕基准
* **Figma Frame 名称**：`圆宝TV - 首页 (优化版)`
* **Node ID**：`1:2`
* **原始尺寸**：1280px × 1730.25px
* **设备形态**：Android TV 大屏（16:9 标准比例）
* **Android 基准宽度**：1280dp
* **换算比例**：1.0 (1px = 1dp)
* **高度策略**：Figma Frame 包含纵向扩展内容，Android 根布局使用 `match_parent` 弹性填充与 `NestedScrollView`，固定视觉块（TopNavBar、Hero Banner）保持精确高度，列表卡片由 RecyclerView 承载。

## 2. 视觉区域预算
1. **顶部导航栏 (Top Navigation)**：高度 64dp，左右外边距 48dp。
2. **沉浸式 Hero 焦点 Banner**：高度 220dp，左右外边距 48dp，圆角 14dp。
3. **继续观看横版行 (16:9)**：卡片尺寸 280dp × 158dp，内嵌 4dp 播放进度条。
4. **热门动画海报行 (2:3)**：卡片尺寸 160dp × 240dp，圆角 12dp。

## 3. Insets 与安全区
* **系统安全区**：TV 页面由 `HomeActivity` 统一管理安全边距（paddingStart/End = 48dp），列表 `clipToPadding="false"`，保证获焦卡片放大 1.08x 时不被边缘裁剪。
