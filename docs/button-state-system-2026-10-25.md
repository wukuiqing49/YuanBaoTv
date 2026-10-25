# 按钮状态统一验收（2026-10-25）

## 修改结果
- 主要操作：浅色背景与深色文字/图标，覆盖首页播放、详情播放、下载确认、元数据保存、NAS 保存/下载；36 个布局按钮已检查背景与文字配对。保留原资源名，用 inset 引用共享状态背景。
- 次要操作：统一深灰背景、浅白文字/图标；默认、焦点、选中、按下、禁用状态复用共享选择器。
- 导航与筛选：选中使用 #383A41 底色及底部短线，焦点使用 #45474F 与白色 2.5dp 描边。选中+焦点同时保留标记。文字颜色用 tv_navigation_text，避免 Kotlin 固定颜色覆盖焦点和禁用态。
- 按下确认：focused+pressed 分支保留白色边框；主要按钮外框与内部浅色面之间留出间隔，避免默认白底与聚焦白底难以区分。
- 禁用：深灰底、灰字/图标，无聚焦描边；次要按钮与 ImageButton、复选框使用状态颜色。
- NAS：文件多选去除蓝色背景，使用共享选中状态；协议切换保留 MaterialButtonToggleGroup 的受管背景和 checked 逻辑，配置背景/描边/文字状态颜色；新建弹窗测试保存/取消/测试按钮均使用自己的背景，清除 Material 默认 tint。

## 主要文件
共享资源：feature/feature_res/src/main/res/values/colors.xml、color/tv_button_text.xml、tv_control_text.xml、home_primary_text.xml、tv_navigation_text.xml、tv_toggle_surface.xml、tv_toggle_stroke.xml；drawable/bg_tv_button_focus.xml、bg_nav_link_active.xml、bg_home_primary_button.xml 与旧按钮入口资源。
页面逻辑：HomeActivity.kt、HomeFragment.kt、MediaLibraryFragment.kt、DetailActivity.kt、NasEditorPopup.kt、NasSettingsFragment.kt、adapter/NasFileAdapter.kt。
布局：feature_app 下对应布局限定符中的主要按钮文字/图标、ImageButton tint、复选框 tint；NAS 协议按钮状态与文件行背景。

## 验证
- Debug APK 构建及 feature_app 30 项单元测试通过。
- 静态组合检查：9 个按钮入口覆盖 focused、selected+focused、pressed+focused、disabled；焦点与按下的组合均保留描边，禁用无描边。检查 primary text pairing 36 个布局按钮。
- 计算文字对比度：普通 13.04、焦点 8.43、选中 10.34、主要默认 15.86、主要按下 11.34。计算只针对实体背景/文字，不代表真实电视显示或整页无障碍验收；禁用态未套用可操作文本阈值。
- Google TV API 36 模拟器（1920×1080）：检查首页主要按钮焦点、顶部导航持续选中短线、新建 NAS 弹窗取消/测试/保存层级和协议切换。未保存配置、未输出凭据。崩溃缓冲无新增异常。
- UI 门禁通过（既有 Insets owner 警告保留）、i18n 通过、git diff --check 通过。
- 截图：app/build/outputs/ui/button-home.png、button-nas-popup.png。组合审计输出：button-state-audit.txt。

## 规则与限制
按 implement-page Workflow 及 Android UI、架构、国际化 Skill 执行；本轮不引入依赖、不更改公共 API。无新增用户文案，默认、简中、英文及 fallback 保留；截图来自现有索引和空白新增表单，无新宣传素材，素材版权未审核。
小米电视仍离线，未进行本轮实机覆盖安装；其他手机、所有弹窗组合、屏幕阅读器及 release 未全面验证。既有全局 cleartext 发布门禁问题未处理。本轮不重扫 NAS，不修改账号、观看记录或媒体文件。
