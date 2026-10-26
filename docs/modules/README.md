# 模块导航

> 状态：现行模块索引；整理日期：2026-10-26。只从各模块读取当前行为；测试、运行和未验收范围见[项目现状](../项目现状.md)，旧设计从历史索引回溯。

## 从修改需求开始

先选功能，再读模块约束，需要具体实现时才展开专题。简单模块直接在原文提供定位表；不要求读完整个 docs 或历史 specs。

| 我要修改什么 | 模块与边界 | 具体代码定位 |
| --- | --- | --- |
| 登录、登出、改密 | [用户与认证](001-user.md) | [修改定位](001-user.md#修改定位) |
| 用户启停、重置密码、部门树、验证码 | [用户与认证](001-user.md) | [配套功能定位](001-user.md#配套功能定位) |
| 数据源新增编辑、连接测试、密码加密 | [数据源](002-datasource.md) | [配套功能定位](002-datasource.md#配套功能定位) |
| 自动采集时间与开关 | [采集](003-metadata-collection.md) | [采集计划](003-metadata-collection.md#配套功能定位) |
| 质量规则、字段标签、旧来源可信度 | [治理](004-metadata-governance.md) | [配套功能定位](004-metadata-governance.md#配套功能定位) |
| 一键治理、重试、业务关系与指标修订 | [治理](004-metadata-governance.md) | [治理任务与业务知识](governance/治理任务与业务知识.md) |
| 点赞差评、字段归因、使用参考分、反馈结案 | [治理](004-metadata-governance.md) | [反馈归因与使用档案](governance/反馈归因与使用档案.md) |
| 角色配置、用户角色、负责源、数据授权写入 | [IAM](007-iam-s1.md) | [授权写入定位](iam/功能与数据授权.md#配套功能定位) |
| 隐藏字段、脱敏策略、确认脱敏候选 | [IAM](007-iam-s1.md) | [字段保护与脱敏候选](iam/字段保护与脱敏候选.md) |
| AI 模型、供应商、连接测试与 Embedding 配置 | [平台配置](010-platform-operations.md) | [AI 配置](010-platform-operations.md#ai-配置) |
| 通知已读、未读数、操作日志 | [平台配置](010-platform-operations.md) | [通知与操作日志](010-platform-operations.md#通知与操作日志) |
| 工作台统计、告警规则 | [平台配置](010-platform-operations.md) | [工作台与告警配置](010-platform-operations.md#工作台与告警配置) |
| 查询审计、慢查询、查询统计 | [审计与血缘](011-audit-lineage.md) | [查询审计](011-audit-lineage.md#查询审计) |
| 血缘图、影响分析、关系新增删除 | [审计与血缘](011-audit-lineage.md) | [实体血缘与图谱](011-audit-lineage.md#实体血缘与图谱) |
| 菜单、面包屑、跨页面数据源/快照范围 | [前端规范](../development/前端开发规范.md) | [导航代码定位](../development/前端开发规范.md#导航代码定位) |
| 用户列表筛选、CSV 导出字段与分页 | [用户与认证](001-user.md) | [修改定位](001-user.md#修改定位) |
| Prompt 提示词编辑、审核、版本生效与回退 | [Prompt 管理范围](../技术栈与模块职责.md#prompt-管理范围) | [修改定位](../技术栈与模块职责.md#prompt-修改定位) |
| 数据源是否可问数、阻断原因 | [数据源](002-datasource.md) | [修改定位](002-datasource.md#修改定位) |
| 采集任务、快照详情、对象目录 | [采集与资产](003-metadata-collection.md) | [修改定位](003-metadata-collection.md#修改定位) |
| 质量检查、问题处理、字段解释 | [治理](004-metadata-governance.md) | [修改定位](004-metadata-governance.md#修改定位) |
| 快照发布、撤回、发布门禁 | [版本与审核](005-metadata-versioning.md) | [修改定位](005-metadata-versioning.md#修改定位) |
| 问数页面交互与结果展示 | [问数](008-query-memory.md) | [实现与测试](query/页面交互与结果展示.md) |
| 问数任务创建、取消与恢复 | [问数](008-query-memory.md) | [实现与测试](query/任务创建取消与恢复.md) |
| SQL 生成与修正 | [问数](008-query-memory.md) | [实现与测试](query/SQL生成与修正.md) |
| 执行预算与失败处理 | [问数](008-query-memory.md) | [实现与测试](query/执行预算与失败处理.md) |
| 会话历史与长期记忆 | [问数](008-query-memory.md) | [实现与测试](query/会话历史与长期记忆.md) |
| 知识文档生成、审核与发布 | [知识](006-knowledge.md) | [实现与测试](knowledge/文档生成审核与发布.md) |
| 业务术语与 AI 释义 | [知识](006-knowledge.md) | [实现与测试](knowledge/业务术语与AI释义.md) |
| 功能与数据授权 | [IAM](007-iam-s1.md) | [实现与测试](iam/功能与数据授权.md) |
| 查询授权与结果保护 | [IAM](007-iam-s1.md) | [实现与测试](iam/查询授权与结果保护.md) |
| 临时访问申请与审批 | [IAM](007-iam-s1.md) | [实现与测试](iam/临时访问审批.md) |
| RAG 构建与集合生命周期 | [RAG](009-schema-rag.md) | [实现与测试](rag/构建与集合生命周期.md) |
| RAG 检索与重排 | [RAG](009-schema-rag.md) | [实现与测试](rag/检索与重排.md) |

## 渐进阅读目录

```text
modules/
├── README.md                         功能导航（本页）
├── 001-user.md                       概览 + 修改定位
├── 002-datasource.md                 概览 + 修改定位
├── 003-metadata-collection.md        概览 + 修改定位
├── 004-metadata-governance.md        概览 + 修改定位
├── 005-metadata-versioning.md        概览 + 修改定位
├── 006-knowledge.md                 知识行为与合同
├── 007-iam-s1.md                     权限行为与合同
├── 008-query-memory.md               问数行为与合同
├── 009-schema-rag.md                 RAG 行为与合同
├── 010-platform-operations.md        平台配置、通知与工作台
├── 011-audit-lineage.md              查询审计与数据血缘
├── governance/
│   ├── 治理任务与业务知识.md
│   └── 反馈归因与使用档案.md
├── query/
│   ├── 页面交互与结果展示.md
│   ├── 任务创建取消与恢复.md
│   ├── SQL生成与修正.md
│   ├── 执行预算与失败处理.md
│   └── 会话历史与长期记忆.md
├── knowledge/
│   ├── 文档生成审核与发布.md
│   └── 业务术语与AI释义.md
├── iam/
│   ├── 功能与数据授权.md
│   ├── 查询授权与结果保护.md
│   ├── 字段保护与脱敏候选.md
│   └── 临时访问审批.md
└── rag/
    ├── 构建与集合生命周期.md
    └── 检索与重排.md
```

例如修改“取消后的提示”：从本页进入任务专题，定位 `useQuerySubmit.cancelCurrentQuery/applyPollResult`；若要改变服务端终态，继续读 `IamS1QueryServiceImpl.cancel` 及对应约束。只改提示无需展开全部 RAG 文档。

## 定位文档的维护规则

- 每篇专题统一给出功能说明、修改位置速查、实现链路、关联影响和测试入口；只列关键符号，不抄录整个类或完整接口清单。
- 用相对链接指向真实文件，以类/方法、函数或配置键定位，不将固定行号作为唯一定位依据。
- 改行为同步更新所属模块；移动文件、重命名关键符号、改变调用链时同步更新对应定位表和索引。没有相关变化无需重写整套文档。
- 跨模块规则只在主责模块维护，其他专题链接过去。简单功能留在模块内；专题内容复杂到影响阅读时再拆分。
- 新导航写入前核对文件、符号与调用关系；测试入口只说明验证去哪里做，测试结果仍进入验收记录。无法确认的关系明确标为待核对，不写成已实现。
- 本索引按业务功能导航，不逐个枚举内部辅助类。功能未命中时先读所属模块；页面导航查前端规范，公共组件/基础设施查架构与开发指南。若仍只能找到包目录，应核对代码后补充定位，不把猜测写成实现事实。

## 模块边界索引

模块职责与约束见上方「模块与边界」列；功能专题只展开实现定位。公共对象、分页与排序见[架构说明](../技术栈与模块职责.md#java-对象目录与分页查询)，接口字段以当前 Controller、schema 和前端 API 为准。

运行方法统一见[本地开发与验证](../development/本地开发与验证.md)，实际结果见[验收索引](../acceptance/README.md)。代码导航不是运行验收；旧规格仅从[适用性索引](../../specs/README.md)回溯。
