# DataOcean

> 企业级 NL2SQL 智能数据查询与治理平台

让业务人员通过自然语言查询企业数据库，AI 生成 SQL 并返回表格/图表结果。核心强调**元数据治理驱动的可信查询**——所有 AI 依据必须来自已治理、已审核的元数据。

## 核心特性

- **自然语言查询** — 输入大白话，自动生成 SQL 并执行，返回数据表格和 ECharts 图表
- **Schema RAG** — 向量检索精准召回相关表（Top 5-10），解决大库上下文过载问题
- **元数据治理** — 采集 → 质量校验 → 审核发布 → 向量化，治理结果驱动 AI 生成
- **skills.md 业务知识库** — AI 生成草稿 + 人工审核，为 SQL 生成注入业务语义
- **字段可信度** — 0-100 动态评分，影响字段选择优先级，用户反馈驱动自学习
- **SQL 安全沙箱** — AST 校验 + 只读账号 + 行列级权限，多层防护确保数据安全
- **多数据源** — 支持接入多个 MySQL 数据源，单次查询限定单库范围

## 技术架构

```
Vue 3 前端 (问答端 + 治理管理端)
    │
    ▼
Spring Boot 3.x 网关层 (鉴权/管理/审计)
    │
    ▼
Python AI 服务 (FastAPI + LangGraph + LlamaIndex)
    │
    ▼
Milvus / MySQL / Redis / Qwen API
```

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + Vite + TypeScript + Element Plus + ECharts + Pinia |
| Java 网关 | Spring Boot 3.x + Spring Security + JWT + MyBatis-Plus + Flyway |
| Python AI | Python 3.13 + FastAPI + LangGraph + LlamaIndex + SQLAlchemy 2.x |
| 存储 | MySQL 8 + Milvus 2.x + Redis |
| AI | Qwen API + text-embedding-v4 |
| 部署 | Docker Compose |

## 项目结构

```
frontend/          — Vue 3 前端
backend/           — Spring Boot Java 网关
python-service/    — Python AI 服务
docs/              — 设计文档
specs/             — 模块规格与实现方案（17 个模块）
```

## 快速开始

```bash
# 启动基础设施
docker compose up -d

# 启动后端
cd backend && mvn spring-boot:run

# 启动 AI 服务
cd python-service && uv run fastapi dev

# 启动前端
cd frontend && npm run dev
```

## MVP 范围

多数据源接入，限定单库多表查询。用户选择一个 MySQL 数据源，在该库内进行多表联合查询。

## License

MIT
