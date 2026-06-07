<p align="center">
  <img src="docs/images/dataocean-banner.png" alt="DataOcean" width="100%"/>
</p>

<h1 align="center">DataOcean</h1>

<p align="center">
  <strong>面向企业数据团队的治理型 NL2SQL 智能查询平台</strong><br/>
  让业务人员用自然语言查询数据，同时保留元数据治理、权限边界、SQL 安全沙箱和结果可追溯性。
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-f97316?style=flat-square" alt="Java 17"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.x-6db33f?style=flat-square" alt="Spring Boot 3.x"/>
  <img src="https://img.shields.io/badge/Vue-3-42b883?style=flat-square" alt="Vue 3"/>
  <img src="https://img.shields.io/badge/Python-3.13-3776ab?style=flat-square" alt="Python 3.13"/>
  <img src="https://img.shields.io/badge/Milvus-2.x-00a1ea?style=flat-square" alt="Milvus 2.x"/>
  <img src="https://img.shields.io/badge/License-MIT-111827?style=flat-square" alt="MIT License"/>
</p>

---

## 项目简介

DataOcean 是一个企业级 NL2SQL 智能数据查询与治理平台。用户在查询端选择一个 MySQL 数据源后，可以直接用中文提问；系统会基于已发布的元数据快照和 `skills.md` 业务知识召回相关上下文，生成 SQL，通过安全沙箱校验和只读执行后返回表格、图表、SQL 与解释。

核心原则：

> AI 只能在可信元数据、明确权限和可审计执行链路内查询数据。

## 核心能力

| 能力 | 说明 |
| --- | --- |
| 自然语言查询 | 用户选择数据源后直接提问，返回表格、图表、SQL、解释和建议问题。 |
| 元数据治理 | 元数据采集、质量检查、问题修复、审核发布、版本快照和生命周期管理。 |
| skills.md 知识库 | 基于治理后的元数据生成业务语义说明书，审核发布后进入 RAG。 |
| Schema RAG | Milvus 向量召回已发布的表、字段、Join Path、指标口径和查询场景。 |
| SQL 安全沙箱 | sqlglot AST 校验、SELECT-only、权限改写、LIMIT 注入、超时和取消。 |
| 权限与脱敏 | 数据源授权、敏感字段脱敏、审计记录和任务状态管理。 |
| 图表生成 | Python 生成 ECharts Option，前端渲染、切换和导出。 |
| 治理闭环 | 查询审计、字段可信度、用户反馈和血缘分析持续回流治理侧。 |

## 系统架构

<p align="center">
  <img src="docs/images/dataocean-architecture.png" alt="DataOcean 系统架构" width="86%"/>
</p>

```text
Vue 3 前端 → Spring Boot Java 网关 → Python FastAPI AI 服务 → Milvus / MySQL / Redis / Qwen
```

## 快速开始

环境要求：JDK 17、Maven 3.9+、Node.js 20+、Python 3.13、uv、Docker Compose v2

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

默认入口：前端 `http://127.0.0.1:5173` | Java `http://127.0.0.1:8080` | Python `http://127.0.0.1:8000`

## 文档导航

| 文档 | 定位 | 内容 |
| --- | --- | --- |
| [`CLAUDE.md`](CLAUDE.md) | **AI Agent 工作手册** | 架构约束、技术栈、模块职责、开发规则 |
| [`AGENTS.md`](AGENTS.md) | **技术参考手册** | API 端点表、代码目录树、内部接口契约 |
| [`docs/后续开发.md`](docs/后续开发.md) | **活的待办清单** | 后续开发任务、设计决策记录、一致性检查 |
| [`docs/nl2sql-单库多表版-项目构想.md`](docs/nl2sql-单库多表版-项目构想.md) | **历史设计文档** | ADR 决策表、置信度策略、架构 rationale |

## License

MIT
