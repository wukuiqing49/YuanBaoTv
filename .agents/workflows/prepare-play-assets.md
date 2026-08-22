# 准备 Google Play 商店素材

## 加载

规则：

- `.agents/rules/execution.md`
- `.agents/rules/play-assets.md`

位图提示模板（按素材类型只读取对应文件）：

- `doc/google-play-icon-prompt.md`
- `doc/google-play-screenshot-prompt.md`
- `doc/google-play-feature-graphic-prompt.md`

Skills：

- Feature Graphic、商店截图、Preview Video 策划、关键词回填或 Brief 验收时加载 `$plan-google-play-listing-assets`
- 实际生成或编辑位图时加载 `$imagegen`

## 流程

1. 检查应用定位、目标用户、品牌信息和 `.ai-work/play-assets/input/` 真实素材。
2. 区分官方图标、设计参考、真实 UI、录屏和缺失输入。
3. 封面、截图和视频策划统一使用 `$plan-google-play-listing-assets`，共享产品事实、Seed Keywords、关键词工具结果和素材定位；旧 `doc/` 提示模板只作兼容参考。
4. 无可靠关键词工具结果时只输出 Seed Keywords 并暂停；数据回填后再生成用户请求的正式 Brief。
5. 在保真和商店合规规则下生成素材 Brief；从 Brief 自动导出封面、逐张截图和视频的独立 Prompt 文件。
6. 对生成位图运行 `.agents/scripts/validate_play_assets.py`；对 Shared Strategy、封面、截图、视频 Brief、独立 Prompt 文件和 Seed CSV 运行 Skill 声明的校验脚本，再检查文字、时间轴、证据、缺失素材和营销声明。
7. 将 Brief 和 Prompt 文件写入 `.ai-work/play-assets/output/`，记录输入、变量和未验证项。
