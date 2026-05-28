<p align="center">
  <img src="docs/images/dataocean-banner.png" alt="DataOcean Banner" width="100%"/>
</p>

<h1 align="center">DataOcean</h1>

<p align="center">
  <strong>治理驱动的企业级 NL2SQL 智能数据查询平台</strong><br/>
  让业务人员用自然语言查询数据库，让 AI 在可信元数据、权限边界和 SQL 沙箱内完成分析。
</p>

<p align="center">
  <a href="#核心能力">核心能力</a> ·
  <a href="#系统架构">系统架构</a> ·
  <a href="#快速开始">快速开始</a> ·
  <a href="#模块进度">模块进度</a> ·
  <a href="#文档导航">文档导航</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-f97316?style=flat-square" alt="Java 17"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.3.5-6db33f?style=flat-square" alt="Spring Boot 3.3.5"/>
  <img src="https://img.shields.io/badge/Vue-3-42b883?style=flat-square" alt="Vue 3"/>
  <img src="https://img.shields.io/badge/Python-3.13-3776ab?style=flat-square" alt="Python 3.13"/>
  <img src="https://img.shields.io/badge/MySQL-8-4479a1?style=flat-square" alt="MySQL 8"/>
  <img src="https://img.shields.io/badge/Milvus-2.5-00a1ea?style=flat-square" alt="Milvus 2.5"/>
  <img src="https://img.shields.io/badge/License-MIT-111827?style=flat-square" alt="MIT License"/>
</p>

---

## 项目定位

DataOcean 是一个面向企业内部数据分析场景的智能查询平台。它把自然语言问答、Schema RAG、元数据治理、SQL 安全沙箱、行列级权限和图表生成串成一个闭环，目标不是简单地“让大模型写 SQL”，而是让大模型只能基于经过治理、审核和授权的数据语义来生成查询。

一句话概括：

> DataOcean = 可信元数据治理 + Schema RAG + NL2SQL Agent + SQL 安全沙箱 + 数据可视化。

它适合这些场景：

| 场景 | DataOcean 的处理方式 |
| --- | --- |
| 业务人员不会写 SQL，却需要快速取数 | 在 `/query` 中选择数据源，用中文直接提问 |
| 数据库表多、字段语义复杂，模型容易选错表 | 通过发布后的 `skills.md` 与向量索引召回相关表字段 |
| 生成 SQL 不可控，存在注入、越权、误删风险 | SQL AST 校验、只读执行、强制 LIMIT、字段脱敏 |
| 元数据质量不稳定，字段注释和业务定义缺失 | 采集、质量校验、问题处理、审核发布形成治理闭环 |
| 查询结果需要解释和展示 | 返回表格、SQL、口径说明与 ECharts 图表配置 |

## 核心能力

| 能力 | 说明 |
| --- | --- |
| 自然语言查询 | 面向业务用户的聊天式查询工作区，数据源与会话历史隔离 |
| 元数据治理 | 支持 Schema 采集、质量校验、问题处理、版本发布与撤回 |
| Schema RAG | 仅将已发布快照与知识文档向量化，降低错误召回概率 |
| NL2SQL Agent | 使用 LangGraph 编排 Query Rewrite、Schema Retrieval、SQL Generation、Execution、Visualization |
| SQL 安全沙箱 | 基于 AST 的 SELECT-only 校验、LIMIT 注入、危险语句阻断与只读执行 |
| 权限与脱敏 | 数据源授权、行列级权限、敏感字段脱敏、禁止字段阻断 |
| 图表与口径说明 | 自动生成 ECharts 配置，并展示使用表、字段和可信度提示 |
| 容错降级 | Python 服务健康检查、Milvus 降级、LLM 超时友好提示与任务取消 |

## 系统架构

<p align="center">
  <img src="docs/images/dataocean-architecture.png" alt="DataOcean 系统架构" width="86%"/>
</p>

DataOcean 采用三层服务拆分：

| 层级 | 职责 | 技术 |
| --- | --- | --- |
| 前端应用 | 查询工作区、治理后台、权限化导航与结果展示 | Vue 3, Vite, TypeScript, Element Plus, ECharts, Pinia |
| Java 网关 | 认证鉴权、用户与角色、数据源、元数据治理、审计、任务状态 | Spring Boot 3.3.5, Spring Security, MyBatis-Plus, Flyway, Redis |
| Python AI 服务 | NL2SQL 工作流、Schema RAG、SQL 沙箱、图表生成、降级处理 | FastAPI, LangGraph, LlamaIndex, sqlglot, pymilvus, DashScope |

基础设施由 Docker Compose 提供：

```text
MySQL 8     管理库与业务库连接验证
Redis 7     登录状态、黑名单、限流、任务状态
etcd        Milvus 元数据依赖
MinIO       Milvus 对象存储依赖
Milvus 2.5  Schema RAG 向量检索
```

## 核心流程

<p align="center">
  <img src="docs/images/dataocean-core-flow.png" alt="DataOcean 查询流程" width="88%"/>
</p>

一次完整查询大致经过：

1. 用户在 `/query` 选择已授权数据源并输入中文问题。
2. Java 网关校验登录态、数据源权限、发布快照和任务状态。
3. Python Agent 改写问题、召回 Schema、生成 SQL，并通过安全沙箱校验。
4. SQL 使用只读连接执行，结果写回任务记录。
5. 前端展示表格、SQL、图表、口径说明与后续建议问题。

## 治理闭环

<p align="center">
  <img src="docs/images/dataocean-governance-cycle.png" alt="DataOcean 元数据治理闭环" width="68%"/>
</p>

DataOcean 的可信查询来自治理前置：

```text
数据源接入
  -> Schema 采集
  -> 质量校验
  -> 问题修正
  -> 快照审核发布
  -> 生成 skills.md
  -> 向量化入库
  -> 查询召回引用
  -> 反馈与审计回流
```

只有 `PUBLISHED` 状态的快照会进入 RAG 与查询链路。这样可以把“模型能看到什么”收束到经过审核的元数据和业务知识上。

## 安全边界

<p align="center">
  <img src="docs/images/dataocean-security-shield.png" alt="DataOcean 安全防护" width="54%"/>
</p>

| 防护面 | 设计 |
| --- | --- |
| 连接安全 | 数据源密码加密存储，业务库建议使用只读账号 |
| SQL 安全 | `sqlglot` AST 校验，仅允许 SELECT，阻断 DROP/DELETE/UPDATE 等危险操作 |
| 执行安全 | 强制 LIMIT、执行超时、连接池保护、任务取消 |
| 权限安全 | 数据源授权、行列级权限在服务端与 SQL 层生效 |
| 数据安全 | SENSITIVE 字段脱敏，BLOCKED 字段禁止召回或查询 |
| 会话安全 | JWT + Redis 黑名单，退出登录后令牌立即失效 |

## 快速开始

### 环境要求

| 工具 | 建议版本 |
| --- | --- |
| JDK | 17 |
| Node.js | 20+ |
| Python | 3.13 |
| Docker | 支持 Compose v2 |
| Maven | 3.9+ |
| uv | 用于 Python 依赖管理 |

### 1. 启动基础设施

```powershell
docker compose up -d mysql redis etcd minio milvus
```

默认端口：

| 服务 | 地址 |
| --- | --- |
| MySQL | `127.0.0.1:3307` |
| Redis | `127.0.0.1:6379` |
| Milvus | `127.0.0.1:19530` |
| MinIO Console | `http://127.0.0.1:9001` |

### 2. 启动 Java 网关

```powershell
cd backend\DataOcean

# 如使用 docker-compose 默认配置，请确保本地配置或环境变量与 MySQL/Redis 密码一致
$env:DB_PASSWORD = "asd123"
$env:REDIS_PASSWORD = "asd123"

mvn spring-boot:run
```

服务启动后，Java 网关默认监听：

```text
http://127.0.0.1:8080
```

### 3. 启动 Python AI 服务

```powershell
cd python-service
Copy-Item .env.example .env

# 编辑 .env，至少填写 DASHSCOPE_API_KEY
uv sync
uv run uvicorn dataocean.main:app --reload --port 8000
```

Python 服务默认监听：

```text
http://127.0.0.1:8000
```

### 4. 启动前端

```powershell
cd frontend
npm install
npm run dev
```

访问：

```text
http://127.0.0.1:5173
```

前端通过 Vite 代理将 `/api` 转发到 Java 网关。登录后默认进入 `/query` 查询工作区；有后台权限的用户可以从右上角用户入口进入 `/admin/*` 治理后台。

## 项目结构

```text
DataOcean/
├── frontend/                 Vue 3 前端应用
│   └── src/
│       ├── views/query/      查询工作区
│       ├── views/admin/      治理后台
│       ├── components/       通用组件与图表组件
│       └── api/              前端 API 封装
├── backend/
│   └── DataOcean/            Spring Boot Java 网关
│       └── src/main/
│           ├── java/com/dataocean/
│           └── resources/db/migration/
├── python-service/           FastAPI AI 服务
│   └── dataocean/
│       ├── agent/            LangGraph NL2SQL 工作流
│       ├── rag/              Schema RAG 检索
│       ├── sandbox/          SQL 安全校验与执行
│       ├── chart/            图表生成
│       └── resilience/       健康检查与降级
├── docs/                     项目设计文档与架构图
├── specs/                    模块规格、计划和任务拆解
└── docker-compose.yml        本地基础设施编排
```

## 模块进度

> 进度来自 `specs/*/tasks.md` 的任务清单，表示当前代码与计划的完成度；最终可用性仍以联调和运行验证为准。

| 模块 | 主题 | 进度 | 当前状态 |
| --- | --- | ---: | --- |
| 001 | 用户、角色、部门、权限基础 | 59/59 | 已完成 |
| 002 | 数据源管理与连接测试 | 31/31 | 已完成 |
| 003 | 元数据采集与快照 | 31/33 | 基本完成，剩余收尾 |
| 004 | 元数据治理与质量校验 | 32/32 | 已完成 |
| 005 | 快照版本、审核、发布、撤回 | 21/21 | 已完成 |
| 006 | `skills.md` 业务知识库 | 43/43 | 已完成 |
| 007 | Schema RAG 与向量化 | 29/29 | 已完成 |
| 008 | NL2SQL Agent 工作流 | 29/30 | 核心完成，持续联调 |
| 009 | SQL 安全沙箱 | 29/30 | 核心完成，持续联调 |
| 010 | 字段标签与可信度 | 31/31 | 已完成 |
| 011 | 查询血缘与审计 | 34/34 | 已完成 |
| 012 | 前端查询工作区 | 35/35 | 已完成 |
| 013 | 后台治理管理端 | 41/41 | 已完成 |
| 014 | Prompt 模板管理 | 27/30 | 基本完成，剩余收尾 |
| 015 | 权限与安全策略 | 33/34 | 基本完成，剩余收尾 |
| 016 | 图表生成与结果解释 | 20/20 | 已完成 |
| 017 | 错误处理、降级与取消 | 30/30 | 已完成 |

## 开发命令

| 目标 | 命令 |
| --- | --- |
| 前端开发 | `cd frontend && npm run dev` |
| 前端构建 | `cd frontend && npm run build` |
| Java 测试 | `cd backend/DataOcean && mvn test` |
| Java 启动 | `cd backend/DataOcean && mvn spring-boot:run` |
| Python 启动 | `cd python-service && uv run uvicorn dataocean.main:app --reload --port 8000` |
| Python 语法检查 | `python -m compileall -q python-service/dataocean` |
| 基础设施启动 | `docker compose up -d` |
| 基础设施停止 | `docker compose down` |

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [`docs/nl2sql-项目构想.md`](docs/nl2sql-%E9%A1%B9%E7%9B%AE%E6%9E%84%E6%83%B3.md) | 项目总体构想与架构背景 |
| [`docs/nl2sql-功能点拆分说明.md`](docs/nl2sql-%E5%8A%9F%E8%83%BD%E7%82%B9%E6%8B%86%E5%88%86%E8%AF%B4%E6%98%8E.md) | 模块拆分与建设顺序 |
| [`docs/modules/001-user.md`](docs/modules/001-user.md) | 用户与权限模块说明 |
| [`docs/modules/002-datasource.md`](docs/modules/002-datasource.md) | 数据源管理模块说明 |
| [`docs/modules/003-metadata-collection.md`](docs/modules/003-metadata-collection.md) | 元数据采集模块说明 |
| [`docs/modules/004-metadata-governance.md`](docs/modules/004-metadata-governance.md) | 元数据治理模块说明 |
| [`docs/modules/005-metadata-versioning.md`](docs/modules/005-metadata-versioning.md) | 版本与发布模块说明 |
| [`docs/modules/006-knowledge.md`](docs/modules/006-knowledge.md) | 业务知识库模块说明 |
| [`specs/`](specs/) | 17 个模块的规格、数据模型、接口契约和任务清单 |

## 设计取向

DataOcean 的 README 参考了成熟开源项目常见的信息组织方式：首屏说明价值、快速给出运行入口、用图展示架构、把核心能力和模块状态表格化。相比把所有教程堆在首页，README 只保留“让新读者快速理解和跑起来”的内容，细节沉到 `docs/` 与 `specs/`。

## License

MIT
