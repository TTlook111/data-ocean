# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

DataOcean — 企业级 NL2SQL 智能数据查询与治理平台（毕业设计项目）。让业务人员通过自然语言查询企业数据库，AI 生成 SQL 并返回表格/图表结果，核心强调元数据治理驱动的可信查询。

MVP 范围：多数据源接入，限定单库多表查询（用户每次选择一个 MySQL 数据源，在该库内多表联合查询）。

## Architecture

三层分离架构，前端统一调 Java，Java 内部代理调 Python：

```
Vue 3 前端 (C端问答 + B端治理，同一项目路由区分)
    │ HTTP API
    ▼
Java 网关层 (Spring Boot 3.x + JDK 17)
  - 鉴权/权限/数据源管理/skills.md管理/可信度/审计
    │ 内部 HTTP (RestClient → FastAPI)
    ▼
Python AI 服务 (Python 3.13 + FastAPI + LangGraph + LangChain)
  - Query Rewrite (时间解析/指代消解/意图提取)
  - Schema RAG (LangChain + Milvus)
  - SQL 生成 (LLM via Qwen API)
  - SQL 安全校验 (sqlglot AST)
  - SQL 沙箱执行 (只读连接, SQLAlchemy 2.x + PyMySQL)
  - 图表生成 (ECharts Option)
    │
    ▼
Milvus 2.x (向量库) / MySQL 8 (业务库+管理库) / Redis (缓存) / Qwen LLM API
```

关键设计决策：
- Java 负责所有管理类 CRUD 和鉴权，Python 仅在"问答查询"场景被调用
- Python 请求级无状态，上下文由 Java 每次传入
- LangGraph 编排 Agent 工作流（Query_Rewriter → Schema_Retriever → SQL_Generator → SQL_Validator → SQL_Executor → Data_Visualizer），失败最多重试 3 次
- Query_Rewriter 负责时间解析、指代消解、意图提取，改写后的结构化查询同时提升 RAG 召回和 SQL 生成质量
- LangChain 封装 RAG 层（MVP 阶段向量检索，阶段二引入 Hybrid Search）
- 行列级权限在 SQL AST 层强制执行（Python sandbox/rewriter.py），不依赖 Prompt
- 敏感字段：SENSITIVE 可进入 RAG（带 maskColumns），Python 标记需脱敏字段，Java 网关统一脱敏
- 查询结果不缓存（避免相似问题、相对时间和权限差异导致错误复用）

## Core Domain Concepts

- **skills.md**: 业务语义说明书，由元数据治理结果生成草稿 → 人工审核 → 发布后按三级标题细粒度切分 → 向量化进入 RAG。结构包含 6 个章节：文档来源、核心表说明（每表独立 chunk）、Join Path（每对关联独立 chunk，含具体 SQL 条件）、指标口径（每个指标独立 chunk，含 SQL 表达式）、字段防坑指南、常见查询场景（含 SQL 骨架）
- **字段可信度**: 0-100 数值评分，影响 SQL 生成时的字段选择优先级。阶段一包含完整动态调整（反馈驱动升降）
- **元数据治理闭环**: 采集 → 质量校验 → 问题修正 → 审核发布快照 → 生成 skills.md → 向量化 → 查询血缘反馈回流
- **RAG 准入控制**: NORMAL/RECOMMENDED/SENSITIVE（带脱敏标记）且 review_status=APPROVED 可进入向量库；DEPRECATED/BLOCKED 禁止召回
- **Schema RAG**: 解决单库表数量过多时的上下文过载问题，精准召回 Top 5-10 张相关表。重排时结合 chunk_type（JOIN_PATH/METRIC/FIELD_NOTE/QUERY_SCENE）和用户查询意图（聚合/关联/防坑）做差异化加权
- **Query Rewrite**: 用户原始问题 → 时间解析 + 指代消解 + 意图提取 → 结构化查询，提升 RAG 和 SQL 生成质量

## Three Main Lines

1. **可信数据依据线**: 数据源接入 → 元数据采集 → 元数据治理 → 快照 → skills.md → RAG
2. **智能查询执行线**: 用户提问 → Query Rewrite → RAG 召回 → SQL 生成 → 安全校验 → 执行 → 图表和解释
3. **治理反馈闭环线**: 查询审计 → 字段可信度 → 用户反馈 → 血缘分析 → 元数据和知识库修正

## Tech Stack (Confirmed for MVP)

| 层 | 技术 |
|---|---|
| 前端框架 | Vue 3 + Vite + TypeScript |
| 前端路由/状态 | Vue Router + Pinia |
| UI 组件库 | Element Plus |
| 图表 | ECharts |
| HTTP/SSE | Axios + EventSource |
| Java 网关 | Spring Boot 3.x + JDK 17 |
| Java 权限 | Spring Security + JWT |
| ORM | MyBatis-Plus |
| 数据库迁移 | Flyway |
| 缓存 | Redis（JWT 黑名单、任务状态、限流计数） |
| Java HTTP 客户端 | RestClient |
| 定时任务 | Spring Scheduler |
| Python AI 服务 | Python 3.13 + FastAPI + LangGraph + LangChain |
| Python 数据库 | SQLAlchemy 2.x + PyMySQL |
| SQL 解析 | sqlglot (MySQL 方言) |
| 向量库 | Milvus 2.x Standalone |
| LLM | Qwen / 通义千问 API |
| Embedding | text-embedding-v4 (默认 1024 维) |
| 管理数据库 | MySQL 8（独立于业务库） |
| 部署 | Docker Compose |

## Project Structure

```
frontend/              — Vue 3 前端项目 (问答端 /query/* + 治理端 /admin/*)
backend/               — Spring Boot Java 网关层
python-service/        — Python AI 服务 (FastAPI + LangGraph + LangChain)
docs/                  — 项目设计文档
specs/                 — 模块规格说明（17 个模块，含 spec/plan/tasks/contracts）
output/playwright/     — 联调截图按功能保存，用于验证开发完整性
```

## Module Breakdown

> Spec 编号与实际代码包名的映射。后端共 13 个业务模块（含 3 个无 spec 的扩展模块）。

| Spec | 模块名 | 实际后端包 | 归属层 | 状态 |
|------|--------|-----------|--------|------|
| 001 | 用户模块 | `user/` | Java | ✅ 完成 |
| 002 | 数据源管理 | `datasource/` | Java | ✅ 完成 |
| 003 | 元数据采集 | `metadata/` | Java | ✅ 完成 |
| 004 | 元数据治理 | `governance/` | Java | ✅ 完成 |
| 005 | 元数据版本与审核 | `versioning/` | Java | ✅ 完成 |
| 006 | skills.md 知识库 | `knowledge/` | Java + Python | ✅ 完成 |
| 007 | Schema RAG | `rag/` (Python) | Python | ✅ 完成 |
| 008 | NL2SQL Agent | `query/` (Java) + `agent/` (Python) | Java + Python | ✅ 核心完成 |
| 009 | SQL 安全沙箱 | `sandbox/` (Python) | Python | ✅ 核心完成 |
| 010 | 字段 Tag 与可信度 | `fieldtag/` | Java | ✅ 完成 |
| 011 | 血缘与审计 | `audit/` | Java | ✅ 完成 |
| 012 | 前端问答端 | — | Frontend | ✅ 完成 |
| 013 | 后台治理管理端 | — | Frontend | ✅ 完成 |
| 014 | Prompt 管理 | `prompt/` (Java) + `prompt/` (Python) | Java + Python | ✅ 完成 |
| 015 | 权限与安全 | `user/` (权限部分) + `permission/` (脱敏部分) | Java | ✅ 完成 |
| 016 | 图表生成 | `chart/` (Python) | Python + Frontend | ✅ 完成 |
| 017 | 错误处理与降级 | — | Java + Python | 🔲 待验证 |
| — | 系统管理 | `system/` | Java | ✅ 完成 |
| — | 仪表盘 | `dashboard/` | Java | ✅ 完成 |

## Current Development Status

> **最后更新**: 基于最近一次完整开发会话的状态快照

### Java 后端模块（13 个）

| 实际包名 | 对应 Spec | 状态 | 完成功能 |
|----------|----------|------|----------|
| `user/` | 001+015 | ✅ 完成 | 登录、强制改密、用户管理（分页搜索/新增/编辑/删除/批量启用禁用/重置密码）、登录失败锁定/解锁、角色列表、部门树、个人资料、修改密码、退出登录。登录历史高级筛选。部门管理（树形/新增/编辑/删除/子部门转移）。角色管理（分页/新增/编辑/删除/权限树/分配权限）。权限管理（树形/新增/编辑/删除/搜索）。用户导入（模板下载/Excel预览/字段映射/校验/提交）。用户导出（字段选择/列排序/预览/Excel下载）。 |
| `datasource/` | 002 | ✅ 完成 | 数据源列表、连接测试、授权列表、启用/禁用、查询端按权限/状态展示可用数据源。健康检查定时任务。 |
| `metadata/` | 003 | ✅ 完成 | 后端连接测试（真实 Schema 采样）。元数据扫描（表/列/类型/注释/主键/索引/行数）。扫描任务管理（异步执行/分页/状态筛选/手动停止/错误信息）。扫描详情查看。字段类型映射（Java/Python/SQL 类型推断）。元数据对比（差异快照）。元数据持久化（MySQL 存储）。元数据同步（增量版本+变更检测）。5 种采集器（Column/Index/Relation/Statistics/Table）。自动同步定时任务。 |
| `governance/` | 004 | ✅ 完成 | 模块入口、CRUD、列设置、搜索、批量操作、Mock 分页、质量问题检测、质量检查历史、一键修复（含撤销）、审核记录、状态筛选。6 维质量检查器（准确性/完整性/一致性/时效性/可追溯性/质量）。 |
| `versioning/` | 005 | ✅ 完成 | 元数据快照（存储/对比/版本管理）、审核工作流（待审核/通过/驳回状态流转+评论）、元数据审核（完整审批/驳回流程）。快照生命周期管理、发布事件机制。 |
| `knowledge/` | 006 | ✅ 完成 | 从元数据生成 skills.md（含字段治理状态/标签/索引信息）、AI 一键批量生成（域分析+逐域生成）、生命周期管理（DRAFT → PENDING_REVIEW → APPROVED → INDEXING → PUBLISHED）、审核工作流（通过/驳回）、发布（版本管理/内容冻结）、知识库条目、状态筛选。向量索引定时任务。分块器（按 ### 三级标题细粒度切分，5 种 ChunkType）。 |
| `query/` | 008 | ✅ 核心完成 | NL2SQL 查询 Java 端。任务管理和会话持久化、SSE 流式推送、PythonAgentClient 调用。字段可信度动态调整（反馈驱动）、信任分值级联计算。 |
| `fieldtag/` | 010 | ✅ 完成 | 字段标签管理、字段可信度评分（动态调整：使用+5/成功+3/反馈+10/-20）、阈值过滤。5 个 Controller（FeedbackReview/FieldAdmin/FieldConfidence/FieldTag/UserFeedback）。 |
| `audit/` | 011 | ✅ 完成 | 查询历史记录、血缘分析（表级/列级）、查询审计（执行成功/失败追踪、用户追踪）。配额策略管理、告警规则、LLM 用量统计。 |
| `prompt/` | 014 | ✅ 完成 | 完整 Prompt CRUD（新增/编辑/删除/预览/筛选/排序）、多版本管理、Prompt 校验、Prompt 分类。内部 API（PromptInternalController）供 Python 调用。 |
| `permission/` | 015 | ✅ 完成 | 数据源访问策略（AccessPolicy）、数据脱敏服务（DataMaskingService）、权限计算器（PermissionCalculator）。3 种枚举（AccessType/MaskStrategy/SubjectType）。 |
| `system/` | — | ✅ 完成 | 系统管理：通知服务、操作日志（AOP 审计切面）、同步调度配置、系统配置。 |
| `dashboard/` | — | ✅ 完成 | 仪表盘统计（聚合展示，无独立数据库表）。 |

### Python AI 服务（7 个路由模块）

| 模块 | 路由前缀 | 状态 | 完成功能 |
|------|----------|------|----------|
| `agent/` | /internal/query | ✅ 核心完成 | LangGraph 6 节点工作流（Query_Rewriter→Schema_Retriever→SQL_Generator→SQL_Validator→SQL_Executor→Data_Visualizer）、条件路由、重试/超时/取消机制、SSE 流式推送、Prompt 版本追踪。 |
| `sandbox/` | /internal/sql | ✅ 核心完成 | AST 校验引擎（6 条规则链 + 注入检测）、AST 改写（行过滤/列检查/LIMIT/脱敏标记）、沙箱执行器（只读事务 + KILL QUERY）、连接池管理（AES-256-CBC 解密）。 |
| `rag/` | /internal/rag | ✅ 完成 | Milvus 语义检索（数据源隔离 + 准入过滤）、规则加权重排（表名命中/可信度/废弃惩罚）、向量化写入（批量 embedding + 版本切换）、Milvus 不可用时自动降级。 |
| `chart/` | /internal/chart | ✅ 完成 | LLM 生成 ECharts option、数据聚合（时间序列/分类 Top10/数值分桶）、ECharts Option JSON 校验。 |
| `knowledge/` | /internal/knowledge | ✅ 完成 | LLM 生成 skills.md 草稿（基于元数据快照）、无注释字段警告标记。 |
| `prompt/` | /internal/prompts | ✅ 完成 | 从 Java 获取 Prompt 模板、Token 预算控制（4000 总量，按优先级裁剪）、Jinja2 渲染。 |
| `infra/` | /health | ✅ 完成 | 基础设施层：LLM 调用（DashScope）、Embedding（text-embedding-v4 1024 维）、SSE 传输层、取消令牌管理、时间预算管理器、健康检查。 |

### 前端（37 个路由，17 个 API 模块，2 个公共组件）

| 功能区 | 状态 | 完成功能 |
|--------|------|----------|
| 问答端 | ✅ 完成 | 智能问答主页面、数据源选择、ECharts 图表渲染（折线/柱状/饼图/散点）、SQL 详情展示。 |
| 用户与权限 | ✅ 完成 | 用户管理（列表/CRUD/批量操作/导入导出）、部门管理（树形）、角色管理（权限树/分配权限）、访问控制、策略编辑器。 |
| 元数据管理 | ✅ 完成 | 同步任务、快照列表、表浏览器、快照差异对比、同步调度配置、快照生命周期、版本历史。 |
| 元数据治理 | ✅ 完成 | 质量看板、治理问题清单、治理状态编辑。 |
| 字段治理 | ✅ 完成 | 字段标签管理、字段可信度看板、反馈审核。 |
| 知识库管理 | ✅ 完成 | 知识库总览、Skills 编辑器、知识版本历史、知识审核页。 |
| AI 调优 | ✅ 完成 | Prompt 管理器。 |
| 审计管理 | ✅ 完成 | 审计日志列表、慢查询列表、血缘查看器。 |
| 系统管理 | ✅ 完成 | 服务健康监控、仪表盘工作台。 |
| 个人中心 | ✅ 完成 | 个人资料、修改密码。 |
| 引导页 | ✅ 完成 | 智能查询引导、管理员引导。 |

### 端到端链路

✅ **完整链路已通**: Java 提交 → Python Agent → RAG 召回 → SQL 生成 → AST 校验改写 → 沙箱执行 → 结果回写 → 前端渲染

### 待完善 / 待验证

- 012 问答端：刷新后历史恢复、SSE 实时进度条、完整历史搜索。
- 003 元数据采集：真实数据源采集流程端到端验证。
- 016 图表生成：端到端图表渲染流程验证（Python 生成 + 前端 ECharts 渲染联调）。
- 联调截图按功能保存到 `output/playwright/`，后续每联调完成一个功能都要继续截图，便于用户验证完整性。

### 最近优化（2026-06-02）

- **skills.md 生成策略优化**: 模板重构为 6 章节结构化输出，强制 LLM 生成含 SQL 表达式的指标口径、含具体 ON 条件的 Join Path、按表拆分的核心说明
- **RAG 分块策略细化**: 从按 `##` 切分的 5 个大 chunk 改为按 `###` 三级标题切分的 15-30 个细粒度 chunk，每张表/每个指标/每对关联独立 chunk
- **RAG 重排增强**: 新增 chunk_type 场景加权（JOIN_PATH +0.15/METRIC +0.15/FIELD_NOTE +0.1/QUERY_SCENE +0.08），实现聚合/关联/防坑意图检测
- **Token 预算调整**: 总预算 4000→5000，skills 预算 1000→1500 与 schema 同级，优先级提升为最高
- **元数据传入增强**: skills.md 生成时传入字段治理状态（governance_status）、字段标签（tags）、索引信息（is_indexed）
- **前端 bug 修复**: 快照下拉版本号显示修复（s.version→s.snapshotVersion）、分页参数修复（pageSize→size）、AI 生成超时调整（120s/180s）
- **.env 修复**: DashScope URL 从旧 API 改为 OpenAI 兼容端点（compatible-mode/v1）
- **错误降级修复（spec 017）**: Milvus 降级在 Agent 主链路真正生效（Java 加载 fallback chunks 传给 Python）；degraded/degrade_notice 标记传递到前端；统一错误消息管理（error_messages.py）避免技术细节泄露；图表生成异常兜底（失败不影响数据返回）；QueryCancelService 死代码清理
- **系统稳定性**: 自定义异步线程池（core=10/max=30/queue=50）；僵尸任务定时清理（PROCESSING 超 3 分钟→TIMEOUT）；取消竞态修复（UPDATE 加 status 条件）；节点内部超时（asyncio.wait_for）；retry_count 统一管理；SSE 事件时序修正；401 防重复跳转；查询结果前端分页
- **AI 配置管理**: sys_config 存储 AI 配置（apiKey AES 加密）；Java AiConfigController + InternalAiConfigController；Python /internal/config/reload 热重载端点（清缓存重建实例）；前端 AiConfig.vue 配置页面（路由+侧边栏）
- **仪表盘美化**: ops-strip 环形进度图（echarts gauge）、统计数值 CountUp 动画（gsap）、活动时间线图标（lucide）、治理健康进度条
- **元数据传入增强**: skills.md 生成时传入字段治理状态（governance_status）、字段标签（tags）、索引信息（is_indexed）
- **前端 bug 修复**: 快照下拉版本号显示修复（s.version→s.snapshotVersion）、分页参数修复（pageSize→size）、AI 生成超时调整（120s/180s）
- **.env 修复**: DashScope URL 从旧 API 改为 OpenAI 兼容端点（compatible-mode/v1）

## Key Internal APIs

| 服务间调用 | 路径 | 说明 |
|-----------|------|------|
| Java → Python | POST /internal/query/execute | 发起 NL2SQL 查询（SSE 流） |
| Java → Python | POST /internal/query/tasks/{taskId}/cancel | 取消查询 |
| Java → Python | POST /internal/rag/retrieve | RAG 召回 |
| Java → Python | POST /internal/rag/vectorize | 触发向量化 |
| Java → Python | POST /internal/knowledge/generate-draft | 触发 skills.md 生成 |
| Java → Python | POST /internal/sql/validate | SQL 安全校验 |
| Java → Python | POST /internal/sql/execute | SQL 沙箱执行 |
| Java → Python | POST /internal/chart/generate | 图表生成 |
| Python → Java | GET /internal/prompts/{code} | 获取 Prompt 模板 |

## Key Public APIs

| 方法 | 路径 | 说明 |
|------|------|------|
| **认证** |||
| POST | /api/auth/login | 登录返回 JWT |
| POST | /api/auth/logout | 退出登录 |
| GET | /api/auth/me | 当前用户信息 |
| PUT | /api/auth/password | 修改密码 |
| **问答查询** |||
| POST | /api/query/ask | 发起查询（异步，返回 taskId + conversationId） |
| GET | /api/query/tasks/{taskId} | 查询任务结果（轮询） |
| POST | /api/query/tasks/{taskId}/cancel | 取消查询 |
| GET | /api/query/conversations | 用户会话列表 |
| DELETE | /api/query/conversations/{id} | 删除会话 |
| GET | /api/query/history | 查询历史（高级筛选） |
| **数据源** |||
| GET | /api/datasources | 用户可访问的数据源列表 |
| POST | /api/admin/datasources/test-connection | 测试数据源连接 |
| POST | /api/admin/datasources | 新增数据源 |
| **用户管理** |||
| GET | /api/admin/users | 用户列表（分页/搜索） |
| POST | /api/admin/users | 创建用户 |
| PUT | /api/admin/users/{id} | 编辑用户 |
| DELETE | /api/admin/users/{id} | 删除用户 |
| **部门管理** |||
| GET | /api/admin/departments/tree | 部门树 |
| POST | /api/admin/departments | 创建部门 |
| **角色管理** |||
| GET | /api/admin/roles | 角色列表 |
| POST | /api/admin/roles | 创建角色 |
| **权限管理** |||
| GET | /api/admin/permissions/tree | 权限树 |
| POST | /api/admin/permissions | 创建权限 |
| **元数据** |||
| GET | /api/admin/metadata/databases | 数据库列表 |
| GET | /api/admin/metadata/tables | 表列表 |
| GET | /api/admin/metadata/columns | 字段列表 |
| POST | /api/admin/metadata/scan | 触发元数据扫描 |
| GET | /api/admin/metadata/scan/tasks | 扫描任务列表 |
| **知识库** |||
| GET | /api/admin/knowledge-docs | skills.md 列表 |
| POST | /api/admin/knowledge-docs/generate-from-snapshot | 触发 skills.md 生成 |
| **Prompt 管理** |||
| GET | /api/admin/prompt-templates | Prompt 列表 |
| POST | /api/admin/prompt-templates | 创建 Prompt |
| PUT | /api/admin/prompt-templates/{id} | 编辑 Prompt |

## Key Security Constraints

- 业务库连接必须使用只读账号，密码 AES-256 加密存储
- SQL 执行前必须经过 AST 安全校验（仅允许 SELECT，禁止危险函数）
- 强制 LIMIT 10000、查询超时 30s、子查询最大 3 层嵌套
- SENSITIVE 字段可查但必须脱敏（Java 网关层执行），DEPRECATED/BLOCKED 禁止召回和执行
- Java→Python 生产环境通信必须 HTTPS/mTLS
- Prompt 注入多层防护：输入预处理 + Role 隔离 + AST 兜底
- JWT 黑名单存 Redis，退出即失效
- 行列级权限在 Python sandbox/rewriter.py 的 AST 层强制执行

## Development Commands

```bash
# 前端
cd frontend && npm install && npm run dev

# Java 后端
cd backend && mvn spring-boot:run

# Python AI 服务
cd python-service && uv run uvicorn dataocean.main:app --reload --port 8000

# Docker 一键启动基础设施
docker compose up -d
```

## Frontend Routing Structure

> 共 37 个路由，路由守卫：未登录→/login，未改密→/change-password?forced=1，/admin 需 admin 权限，子路由通过 meta.permission 细粒度校验。

```
/                                — 重定向 → /query
/login                           — 登录页 (LoginPage.vue)
/change-password                 — 修改密码 (ChangePassword.vue)
/profile                         — 个人资料 (ProfileView.vue)
/query                           — 智能问答主页面 (QueryDatasourceView.vue)
/guide/query                     — 智能查询引导页 (QueryGuide.vue)
/guide/admin                     — 管理员引导页 (AdminGuide.vue)

/admin                           — 工作台首页 (AdminHomeView.vue)
  ├─ /admin/users                — 用户管理 (UserList.vue)
  ├─ /admin/roles                — 角色管理 (RoleList.vue)
  ├─ /admin/departments          — 部门管理 (DepartmentTree.vue)
  ├─ /admin/datasources          — 数据源管理 (DatasourceList.vue)
  ├─ /admin/metadata/sync        — 同步任务 (SyncTask.vue)
  ├─ /admin/metadata/snapshots   — 快照列表 (SnapshotList.vue)
  ├─ /admin/metadata/tables      — 表浏览器 (TableExplorer.vue)
  ├─ /admin/metadata/diff        — 快照差异对比 (SnapshotDiff.vue)
  ├─ /admin/metadata/schedule    — 同步调度配置 (SyncSchedule.vue)
  ├─ /admin/metadata/lifecycle   — 快照生命周期 (SnapshotLifecycle.vue)
  ├─ /admin/metadata/version-history — 版本历史 (VersionHistory.vue)
  ├─ /admin/governance/quality   — 质量看板 (QualityDashboard.vue)
  ├─ /admin/governance/issues    — 治理问题清单 (IssueList.vue)
  ├─ /admin/governance/status    — 治理状态编辑 (StatusEditor.vue)
  ├─ /admin/knowledge            — 知识库总览 (KnowledgeDashboard.vue)
  ├─ /admin/knowledge/editor/:id? — Skills 编辑器 (SkillsEditor.vue)
  ├─ /admin/knowledge/versions/:id — 知识版本历史 (VersionList.vue)
  ├─ /admin/knowledge/review     — 知识审核页 (ReviewPage.vue)
  ├─ /admin/prompts              — Prompt 管理 (PromptManager.vue)
  ├─ /admin/field/tags           — 字段标签管理 (FieldTagManager.vue)
  ├─ /admin/field/confidence     — 字段可信度看板 (ConfidenceDashboard.vue)
  ├─ /admin/field/feedback-review — 反馈审核 (FeedbackReview.vue)
  ├─ /admin/audit/logs           — 审计日志列表 (AuditLogList.vue)
  ├─ /admin/audit/slow-queries   — 慢查询列表 (SlowQueryList.vue)
  ├─ /admin/audit/lineage        — 血缘查看器 (LineageViewer.vue)
  ├─ /admin/permission/access    — 访问控制 (AccessControl.vue)
  ├─ /admin/permission/policies  — 策略编辑器 (PolicyEditor.vue)
  ├─ /admin/system/health        — 服务健康监控 (ServiceHealth.vue)
  └─ /admin/system/ai-config     — AI 配置管理 (AiConfig.vue)

/:pathMatch(.*)*                 — 404 页面 (NotFound.vue)
```

## Database Migrations

> 共 35 个 Flyway 迁移脚本（V1-V35），路径：`backend/DataOcean/src/main/resources/db/migration/`

| 版本 | 说明 |
|------|------|
| V1-V3 | 用户表、角色初始化、密码变更标记 |
| V4-V6 | 数据源表、数据源注释、系统表注释 |
| V7-V9 | 元数据表、元数据权限、表索引信息 |
| V10 | 系统配置表 |
| V11-V12 | 治理表、快照审核日志 |
| V13-V14 | 知识库表、向量任务版本元数据 |
| V15 | 查询会话表 |
| V16-V19 | 字段标签表、预定义标签初始化、用户反馈唯一索引、字段标签权限 |
| V20-V22 | 审计表、配额表、通知操作日志 |
| V23-V24 | Prompt 表、Prompt 模板初始化 |
| V25 | 权限安全表 |
| V26-V29 | 查询任务脱敏字段、角色管理权限、Prompt 版本审计、查询任务进度 |

## Frontend API Modules

> 共 17 个 API 文件，路径：`frontend/src/api/`

```
api/
├── http.ts              — Axios 实例封装（请求/响应拦截器）
├── auth.ts              — 认证（登录/登出/Token）
├── datasource.ts        — 数据源（用户侧）
├── query.ts             — 智能查询
├── types.ts             — 公共 TypeScript 类型定义
└── admin/
    ├── audit.ts         — 审计日志/慢查询
    ├── dashboard.ts     — 管理后台看板数据
    ├── datasource.ts    — 数据源管理
    ├── field.ts         — 字段标签/可信度
    ├── governance.ts    — 元数据治理
    ├── knowledge.ts     — 知识库管理
    ├── metadata.ts      — 元数据同步/快照
    ├── permission.ts    — 权限/策略
    ├── prompt.ts        — Prompt 管理
    ├── system.ts        — 系统健康监控
    ├── user.ts          — 用户/角色/部门
    └── versioning.ts    — 版本管理
```

## Frontend Components

> 共 2 个公共组件，路径：`frontend/src/components/`

```
components/
├── AppShell.vue             — 管理后台整体布局骨架（侧边栏+顶栏+内容区）
└── chart/
    └── ChartContainer.vue   — 图表容器（统一渲染壳）
```

## Backend Package Structure

> 实际代码使用语义名称组织模块，与 spec 编号的映射见 Module Breakdown 表。

```
backend/DataOcean/src/main/java/com/dataocean/
├── common/                    — 公共模块（异常/常量/工具/基础类）
├── module/                    — 业务模块（共 13 个，350 个 Java 文件）
│   ├── user/                 — 用户管理（spec 001 + 015 权限部分）
│   │   └── controller: Auth/Department/Role/User
│   ├── datasource/           — 数据源管理（spec 002）
│   │   └── client: PythonPoolClient, scheduler: HealthCheck
│   ├── metadata/             — 元数据采集（spec 003）
│   │   └── collector: Column/Index/Relation/Statistics/Table, scheduler: AutoSync
│   ├── query/                — NL2SQL 查询（spec 008 Java 端）
│   │   └── client: PythonAgentClient, enums: QueryTaskStatus
│   ├── fieldtag/             — 字段标签与可信度（spec 010）
│   │   └── controller: FeedbackReview/FieldAdmin/FieldConfidence/FieldTag/UserFeedback
│   ├── governance/           — 元数据治理（spec 004）
│   │   └── checker: Accuracy/Completeness/Consistency/Timeliness/Traceability/Quality
│   ├── audit/                — 审计与配额（spec 011）
│   │   └── controller: Alert/AuditLog/Lineage/Quota
│   ├── permission/           — 权限与数据脱敏（spec 015 数据部分）
│   │   └── enums: AccessType/MaskStrategy/SubjectType
│   ├── knowledge/            — 知识库管理（spec 006）
│   │   └── client: PythonKnowledge/PythonRag, scheduler: VectorIndexTask, support: KnowledgeDependencySnapshotBuilder
│   ├── prompt/               — Prompt 模板管理（spec 014）
│   │   └── controller: PromptInternal/PromptTemplate
│   ├── versioning/           — 快照版本管理（spec 005）
│   │   └── event: SnapshotPublished/Expired, service: Lifecycle/Publish/AuditLog
│   ├── system/               — 系统管理（通知/操作日志/同步调度/AOP 审计）
│   │   └── aspect: AdminAuditLog/OperationLogAspect, controller: Notification/OperationLog/SyncSchedule
│   └── dashboard/            — 仪表盘统计（聚合展示，无独立表）
│       └── controller: Dashboard
└── DataOceanApplication.java — 启动类
```

每个模块内部结构：
```
module/xxx/
├── controller/    — REST 控制器
├── service/       — 服务接口
│   └── impl/      — 服务实现
├── mapper/        — MyBatis-Plus Mapper
├── entity/        — 数据库实体
│   ├── dto/       — 数据传输对象
│   ├── vo/        — 视图对象
│   └── query/     — 查询对象
└── client/        — 外部服务调用客户端（如 PythonPoolClient）
    └── impl/      — 客户端实现
```

## Python Service Structure

> 7 个路由模块，统一 `/internal/` 前缀。依赖：FastAPI + LangGraph + LangChain + SQLAlchemy + pymilvus + sqlglot + Jinja2 + DashScope (Qwen)。

```
python-service/dataocean/
├── main.py                        — FastAPI 入口，路由注册 + 生命周期
├── core/                          — 核心基础层
│   ├── config.py                 — 统一配置（pydantic-settings, @lru_cache 单例）
│   ├── exceptions.py             — ServiceException(500)/LLMException(502)/ValidationException(400)
│   └── logging.py                — 统一日志格式
├── infra/                         — 基础设施层（中性，无业务依赖）
│   ├── llm.py                    — LangChain ChatOpenAI → DashScope，按 (model, temperature) 缓存
│   ├── embeddings.py             — OpenAIEmbeddings (text-embedding-v4, 1024 维)
│   ├── sse.py                    — SSE 传输层（队列 + 15s 心跳 + 120s 超时）
│   ├── parsers.py                — SqlOutputParser / JsonBlockOutputParser / LinesOutputParser
│   ├── cancellation.py           — 内存取消令牌（TTL 1h 自动清理）
│   ├── timeout_budget.py         — 时间预算管理器（总 100s，按节点分配）
│   └── health.py                 — /health 端点（Milvus + LLM 探活）
├── agent/                         — NL2SQL Agent 工作流（spec 008）
│   ├── graph.py                  — LangGraph StateGraph（6 节点 + 条件路由 + 重试）
│   ├── state.py                  — AgentState TypedDict
│   ├── schema.py                 — ExecuteRequest/QueryResult（camelCase + snake_case 兼容）
│   ├── router.py                 — HTTP 路由（/execute, /cancel, /health）
│   ├── sse.py                    — 业务层 SSE 事件封装
│   ├── config.py                 — Agent 工作流配置
│   ├── prompt_tracking.py        — Prompt 版本追踪
│   ├── nodes/                    — 工作流节点实现
│   │   ├── query_rewriter.py     — 时间解析/指代消解/意图提取
│   │   ├── schema_retriever.py   — RAG 语义检索（Milvus 不可用时自动降级）
│   │   ├── sql_generator.py      — LLM 生成 SELECT（支持 Prompt 管理模板降级到本地 .j2）
│   │   ├── sql_validator.py      — AST 安全校验 + 权限改写
│   │   ├── sql_executor.py       — 沙箱只读执行
│   │   └── data_visualizer.py    — ECharts 生成 + 追问建议
│   └── prompts/                  — Jinja2 Prompt 模板
│       ├── query_rewrite.j2
│       └── sql_generation.j2
├── sandbox/                       — SQL 安全沙箱（spec 009）
│   ├── config.py                 — 沙箱安全配置
│   ├── validator.py              — AST 校验引擎（规则链聚合 + 注入检测）
│   ├── rewriter.py               — AST 改写（行过滤/列检查/LIMIT/脱敏标记）
│   ├── executor.py               — 只读事务 + max_execution_time + KILL QUERY
│   ├── pool_manager.py           — 连接池管理（按 datasource_id + AES-256-CBC 解密）
│   ├── router.py                 — HTTP 路由（/validate, /execute, /pools）
│   ├── schema.py                 — 请求/响应模型
│   └── rules/                    — 6 条独立校验规则
│       ├── statement_rule.py     — 仅允许 SELECT
│       ├── function_rule.py      — 危险函数黑名单
│       ├── depth_rule.py         — 子查询嵌套深度（最大 3 层）
│       ├── star_rule.py          — 禁止 SELECT *
│       ├── table_rule.py         — 表白名单校验
│       └── limit_rule.py         — LIMIT 上限校验
├── rag/                           — RAG 检索增强生成（spec 007）
│   ├── milvus_client.py          — Milvus 连接管理
│   ├── init_collection.py        — Collection 初始化（13 字段，IVF_FLAT 索引）
│   ├── retriever.py              — 语义检索（数据源隔离 + 准入过滤）
│   ├── reranker.py               — 规则加权重排（表名命中/可信度/废弃惩罚/chunk_type 意图加权/RECOMMENDED 加分）
│   ├── chunker.py                — 分块策略（表级 + skills.md 按三级标题细粒度切分，支持表名提取）
│   ├── vectorizer.py             — 向量化写入（批量 embedding + 版本切换）
│   ├── fallback.py               — Milvus 不可用时的兜底检索
│   ├── service.py                — RAG 业务编排（embedding→检索→重排→阈值过滤）
│   ├── router.py                 — HTTP 路由（/vectorize, /retrieve, /vectors）
│   └── schema.py                 — 请求/响应模型（camelCase 兼容）
├── chart/                         — 图表生成（spec 016）
│   ├── service.py                — LLM 生成 ECharts option
│   ├── data_aggregator.py        — 数据聚合（时间序列/分类 Top10/数值分桶）
│   ├── chart_validator.py        — ECharts Option JSON 校验
│   └── router.py                 — HTTP 路由（/generate）
├── knowledge/                     — 知识库（spec 006 Python 端）
│   ├── service.py                — LLM 生成 skills.md 草稿
│   ├── schema.py                 — 请求/响应模型
│   ├── router.py                 — HTTP 路由（/generate-draft）
│   └── prompts/
│       └── skills_md_template.j2 — skills.md 生成模板
└── prompt/                        — Prompt 管理（spec 014 Python 端）
    ├── service.py                — 从 Java 获取模板 + Token 预算裁剪 + 渲染
    ├── renderer.py               — Jinja2 渲染器（LangChain PromptTemplate）
    ├── token_budget.py           — Token 预算控制（5000 总量，skills=1500/schema=1500 同级优先，按优先级裁剪）
    └── router.py                 — HTTP 路由（/get, /render）
```

## Conventions

- Java 包名: com.dataocean.*
- Python 模块: dataocean.*
- 数据库迁移: backend/src/main/resources/db/migration/V{version}__{description}.sql
- API 前缀: /api/* (用户端), /api/admin/* (管理端), /internal/* (Java↔Python 内部)
- 环境变量: QWEN_API_KEY, QWEN_MODEL, QWEN_EMBEDDING_MODEL, EMBEDDING_DIMENSION, MILVUS_HOST, REDIS_HOST
- Python 单实例部署（MVP），CancellationToken 在 dataocean/agent/cancellation.py 中统一管理
- 权限注入统一由 Python sandbox/rewriter.py 执行（AST 层强制），Java permission/ 模块只负责权限数据 CRUD 和传递
- 重要模块开发完成后，同步更新 `AGENTS.md` 和必要的 `README.md` 内容，保证文档跟着实际开发进度走。
- 前后端联调时，每验证一个用户可见功能都要保留截图证据，截图命名要能看出模块和功能。

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the relevant module plan
under specs/<module>/plan.md.
<!-- SPECKIT END -->

## Local Tool Paths

- Maven: `D:\tool\apache-maven-3.9.16`
- When Maven is needed on this machine, use `D:\tool\apache-maven-3.9.16\bin\mvn.cmd` or add `D:\tool\apache-maven-3.9.16\bin` to `PATH` for the current shell session.
- Backend Maven project path: `backend\DataOcean`

## Local Environment Rules

- Do not add project downloads, generated assets, dependency caches, exported files, or temporary project files to `C:\`.
- Keep project-related downloaded/generated files under `D:\Java_study\GraduationProject` unless they are required by developer tooling such as Codex or Codex.
- Before introducing any new Docker container or infrastructure service, tell the user what container is needed and why, then wait for confirmation.
- Local Docker currently has MySQL, Redis, Elasticsearch, Kibana, RabbitMQ, Nacos, and Seata containers available. Treat exact local credentials as private local notes, not repository documentation.
- 本项目当前没有 Figma 原型，默认不要使用 Figma 相关 workflow 或 skills。前端排版改造优先使用 `dataocean-admin-ui-layout` skill，并参考成熟管理后台布局进行本地实现和截图验证。

## Database Rules

- 不创建数据库级外键约束。业务表保留 `department_id`、`user_id`、`role_id` 等关联字段，按需添加普通索引，关系有效性统一在 service 层业务逻辑中校验。

## Backend Layering Rules

- 实体相关对象统一收拢在 `entity` 下：数据库实体直接放 `entity`，请求/传递参数放 `entity.dto`（类名后缀 `*DTO`），查询对象放 `entity.query`（类名后缀 `*Query`），返回/视图对象放 `entity.vo`（类名后缀 `*VO`）。
- Mapper 放在 `mapper`，Controller 放在 `controller`，Service 接口放在 `service`，实现类放在 `service.impl`。
- 外部服务调用客户端（如 PythonPoolClient）放在模块内的 `client` 包，实现类放 `client.impl`，不要混入 `service` 包。
- 每个 service 都必须在 `service` 下暴露接口，实现类放在 `service.impl`，并使用 `*ServiceImpl` 后缀。
- 抛出异常的提示、代码注释、日志消息都使用中文。日志中不要输出密码、JWT 原文、密钥等敏感值。
