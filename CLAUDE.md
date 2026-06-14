# CLAUDE.md

This file gives AI coding agents project-specific guidance for working in this repository.

## Project Overview

DataOcean is an enterprise NL2SQL intelligent data query and governance platform. It lets business users ask questions in natural language, generates safe SQL, executes read-only queries, and returns table/chart results. The core design is metadata-governance-driven trustworthy querying.

MVP scope: multi-data-source management, with each query selecting one MySQL data source and supporting multi-table joins inside that database.

## Architecture

```text
Vue 3 frontend
  - query app and admin governance app
  - Axios, Element Plus, ECharts, GSAP
        |
        | HTTP API
        v
Spring Boot Java gateway
  - auth, permissions, datasource management
  - metadata governance, skills.md lifecycle
  - audit, masking, task state, versioning
        |
        | internal HTTP (RestClient)
        v
Python FastAPI AI service
  - query rewrite, Schema RAG, SQL generation
  - SQL AST validation and sandbox execution
  - chart generation, chunking, embedding, reranking
        |
        v
Milvus / MySQL / Redis / Qwen
```

Important boundary:

- Java owns management lifecycle: document drafts, review, versioning, publishing, task state, permissions, audit, and Java-side persistence.
- Python owns AI/RAG execution: chunking, embedding, Milvus writes, retrieval, reranking, SQL generation, SQL validation, and sandbox execution.
- Frontend calls Java only. Java calls Python through internal APIs.
- Java→Python calls use `RestClient` with `@Retryable` on knowledge/RAG client methods (2 attempts, 1s backoff). SSE streaming and health checks do not retry.

## Current Status

Last updated: 2026-06-13.

The main end-to-end chain is implemented:

```text
Java query task -> Python Agent -> RAG retrieval -> SQL generation
-> sqlglot AST validation/rewrite -> sandbox execution -> Java persistence -> frontend rendering
```

Module status summary:

| Area | Status |
| --- | --- |
| Frontend query app | Core complete |
| Frontend admin governance app | Core complete |
| Java user/auth/permission modules | Complete |
| Java datasource/metadata/governance/versioning modules | Complete |
| Java knowledge/skills.md lifecycle | Complete, with Python chunking integration |
| Java query/audit/field confidence modules | Core complete; conversation persistence and feedback confidence updates are implemented |
| Java prompt module | Complete, including template approval workflow and version rollback |
| Java system/dashboard modules | Complete; AI config management and admin dashboard are implemented |
| Python Agent workflow | Core complete, with timeout/cancel handling and degraded result propagation |
| Python RAG/vectorization | Complete |
| Python SQL sandbox | Core complete |
| Python chart generation | Complete |

Known follow-up areas — see `docs/development/后续开发.md` for the full prioritized list.

Recently completed or verified:

- **阶段一：权限治理修复已完成**（2026-06-14）：按统一路线图完成权限治理修复，包括：(1) 权限合并逻辑从交集改为并集（安全优先：任一维度 DENY 即禁止，任一维度 MASK 即脱敏）；(2) 权限计算器批量查询优化（消除 N+1）；(3) 缓存事务隔离（@TransactionalEventListener AFTER_COMMIT）；(4) 治理 Issue 状态机新增 REOPENED 状态（RESOLVED/REJECTED → REOPENED → CONFIRMED）；(5) SQL 注入防御已确认存在（AccessPolicyServiceImpl.validateRowFilterExpression）；(6) Java→Python 权限协议补齐 tableScopeMode（UNRESTRICTED/ALLOWLIST），修正 `*` 表策略语义；(7) 冗余 Mapper 删除任务取消（DatasourceMapper 实际被 16 个类使用）。详见 `docs/development/DataOcean统一执行路线图.md`。
- **阶段二：RAG 重构已完成**（2026-06-14）：(1) Embedding 初始化竞态修复 — asyncio.Lock + double-check 模式（embeddings.py）；(2) LLM 初始化竞态修复 — threading.Lock + double-check 模式（llm.py）；(3) 向量化 force 模式 staging 语义明确化（vectorizer.py），修复 _count_vectors limit=1000 上限 bug；(4) SSE 事件流添加 try/finally 清理保证（sse.py）；(5) RAG 架构已确认分层清晰（service → retriever → vector_store / vectorizer / reranker），chunk type 权重已在 reranker 中实现。详见 `docs/development/DataOcean统一执行路线图.md`。
- **阶段三：实体关系图谱已完成**（2026-06-14）：(1) 创建 metadata_entity 和 metadata_relationship 表（V37 迁移）；(2) 实现 MetadataEntity/MetadataRelationship 实体、Mapper、Service；(3) 实现 FQN 体系（datasource.db.table.column）；(4) 快照发布时自动同步实体-关系图谱（SnapshotEntitySyncListener）；(5) 实现元数据目录搜索 API（/api/admin/catalog/search）；(6) 实现血缘类型设计（QUERY/ETL/MANUAL）；(7) 实现血缘 DAG 可视化前端（LineageGraph.vue + ECharts）；(8) 实现下游影响分析 API。详见 `docs/development/DataOcean统一执行路线图.md`。
- **阶段四：业务术语表已完成**（2026-06-14）：(1) 创建 glossary 和 glossary_term 表（V38 迁移）；(2) 实现 Glossary/GlossaryTerm 实体、Mapper、Service、Controller（/api/admin/glossary）；(3) 实现术语审核流程（DRAFT → PENDING_REVIEW → APPROVED/REJECTED）；(4) 实现 RAG 集成——查询改写阶段自动匹配术语同义词扩展用户问题；(5) 实现前端术语管理页面（GlossaryList.vue）；(6) Java→Python 请求传递 glossary_terms。详见 `docs/development/DataOcean统一执行路线图.md`。
- **阶段五：分类标签与质量深化已完成**（2026-06-14）：(1) 创建 classification 和 tag 表（V39 迁移），预置 PII/数据分级/业务域三类 14 个标签；(2) 实现 Python AutoTagger 标签自动推断器（基于列名模式匹配 PII/业务域标签）；(3) 扩展 metadata_quality_rule 表新增 check_type/check_expression/threshold 字段；(4) 新增 4 条数据级质量规则（空值率、唯一性、外键孤儿、数据陈旧）；(5) 创建 quality_check_result 质量趋势时序表；(6) 标记 PredefinedTag 为 @Deprecated。详见 `docs/development/DataOcean统一执行路线图.md`。
- **智能问数链路已跑通**（2026-06-13）：完整链路测试成功，包括 RAG 检索、SQL 生成、SQL 校验、SQL 执行、图表生成。修复了 Milvus 连接兼容性、SQL 分号校验、Decimal 序列化等问题。详见 `docs/development/智能问数链路诊断报告.md`。
- **F0 排雷任务已完成**（2026-06-13）：按审查报告第十二章实施 17 项代码修复，包括向量化 force 模式安全修复、内部路由统一认证、表白名单空值语义、Prompt 注入防护、危险函数黑名单补齐、retry_count 边界修复、VectorStore 缓存、reranker 分数 clamp、SSE 解析完善、LLM/Embedding 初始化竞态修复、配置热重载竞态修复、连接池清理 TOCTOU 修复等。12.3 设计改进建议暂未实施。
- Query conversations are persisted in MySQL (`conversation`, `conversation_message`) and can be restored from the frontend after refresh.
- Prompt template management includes CRUD, version history, approval flow (`DRAFT -> PENDING_REVIEW -> APPROVED/REJECTED`), rollback, and frontend workflow controls.
- AI configuration is managed through `sys_config`; Java exposes admin/internal config APIs, and Python supports `/internal/config/reload`.
- Milvus degradation now reaches the main Agent path through Java-provided fallback chunks; `degraded`/`degrade_notice` are propagated in query results.
- Error messages are sanitized through `dataocean/core/error_messages.py`, and chart generation failure falls back without failing the data query.
- Query task stability work includes custom async executor sizing, stale task timeout cleanup, cancel-race guarded updates, and frontend result pagination.

## RAG And skills.md Lifecycle

The current RAG implementation intentionally uses Python for chunking and vector operations.

Responsibilities:

- Java:
  - manages skills.md lifecycle: `DRAFT -> PENDING_REVIEW -> APPROVED -> INDEXING -> PUBLISHED`;
  - manages review, versioning, publish task state, rollback state, and audit;
  - stores the chunk snapshot returned by Python in MySQL table `knowledge_chunk`;
  - marks the new version active only after Milvus write and verification succeed;
  - keeps the old active version available when new vectorization fails.
- Python:
  - chunks skills.md through `/internal/rag/chunk`;
  - embeds chunks;
  - writes vectors to Milvus;
  - verifies vector count;
  - serves retrieval and reranking.

Publishing flow:

```text
APPROVED document
  -> Java marks INDEXING and creates vector task
  -> Python chunks skills.md
  -> Java stores returned chunk snapshot in MySQL
  -> Python embeds and writes Milvus vectors
  -> Python verifies Milvus vector count
  -> Java transaction marks chunks INDEXED, document PUBLISHED, task COMPLETED
  -> Python cleans previous version vectors
```

Failure rule:

- If chunking or vectorization fails, Java restores the document to `APPROVED`.
- Old active vectors are not deleted before the new version is successfully verified.
- Same-version rebuilds delete only `doc_id + version_no`, not all vectors for the document.

Recent RAG lifecycle change:

- Java-side `KnowledgeChunkSplitter` was removed.
- Python `chunker.py` is the source of truth for chunking.
- Python chunking splits by `##` sections and then by `###` subsections for fine-grained chunks.
- Flyway migration `V35__rag_python_chunking_lifecycle.sql` updates chunk lifecycle metadata and adds `idx_chunk_doc_version`.
- skills.md generation is expected to output six structured sections, including concrete Join Path SQL conditions, metric SQL expressions, field notes, and query scenes.
- RAG reranking applies chunk-type bonuses for `JOIN_PATH`, `METRIC`, `FIELD_NOTE`, and `QUERY_SCENE` based on query intent.
- Prompt token budget is 5000 total, with `skills` and `schema` both budgeted at 1500 and treated as highest-priority context.

Current RAG/NL2SQL follow-up cautions:

- Do not implement datasource-wide force vectorization as "delete old vectors, then write new vectors". Prefer `doc_id`/version-scoped rebuilds or staging writes verified before cleanup.
- Internal Python APIs currently rely heavily on network isolation; future hardening should add a shared internal token or equivalent guard across `/internal/*`.
- Empty table allowlists need an explicit protocol: "not provided" should not silently mean unrestricted access.

## Core Domain Concepts

- `skills.md`: business semantic knowledge generated from metadata governance results, reviewed by humans, then published into RAG.
- Field confidence: 0-100 score influencing SQL field selection.
- RAG admission control: only approved and allowed governance states can enter retrieval.
- Sensitive fields: may enter RAG with mask metadata, but Java gateway performs final masking.
- Deprecated/blocked fields: must not be retrieved or used in SQL generation.
- Query Rewrite: resolves time expressions, references, and user intent before retrieval and SQL generation.
- Prompt templates: managed in Java, fetched by Python, and locally downgraded to Jinja2 templates when Java-managed templates are unavailable.
- AI config: stored in Java `sys_config` with encrypted API key values; Python instances reload config on internal callback.
- Conversation persistence: Java owns durable conversation and message storage; Python receives only request-scoped `conversation_history`.

## Development Commands

Frontend:

```bash
cd frontend
npm install
npm run dev
```

Java backend:

```bash
cd backend/DataOcean
mvn spring-boot:run
```

Python service:

```bash
cd python-service
uv run uvicorn dataocean.main:app --reload --port 8000
```

Infrastructure:

```bash
docker compose up -d
```

Tests:

```bash
cd python-service
uv run pytest

cd backend/DataOcean
mvn test
```

Latest verified test result:

- Python: 102 tests passed, 4 skipped (E2E tests require full environment).
- Java: 72 tests passed.

The next testing gap is Agent workflow coverage: query rewrite, SQL generation/validation/execution, visualization fallback, RAG degradation, and Java query integration.

## Repository Structure

```text
frontend/              Vue 3 frontend (Vite + Element Plus + ECharts + GSAP)
backend/               Spring Boot Java gateway
backend/DataOcean/     Java application root
python-service/        FastAPI AI/RAG service (LangGraph + LangChain + sqlglot)
docs/                  design and module documentation
specs/                 module specifications, plans, tasks, contracts
```

## Java Backend Notes

Main package:

```text
backend/DataOcean/src/main/java/com/dataocean/
```

Important modules:

- `user`: authentication, user, role, department, permission management.
- `datasource`: datasource management and health checks.
- `metadata`: metadata scanning, synchronization, comparison.
- `governance`: metadata quality checks and governance status.
- `versioning`: metadata snapshot lifecycle and review.
- `knowledge`: skills.md lifecycle, chunk snapshot persistence, vector publish tasks.
- `query`: Java-side NL2SQL task management, conversation persistence, SSE bridge, result persistence, and fallback chunk loading.
- `fieldtag`: field tags, confidence, feedback.
- `audit`: query audit, lineage, quotas, alerts.
- `permission`: access policy and data masking.
- `prompt`: prompt template CRUD, approval workflow, version history, rollback, and internal template API for Python.
- `system`: config, notifications, operation logs, AI config, scheduling.
- `dashboard`: admin homepage statistics aggregation.

Database migrations live in:

```text
backend/DataOcean/src/main/resources/db/migration/
```

Migration notes:

- `V15` creates query task and conversation persistence tables.
- `V23-V24` create and initialize prompt template tables.
- `V29` adds query task progress fields.
- `V35` updates RAG Python chunking lifecycle metadata.
- `V36` adds Prompt approval workflow fields and permissions.

## Python Service Notes

Main package:

```text
python-service/dataocean/
```

Important modules:

- `agent`: LangGraph NL2SQL workflow.
- `rag`: chunking, embedding, Milvus vectorization, retrieval, reranking.
- `sandbox`: SQL AST validation, permission rewriting, read-only execution.
- `knowledge`: skills.md draft generation.
- `chart`: ECharts option generation.
- `prompt`: prompt fetching and rendering.
- `infra`: LLM (LangChain ChatOpenAI), embedding (LangChain OpenAIEmbeddings), SSE, cancellation, health, config, timeout budget, parsers, and config reload support.

Python route notes:

- `/internal/query`: Agent execution/cancel/health.
- `/internal/rag`: chunking, vectorization, retrieval, and vector management.
- `/internal/sql`: SQL validation, execution, and connection-pool management.
- `/internal/chart`: ECharts option generation.
- `/internal/knowledge`: skills.md draft generation.
- `/internal/prompts`: prompt template access.
- `/internal/config/reload`: AI config reload callback.

## Frontend Notes

Frontend routes are split between:

- `/query`: user-facing intelligent query flow.
- `/admin/*`: governance, metadata, knowledge, prompt, audit, permissions, and system management.
- `/admin/system/ai-config`: AI provider/model/embedding configuration.

The query page persists server-side conversations and can reload historical messages through `/api/query/conversations` and `/api/query/conversations/{id}/messages`.

## Working Rules

- Prefer existing project patterns over introducing new abstractions.
- Keep Java responsible for governance and lifecycle state.
- Keep Python responsible for AI execution, chunking, embedding, Milvus, retrieval, reranking, and SQL sandbox behavior.
- Never delete active RAG vectors until the replacement version is written and verified.
- Java owns durable conversation history. If Redis memory is introduced later, avoid having Java and Python both write the same conversation-history key.
- Java consumes Python SSE as a client. Do not replace this with Spring `SseEmitter`; fix client-side SSE parsing and read timeouts instead.
- Use focused tests when changing lifecycle, RAG, SQL safety, permissions, or public API behavior.
- Preserve user changes in the working tree; do not reset or revert unrelated files.
- Docker boundary: when MySQL, Redis, Milvus, MinIO, etc. are stopped or missing, do not automatically start, create, recreate, or delete containers. Tell the user which existing service/container should be started, and only run Docker commands when the user explicitly asks.
