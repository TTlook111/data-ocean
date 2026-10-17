# 005 元数据版本与审核

> 文档类型：现行模块说明；核对日期：2026-10-17；代码基线：`619bca6`。本轮静态核对，运行验收见[项目现状](../项目现状.md)。

## 概览与当前状态机

Java 管理快照的质量阶段、审核、发布、过期、撤回、版本历史与审计。只有合规版本才能作为知识事实来源；快照发布不是 RAG 活动 build 切换。

[SnapshotStatus](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/enums/SnapshotStatus.java)定义：

```text
DRAFT → CHECKING → ISSUE_FOUND → APPROVED → PUBLISHED → EXPIRED
                 └ 无问题时 CHECKING → APPROVED
PUBLISHED → APPROVED（撤回分支）
```

具体条件由 Service 决定。旧说明中的 REVIEWING 不是当前该枚举状态，不能据此增加前端分支或数据库值。

## 发布边界

- 同一数据源发布新快照时处理旧发布版本，记录操作人与审计。
- 发布/过期事件同步实体和通知等现有流程；不将事件误写成文档自动向量化。
- 乐观锁冲突按现有 409 契约处理，不能静默覆盖。
- 已绑定的知识版本和进行中查询保留来源与依赖；旧 build 清理须等待引用结束。

## 入口

| 代码 | 用途 |
| --- | --- |
| [SnapshotVersionController](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/controller/SnapshotVersionController.java) | `/api/admin/snapshots/{snapshotId}`下 status、publish、revoke、audit-logs、diff |
| [versioning/service](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/service/) | 生命周期、发布、审计 |
| [versioning/event](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/event/) | 发布/过期事件 |
| [ReleasesView](../../frontend/src/views/admin/releases/ReleasesView.vue) | 正式版本发布工作区 |
| [versioning API](../../frontend/src/api/admin/versioning.ts) | 版本与审核请求 |

数据源 version-history/published-snapshot/audit-logs 等具体接口以 Controller 为准；旧独立 SnapshotLifecycle/VersionHistory 页面不再是正式导航入口。

## 修改与验证

检查允许与拒绝的状态迁移、审核人/操作者、重复发布、并发冲突、撤回和跨源拒绝。快照变化后还需核对[知识文档](006-knowledge.md)和[RAG](009-schema-rag.md)的来源绑定，不能仅凭快照状态宣称问数已就绪。
