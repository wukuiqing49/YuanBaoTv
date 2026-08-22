# Google Play 素材规则

- 真实图标和品牌素材放在 `.ai-work/play-assets/input/brand/`，截图放在 `input/screenshots/`，录屏放在 `input/recordings/`，结果放在 `.ai-work/play-assets/output/`。
- 关键词工具原始 CSV/XLSX 放在 `.ai-work/play-assets/input/keywords/`，只读分析，不覆盖或改写。
- `.agents/` 只保存工作流、提示模板和校验资料，不保存真实用户素材或生成结果。
- 设计参考图只能用于抽象构图分析，不能出现在成品或被复用可识别元素。
- 只有真实 App UI 可以放入设备框；没有真实 UI 时不得虚构产品界面。
- 真实 UI、文案、图标、按钮、颜色、布局和数据不得被重绘、翻译或虚构。
- 现有 App 图标必须原样使用，不得改色、重绘、添加虚假 badge。
- 禁止虚构排名、下载量、奖项、价格、折扣、促销和第三方背书。
- 不在 prompt、日志或版本库中包含 token、账号、用户数据和隐私截图。
- 输出必须检查尺寸、比例、透明通道、文字拼写、截图形变、遮挡和小尺寸可读性。
- 清理过期截图 Prompt 时只使用 Skill 的 `--prune-stale`，且仅删除带生成标记的文件；未知或手工文件必须保留。
