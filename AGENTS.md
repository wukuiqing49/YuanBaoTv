# AGENTS.md

你是目标 Android 项目的高级开发助理。本文件是仓库唯一自动入口，只负责项目事实、加载顺序、任务路由和全局红线；领域规则、执行流程和能力实现分别放在 `.agents/rules/`、`.agents/workflows/` 和 `.agents/skills/`。

## 语言

- 所有回复使用中文。
- 代码注释优先中文。
- 解释按“结论 -> 原因 -> 方案”组织。

## 加载顺序

1. 先读取 `.agents/config/project-profile.yml`，了解当前工程技术栈和事实源位置。
2. 根据“任务路由”选择一个主 Workflow；同时命中多个领域时，读取主 Workflow 声明的全部 Rules 和 Skills。
3. Workflow 负责加载项目规则和组合 Skills；不要自行复制或改写规则。
4. Skill 只在明确需要时读取自己的 `references/`、运行 `scripts/` 或使用 `assets/`。
5. `.agents/prompts/` 仅供用户填写任务输入，不是规则源；`.agents/INDEX.md` 仅用于导航。

加载链：

```text
AGENTS.md -> project-profile.yml -> Workflow -> Rules + Skills -> references/scripts/assets
```

## 冲突优先级

```text
用户当前明确要求
  > AGENTS.md 项目级约束
  > .agents/rules 稳定规则
  > .agents/workflows 执行顺序
  > Skill 实现细节
  > references 补充资料
```

实际 Gradle、Manifest 和源码事实高于项目档案中的概览；发现不一致时先核对实际工程并报告，不静默覆盖。

## 任务路由

- 新建 Android 工程、初始化或调整模块脚手架：读取 `.agents/workflows/create-project.md`。
- 模块边界、共享能力、WKQ 复用、网络/存储/权限/路由等基础能力：读取 `.agents/workflows/change-architecture.md`。
- Activity、Fragment、XML、列表、Tab、弹窗、Insets 或视觉调整：读取 `.agents/workflows/implement-page.md`。
- strings、多语言、plurals、领域/产品/法律/计费文案、raw HTML/WebView 文案：读取 `.agents/workflows/localize-content.md`。
- Gradle、依赖、插件、SDK、manifest、签名、打包、release、R8、native：读取 `.agents/workflows/change-build.md`。
- 崩溃、错误日志、异常行为或回归：读取 `.agents/workflows/fix-bug.md`，再按错误领域加载子 Workflow。
- Figma 页面分析：读取系统 `figma` Skill 和 `.agents/workflows/figma-process.md`，只分析不生成代码。
- 已确认 Figma 任务卡的页面实现：读取 `.agents/workflows/figma-code.md`。
- Google Play 图标、截图、Feature Graphic、视频脚本或素材验收：读取 `.agents/workflows/prepare-play-assets.md`；实际生成位图时联动 `imagegen`。

## 项目事实源

- 技术栈和模块概览：`.agents/config/project-profile.yml`。
- 脚手架生成参数：`.agents/config/project-scaffold.yml`。
- 依赖、插件和 SDK 版本：`gradle/libs.versions.toml`。
- Gradle 版本：`gradle/wrapper/gradle-wrapper.properties`。
- namespace、applicationId、versionName 和发布签名开关：`app-config.properties`。
- Gradle/JVM/AndroidX 构建开关：`gradle.properties`。
- 模块注册：`settings.gradle`。
- 模块插件、依赖和 Android 配置：各模块 `build.gradle`。
- 真实签名参数：被忽略的本地配置或 CI 环境变量，禁止写入规则、日志和版本库。

## 全局红线

- 修改前先分析原因、现有实现和影响范围；优先最小修改。
- 不删除现有功能，不做无关重构，不随意修改 public API、包名、模块名、资源名或外部调用方式。
- 不引入不必要依赖；新增基础能力前先检查项目共享层和 WKQ 能力。
- 不输出真实密钥、证书、签名密码、token 或用户隐私数据。
- 修改后运行主 Workflow 要求的最小验证；无法验证时说明原因和剩余风险。

## 输出

最终回复说明：修改文件及原因、采用的规则/Workflow/Skill、执行的验证、未验证项和剩余风险。用户可见文案或营销素材任务额外说明语言覆盖、fallback、真实素材来源和合规未验证项。
