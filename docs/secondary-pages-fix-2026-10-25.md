# 二级页面与全部媒体崩溃修复（2026-10-25）

## 结论
已修复可稳定复现的全部媒体焦点崩溃，并调整电视详情、媒体库、下载和 NAS 页面配色/间距。用户要求先修改再进行小米电视验收；本轮验证使用已有 Google TV API 36 模拟器（1920×1080，320dpi）。小米电视无线地址离线，未覆盖安装或声称实机通过。

## 根因与修改
- `feature/feature_app/.../utils/TvFocusHelper.kt`：原先从整个窗口查找 home_scroll，ViewPager 保留的首页是媒体库的兄弟页面；媒体库卡片获焦后调用 offsetDescendantRectToMyCoords 抛出 IllegalArgumentException: parameter must be a descendant of this view。改为只沿当前卡片祖先链寻找首页/详情滚动容器，并检查连接状态。
- `MediaLibraryFragment.kt`：电视网格最小卡宽 200dp，常见 960dp 视口为四列；保留手机和其他设备原有计算。
- `DetailActivity.kt`：电视展示名使用已有 MediaLabels 清理，编辑、索引与搜索保留完整原名。
- 三个 `layout*television/activity_detail.xml`：页边距统一 48dp，海报 160×240dp，信息间距 24dp，标题采用页面标题尺寸，按钮 44dp、按可用宽度换行；主要播放按钮白底深字；增加整体滚动，选集不因长标题/大字体被挤出可访问范围。
- 三个 `layout*television/activity_downloads.xml`：标题和导入按钮同一行，存储信息独立放下一行，修复英文标题与按钮重叠；状态切换按钮 44dp、单行；存储选择按钮从 32dp 增至 44dp。
- 三个 `layout*television/activity_media_library.xml`：过滤按钮间距统一 8dp。
- 四个 `values*television/dimens_nas_browser.xml`：补齐电视横屏分支，避免 land 手机尺寸覆盖 television；NAS 安全页边距 48dp，标题/控件/文件行使用电视尺寸。
- `NasBrowserActivity.kt`：显式清除自定义背景控件的 Material 背景 tint，修复浅底浅字。
- `layout/activity_nas_browser.xml`：加载指示器使用中性辅助色。
- `feature_res/.../values/colors.xml` 与 `bg_detail_scrim.xml`：辅助强调色、徽章、弹窗操作、禁用态和详情遮罩统一中性黑灰白；错误/语义状态色保留。

## 验证
1. 在旧 APK 上进入 All Media，Tab/方向下键使网格卡片获焦，实际复现异常；日志位置 TvFocusHelper.kt:42。
2. 修复后相同路径成功进入网格；UI dump 确认 card_container focused=true；进程 PID 保持 4059，崩溃缓冲无新增异常。也检查详情选集焦点路径。
3. 模拟器检查媒体库四列布局、电影详情、剧集详情（含英文长按钮）、下载页空态和 NAS 控件配色。模拟器现有索引/封面部分离线，不把缺图或旧索引当成真实 NAS 回归通过。
4. 详情页检查 1.3 倍字体：长按钮省略、换行，选集可访问；测试结束恢复 1.0。未全面覆盖所有长英文标题/简介组合。
5. `:app:assembleDebug` 和 `:feature:feature_app:testDebugUnitTest` 通过（30 项）。UI 验证通过，保留既有多个 Insets owner 警告；i18n 验证与 git diff --check 通过。
6. 截图：app/build/outputs/ui/library-fixed.png、detail-fixed.png、detail-series-fixed.png、detail-font130.png、detail-font130-focus.png、downloads-fixed.png、nas-fixed.png。

## 规则、语言与未验证项
采用 fix-bug 和 implement-page Workflow，Android UI、架构、国际化及构建 Skill；遵循不删除功能、不更改模块/公开接口、不引入依赖的规则。本轮无新增用户文案，默认、简中、英文及 fallback 保留；素材仍来自既有片库，未制作新宣传素材，版权授权未审核。

小米电视、其他分辨率和手机布局未回归；未进行 NAS 重扫、真实下载和播放网络测试，也未验证全部弹窗组合、release。既有 network_security_config 全局 cleartext 静态门禁问题未在本轮处理，不应视作正式发布验收通过。
