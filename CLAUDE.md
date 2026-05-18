# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
    │ 内部 HTTP (OpenFeign → FastAPI)
    ▼
Python AI 服务 (Python 3.13 + FastAPI + LangGraph + LlamaIndex)
  - Query Rewrite (时间解析/指代消解/意图提取)
  - Schema RAG (LlamaIndex + Milvus)
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
- LlamaIndex 封装 RAG 层（MVP 阶段向量检索，阶段二引入 Hybrid Search）
- 行列级权限在 SQL AST 层强制执行（009 模块 rewriter.py），不依赖 Prompt
- 敏感字段：SENSITIVE 可进入 RAG（带 maskColumns），Python 标记需脱敏字段，Java 网关统一脱敏
- 查询结果不缓存（避免相似问题、相对时间和权限差异导致错误复用）

## Core Domain Concepts

- **skills.md**: 业务语义说明书，由元数据治理结果生成草稿 → 人工审核 → 发布后向量化进入 RAG
- **字段可信度**: 0-100 数值评分，影响 SQL 生成时的字段选择优先级。阶段一包含完整动态调整（反馈驱动升降）
- **元数据治理闭环**: 采集 → 质量校验 → 问题修正 → 审核发布快照 → 生成 skills.md → 向量化 → 查询血缘反馈回流
- **RAG 准入控制**: NORMAL/RECOMMENDED/SENSITIVE（带脱敏标记）且 review_status=APPROVED 可进入向量库；DEPRECATED/BLOCKED 禁止召回
- **Schema RAG**: 解决单库表数量过多时的上下文过载问题，精准召回 Top 5-10 张相关表
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
| Java HTTP 客户端 | OpenFeign |
| 定时任务 | Spring Scheduler |
| Python AI 服务 | Python 3.13 + FastAPI + LangGraph + LlamaIndex |
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
python-service/        — Python AI 服务 (FastAPI + LangGraph + LlamaIndex)
docs/                  — 项目设计文档
specs/                 — 模块规格说明（17 个模块，含 spec/plan/tasks/contracts）
```

## Module Breakdown (17 modules)

| # | 模块 | 归属层 | Spec 路径 |
|---|------|--------|-----------|
| 001 | 用户模块 | Java | specs/001-user-module/ |
| 002 | 数据源管理 | Java | specs/002-datasource-management/ |
| 003 | 元数据采集 | Java + Python | specs/003-metadata-collection/ |
| 004 | 元数据治理 | Java | specs/004-metadata-governance/ |
| 005 | 元数据版本与审核 | Java | specs/005-metadata-versioning/ |
| 006 | skills.md 知识库 | Java + Python | specs/006-skills-md-knowledge/ |
| 007 | Schema RAG | Python | specs/007-schema-rag/ |
| 008 | NL2SQL Agent | Python | specs/008-nl2sql-agent/ |
| 009 | SQL 安全沙箱 | Python | specs/009-sql-security-sandbox/ |
| 010 | 字段 Tag 与可信度 | Java | specs/010-field-tag-confidence/ |
| 011 | 血缘与审计 | Java | specs/011-lineage-audit/ |
| 012 | 前端问答端 | Frontend | specs/012-frontend-query/ |
| 013 | 后台治理管理端 | Frontend | specs/013-backend-admin/ |
| 014 | Prompt 管理 | Java + Python | specs/014-prompt-management/ |
| 015 | 权限与安全 | Java | specs/015-permission-security/ |
| 016 | 图表生成 | Python + Frontend | specs/016-chart-generation/ |
| 017 | 错误处理与降级 | Java + Python | specs/017-error-degradation/ |

## Key Internal APIs

| 服务间调用 | 路径 | 说明 |
|-----------|------|------|
| Java → Python | POST /internal/query/execute | 发起 NL2SQL 查询 |
| Java → Python | POST /internal/tasks/{taskId}/cancel | 取消查询 |
| Java → Python | POST /internal/rag/retrieve | RAG 召回 |
| Java → Python | POST /internal/rag/vectorize | 触发向量化 |
| Java → Python | POST /internal/sql/validate | SQL 安全校验 |
| Java → Python | POST /internal/sql/execute | SQL 沙箱执行 |
| Java → Python | POST /internal/chart/generate | 图表生成 |
| Python → Java | GET /internal/prompts/{code} | 获取 Prompt 模板 |

## Key Public APIs

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/auth/login | 登录返回 JWT |
| GET | /api/auth/me | 当前用户信息 |
| POST | /api/query/ask | 发起查询（异步，返回 taskId） |
| GET | /api/query/stream/{taskId} | SSE 流式进度 |
| GET | /api/query/tasks/{id} | 轮询降级方案 |
| POST | /api/query/tasks/{id}/cancel | 取消查询 |
| GET | /api/datasources | 用户可访问的数据源列表 |
| POST | /api/admin/datasources | 新增数据源 |
| GET | /api/admin/users | 用户列表 |
| POST | /api/admin/users | 创建用户 |

## Key Security Constraints

- 业务库连接必须使用只读账号，密码 AES-256 加密存储
- SQL 执行前必须经过 AST 安全校验（仅允许 SELECT，禁止危险函数）
- 强制 LIMIT 10000、查询超时 30s、子查询最大 3 层嵌套
- SENSITIVE 字段可查但必须脱敏（Java 网关层执行），DEPRECATED/BLOCKED 禁止召回和执行
- Java→Python 生产环境通信必须 HTTPS/mTLS
- Prompt 注入多层防护：输入预处理 + Role 隔离 + AST 兜底
- JWT 黑名单存 Redis，退出即失效
- 行列级权限在 009 模块 rewriter.py 的 AST 层强制执行

## Development Commands

```bash
# 前端
cd frontend && npm install && npm run dev

# Java 后端
cd backend && mvn spring-boot:run

# Python AI 服务
cd python-service && uv run fastapi dev

# Docker 一键启动基础设施
docker compose up -d
```

## Conventions

- Java 包名: com.dataocean.*
- Python 模块: dataocean.*
- 数据库迁移: backend/src/main/resources/db/migration/V{version}__{description}.sql
- API 前缀: /api/* (用户端), /api/admin/* (管理端), /internal/* (Java↔Python 内部)
- 环境变量: QWEN_API_KEY, QWEN_MODEL, QWEN_EMBEDDING_MODEL, EMBEDDING_DIMENSION, MILVUS_HOST, REDIS_HOST
- Python 单实例部署（MVP），CancellationToken 在 dataocean/agent/cancellation.py 中统一管理
- 权限注入统一由 009 模块 rewriter.py 执行，015 模块只负责权限数据 CRUD 和传递

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
- Keep project-related downloaded/generated files under `D:\Java_study\GraduationProject` unless they are required by developer tooling such as Claude Code or Codex.
- Before introducing any new Docker container or infrastructure service, tell the user what container is needed and why, then wait for confirmation.
- Local Docker currently has MySQL, Redis, Elasticsearch, Kibana, RabbitMQ, Nacos, and Seata containers available. Treat exact local credentials as private local notes, not repository documentation.

## Database Rules

- 不创建数据库级外键约束。业务表保留 `department_id`、`user_id`、`role_id` 等关联字段，按需添加普通索引，关系有效性统一在 service 层业务逻辑中校验。

## Backend Layering Rules

- 实体相关对象统一收拢在 `entity` 下：数据库实体直接放 `entity`，请求对象放 `entity.req`，查询对象放 `entity.query`，返回/视图对象放 `entity.vo`；Mapper 放在 `mapper`，Controller 放在 `controller`，Service 接口放在 `service`，实现类放在 `service.impl`。
- 每个 service 都必须在 `service` 下暴露接口，实现类放在 `service.impl`，并使用 `*ServiceImpl` 后缀。
- 抛出异常的提示、代码注释、日志消息都使用中文。日志中不要输出密码、JWT 原文、密钥等敏感值。
