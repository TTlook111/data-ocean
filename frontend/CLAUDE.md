# Frontend CLAUDE.md

修改前端前，读取根 [AGENTS.md](../AGENTS.md)与[前端开发规范](../docs/development/前端开发规范.md)。本文件只保留局部边界。

## 前端边界

- 只调用 Java，读取 IAM-SIMPLE-1 能力摘要；不以 roles/permissions、meta.permission 或旧权限 API 判权。
- 按用户任务组织页面，保留精确高亮、上下文白名单、有权入口和深链兼容。普通说明按需展示，状态、错误、对象标识和危险影响直接显示。
- 复用适用的共用组件、Lucide 与统一 CSS 令牌；先验证整页样板再扩展，不叠加主题覆盖。
- readiness、发布、RAG build 及任务结果来自后端，不为视觉需求修改数据或授权。加载、空、失败、无权和部分成功必须区分，保留键盘、焦点和窄屏行为。

## 按需继续

- 问数与会话：[模块说明](../docs/modules/008-query-memory.md)。
- 启动与测试：[本地开发指南](../docs/development/本地开发与验证.md)。
- 覆盖范围：[项目现状](../docs/项目现状.md)与[验收索引](../docs/acceptance/README.md)。
