# 规格与阶段契约适用性

> 文档类型：历史规格适用性索引；核对日期：2026-10-17；代码基线：`619bca6`。保留原 16 组需求、研究、数据模型与阶段契约。

原 spec/research/plan/data-model/contracts 是分阶段设计记录。“部分适用”意味着目标、术语或部分合同可参考，不代表所有字段、接口或步骤仍有效；新开发从当前模块说明进入，再核对实际代码。旧 Draft 不代表当前未完成，历史计划也不构成执行授权。

| 原目录 | 适用性 | 当前入口 |
| --- | --- | --- |
| [001-user-module](001-user-module/spec.md) | 部分适用；旧角色/JWT 权限描述已被替代 | [当前说明](../docs/modules/001-user.md)、[启动指南](../docs/development/本地开发与验证.md) |
| [002-datasource-management](002-datasource-management/spec.md) | 部分适用；旧 datasource_access 授权已退休 | [当前说明](../docs/modules/002-datasource.md) |
| [003-metadata-collection](003-metadata-collection/spec.md) | 部分适用；按现行快照/目录和正式页面核对 | [当前说明](../docs/modules/003-metadata-collection.md) |
| [004-metadata-governance](004-metadata-governance/spec.md) | 部分适用；治理准入还受当前 IAM/build 约束 | [当前说明](../docs/modules/004-metadata-governance.md) |
| [005-metadata-versioning](005-metadata-versioning/spec.md) | 部分适用；状态机以当前枚举为准 | [当前说明](../docs/modules/005-metadata-versioning.md) |
| [006-skills-md-knowledge](006-skills-md-knowledge/spec.md) | 部分适用；发布即自动索引的流程已被替代 | [当前说明](../docs/modules/006-knowledge.md) |
| [007-schema-rag](007-schema-rag/spec.md) | 部分适用；默认集合和旧重建流程不能用于 S1 | [当前说明](../docs/modules/009-schema-rag.md) |
| [008-nl2sql-agent](008-nl2sql-agent/spec.md) | 旧实现已替代；保留原始目标与研究 | [当前说明](../docs/modules/008-query-memory.md) |
| [009-sql-security-sandbox](009-sql-security-sandbox/spec.md) | 部分适用；当前严格执行以 S1 AST 合同为准 | [当前说明](../docs/modules/008-query-memory.md) |
| [010-field-tag-confidence](010-field-tag-confidence/spec.md) | 部分适用；结合当前可信度衰减与治理反馈 | [当前说明](../docs/modules/004-metadata-governance.md) |
| [012-frontend-query](012-frontend-query/spec.md) | 部分适用；旧布局/选表/旧查询 API 不再作为目标 | [当前说明](../docs/modules/008-query-memory.md) |
| [013-backend-admin](013-backend-admin/spec.md) | 部分适用；导航与账号等采用后续明确决定 | [当前说明](../docs/development/前端开发规范.md) |
| [014-prompt-management](014-prompt-management/spec.md) | 部分适用；具体接口和审批状态以现存代码为准 | [职责说明](../docs/技术栈与模块职责.md)、[当前 Controller](../backend/DataOcean/src/main/java/com/dataocean/module/prompt/controller/PromptTemplateController.java)、[状态枚举](../backend/DataOcean/src/main/java/com/dataocean/module/prompt/enums/PromptStatus.java) |
| [015-permission-security](015-permission-security/spec.md) | 旧权限模型已替代；S1 阶段合同部分适用 | [当前说明](../docs/modules/007-iam-s1.md) |
| [016-chart-generation](016-chart-generation/spec.md) | 部分适用；当前制图消费 Java 受保护结果 | [当前说明](../docs/modules/008-query-memory.md) |
| [017-error-degradation](017-error-degradation/spec.md) | 部分适用；服务失败不能转为业务澄清 | [当前说明](../docs/modules/008-query-memory.md) |

## IAM 阶段合同

[iam-s1-b3](015-permission-security/contracts/iam-s1-b3.md)与[iam-s1-b4](015-permission-security/contracts/iam-s1-b4.md)保留有效设计依据和当时测试记录，但不包含全部后续查询尝试、预算、会话和最终完成合同。它们不作为当前完整 API 合同；当前入口见[IAM](../docs/modules/007-iam-s1.md)与[问数](../docs/modules/008-query-memory.md)。

## 使用规则

- 保留仍有参考价值的需求、设计依据与契约；不维护已完成任务清单，启动方法统一见[本地开发与验证](../docs/development/本地开发与验证.md)。
- 旧 RBAC/Policy、旧 Agent、发布即向量化、内容区二级导航等已替代方案不得恢复。
- 新行为更新现行模块说明与实现；只有继续维护的明确契约才同步更新对应契约，不要求改写全部历史规格。
- 发现要求与代码不一致时记录差距；日期较新、文件存在或任务已勾选不构成批准或验收。

返回[文档导航](../docs/README.md)。
