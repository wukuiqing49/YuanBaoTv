# Apple TV 风格配色落地（2026-10-25）

## 目标与范围
采用中性深色、内容优先、浅色主按钮的方向；色值为本项目适配方案，不是 Apple 官方色值。保留 Material 图标、原有功能、焦点放大和圆角裁剪修复；没有引入模糊或渲染依赖。

## 修改文件与原因
- `feature/feature_res/src/main/res/values/colors.xml`：页面 #101010、次级表面 #1C1C1E、弹窗 #242426、普通按钮 #333335、主按钮 #F2F2F7 配 #171719。增加正文 #E5E5EA，辅助说明 #B0B0B6，元数据 #929298。
- 同模块 drawable：首页/详情/标题/卡片渐变统一到中性黑；首页无图占位与启动光晕去青色；标签去金色边；玻璃面板、弹窗、普通卡片去常驻描边，保留焦点描边。
- 新增 `bg_tv_light_button.xml`：复用已有导航状态，普通透明，选中与焦点独立表达，不改变焦点几何和边距。
- `feature/feature_app/src/main/res/drawable`：NAS 底栏、底部导航去蓝灰；NAS 文件行普通态透明，返回/刷新/编辑等轻操作复用新背景。
- `feature/feature_app/src/main/res/layout*`：同步各限定版本的卡片片名、年份/来源文字层级；详情收藏与编辑改轻操作；下载存储卡去大块底板，选择位置改次要按钮；媒体库排序/收藏筛选/来源/清除改轻操作。
- `MediaLibraryFragment.kt`、`DetailActivity.kt`：未选中的分类与分季不再被代码恢复为实心按钮。
- `app/src/main/res/values/colors.xml` 与 `values-night/colors.xml`：壳层主要操作颜色同步。
- 未改 strings：默认、英语、简体中文文案与 fallback 沿用现有资源；没有新增营销素材或外部图片。

## 验证
- `:app:assembleDebug` 通过。
- `:feature:feature_app:testDebugUnitTest` 通过，30 项。
- UI 门禁通过，保留既有多个 Insets owner 提示；i18n 门禁通过。
- `git -c core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol diff --check` 通过。普通 diff --check 对既有 CRLF 文件报告行尾空白；未批量改动无关文件。
- Google TV API36 emulator-5556 覆盖安装、首页、媒体库、电影详情、下载空态、NAS 加载页、新增空白 NAS 弹窗截图检查；首页主播放按钮遥控器焦点边框完整。
- 截图位于 `app/build/outputs/ui/apple-palette-*.png`。`focus.png` 是首页内容加载完成后的焦点截图。
- 实体色计算对比度：主文字/背景 17.48，次文字/背景 8.82，元数据/背景 6.15，主文字/焦点底色 8.38，主按钮深字/浅底 16.04。透明叠加图片区域未进行逐像素全覆盖对比度验收。
- crash buffer 检查无输出。

## 未验证与风险
- 小米 192.168.5.17:5555 仍 offline，未安装或实机验收本轮 APK。
- 模拟器真实远程封面多数离线，能检查占位和控件配色，不能据此声称真实海报/背景效果全部通过；NAS 内容加载与文件操作未完成回归。
- 未重新覆盖手机、小屏、1.3 字体、所有选集/缓存弹窗、所有禁用与组合焦点状态；尺寸与交互沿用原实现。
- 元数据与海报质量问题独立于本轮配色，旧模拟器索引仍可能出现未清理片名或重复条目。
