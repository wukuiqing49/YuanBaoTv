# Figma 真实图层分析报告 (Figma Layer Report)

- **Figma Frame Node ID**：`1:2`
- **Frame 名称**：圆宝TV - 首页 (优化版)
- **本地高清位图截图**：`.ai-work/figma/output/figma_home_screenshot.png`
- **逻辑尺寸**：1280px × 1730px
- **Android 基准尺寸**：1280dp 逻辑宽度（TV 16:9 + 手机横屏）

---

## 一、真实图层树分解 (Layer Breakdown)

### 1. 顶部导航栏 `1:3 Top Navigation`
- **尺寸 & 坐标**：x: 0, y: 0, width: 1280, height: 204 (paddingTop: 48px, paddingHorizontal: 80px)
- **子图层**：
  - `1:4 Brand`：
    - `1:5 圆宝TV Logo`：56px × 56px, 圆形图标（`rounded-full`）, 阴影 `0 10px 15px -3px rgba(0,0,0,0.1)`
    - `1:7 Text`：36px Bold, 颜色 `#E5E2E1`, 文本 "圆宝TV"
  - `1:8 Navigation Links`：
    - 容器属性：胶囊药丸 `rounded-full`, 毛玻璃 `backdrop-blur 12px`, 背景 `rgba(32,31,31,0.4)`, 边框 `1px rgba(79,69,53,0.3)`
    - `1:9 Link (首页)`：选中态, 背景 `rgba(53,53,52,0.8)`, 文字 `#E5E2E1`, 字号 28px(14sp)
    - `1:11 Link (媒体库)`：未选中态, 文字 `#D3C5AF`, 字号 28px(14sp)
    - `1:13 Link (已下载)`：未选中态, 文字 `#D3C5AF`, 字号 28px(14sp)
    - `1:15 Link (NAS)`：未选中态, 文字 `#D3C5AF`, 字号 28px(14sp)
    - `1:17 Link (设置)`：未选中态, 文字 `#D3C5AF`, 字号 28px(14sp)
  - `1:19 Trailing Actions`：
    - `1:20 NAS 在线胶囊`：胶囊容器, 背景 `rgba(32,31,31,0.4)`, 边框 `rgba(79,69,53,0.3)`, 图标 `1:22` + 文本 `1:24` (字号 20px/10sp SemiBold, 颜色 `#84F5E8` 发光青绿)
    - `1:25 时钟胶囊`：圆角 12px 容器, 文本 `1:26 "20:36"` (字号 24px/12sp Bold, 颜色 `#E5E2E1`, 字符间距 2.4px 等宽)

---

### 2. Hero 焦点内容区 `1:28 Section - Hero Content`
- **尺寸 & 坐标**：x: 80, y: 144, width: 896, height: 354
- **子图层**：
  - `1:30 Heading 1`：
    - `1:31 Text`：72px(36sp) Bold, 颜色 `#E5E2E1`, 文本 "汪汪队立大功", 文字阴影 `0 4px 4px rgba(0,0,0,0.5)`
  - `1:32 Container (元数据标签栏)`：
    - `1:33 Overlay`：圆角 8px 小胶囊, 背景 `rgba(42,42,42,0.8)`, 边框 `rgba(79,69,53,0.5)`, 文本 `1:34 "第 3 季 · 动画"` (18px/12sp Medium, 暖金色 `#FFDEA6`)
    - `1:35 Shadow`：文本 `1:36 "上次看到：第 5 集"` (28px/14sp Medium, 半透明白 `rgba(255,255,255,0.9)`)
  - `1:37 Buttons (主操作按键组)`：
    - `1:38 Button (继续播放)`：
      - 容器：圆角 16px, 实心暖金背景 `#FFE1B0`, 边框 2px `#101010`, 投影 `0 8px 15px rgba(255,222,166,0.3)`, 获焦 1.05x 放大
      - 图标 `1:40`：深色播放三角形
      - 文本 `1:42`：字号 24px(13sp) Medium, 深褐色文本 `#412D00`
    - `1:43 Button (详情)`：
      - 容器：圆角 16px, 毛玻璃背景 `rgba(42,42,42,0.6)`, 边框 2px `rgba(156,143,124,0.5)`
      - 图标 `1:45`：信息圆形图标
      - 文本 `1:47`：字号 24px(13sp) Medium, 白色文本 `#E5E2E1`

---

### 3. 第一行：继续观看 `1:48 Section - Row 1: Continue Watching`
- **尺寸 & 坐标**：x: 80, y: 498, width: 1120, height: 416
- **子图层**：
  - `1:50 Heading 2`：36px(18sp) Bold, 文本 "继续观看", 颜色 `#E5E2E1`
  - `1:52 Horizontal Container (16:9 卡片水平流)`：
    - `1:53 Card 1` (420px × 236px, 16:9 比例, 圆角 16px, 阴影 `0 25px 50px -12px rgba(0,0,0,0.25)`):
      - `1:54 Image`：剧照底图
      - `1:55 Overlay`：30% 黑色蒙层 `rgba(0,0,0,0.3)`
      - `1:57 Center Play Button`：圆形 80px × 80px 毛玻璃悬浮播放键, 背景 `rgba(0,0,0,0.6)`, 边框 2px `rgba(255,255,255,0.8)`
      - `1:61 Progress Bar & Title`：底部渐变暗幕 (`from rgba(0,0,0,0.95) via rgba(0,0,0,0.5) to transparent`)
      - `1:63 Text`：标题 "汪汪队立大功 E05" (28px/14sp, 白色)
      - `1:64 / 1:65 荧光发光进度条`：高度 8px, 进度条底色 `rgba(53,53,52,0.8)`, 前景发光暖金色 `#FFDEA6`, 发光阴影 `0 0 10px rgba(255,222,166,0.8)`
    - `1:66 Card 2`：同上结构
    - `1:79 Card 3`：同上结构

---

### 4. 第二行：热门动画海报流 `1:92 Section - Row 2: Cartoons`
- **尺寸 & 坐标**：x: 80, y: 914, width: 1120, height: 516
- **子图层**：
  - `1:94 Heading 2`：36px(18sp) Bold, 文本 "动画片", 颜色 `#E5E2E1`
  - `1:96 Horizontal Container (2:3 海报流)`：
    - `1:97 Poster 1` (224px × 336px, 2:3 黄金比例, 圆角 16px):
      - `1:98 Image`：纵向海报封面
      - `1:99 Background`：底部渐变暗角
      - `1:101 Text`：剧集标题 (24px/13sp, 白色)
    - `1:102 Poster 2`、`1:107 Poster 3`、`1:112 Poster 4`、`1:117 Poster 5`：结构同上

---

### 5. 全屏电影级大背景 `1:122 Cinematic Hero Background`
- **尺寸**：1280px × 800px
- **图层属性**：`1:123 Image`（全景剧照）+ 双向暗角渐变（左至右从深黑渐变到透明，下至上从深黑 `#131313` 渐变到透明）

---

## 二、设计 Token 规范总结

| 分类 | 属性名 | Figma 真实值 | Android 映射 |
| :--- | :--- | :--- | :--- |
| **背景色** | 全局暗场 | `rgb(19, 19, 19)` | `#131313` |
| **主文本** | 高亮主标题 | `#E5E2E1` | `#E5E2E1` |
| **次文本** | 导航/副标题 | `#D3C5AF` | `#D3C5AF` |
| **三级文本** | 简介/说明 | `#94A3B8` | `#94A3B8` |
| **NAS 在线** | 状态文本 | `#84F5E8` | `#84F5E8` (青绿发光) |
| **主按键** | 继续播放背景 | `#FFE1B0` | `#FFE1B0` (暖金实心) |
| **主按键字** | 继续播放文本 | `#412D00` | `#412D00` (深褐粗体) |
| **次按键** | 详情按键背景 | `rgba(42,42,42,0.6)` | 半透明毛玻璃 |
| **发光进度条**| 进度高亮 | `#FFDEA6` | `#FFDEA6` (金黄发光) |
