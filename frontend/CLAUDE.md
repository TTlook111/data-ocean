# Frontend CLAUDE.md

修改前端前，先读取根 [AGENTS.md](../AGENTS.md)与[前端开发规范](../docs/development/前端开发规范.md)。本文件只保留局部必读边界，详细要求按任务进入专题。

## 不可省略的规则

- 前端只调用 Java；Java 是权限、状态和最终保护的安全边界。
- 读取 IAM-SIMPLE-1 能力摘要；不恢复旧 roles/permissions 判权、meta.permission 或旧权限 API。
- 七域两级侧栏、高亮 meta 和上下文白名单遵循 router/adminNavigation.ts；不恢复内容区工作区栏。
- 账号在侧栏左下角；普通说明在标题右上角问号，状态、错误与对象标识直接显示。
- 沿用共用组件、Lucide 与 CSS 令牌；当前海蓝风格和用户后续修正优先于旧指南色值与图稿。
- readiness、发布、RAG build 及任务结果来自后端，不为了截图改变数据或授权。
- Loading、空、失败、无权与部分成功必须区分；保留键盘、焦点和窄屏行为。

## 按需继续

- 导航、视觉、交互、状态与代码入口：[前端规范](../docs/development/前端开发规范.md)。
- 问数与会话：[模块说明](../docs/modules/008-query-memory.md)。
- 启动与测试：[本地开发指南](../docs/development/本地开发与验证.md)。
- 覆盖范围：[项目现状](../docs/项目现状.md)；原始时间线：[前端设计验收记录](../docs/acceptance/前端设计验收记录.md)。

规范变化只更新对应现行文档；不在这里维护另一张进度表或色值表。
