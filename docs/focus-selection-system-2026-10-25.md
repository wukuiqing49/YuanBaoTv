# 遥控器焦点与选中状态统一

## 本轮修改
- `feature/feature_res/src/main/res/values/colors.xml`：默认焦点统一浅白 #F5F5F7；保留旧资源名兼容引用，旧青/金焦点入口也指向同一颜色。普通按钮 #333335，选中 #444446，获焦 #56565A，选中且获焦 #626266，获焦且按下 #48484C。
- `bg_tv_button_focus.xml`、`bg_nav_link_active.xml`、`bg_menu_item.xml`、`feature_app/.../bg_nas_browser_file_row.xml`：显式覆盖 selected/checked/activated 与 focused/pressed 组合，禁用优先；焦点白框 3dp，选中短线持续存在，普通轻操作保持透明。
- `bg_home_primary_button.xml`：浅色按钮内容内缩 5dp，外圈 3dp 与浅色内面之间留出深色间隔；内容圆角同步 19dp，外圆角 24dp，保持放大时的边界留白。
- `bg_card_focus_foreground.xml`：封面焦点白框 3dp，保留原圆角和视口裁剪修复。
- `tv_button_text.xml`、`tv_control_text.xml`、`tv_navigation_text.xml`：文字和 tint 使用同一套禁用、获焦、选中、勾选状态。浅色主按钮继续使用深色文字/图标。
- `tv_toggle_surface.xml`：Material 协议切换补齐组合状态，未选中使用较暗表面，保留受管背景与勾选逻辑。
- 新增 `tv_input_stroke.xml`：输入框获焦白边，普通边框弱化；NAS 表单及元数据编辑输入框接入。
- `bg_player_drawer_surface.xml`：播放器抽屉去蓝灰，统一中性浮层底色与淡分隔线。
- `ic_tv_more.xml`：资源尺寸从 28dp 统一为 24dp。
- `CacheSelectionPopup.kt`、`item_cache_choice.xml`、新增 `tv_caption_text.xml`：不可选缓存条目明确退出焦点导航，标题/说明/勾选同步禁用状态，避免仅父容器禁用而文字仍然高亮。

## 验证
- `:app:assembleDebug` 通过。
- `:feature:feature_app:testDebugUnitTest` 通过（30 项）。
- UI 门禁通过，存在既有多个 Insets owner 提示；i18n 门禁通过。
- 静态组合状态审计通过：selected/checked/activated 获焦及按下时仍有白框与选中短线；仅选中时没有焦点框；禁用覆盖焦点。记录：`app/build/outputs/ui/focus-state-audit.txt`。
- 本轮没有新增/修改文案，默认语言、英语、简体中文及 fallback 沿用现有资源；没有新增外部素材。

## Workflow / Skill
按 implement-page Workflow，复用 Android UI、项目架构和 i18n Skill。未新增依赖或修改主题/Manifest；保留工作区既有播控与其他页面改动。

## 未验证项
自动审批拦截了模拟器覆盖安装与启动命令，未提供具体理由，因此本轮模拟器/小米电视视觉验收均不标记为通过。APK 已构建，但本轮未确认部署。
尚需实机验证远距离焦点可见性、明亮封面上的白框、白色主按钮的间隔、首末项放大边界、输入框与系统键盘、大字体及所有弹窗的组合状态。
白色焦点为默认方案，本轮没有新增黄色高对比模式设置入口。
