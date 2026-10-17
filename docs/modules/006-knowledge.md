# 006 skills.md 知识文档

> 文档类型：现行模块说明；核对日期：2026-10-17；代码基线：`619bca6`。本轮静态核对，运行验收见[项目现状](../项目现状.md)。

## 概览与关键决定

Java 管理文档草稿、审核、版本、发布、事实校验和构建确认；Python 生成草稿并负责切分/向量操作。**发布知识文档和明确确认 RAG build 是两个独立动作。**

```text
DRAFT → PENDING_REVIEW → APPROVED → PUBLISHED
审核拒绝 → 返回草稿修订
PUBLISHED 的合规版本 → 有权人员明确确认数据源级 RAG build
```

旧文档中的“发布 → INDEXING → 自动索引成功后 PUBLISHED”不是当前发布主链。枚举仍有 INDEXING 不构成恢复旧流程的理由。

## 内容与审核

六类内容为文档来源、核心表说明、具体 Join 条件、带 SQL 表达式的指标、字段说明和常见查询场景。已确认的完整性要求是：文档体系覆盖所绑定快照中的表和字段，可按业务域或表拆成多篇，也可用完整字段清单承接；不要求单篇巨型文档。该要求不能被“只描述常见字段”的旧模板覆盖，具体覆盖程度需按版本清单核对。模型根据字段名猜测的口径、未审核 Join 或指标必须标记待确认，不进入已发布事实。

发布校验当前版本的 APPROVED 审核状态、来源快照与事实依赖，以及表字段治理状态。Java 保存版本、审核任务与 chunk/build membership，不在 Java 重新实现 Python token 切分。

## 关键入口

| 代码 | 用途 |
| --- | --- |
| [KnowledgeDocController](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/controller/KnowledgeDocController.java) | `/api/admin/knowledge-docs`下 CRUD、review、publish、版本、预览与 rag-builds |
| [KnowledgeDocCrudService](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/KnowledgeDocCrudService.java) | 列表、创建与编辑 |
| [KnowledgeDocLifecycleService](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/KnowledgeDocLifecycleService.java) | 审核与文档发布 |
| [KnowledgeDocPublishService](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/KnowledgeDocPublishService.java) | AI 草稿生成与预览；类名不表示负责活动 build 切换 |
| [KnowledgeSnapshotFactValidator](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/support/KnowledgeSnapshotFactValidator.java) | 快照事实校验 |
| [KnowledgeView](../../frontend/src/views/admin/semantics/KnowledgeView.vue)、[KnowledgeDocView](../../frontend/src/views/admin/semantics/KnowledgeDocView.vue) | 语义知识工作区与详情 |

审核理由、来源快照、版本、切分预览与历史向量任务有独立读取入口，具体以 Controller 为准。旧 `KnowledgeDocServiceImpl`已拆分；其原路径与行数不能继续用于定位。

## 构建与验证入口

`POST /api/admin/knowledge-docs/rag-builds`确认数据源/来源快照下的构建，`GET`读取构建列表。Java 冻结合规版本清单，后续执行、校验和清理由[RAG 模块](009-schema-rag.md)说明。文档状态不等于索引状态。

改审核与发布时验证无审核、错误快照、禁止字段、重复操作和并发冲突；改构建时同时核对 build 失败不替换活动指针、旧查询引用不被清理。原始历史方案仅供追溯。
