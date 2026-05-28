<p align="center">
  <img src="docs/images/dataocean-banner.png" alt="DataOcean" width="100%"/>
</p>

<h1 align="center">DataOcean</h1>

<p align="center">
  <strong>面向企业数据团队的治理型 NL2SQL 平台</strong><br/>
  用自然语言查询数据库，同时保留元数据治理、权限边界、SQL 安全沙箱和结果可追溯性。
</p>

<p align="center">
  <a href="#快速开始"><strong>快速开始</strong></a> ·
  <a href="#为什么是-dataocean"><strong>为什么是 DataOcean</strong></a> ·
  <a href="#核心能力"><strong>核心能力</strong></a> ·
  <a href="#系统架构"><strong>系统架构</strong></a> ·
  <a href="#文档导航"><strong>文档导航</strong></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-f97316?style=flat-square" alt="Java 17"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.3.5-6db33f?style=flat-square" alt="Spring Boot 3.3.5"/>
  <img src="https://img.shields.io/badge/Vue-3-42b883?style=flat-square" alt="Vue 3"/>
  <img src="https://img.shields.io/badge/Python-3.13-3776ab?style=flat-square" alt="Python 3.13"/>
  <img src="https://img.shields.io/badge/Milvus-2.5-00a1ea?style=flat-square" alt="Milvus 2.5"/>
  <img src="https://img.shields.io/badge/License-MIT-111827?style=flat-square" alt="MIT License"/>
</p>

---

## DataOcean 是什么？

DataOcean 是一个面向企业内部数据分析场景的 NL2SQL 平台。业务人员可以像聊天一样提出数据问题，系统会基于已治理的元数据召回相关表字段，生成 SQL，通过安全沙箱校验后执行，并返回表格、图表、SQL 和口径说明。

它不是一个简单的 Text-to-SQL Demo。DataOcean 的核心原则是：

> **AI 只能在可信元数据、明确权限和可审计执行链路内查询数据。**

## 为什么是 DataOcean

很多 NL2SQL 系统只解决“把问题变成 SQL”，但企业真实环境里还要解决可信、安全、权限、审计和治理。

| 企业数据查询中的问题 | DataOcean 的回答 |
| --- | --- |
| 业务人员不会写 SQL，每次取数都依赖开发 | 在 `/query` 查询工作区选择数据源，用中文直接提问 |
| 大模型容易选错表、编造字段或误解业务语义 | 只从已发布的元数据快照和 `skills.md` 业务知识中召回上下文 |
| 生成 SQL 不透明，不知道是否安全 | SQL 会被解析、校验、强制限制、只读执行，并作为查询任务持久化 |
| 敏感字段、权限边界不能只靠 Prompt 约束 | 数据源授权、行列级权限、字段脱敏和禁止字段在服务端生效 |
| 元数据质量会变化，需要治理流程兜底 | 采集、质检、问题处理、审核、发布、撤回、审计形成闭环 |

## 产品流程

<p align="center">
  <img src="docs/images/dataocean-core-flow.png" alt="DataOcean 查询流程" width="88%"/>
</p>

```text
自然语言提问
  -> 改写查询意图
  -> 召回可信 Schema
  -> 生成 SQL
  -> 通过安全沙箱校验
  -> 按权限执行查询
  -> 返回表格、图表、SQL 与口径说明
```

## 核心能力

| | |
| --- | --- |
| **自然语言查询** | 类似对话的查询工作区，支持按数据源隔离历史，并展示表格、SQL、图表等结果。 |
| **元数据治理** | 支持元数据采集、质量评分、问题处理、生命周期审核、发布、撤回和版本历史。 |
| **Schema RAG** | 基于 Milvus 检索已发布的表结构与业务知识，在向量检索不可用时提供降级上下文。 |
| **SQL 安全沙箱** | 基于 `sqlglot` 做 AST 校验、SELECT-only 限制、LIMIT 注入、只读执行、超时和取消。 |
| **权限与脱敏** | 支持数据源授权、行列级策略、敏感字段脱敏和禁止字段拦截。 |
| **可解释结果** | 返回使用的表、字段、字段可信度、图表配置、口径说明和后续建议问题。 |

## 快速开始

### 环境要求

| 运行环境 | 建议版本 |
| --- | --- |
| JDK | 17 |
| Node.js | 20+ |
| Python | 3.13 |
| Docker | Compose v2 |
| Maven | 3.9+ |
| uv | latest stable |

### 1. 启动基础设施

```powershell
docker compose up -d mysql redis etcd minio milvus
```

默认本地服务：

```text
MySQL   127.0.0.1:3307
Redis   127.0.0.1:6379
Milvus  127.0.0.1:19530
MinIO   http://127.0.0.1:9001
```

### 2. 启动 Java 网关

```powershell
cd backend\DataOcean
$env:DB_PASSWORD = "asd123"
$env:REDIS_PASSWORD = "asd123"
mvn spring-boot:run
```

Java 网关地址：`http://127.0.0.1:8080`

### 3. 启动 Python AI 服务

```powershell
cd python-service
Copy-Item .env.example .env
# 在 .env 中填写 DASHSCOPE_API_KEY
uv sync
uv run uvicorn dataocean.main:app --reload --port 8000
```

AI 服务地址：`http://127.0.0.1:8000`

### 4. 启动前端

```powershell
cd frontend
npm install
npm run dev
```

打开 `http://127.0.0.1:5173`。登录后普通用户进入 `/query` 查询工作区；有后台权限的用户可以从右上角用户入口进入 `/admin/*` 治理后台。

## 系统架构

<p align="center">
  <img src="docs/images/dataocean-architecture.png" alt="DataOcean 系统架构" width="86%"/>
</p>

DataOcean 分为三层：

| 层级 | 职责 | 技术栈 |
| --- | --- | --- |
| 前端应用 | 查询工作区、治理后台、结果展示、权限化导航 | Vue 3, Vite, TypeScript, Element Plus, Pinia, ECharts |
| Java 网关 | 认证鉴权、用户角色、数据源、元数据治理、审计、任务状态 | Spring Boot, Spring Security, MyBatis-Plus, Flyway, Redis |
| Python AI 服务 | NL2SQL 工作流、Schema RAG、SQL 沙箱、图表生成、降级处理 | FastAPI, LangGraph, LlamaIndex, sqlglot, pymilvus, DashScope |

## 治理闭环

<p align="center">
  <img src="docs/images/dataocean-governance-cycle.png" alt="DataOcean 治理闭环" width="68%"/>
</p>

只有已发布的元数据才能进入查询链路：

```text
接入数据源
  -> 采集 Schema
  -> 执行质量校验
  -> 处理治理问题
  -> 审核并发布快照
  -> 生成 skills.md
  -> 向量化知识
  -> 查询时召回
  -> 审计与反馈回流
```

## 安全模型

<p align="center">
  <img src="docs/images/dataocean-security-shield.png" alt="DataOcean 安全模型" width="54%"/>
</p>

| 边界 | 防护方式 |
| --- | --- |
| 认证 | JWT + Redis 黑名单，退出登录后令牌立即失效 |
| 数据源 | 连接密钥加密存储，建议业务库使用只读账号 |
| SQL | SELECT-only AST 校验、危险语句阻断、强制 LIMIT |
| 执行 | 超时、取消、连接池保护和友好降级 |
| 权限 | 数据源授权、行列级策略、敏感标签和禁止字段 |
| 审计 | 查询任务状态、SQL、结果元信息、反馈、血缘和慢查询记录 |

## 仓库结构

```text
DataOcean/
├── frontend/              Vue 3 前端应用
├── backend/DataOcean/     Spring Boot Java 网关
├── python-service/        FastAPI AI 服务
├── docs/                  架构说明和模块文档
├── specs/                 模块规格、接口契约和任务计划
└── docker-compose.yml     本地基础设施编排
```

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [`docs/nl2sql-项目构想.md`](docs/nl2sql-%E9%A1%B9%E7%9B%AE%E6%9E%84%E6%83%B3.md) | 项目构想与架构背景 |
| [`docs/nl2sql-功能点拆分说明.md`](docs/nl2sql-%E5%8A%9F%E8%83%BD%E7%82%B9%E6%8B%86%E5%88%86%E8%AF%B4%E6%98%8E.md) | 功能拆分与模块规划 |
| [`docs/modules/001-user.md`](docs/modules/001-user.md) | 用户、角色与权限模块 |
| [`docs/modules/002-datasource.md`](docs/modules/002-datasource.md) | 数据源管理 |
| [`docs/modules/003-metadata-collection.md`](docs/modules/003-metadata-collection.md) | 元数据采集 |
| [`docs/modules/004-metadata-governance.md`](docs/modules/004-metadata-governance.md) | 元数据治理 |
| [`docs/modules/005-metadata-versioning.md`](docs/modules/005-metadata-versioning.md) | 快照版本与发布 |
| [`docs/modules/006-knowledge.md`](docs/modules/006-knowledge.md) | `skills.md` 业务知识生成 |
| [`specs/`](specs/) | 详细规格、数据模型、API 契约和任务清单 |

<details>
<summary><strong>模块进度</strong></summary>

进度来自 `specs/*/tasks.md`，表示任务清单完成度；最终可用性仍以联调和运行验证为准。

| 模块 | 主题 | 进度 | 状态 |
| --- | --- | ---: | --- |
| 001 | 用户、角色、部门、权限 | 59/59 | 已完成 |
| 002 | 数据源管理 | 31/31 | 已完成 |
| 003 | 元数据采集 | 31/33 | 基本完成 |
| 004 | 元数据治理 | 32/32 | 已完成 |
| 005 | 快照版本与发布 | 21/21 | 已完成 |
| 006 | `skills.md` 业务知识库 | 43/43 | 已完成 |
| 007 | Schema RAG | 29/29 | 已完成 |
| 008 | NL2SQL Agent | 29/30 | 核心完成，持续联调 |
| 009 | SQL 安全沙箱 | 29/30 | 核心完成，持续联调 |
| 010 | 字段标签与可信度 | 31/31 | 已完成 |
| 011 | 血缘与审计 | 34/34 | 已完成 |
| 012 | 前端查询工作区 | 35/35 | 已完成 |
| 013 | 后台治理管理端 | 41/41 | 已完成 |
| 014 | Prompt 模板管理 | 27/30 | 基本完成 |
| 015 | 权限与安全策略 | 33/34 | 基本完成 |
| 016 | 图表生成与结果解释 | 20/20 | 已完成 |
| 017 | 错误处理与降级 | 30/30 | 已完成 |

</details>

<details>
<summary><strong>常用开发命令</strong></summary>

| 目标 | 命令 |
| --- | --- |
| 前端开发服务 | `cd frontend && npm run dev` |
| 前端构建 | `cd frontend && npm run build` |
| Java 测试 | `cd backend/DataOcean && mvn test` |
| Java 网关 | `cd backend/DataOcean && mvn spring-boot:run` |
| Python AI 服务 | `cd python-service && uv run uvicorn dataocean.main:app --reload --port 8000` |
| Python 语法检查 | `python -m compileall -q python-service/dataocean` |
| 启动基础设施 | `docker compose up -d` |
| 停止基础设施 | `docker compose down` |

</details>

## License

MIT
