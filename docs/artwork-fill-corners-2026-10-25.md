# 封面填充与圆角修复（2026-10-25）

## 根因与改动
- MediaArtwork.loadPoster 原先先用 FIT_CENTER 请求图片，加载完成后按比例阈值切到 CENTER_CROP。既造成留白/裁切规则不稳定，也可能按适合完整容纳的尺寸解码后再裁切放大。现在在发请求前固定 CENTER_CROP + Coil Scale.FILL，始终等比例铺满，不拉伸；不同比例素材允许居中裁切边缘。
- loadPoster 没有明确提供 outlineProvider，仅 invalidateOutline。现在所有封面轮廓在加载前设置，不依赖加载中、成功、错误或 CrossfadeDrawable 是否能生成圆角轮廓。
- 新增共享 ui_card_corner_radius=16dp、ui_artwork_corner_radius=12dp。卡片外轮廓、焦点 foreground、ripple mask 使用外圆角；有 4dp 内边距的卡片图片使用 12dp 内圆角，满版选集与详情海报保持 16dp。
- MediaArtwork.load 为卡片图片与已缓存海报设置等比例铺满及显式圆角，避免缓存海报继续保持方角。
- item_poster_card 各尺寸布局的 scaleType 同步为 centerCrop；item_continue_watching 的进度条向内缩进 12dp、上移 4dp，避免方形端点覆盖图片底部圆角。
- HomeFragment 的大背景固定 centerCrop，只使用 backdropUri；没有横向背景时使用既有渐变兜底，不再把竖海报强行当电影背景。

主要文件：feature_app/.../utils/MediaArtwork.kt、TvFocusHelper.kt、HomeFragment.kt；layout*/item_poster_card.xml、item_continue_watching.xml；feature_res/.../values/dimens.xml、drawable/bg_poster_image_surface.xml、bg_card_focus_foreground.xml、bg_poster_surface.xml、bg_card_scrim_bottom.xml。

## 验证
- assembleDebug 与 feature_app 30 项测试通过；UI 门禁通过（既有 Insets owner 警告）、i18n 与 git diff --check 通过。
- Google TV API 36 模拟器内临时加载 16:9 和 2:3 几何测试图：圆形与网格用于检查拉伸，彩色边缘用于检查居中裁切，另加入缺失 URI 检查错误占位。
- 截图已检查首页焦点卡片（包括图片与外框圆角配合）、媒体库横竖素材混合及缺图、详情竖海报。图片均铺满图框，中心圆保持圆形，圆角边缘无方形图片越界。截图为诊断数据，不是真实海报质量展示。
- 测试仅临时替换模拟器的封面字段，finally 中恢复原始数据库、删除测试图片；原索引/账号未改变，小米电视未操作。
- 截图：app/build/outputs/ui/artwork-fixture-home.png、artwork-fixture-grid.png、artwork-fixture-detail.png；测试脚本位于忽略的 build 输出目录 check_artwork_fixture.py。崩溃缓冲没有新增异常。

## 规则及未验证项
采用 implement-page Workflow 及 Android UI / 架构 / 国际化 Skill，保留既有模块/API和资源入口，无新增依赖。用户文案、默认/简中/英文及 fallback 不变。几何图为内部诊断素材，无新影视宣传图或版权授权结论。
小米电视真实封面、手机布局、极端长宽比、所有视频进度条场景和 release 未全面回归。居中铺满会裁去不同比例图片的边缘，不保证保留海报内全部文字；已有错误封面来源或图像自带黑边不属于控件填充逻辑。本轮未处理既有网络安全发布门禁。
