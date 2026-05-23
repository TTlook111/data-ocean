# 002 数据源管理

## 概述
数据源管理模块负责企业数据库连接的全生命周期管理，包括数据源的注册、连接测试、健康检查、启用/禁用控制和用户授权。是元数据采集和 NL2SQL 查询的前置依赖。

## 解决的问题
- 多数据源注册与连接信息安全存储（密码 AES-256 加密）
- 连接可用性验证（创建时测试 + 定时健康检查）
- 数据源启用/禁用状态控制
- 用户级数据源访问授权（谁能查询哪个库）
- 为 Python 服务提供连接池销毁通知

## 实现方案
- **密码安全**: 独立的 `datasource_secret` 表存储 AES-256 加密后的密码，与主表分离
- **连接测试**: 使用 JDBC 直连测试，不创建持久连接池
- **健康检查**: @Scheduled 定时任务每 10 分钟检查所有启用数据源的连接状态
- **授权模型**: `datasource_access` 中间表记录用户-数据源授权关系
- **Python 通知**: 数据源删除/禁用时通过 HTTP 通知 Python 服务销毁对应连接池

## 代码位置

### 后端
- Controller: `backend/DataOcean/src/main/java/com/dataocean/module/datasource/controller/`
  - `DatasourceAdminController.java` — 管理端 CRUD + 授权
  - `DatasourceUserController.java` — 用户端查询可用数据源
- Service: `backend/DataOcean/src/main/java/com/dataocean/module/datasource/service/`
  - `DatasourceService.java` / `impl/DatasourceServiceImpl.java`
  - `DatasourceAccessService.java`
  - `DatasourceSecretService.java` / `impl/DatasourceSecretServiceImpl.java`
  - `DatasourceConnectionService.java` / `impl/DatasourceConnectionServiceImpl.java`
- Client: `backend/DataOcean/src/main/java/com/dataocean/module/datasource/client/`
  - `PythonPoolClient.java` / `impl/PythonPoolClientImpl.java`
- Scheduler: `backend/DataOcean/src/main/java/com/dataocean/module/datasource/scheduler/`
  - `DatasourceHealthCheckScheduler.java`
- Entity: `backend/DataOcean/src/main/java/com/dataocean/module/datasource/entity/`
  - `Datasource.java`, `DatasourceAccess.java`, `DatasourceHealthCheck.java`, `DatasourceSecret.java`

### 前端
- 页面: `frontend/src/views/admin/datasource/DatasourceList.vue`, `frontend/src/views/query/QueryDatasourceView.vue`
- API: `frontend/src/api/admin/datasource.ts`

### 数据库
- 迁移脚本: `V4__create_datasource_tables.sql`, `V5__add_datasource_comments.sql`
- 涉及表: `datasource`, `datasource_access`, `datasource_health_check`, `datasource_secret`

## API 接口清单

| 方法 | 路径 | 用途 | 调用方 |
|------|------|------|--------|
| GET | `/api/admin/datasources` | 数据源列表 | 前端管理页 |
| POST | `/api/admin/datasources` | 创建数据源 | 前端管理页 |
| PUT | `/api/admin/datasources/{id}` | 更新数据源 | 前端管理页 |
| DELETE | `/api/admin/datasources/{id}` | 删除数据源 | 前端管理页 |
| PATCH | `/api/admin/datasources/{id}/status` | 启用/禁用 | 前端管理页 |
| POST | `/api/admin/datasources/test-connection` | 测试连接（不保存） | 前端创建表单 |
| POST | `/api/admin/datasources/{id}/test-connection` | 测试已保存数据源 | 前端管理页 |
| POST | `/api/admin/datasources/{id}/access` | 批量授权 | 前端授权弹窗 |
| GET | `/api/admin/datasources/{id}/access` | 查询授权列表 | 前端授权弹窗 |
| DELETE | `/api/admin/datasources/{id}/access/{userId}` | 撤销授权 | 前端授权弹窗 |
| GET | `/api/datasources` | 用户可用数据源 | 前端问答端 |

## 模块间依赖
- **被依赖**: 003 元数据采集（需要数据源连接信息）、006 知识库（按数据源管理文档）、008 NL2SQL（查询目标库）
- **依赖**: 001 用户模块（授权关联用户）、Python 服务（连接池通知）
