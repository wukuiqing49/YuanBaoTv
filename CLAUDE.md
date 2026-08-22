# Claude Code 项目入口

@./AGENTS.md

本文件只适配 Claude Code，不维护项目规则。`AGENTS.md` 是唯一规则和任务路由入口。

## 启动

1. 使用上面的导入加载根目录 `AGENTS.md`；当前客户端不支持导入时，先主动读取该文件。
2. 按 `AGENTS.md` 读取项目档案并选择一个主 Workflow。
3. 只加载主 Workflow 声明的 Rules、Skills 和当前阶段 references。
4. 执行对应验证并按项目要求汇报结果。

不要默认加载 `.agents/INDEX.md`、全部 Rules、全部 Skills 或全部 references，也不要在本文件复制这些内容。
