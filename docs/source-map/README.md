# 源码定位

这里定位当前正式实现与仓库保留的其他系统代码，供开发、运行维护和复用评估。业务与设计依据分别见[产品方案](../product/README.md)、[前端开发入口](../frontend/README.md#开发前必读与完成验收)、[后端设计](../backend/README.md)和[系统架构](../architecture/README.md)，不能用源码偏差改写已确认规则。

说明必须与仍存在的源码对应。源码发生已授权变更时同步路径和事实；已无用途的说明直接删除，不维护过期区。

## 首批正式实现

| 范围 | 实际入口 |
| --- | --- |
| React 路由、登录边界与后台外壳 | [应用装配](../../frontend/src/app/Application.tsx)、[启动与主题](../../frontend/src/app/main.tsx) |
| 登录与首次改密、账号、人员、部门、角色配置及权限审计页面 | [IAM 页面](../../frontend/src/modules/iam/)；覆盖与验收边界见[前端实现状态](../frontend/README.md#当前实现与验证边界) |
| 前端请求、会话、表格与操作核对 | [共用代码](../../frontend/src/shared/) |
| Java 认证、人员、部门、角色与权限 | [IAM 模块](../../backend/src/main/java/com/dataocean/module/iam/)；接口设计与验收见[首批模块入口](../backend/详细设计与验收.md) |
| Java 审计及身份建库基线 | [审计模块](../../backend/src/main/java/com/dataocean/module/audit/)、[建库脚本](../../backend/src/main/resources/db/migration/) |
| Python 正式公共包、配置与存活 API | [API](../../python-service/src/dataocean_ai/api/app.py)、[公共配置／返回／日志](../../python-service/src/dataocean_ai/core/)、[构建与运行](../../python-service/README.md)；旧 `dataocean` 不进入正式产物，工作流执行器按业务批次接入 |

以上仅说明已存在的源码位置，不代表逐接口、界面或跨端链路全部验收通过。离线原型的定位仍由[页面专题](../frontend/pages/README.md#原型定位与衔接)维护。

## 模块定位专题

| 模块 |
| --- |
| [001 用户与认证](001-user.md) |
| [002 数据源与就绪检查](002-datasource.md) |
| [003 元数据采集与资产目录](003-metadata-collection.md) |
| [004 元数据治理与可信度](004-metadata-governance.md) |
| [005 元数据版本与审核](005-metadata-versioning.md) |
| [006 业务术语与知识文档](006-knowledge.md) |
| [007 人员、角色与功能权限](007-iam-s1.md) |
| [008 S1 问数与会话记忆](008-query-memory.md) |
| [009 Schema RAG 构建与检索](009-schema-rag.md) |
| [010 平台配置、通知与工作台](010-platform-operations.md) |
| [011 查询审计与数据血缘](011-audit-lineage.md) |
