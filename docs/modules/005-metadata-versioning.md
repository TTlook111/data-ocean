# 005 元数据版本与审核

## 概述
元数据版本与审核模块管理元数据快照的生命周期状态流转（DRAFT → REVIEWING → PUBLISHED → EXPIRED），提供快照发布、撤回、版本历史查询和审计日志功能。只有已发布的快照才能被 RAG 和知识库模块使用。

## 解决的问题
- 快照生命周期状态管理（草稿/审核中/已发布/已过期）
- 快照发布控制（确保只有审核通过的快照对外生效）
- 发布时自动过期旧版本（同一数据源只有一个已发布快照）
- 操作审计追踪（谁在什么时候做了什么操作）
- 版本历史查询和对比

## 实现方案
- **状态机**: 快照状态通过 `SnapshotStatus` 枚举管理，状态变更有严格的前置条件校验
- **发布机制**: 发布新快照时自动将同数据源的旧已发布快照标记为 EXPIRED
- **事件驱动**: 发布/过期操作触发 Spring Event（SnapshotPublishedEvent/SnapshotExpiredEvent），解耦后续处理
- **审计日志**: 每次状态变更自动记录到 `snapshot_audit_log`，包含操作人、操作类型、备注

## 代码位置

### 后端
- Controller: `backend/DataOcean/src/main/java/com/dataocean/module/versioning/controller/SnapshotVersionController.java`
- Service: `backend/DataOcean/src/main/java/com/dataocean/module/versioning/service/`
  - `SnapshotLifecycleService.java` / `impl/SnapshotLifecycleServiceImpl.java`
  - `SnapshotPublishService.java` / `impl/SnapshotPublishServiceImpl.java`
  - `SnapshotAuditLogService.java` / `impl/SnapshotAuditLogServiceImpl.java`
- Event: `backend/DataOcean/src/main/java/com/dataocean/module/versioning/event/`
  - `SnapshotPublishedEvent.java`, `SnapshotExpiredEvent.java`, `SnapshotPublishedEventListener.java`
- Entity: `backend/DataOcean/src/main/java/com/dataocean/module/versioning/entity/`
  - `SnapshotAuditLog.java`, `SnapshotStatus.java`

### 前端
- 页面:
  - `frontend/src/views/admin/metadata/SnapshotLifecycle.vue` — 快照生命周期管理
  - `frontend/src/views/admin/metadata/VersionHistory.vue` — 版本历史
- API: `frontend/src/api/admin/versioning.ts`

### 数据库
- 迁移脚本: `V12__create_snapshot_audit_log.sql`
- 涉及表: `snapshot_audit_log`（快照主表 `metadata_snapshot` 属于 003 模块）

## API 接口清单

| 方法 | 路径 | 用途 | 调用方 |
|------|------|------|--------|
| PATCH | `/api/admin/snapshots/{snapshotId}/status` | 变更快照状态 | 前端生命周期页 |
| POST | `/api/admin/snapshots/{snapshotId}/publish` | 发布快照 | 前端生命周期页 |
| POST | `/api/admin/snapshots/{snapshotId}/revoke` | 撤回已发布快照 | 前端生命周期页 |
| GET | `/api/admin/datasources/{datasourceId}/version-history` | 版本历史 | 前端版本历史页 |
| GET | `/api/admin/datasources/{datasourceId}/published-snapshot` | 当前已发布快照 | 前端/006 知识库 |
| GET | `/api/admin/snapshots/{snapshotId}/audit-logs` | 快照审计日志 | 前端生命周期页 |
| GET | `/api/admin/datasources/{datasourceId}/audit-logs` | 数据源审计日志 | 前端版本历史页 |
| GET | `/api/admin/snapshots/{snapshotId}/diff/{compareSnapshotId}` | 版本差异对比 | 前端版本历史页 |

## 模块间依赖
- **被依赖**: 006 知识库（基于已发布快照生成 skills.md）
- **依赖**: 003 元数据采集（管理其生成的快照）
