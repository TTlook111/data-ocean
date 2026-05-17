# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

DataOcean — 企业级 NL2SQL 智能数据查询与治理平台（毕业设计项目）。让业务人员通过自然语言查询企业数据库，AI 生成 SQL 并返回表格/图表结果，核心强调元数据治理驱动的可信查询。

当前阶段：设计与规格文档阶段，尚未进入可运行工程实现。

当前仓库状态（2026-05-17）：
- `docs/` 与 `specs/` 已包含项目构想、设计文档和 17 个模块规格。
- `frontend/`、`backend/`、`python-service/` 目前是占位目录，尚未包含 `package.json`、`pom.xml`、`pyproject.toml` 等可运行工程脚手架。
- 仓库根目录当前没有 `docker-compose.yml`，不要假设 Docker 一键启动命令已经可用。
- 后续实现时应先检查目标目录内的实际配置文件，再运行对应启动、测试或构建命令。

MVP 范围：多数据源接入，限定单库多表查询（用户每次选择一个 MySQL 数据源，在该库内多表联合查询）。

## Architecture

三层分离架构，前端统一调 Java，Java 内部代理调 Python：

```
Vue 3 前端 (C端问答 + B端治理)
    │ HTTP API
    ▼
Java 网关层 (Spring Boot 3.x + JDK 17)
  - 鉴权/权限/数据源管理/skills.md管理/可信度/审计
    │ 内部 HTTP (OpenFeign → FastAPI)
    ▼
Python AI 服务 (Python 3.13 + FastAPI + LangGraph + LlamaIndex)
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
- LangGraph 编排 Agent 工作流（Schema_Retriever → SQL_Generator → SQL_Validator → SQL_Executor → Data_Visualizer），失败最多重试 3 次
- LlamaIndex 封装 RAG 层（向量检索 + BM25 + 模板召回的 Hybrid Search）
- 行列级权限在 SQL AST 层强制执行，不依赖 Prompt
- 查询结果不缓存（避免相似问题、相对时间和权限差异导致错误复用）

## Core Domain Concepts

- **skills.md**: 业务语义说明书，由元数据治理结果生成草稿 → 人工审核 → 发布后向量化进入 RAG。不是普通文档，是"元数据治理结果的发布形态"
- **字段可信度**: 0-100 数值评分，影响 SQL 生成时的字段选择优先级。来源于 skills.md 定义、人工确认、查询反馈动态调整
- **元数据治理闭环**: 采集 → 质量校验 → 问题修正 → 审核发布快照 → 生成 skills.md → 向量化 → 查询血缘反馈回流
- **RAG 准入控制**: 只有 governance_status 为 NORMAL/RECOMMENDED 且 review_status 为 APPROVED 的内容才能进入向量库被召回
- **Schema RAG**: 解决单库表数量过多（几百上千张）时的上下文过载问题，精准召回 Top 5-10 张相关表

## Three Main Lines

1. **可信数据依据线**: 数据源接入 → 元数据采集 → 元数据治理 → 快照 → skills.md → RAG
2. **智能查询执行线**: 用户提问 → RAG 召回 → SQL 生成 → 安全校验 → 执行 → 图表和解释
3. **治理反馈闭环线**: 查询审计 → 字段可信度 → 用户反馈 → 血缘分析 → 元数据和知识库修正

第一条线是基础，元数据治理正确后面才有可信基础。

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
frontend/              — Vue 3 前端项目占位目录
backend/               — Spring Boot Java 网关层占位目录
python-service/        — Python AI 服务占位目录 (FastAPI + LangGraph + LlamaIndex)
docs/                  — 项目设计文档
specs/                 — 模块规格说明（17 个模块）
.specify/              — Spec Kit 配置与模板
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

## Key Security Constraints

- 业务库连接必须使用只读账号，密码 AES-256 加密存储
- SQL 执行前必须经过 AST 安全校验（仅允许 SELECT，禁止危险函数）
- 强制 LIMIT 10000、查询超时 30s、子查询最大 3 层嵌套
- 敏感字段脱敏，废弃/阻断字段不进入 RAG
- Java→Python 通信生产环境必须 HTTPS/mTLS
- Prompt 注入多层防护：输入预处理 + Role 隔离 + AST 兜底
- JWT 黑名单存 Redis，退出即失效

## Key API Endpoints

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/auth/login | 登录返回 JWT |
| GET | /api/auth/me | 当前用户信息 |
| POST | /api/query/ask | 发起查询（异步，返回 taskId） |
| GET | /api/query/stream/{taskId} | SSE 流式进度 |
| GET | /api/query/tasks/{id} | 轮询降级方案 |
| POST | /api/query/tasks/{id}/cancel | 取消查询 |

## Development Commands

当前仓库尚未创建可运行脚手架。下面是 MVP 目标命令，只有在对应配置文件存在后才应执行。

```bash
# 前端
cd frontend && npm install && npm run dev

# Java 后端
cd backend && mvn spring-boot:run

# Python AI 服务
cd python-service && uv run fastapi dev

# Docker 一键启动
docker compose up -d
```

当前阶段常用检查命令：

```bash
rg --files
git status --short
```

## Conventions

- Java 包名: com.dataocean.*
- Python 模块: dataocean.*
- 数据库迁移: backend/src/main/resources/db/migration/V{version}__{description}.sql
- API 前缀: /api/* (用户端), /api/admin/* (管理端), /internal/* (Java→Python 内部)
- 环境变量: QWEN_API_KEY, QWEN_MODEL, QWEN_EMBEDDING_MODEL, EMBEDDING_DIMENSION, MILVUS_HOST, REDIS_HOST

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the relevant module plan
under specs/<module>/plan.md. The first module plan is specs/001-user-module/plan.md.
<!-- SPECKIT END -->
