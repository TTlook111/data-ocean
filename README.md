<p align="center">
  <img src="docs/images/dataocean-readme-hero.png" alt="DataOcean hero banner" width="100%" />
</p>

<h1 align="center">DataOcean</h1>

<p align="center">
  企业级 NL2SQL 智能数据查询与治理平台
</p>

<p align="center">
  <a href="#项目定位">项目定位</a> ·
  <a href="#核心能力">核心能力</a> ·
  <a href="#系统架构">系统架构</a> ·
  <a href="#快速启动">快速启动</a> ·
  <a href="#项目结构">项目结构</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-f97316?style=flat-square" alt="Java 17" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3.x-6db33f?style=flat-square" alt="Spring Boot 3.x" />
  <img src="https://img.shields.io/badge/Vue-3-42b883?style=flat-square" alt="Vue 3" />
  <img src="https://img.shields.io/badge/Python-3.13-3776ab?style=flat-square" alt="Python 3.13" />
  <img src="https://img.shields.io/badge/FastAPI-LangGraph-009688?style=flat-square" alt="FastAPI and LangGraph" />
  <img src="https://img.shields.io/badge/Milvus-2.x-00a1ea?style=flat-square" alt="Milvus 2.x" />
</p>

## 项目定位

DataOcean 面向企业内部数据查询场景，帮助业务人员在选定 MySQL 数据源后，直接用自然语言提问。系统会基于已发布的元数据快照和 `skills.md` 业务语义知识召回上下文，生成 SQL，经过 AST 安全校验、权限改写和只读沙箱执行后，返回表格、图表、SQL 与解释。

它不是一个单纯的 SQL 生成 Demo，而是把 **元数据治理、权限边界、可信知识库、SQL 安全沙箱、审计反馈** 放进同一条查询链路里的毕业设计项目。

> AI 只能在可信元数据、明确权限和可审计执行链路内查询数据。

## 核心能力

| 能力 | 说明 |
| --- | --- |
| 自然语言查询 | 用户选择数据源后用中文提问，系统返回查询结果、ECharts 图表、SQL、解释和追问建议。 |
| 元数据治理 | 支持元数据采集、质量检查、问题修复、审核发布、快照版本和生命周期管理。 |
| `skills.md` 知识库 | 从治理后的元数据生成业务语义说明书，审核发布后按三级标题切分并进入 RAG。 |
| Schema RAG | 通过 Milvus 召回相关表、字段、Join Path、指标口径、字段防坑和查询场景。 |
| SQL 安全沙箱 | 使用 sqlglot AST 校验和改写 SQL，仅允许安全 SELECT，并强制权限、LIMIT、超时和取消。 |
| 权限与脱敏 | 支持数据源授权、行列级权限、敏感字段脱敏、查询审计和任务状态管理。 |
| 治理闭环 | 查询审计、字段可信度、用户反馈和血缘分析持续回流到治理侧。 |

## 系统架构

<p align="center">
  <img src="docs/images/dataocean-architecture.png" alt="DataOcean system architecture" width="88%" />
</p>

```text
Vue 3 前端
  -> Spring Boot Java 网关
  -> Python FastAPI AI 服务
  -> Milvus / MySQL / Redis / Qwen
```

三层职责清晰分离：

| 层 | 职责 |
| --- | --- |
| 前端 | 查询端、治理端、系统管理端，同一 Vue 项目按路由区分。 |
| Java 网关 | 鉴权、权限、数据源、元数据治理、知识库生命周期、审计和脱敏。 |
| Python AI 服务 | Query Rewrite、Schema RAG、SQL 生成、SQL 校验、沙箱执行和图表生成。 |

## 查询链路

<p align="center">
  <img src="docs/images/dataocean-core-flow.png" alt="DataOcean query flow" width="82%" />
</p>

```text
用户提问
  -> Query Rewrite
  -> Schema RAG
  -> SQL Generator
  -> SQL Validator / Rewriter
  -> Read-only Sandbox Executor
  -> ECharts Option / Result Explanation
```

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 前端 | Vue 3, Vite, TypeScript, Vue Router, Pinia, Element Plus, ECharts |
| Java 网关 | Spring Boot 3.x, JDK 17, Spring Security, JWT, MyBatis-Plus, Flyway, Redis |
| Python AI 服务 | Python 3.13, FastAPI, LangGraph, LangChain, SQLAlchemy 2.x, PyMySQL, sqlglot |
| 基础设施 | MySQL 8, Milvus 2.x Standalone, Redis, Docker Compose |
| 模型服务 | Qwen / 通义千问 API, text-embedding-v4 |

## 快速启动

环境要求：JDK 17、Maven 3.9+、Node.js 20+、Python 3.13、uv、Docker Compose v2。

```powershell
# 1. 启动基础设施
docker compose up -d

# 2. 启动 Java 网关
cd backend\DataOcean
mvn spring-boot:run

# 3. 启动 Python AI 服务
cd python-service
uv sync
uv run uvicorn dataocean.main:app --reload --port 8000

# 4. 启动前端
cd frontend
npm install
npm run dev
```

默认入口：

| 服务 | 地址 |
| --- | --- |
| 前端 | `http://127.0.0.1:5173` |
| Java 网关 | `http://127.0.0.1:8080` |
| Python AI 服务 | `http://127.0.0.1:8000` |

## 项目结构

```text
frontend/        Vue 3 前端项目，包含查询端和治理端
backend/         Spring Boot Java 网关层
python-service/  FastAPI + LangGraph AI 服务
docs/            项目设计与开发文档
specs/           模块规格说明
output/          联调截图与验证产物
```

## 文档入口

| 文档 | 用途 |
| --- | --- |
| [`AGENTS.md`](AGENTS.md) | 给代码协作者看的项目约束、架构、模块、接口和开发命令。 |
| [`docs/nl2sql-单库多表版-项目构想.md`](docs/nl2sql-单库多表版-项目构想.md) | 项目设计思路、架构决策和 NL2SQL 方案背景。 |
| [`frontend/README.md`](frontend/README.md) | 前端工程说明。 |

## License

MIT
