# DataOcean

打开[项目运行图](prototypes/项目运行图.html)可查看总流程、知识发布、员工问数和访问申请。业务规则与验收场景见[产品方案](docs/产品方案.docx)。下表描述重构前的实现，供逐模块判断复用范围。重构顺序见 [AGENTS.md](AGENTS.md)。

| 现有模块 | 实现说明 |
| --- | --- |
| 用户与认证 | [账号、登录与部门](docs/modules/001-user.md) |
| 数据源 | [连接管理与可见范围](docs/modules/002-datasource.md) |
| 元数据采集 | [采集任务与对象目录](docs/modules/003-metadata-collection.md) |
| 元数据治理 | [字段、质量检查与治理任务](docs/modules/004-metadata-governance.md) |
| 元数据版本 | [快照、审核与发布](docs/modules/005-metadata-versioning.md) |
| 知识管理 | [术语与知识文档](docs/modules/006-knowledge.md) |
| 访问控制 | [功能授权、数据权限与审批](docs/modules/007-iam-s1.md) |
| 问数与会话 | [查询执行与会话记忆](docs/modules/008-query-memory.md) |
| Schema RAG | [索引构建与检索](docs/modules/009-schema-rag.md) |
| 平台运行 | [配置、工作台与告警](docs/modules/010-platform-operations.md) |
| 审计与血缘 | [查询记录与关系追溯](docs/modules/011-audit-lineage.md) |

[English](README.en.md) · [MIT 许可证](LICENSE)
