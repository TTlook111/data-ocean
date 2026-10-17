# DataOcean Frontend

Vue 3 + TypeScript + Vite 的查询端与治理后台，共用身份与 IAM-SIMPLE-1 能力摘要，前端只调用 Java。

## 本地运行

先按[本地开发指南](../docs/development/本地开发与验证.md)核对 Node/Vite 要求、网关与当前机器环境。在 frontend 目录执行：

```powershell
npm ci
npm run dev
```

## 验证

```powershell
npm run test:run
npm run build
```

build 已包含 vue-tsc 与 Vite。定向检查使用已有 Vitest 命令并传入测试路径；历史测试数量见验收记录，不在这里复制。

## 按任务阅读

- 界面、两级侧栏、上下文、能力摘要、状态与视觉：[前端开发规范](../docs/development/前端开发规范.md)。
- 问数与历史：[S1 问数与会话记忆](../docs/modules/008-query-memory.md)。
- Agent 约束：[本目录 CLAUDE.md](CLAUDE.md)与[根 AGENTS.md](../AGENTS.md)。
- 当前覆盖和限制：[项目现状](../docs/项目现状.md)；既有对照过程：[前端设计验收记录](../docs/acceptance/前端设计验收记录.md)。

代码入口为 src/api、components、composables、router、stores、views；共享视觉入口为 src/styles/variables.css 与 src/style.css。正式页面和路由以当前源码为准，不从历史规格恢复已删除页面。
