# AGENTS.md

This file provides guidance to Codex and other AI coding agents when working in this repository.

## Documentation Lookup Rule

Use Context7 MCP to fetch current documentation whenever the user asks about a library, framework, SDK, API, CLI tool, or cloud service. This applies even to well-known tools such as React, Vue, Vite, Element Plus, ECharts, Spring Boot, MyBatis-Plus, FastAPI, LangGraph, LangChain, SQLAlchemy, Milvus, Redis, or Docker.

Do not use Context7 for refactoring, writing scripts from scratch, debugging business logic, code review, or general programming concepts.

Steps:

1. Start with `resolve-library-id` using the library name and the user's full question, unless the user provides an exact `/org/project` library ID.
2. Pick the best match by exact name, relevance, snippet count, source reputation, and benchmark score. Use version-specific IDs when the user mentions a version.
3. Call `query-docs` with the selected library ID and the full user question.
4. Answer or implement using the fetched docs.

## Project Overview

DataOcean is an enterprise NL2SQL intelligent data query and governance platform for a graduation project. Business users ask questions in natural language; the system generates safe SQL, executes read-only queries, and returns table/chart results. The core design is metadata-governance-driven trustworthy querying.

MVP scope: multi-data-source management. Each query selects one MySQL data source and supports multi-table joins inside that database.

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
  - query rewrite, glossary expansion, Schema RAG
  - SQL generation, SQL AST validation, sandbox execution
  - chart generation, chunking, embedding, reranking
        |
        v
Milvus / MySQL / Redis / Qwen
```

技术版本、技术与模块的对应关系、数据存储归属和异步边界详见
[`docs/development/DataOcean技术栈与模块职责.md`](docs/development/DataOcean技术栈与模块职责.md)。

Important boundaries:

- Frontend calls Java only. Java calls Python through internal APIs.
- Java owns management lifecycle: users, permissions, data sources, metadata governance, review, versioning, publishing, task state, masking, audit, and durable persistence.
- Python owns AI/RAG execution: query rewrite, glossary hints, chunking, embedding, Milvus writes, retrieval, reranking, SQL generation, SQL validation, and sandbox execution.
- Java to Python calls use `RestClient`; knowledge/RAG clients use `@Retryable` where configured. SSE streaming and health checks should not be blindly retried.
- Java asynchronous work uses dedicated executors for query execution, conversation summaries, and datasource health checks; saturation must not make request threads run the full Agent or summary LLM call.
- Query results are not cached because similar questions, relative dates, and permission differences can make reuse unsafe.

## Current Status

Last updated: 2026-09-22.

The main end-to-end chain is implemented and has been run through:

```text
Java query task -> Python Agent -> query rewrite/glossary hints -> RAG retrieval
-> SQL generation -> sqlglot AST validation/rewrite -> sandbox execution
-> Java persistence/masking -> frontend table/chart rendering
```

Module status summary:

| Area | Status |
| --- | --- |
| Frontend query app | Core complete; server-side conversation restore is implemented |
| Frontend admin governance app | Core complete; includes catalog search, glossary, permissions, audit, system pages |
| Java user/auth/permission modules | Legacy permission-specific objects remain pending B6 cleanup; IAM-SIMPLE-1 B0–B4 (including B4-A and batches 1–6) code and automation are in `1f0a5f4`; 23 controllers migrated; current code switch commit is `1e2f458`; B5 local-development switch and core browser acceptance are complete on IT-GO-1225, while B6 has not executed |
| Java datasource/metadata/governance/versioning modules | Complete; metadata entity graph and event recording are implemented |
| Java glossary module | Complete; glossary and term approval flow are implemented |
| Java knowledge/skills.md lifecycle | Complete, with Python-owned chunking integration |
| Java query/audit/field confidence modules | Core complete; conversation persistence and feedback confidence updates are implemented; confidence read-time decay with configurable half-life (30d default) added |
| Java prompt module | Complete, including approval workflow, version history, and rollback |
| Java system/dashboard modules | Complete; AI config management and admin dashboard are implemented |
| Python Agent workflow | Core complete, with timeout/cancel handling, glossary hints, degraded result propagation, column-level Schema Linking, LLM self-correction on execution failure, and agent graph parallel fan-out (Rewriter + Metadata Prefetch) |
| Python RAG/vectorization | Core complete; token-aware skills.md chunking (target 900/max 1000, overlap 150), chunk metadata propagation, snapshot-safe fallback, adjacent context expansion, verified staging rebuild, and model/config-aware embedding cache are implemented |
| Python SQL sandbox | Core complete; SQL-to-Schema hallucination detection added (zero extra LLM calls) |
| Python chart generation | Complete with fallback behavior |
| Data source readiness | Complete |

Current status, Track A remediation details, and admin navigation rules live in `docs/development/DataOcean后台重构状态与整改计划.md`; treat it as the single source of truth. The ordered next-action queue lives in `docs/development/后续开发.md` and must not duplicate status claims. The seven-stage refactor roadmap is complete; do not treat `docs/development/completed/DataOcean统一执行路线图.md` as an active implementation plan unless the user explicitly asks to revisit it.

Track B targets the simple permission design in `docs/development/guides/DataOcean-完整权限体系设计.md` (IAM-SIMPLE-1). B0 through B4, including B4-A and batches 1–6, are merged at `1f0a5f4` on `codex/iam-s1-b5-preparation` (IAM implementation commit `1a6e426`). **23 controllers** are annotation-migrated; batch 6 is **61 handlers**; the B4 automation baseline is Java **557** and frontend Vitest **67**. The current code switch commit is `1e2f458`; it removes the formal legacy organization nav and `/admin/access/organization` route. `RoleController` / `PermissionController` / `DatasourcePermissionController` / `AccessPolicyController` / `AccessApprovalController` and their services/tables stay on the pre-B6 path. The 22 `IamS1*` permission-domain endpoints remain on explicit guards. `IamS1AuthorizationAspect` must keep `@Aspect` **and** `@Component`. New authorization decisions must read only isolated IAM-SIMPLE-1 facts; do not map or backfill old roles, grants, JWT authorities, or caches. On IT-GO-1225, the local development database completed V51 → V52 → V54 → V55 → V56 → V57 to V57; bootstrap completed for userId=1; the fixed catalog has 54 codes; `iam_s1_data_grant` remains 0; and core service/browser acceptance completed. This is not production evidence or a claim about other machines. Recovery rehearsal on an independent MySQL instance and the two real-user negative scenarios remain gaps; B6 has not executed. V53 is permanently unused; P9 must use V58 or higher. The B5 switch/rollback handbook remains the generic procedure for other environments. Automation passing does not mean B5 is production-complete.

B0 文档已评审通过；`docs/development/轨道B-B0权限清单与决策冻结.md` 是权限消费清单、IAM-SIMPLE-1 独立新表方案、新契约、启动式首个管理员 bootstrap 和 B6 删除/保留基线。B1～B4 代码与自动化验证已合入 `1f0a5f4`，B4 最新基线为 Java **557**、前端 Vitest **67**。IT-GO-1225 本机开发环境的 B5 切换和核心验收已完成，但恢复演练与两个真实用户负向场景仍缺；`iam_s1_data_grant` 为 0，B6 未执行。V53 永久不使用，P9 使用 V58 或更高未占用版本。B5 手册保留真实环境的只读 SQL 门禁、`mysqldump --result-file`、禁止覆盖式导入和独立 MySQL 恢复演练要求；该通用流程不等于其他环境已执行 B5。账号、部门、数据源、元数据、知识、会话、审计等业务数据保留，旧权限专用对象只在 B6 按冻结清单处理。

- B5 验收摘要（仅 IT-GO-1225 本机）：固定功能目录 54 项，浏览器 Console error/warn 为 0，前端定向测试 6/6、全量 Vitest 67/67、构建和 `git diff --check` 通过；针对 `1e2f458` 的只读 preflight 为 failures=0、exit code=0。

## Recently Completed

- **F0 fixes completed** (2026-06-13): force-vectorization safety, internal route authentication, table allowlist semantics, prompt-injection defenses, dangerous-function blacklist, retry_count boundary fixes, VectorStore cache, reranker score clamp, SSE parsing, LLM/Embedding init race fixes, config reload race fixes, and pool cleanup TOCTOU fixes.
- **Intelligent query chain verified** (2026-06-13): RAG retrieval, SQL generation, SQL validation, SQL execution, and chart generation have successfully run together.
- **Stage 1 permission governance completed** (2026-06-14): permission merge logic, batch permission calculation, transaction-safe cache invalidation, governance issue `REOPENED`, row-filter validation, and `tableScopeMode` protocol.
- **Stage 2 RAG refactor completed** (2026-06-14): Embedding/LLM init locks, safe vectorization staging, vector count fixes, SSE cleanup, and clearer RAG layering.
- **Stage 3 entity relationship graph completed** (2026-06-14): `metadata_entity`, `metadata_relationship`, FQN model, snapshot publish sync, catalog search API, lineage DAG visualization, and downstream impact analysis.
- **Stage 4 glossary completed** (2026-06-14): `glossary`, `glossary_term`, glossary term review flow, query rewrite synonym expansion, frontend glossary page, and Java to Python `glossary_terms`.
- **Stage 5 classification and quality deepening completed** (2026-06-14): `classification`, `tag`, 14 seeded tags, Python auto tagger, data-level quality rules, and quality trend table.
- **Stage 6 permission enhancement completed** (2026-06-14): policy priority, validity windows, time schedules, permission change log, and audit integration.
- **Stage 7 event-driven governance completed** (2026-06-14): `metadata_change_event`, access approval request flow, temporary allow policies, expiry cleanup, and blocked/deprecated access constraints.
- **P1 notification system integration completed** (2026-06-21): frontend notification bell/dropdown and `/api/notifications` client are connected; field feedback group-threshold and snapshot publish/expire events now send system notifications.
- **Datasource grant semantics added**: `V42__datasource_access_effect.sql` makes datasource grant allow/deny decisions explicit.
- **Datasource readiness and admin IA added** (2026-06-24; navigation adjustment pending): datasource readiness aggregates connection, published metadata snapshot, blocking governance issues, published skills.md, and permission state. Query entry blocks non-askable sources with visible reasons. The current code still renders primary navigation in the sidebar and workspace navigation in the content header, but the approved target is to place both levels in the sidebar; see `docs/development/DataOcean后台重构状态与整改计划.md`.
- **P6 operation log coverage completed** (2026-08-14): 13 admin controllers annotated with `@AdminAuditLog` (governance, snapshot publish/review, glossary, skills.md, alerts, access approval, AI config, sync schedule, roles/permissions/departments). `AdminAuditLog` gained a `logReads` attribute so read-heavy controllers (catalog/collection) only log writes. `OperationLogAspect` now extracts `targetId` from the path and the self-referential `OperationLogController` annotation was removed. The operation-log list supports multi-condition query (`operatorName`, `operationType`, `isSuccess`, time range, `ipAddress`, `requestPath`, target resource/ID, `keyword`) via `OperationLogQueryDTO` + dynamic `LambdaQueryWrapper`, with a frontend filter bar in `OperationLogList.vue`. Frontend `npm run build` passes; Java unit tests have since passed in the 2026-08-31 full verification.
- **Phase 0-3 深度优化完成**（2026-07-24）：18 项优化全链路实施，详见 `docs/development/DataOcean深度优化参考方案.md`。覆盖：Embedding/术语表/Fallback/密码/权限 Redis 缓存体系、列级 Schema Linking、SQL-to-Schema 幻觉检测、置信度读时衰减与治理联动、Few-shot embedding 升级、LLM 执行反馈自校正、列元数据采样值采集、Agent 图并行 fan-out、自动标签 PII 检测、质量评分聚合、大结果集 SSE 分块传输。新增 V44（`metadata_quality_issue.column_meta_id`）、V45（`db_column_meta.sample_values`）数据库迁移。
- **RAG 文档与切分修复完成基础实现**（2026-08-31）：skills.md 模板不再把字段名推测、未审核指标或 Join 当作事实；Python chunker 按语义单元和 token 预算切分（目标 900、最大 1000、overlap 150），保留短语义单元并传递 `chunk_index`/`chunk_group_id`/多表多字段/entity/trust/hash metadata；Milvus 检索补齐 `embedding` 字段和 IP 度量校验及相邻 chunk 扩展；fallback 绑定 active snapshot、按问题隔离缓存并支持中文排序；新增 V50 `knowledge_chunk` metadata 迁移。详见 `docs/development/completed/DataOcean-RAG问题修复与知识文档切分优化方案.md`。
- **轨道 A 运行时整改与真实验收完成**（2026-09-13）：补齐 MyBatis-Plus 乐观锁与冲突 409、Java→Python 统一内部令牌及 SSE 原始流消费、质量检查真实闭环、超级管理员 `*` Controller 语义、正式侧栏两级导航、问数数据源上下文与推荐问题历史恢复；Python 修复失败结果传播、并行 Agent 状态冲突、Embedding 供应商批次上限、内部回环 HTTP 代理和列级血缘字段协议；真实桌面浏览器验收已通过。

## Core Domain Concepts

- `skills.md`: business semantic knowledge generated from metadata governance results, reviewed by humans, then published into RAG. The expected structure has six sections: document source, core table descriptions, Join Paths with concrete SQL conditions, metrics with SQL expressions, field notes, and common query scenes.
- Field confidence: 0-100 score influencing SQL field selection. Usage, successful execution, and feedback adjust confidence.
- Metadata governance loop: collect -> quality check -> fix -> review/publish snapshot -> generate `skills.md` -> vectorize -> feed query lineage and feedback back into governance.
- RAG admission control: only approved and allowed governance states should enter retrieval. `DEPRECATED` and `BLOCKED` fields must not be retrieved or used.
- Sensitive fields: may enter RAG with mask metadata, but Java gateway performs final masking.
- Entity relationship graph: metadata entities, relationships, glossary terms, tags, lineage, and downstream impact analysis share the `metadata_entity`/`metadata_relationship` model.
- Glossary: approved terms and synonyms are sent from Java to Python and used during query rewrite.
- Access approval: users can request temporary data access; approval creates auditable temporary allow policies with expiry.
- Conversation persistence: Java owns durable conversation, message, and structured long-term summary storage. For each query Java sends Python request-scoped `conversation_history` plus `conversation_summary`; Python does not receive `conversationId` or persist session state. Summary refresh is an asynchronous Python LLM call triggered by Java after an assistant message is saved.

## RAG And skills.md Lifecycle

The current RAG implementation intentionally uses Python for chunking and vector operations.

Responsibilities:

- Java manages `skills.md` lifecycle: `DRAFT -> PENDING_REVIEW -> APPROVED -> INDEXING -> PUBLISHED`.
- Java manages review, versioning, publish task state, rollback state, audit, and MySQL chunk snapshots.
- Python chunks `skills.md` through `/internal/rag/chunk`, embeds chunks, writes vectors to Milvus, verifies vector counts, and serves retrieval/reranking.
- Java marks the new version active only after Milvus write and verification succeed; old-vector cleanup is a post-commit compensating action.
- Old active vectors remain available when new vectorization or the publish transaction fails.

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
  -> cleanup failure: task enters CLEANUP_PENDING and is retried without re-vectorization
```

Failure rule:

- If chunking or vectorization fails, Java restores the document to `APPROVED`.
- Old active vectors are not deleted before the replacement version is successfully verified.
- The Java publish transaction commits before old-vector cleanup; cleanup failure leaves the new version published and enters `CLEANUP_PENDING` for retry.
- If the publish transaction itself fails after vectorization, Java compensates by deleting the current version vectors while preserving the old version.
- Same-version rebuilds delete only `doc_id + version_no`, not all vectors for the document.
- Do not implement datasource-wide force vectorization as "delete old vectors, then write new vectors". Use doc/version scoped rebuilds or verified staging writes.

Current RAG details:

- Java-side `KnowledgeChunkSplitter` was removed.
- Python `chunker.py` is the source of truth for chunking.
- Python chunking splits by `##` sections and then by `###` subsections for fine-grained chunks.
- Python chunking uses a token-aware splitter: target about 900 tokens, maximum 1000 tokens, and about 150-token overlap for long semantic units. Short meaningful units are kept as-is; no fixed-character truncation is allowed.
- `knowledge_chunk` is the durable metadata snapshot. Milvus stores a lightweight copy of `doc_id`, version, group/index, related tables/columns, entity IDs, trust score, and content hash for filtering and context expansion.
- Milvus collections must use vector field `embedding` and the configured embedding dimension. Existing incompatible collections must be rebuilt before indexing; they are not silently mixed.
- RAG reranking applies chunk-type bonuses for `JOIN_PATH`, `METRIC`, `FIELD_NOTE`, and `QUERY_SCENE`.
- Prompt templates are fetched from Java and rendered in Python without hard-coded per-section token quotas; provider context limits remain an external runtime concern.

## Project Structure

```text
frontend/              Vue 3 frontend (query app + admin governance app)
backend/               Spring Boot Java gateway wrapper
backend/DataOcean/     Java application root
python-service/        FastAPI AI/RAG service
docs/                  design and development documentation
specs/                 module specifications, plans, tasks, contracts
output/playwright/     integration screenshots for visible feature verification
```

## Java Backend Notes

Main package:

```text
backend/DataOcean/src/main/java/com/dataocean/
```

Important modules:

- `user`: authentication, user, role, department, permission management.
- `datasource`: datasource management and health checks.
- `metadata`: metadata scanning/sync/comparison, entity graph, catalog search, metadata events.
- `governance`: metadata quality checks, governance status, quality issue lifecycle, quality score aggregation.
- `versioning`: metadata snapshot lifecycle and review.
- `knowledge`: skills.md lifecycle, chunk snapshot persistence, vector publish tasks.
- `query`: Java-side NL2SQL task management, conversation persistence, SSE bridge, result persistence, fallback chunk loading, glossary term passing.
- `fieldtag`: field tags, confidence, feedback. The older `PredefinedTag` path is deprecated in favor of classification/tag governance where applicable.
- `glossary`: glossary and glossary term management/review.
- `audit`: query audit, lineage, alerts.
- `permission`: access policy, data masking, priority/time conditions, access approvals, permission change logs.
- `prompt`: prompt template CRUD, approval workflow, version history, rollback, and internal template API for Python.
- `system`: config, notifications, operation logs (multi-condition query), AI config, scheduling.
- `dashboard`: admin homepage statistics aggregation.

Database migrations live in:

```text
backend/DataOcean/src/main/resources/db/migration/
```

Migration notes:

- `V1-V14`: user, datasource, metadata, system config, governance, snapshot audit, knowledge tables.
- `V15`: query task and conversation persistence tables.
- `V16-V22`: field tags, user feedback, audit, notifications, operation logs.
- `V23-V24`: prompt template tables and initial templates.
- `V25-V34`: permission security, query task mask/progress, prompt updates, degradation and AI config.
- `V35`: Python-owned RAG chunking lifecycle metadata.
- `V36`: prompt approval workflow fields and permissions.
- `V37`: metadata entity relationship graph.
- `V38`: glossary and glossary terms.
- `V39`: classification/tag tables and seeded tags.
- `V40`: permission enhancement with priority/time/changelog.
- `V41`: metadata change events and access approval requests.
- `V42`: explicit datasource access effect semantics.
- `V44`: adds `metadata_quality_issue.column_meta_id` for governance-confidence linkage (Phase 1).
- `V45`: adds `db_column_meta.sample_values` for column sample value collection (Phase 2).
- `V50`: adds RAG chunk order/group, multi-table/multi-column, entity, trust, and content hash metadata.
- `V54`: adds IAM-SIMPLE-1 B1 isolated function/role/binding/bootstrap facts plus the fixed 54-code function catalog; only the protected built-in `IAM_S1_SYSTEM_ADMIN` role is granted those codes initially.
- `V55`: adds IAM-SIMPLE-1 B2 data grants, explicit grant columns, structured row conditions, and field protection; committed and pushed.
- `V56`: adds B3 S1 query execution evidence, safe snapshot/resource/source/capability summaries, revision/snapshot identifiers and final protection status; committed with B3 (`d9a0c3b`).
- `V57`: adds the B4 S1 access-request and access-approval tables; committed with B4 (`8a9c5a1`).
- **Machine-local migration fact (IT-GO-1225 only):** the local development MySQL database completed V51, V52, V54, V55, V56 and V57 in order and is at V57; Flyway failure records are 0, 14 `iam_s1_*` tables exist, the fixed IAM-SIMPLE-1 catalog has 54 codes, and bootstrap completed for userId=1. This does not establish the state of any other machine or production environment; reverify those environments before any operation.
- The IT-GO-1225 B5 result is a local development switch and core acceptance, not a production release. Backup integrity was checked, but restore rehearsal on an independent MySQL instance remains unverified. Only one real user exists, so the enabled-without-S1-binding and legacy-only negative scenarios remain uncovered. No business roles, responsible datasources, table/field grants, or approvers were initialized; `iam_s1_data_grant` remains 0. B6 has not executed, and the old permission-specific controllers, services, tables, and compatibility objects remain until B6.
- There is no `V53` migration file, and **V53 is permanently unused**. P9 alert history must use V58 or a higher unused version. Because `outOfOrder` is not enabled, adding V53 *after* V54–V57 have been applied would fail validation and break startup. Do not create an empty V53 just to fill the number gap.

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
- `infra`: LLM, embedding, SSE, cancellation, health, config, timeout budget, parsers, auto tagger, and config reload support.

Python route notes:

- `/internal/query`: Agent execution/cancel/health.
- `/internal/iam-s1/query`: independent IAM-SIMPLE-1 query execution/cancel SSE.
- `/internal/rag`: chunking, vectorization, retrieval, and vector management.
- `/internal/sql`: SQL validation, execution, and connection-pool management.
- `/internal/iam-s1/sql`: strict S1 AST validation and parameterized execution.
- `/internal/iam-s1/rag/retrieve`: S1 snapshot-bound chunk filtering/retrieval.
- `/internal/chart`: ECharts option generation.
- `/internal/knowledge`: skills.md draft generation.
- `/internal/prompts`: prompt template access.
- `/internal/config/reload`: AI config reload callback.

Python async boundary notes (2026-09-07): native async is used for LLM, Embedding, Redis, HTTP, SSE, and Agent execution; synchronous SQLAlchemy/PyMySQL, Milvus, connection-pool lifecycle, and token-aware skills.md processing are isolated with `asyncio.to_thread()`. Independent knowledge-domain documents use bounded `asyncio.TaskGroup` concurrency.

## Frontend Notes

Frontend routes are split between business-oriented domains:

- `/query`: user-facing intelligent query flow.
- `/admin/*`: admin app uses seven first-level business domains in `AdminShell.vue`: 工作台、数据接入、数据资产、数据治理、语义中心、权限与组织、运营与平台.
- Track A places first-level domains and second-level workspaces together in the desktop left sidebar. `AdminWorkspaceNav.vue` has been removed; do not restore a content-area global workspace bar.
- New admin pages must follow the domain/workspace ownership and two-level sidebar rules in `docs/development/DataOcean后台重构状态与整改计划.md` before adding routes or navigation entries.
- `/admin/assets`: metadata catalog search and entity graph entry.
- `/admin/semantics/glossaries`: glossary management.
- `/admin/platform/operation-logs`: operation log management.
- `/admin/platform/ai`: AI provider/model/embedding configuration.

The query page persists server-side conversations and can reload historical messages through `/api/query/conversations` and `/api/query/conversations/{id}/messages`. It also checks `/api/datasources/{datasourceId}/readiness` so users can only ask against sources whose lifecycle is ready.

Frontend API modules live under `frontend/src/api/`, including notification and admin modules for catalog, glossary, metadata, operation-log, permission, prompt, system, user, versioning, and related domains.

## Key APIs

Internal service APIs:

| Direction | Path | Purpose |
| --- | --- | --- |
| Java -> Python | `POST /internal/query/execute` | Start NL2SQL query through SSE |
| Java -> Python | `POST /internal/query/context-summary` | Generate a structured conversation summary from Java-provided message deltas |
| Java -> Python | `POST /internal/query/tasks/{taskId}/cancel` | Cancel query |
| Java -> Python | `POST /internal/rag/retrieve` | RAG retrieval |
| Java -> Python | `POST /internal/rag/vectorize` | Vectorization |
| Java -> Python | `POST /internal/rag/chunk` | Python-owned chunking |
| Java -> Python | `POST /internal/knowledge/generate-draft` | Generate skills.md draft |
| Java -> Python | `POST /internal/sql/validate` | SQL validation |
| Java -> Python | `POST /internal/sql/execute` | SQL sandbox execution |
| Java -> Python | `POST /internal/chart/generate` | Chart generation |
| Python -> Java | `GET /internal/prompts/{code}` | Fetch prompt template |

Selected public APIs:

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/auth/login` | Login |
| `GET` | `/api/auth/me` | Current user |
| `POST` | `/api/query/ask` | Start query |
| `GET` | `/api/query/tasks/{taskId}` | Query task result |
| `GET` | `/api/query/conversations` | Conversation list |
| `GET` | `/api/query/conversations/{id}/messages` | Conversation messages |
| `GET` | `/api/datasources/{datasourceId}/readiness` | Current user's datasource askability |
| `GET` | `/api/admin/datasources/{id}/readiness` | Admin datasource lifecycle readiness |
| `GET` | `/api/admin/catalog/search` | Metadata catalog search |
| `GET` | `/api/admin/catalog/entities/{id}` | Entity detail |
| `GET` | `/api/admin/glossary` | Glossary list |
| `POST` | `/api/admin/glossary/{id}/terms` | Create glossary term |
| `POST` | `/api/admin/glossary/terms/{id}/review` | Review glossary term |
| `GET` | `/api/admin/access-approvals` | Access approval list |
| `POST` | `/api/admin/access-approvals` | Submit access request |
| `POST` | `/api/admin/access-approvals/{id}/review` | Review access request |
| `GET` | `/api/admin/operation-logs` | Operation log list (multi-condition query) |
| `GET` | `/api/notifications` | Current user notifications |
| `PATCH` | `/api/notifications/{id}/read` | Mark notification read |
| `GET` | `/api/notifications/unread-count` | Current user unread notification count |

## Optional Machine-Specific Environment

- Before starting the project or diagnosing the local runtime, check whether `.dataocean/local-environment.md` exists. If it exists, read it completely and use it only to determine the current machine's tool locations, service locations, and startup topology.
- `.dataocean/local-environment.md` is deliberately machine-local and ignored by Git. The office computer and home computer must each maintain their own file; never copy one machine's populated profile to another. Use `.dataocean/local-environment.example.md` only as the shared template.
- Verify the profile's hostname and any drift-prone runtime state with read-only checks before relying on it. The profile is a machine-local hint, not proof that a process, port, database, or container is currently available.
- If the recorded hostname does not match the current machine, ignore the profile for runtime decisions and rebuild it from fresh inspection when the user asks to set up that machine. After an OS reinstall, treat all old paths, services, ports, and container names as stale until reverified.
- If the file does not exist, do not pause and do not ask the user to create it. Continue with the existing project documentation and current-machine inspection, then start the project normally when requested.
- A machine-local profile cannot override this repository's architecture, security constraints, Git rules, Docker confirmation boundary, or the user's current request.
- Never store or print passwords, API keys, tokens, or other secrets in the machine-local profile. Keep secrets in the ignored runtime configuration files intended for them.

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

If Maven is not on `PATH`, use the executable recorded in the current machine's `.dataocean/local-environment.md` after verifying that path exists. Do not put one computer's absolute Maven path in tracked documentation.

Python service:

```bash
cd python-service
uv run uvicorn dataocean.main:app --reload --port 8000
```

Infrastructure topology is machine-specific. Read `.dataocean/local-environment.md`, verify the current hostname and inspect actual services, ports, containers, and Compose files before taking action. MySQL may be a native service or an existing container; Redis and Milvus may also differ by machine. Never run a fixed `docker start ...` command from tracked documentation.

Tests:

```bash
cd python-service
uv run pytest

cd backend/DataOcean
mvn test
```

Latest documented verification:

- Frontend: `npm run build` passed.
- Python (2026-09-07): 152 tests passed, 4 skipped, 1 deprecation warning.
- Java: 119 tests passed.
- Remaining test gap: Agent workflow coverage around query rewrite, SQL generation/validation/execution, visualization fallback, RAG degradation, and Java query integration.

## Security Constraints

- Business database connections must use read-only accounts; passwords are AES-256 encrypted at rest.
- SQL execution must pass AST validation. Only safe `SELECT` queries are allowed.
- Enforce LIMIT 10000, execution timeout, and maximum subquery depth.
- Row/column permissions are enforced in Python sandbox AST rewriting, not by prompt alone.
- Java performs final masking for sensitive fields.
- `DEPRECATED` and `BLOCKED` tables/columns must not be retrieved, used for SQL generation, or approved through temporary access requests.
- JWT blacklist lives in Redis; logout invalidates tokens.
- Do not log passwords, raw JWTs, API keys, or secrets.

## Local Environment Rules

- Do not add project downloads, generated assets, dependency caches, exported files, or temporary project files to the repository or an arbitrary system root. Use a verified machine-local workspace/runtime directory recorded in `.dataocean/local-environment.md`, or a temporary directory created by the relevant tool.
- Before introducing any new Docker container or infrastructure service, tell the user what container is needed and why, then wait for confirmation.
- If an existing local infrastructure service is stopped or missing during development, do not automatically create, recreate, delete, or start Docker containers. Tell the user which existing container/service should be started, and let the user start it manually unless the user explicitly says to run the Docker command.
- Do not assume a fixed Docker inventory or that MySQL runs in Docker. Use the optional machine-local profile and read-only runtime inspection to determine service locations. Treat exact local credentials as private local configuration, not repository documentation.
- This project currently has no Figma prototype. Do not use Figma-related workflows by default.

## Backend Layering Rules

- Java package name: `com.dataocean.*`.
- Python package name: `dataocean.*`.
- Database migrations: `backend/DataOcean/src/main/resources/db/migration/V{version}__{description}.sql`.
- API prefixes: `/api/*` for user APIs, `/api/admin/*` for admin APIs, `/internal/*` for internal Java/Python APIs.
- Entity-related Java objects live under `entity`: database entities in `entity`, request/transport DTOs in `entity.dto` with `*DTO`, query objects in `entity.query` with `*Query`, and response/view objects in `entity.vo` with `*VO`.
- Mapper classes live in `mapper`, controllers in `controller`, service interfaces in `service`, implementations in `service.impl` with `*ServiceImpl`.
- External service clients such as Python clients live in a module-local `client` package, with implementations in `client.impl`; do not mix them into `service`.
- Do not create database-level foreign key constraints. Keep association IDs and ordinary indexes, and validate relationship integrity in service-layer business logic.
- Exception messages, code comments, and log messages in Java should use Chinese where the surrounding module does.

## Working Rules

- Prefer existing project patterns over introducing new abstractions.
- Keep Java responsible for governance and lifecycle state.
- Keep Python responsible for AI execution, chunking, embedding, Milvus, retrieval, reranking, and SQL sandbox behavior.
- Never delete active RAG vectors until the replacement version is written and verified.
- Java owns durable conversation history and long-term summaries. Python receives request-scoped history and summary data only; do not add a Python session ID or let Java and Python both persist the same conversation state.
- Java consumes Python SSE as a client. Do not replace this with Spring `SseEmitter`; fix client-side SSE parsing and read timeouts instead.
- **Caching is Redis-only**: All new caching uses the existing `RedisTemplate<String, Object>` bean (Java) or `_get_redis()` (Python). Do not introduce new local-cache layers. Cache failures must gracefully degrade — `try/catch` with warning logs, never block the main path.
- Use focused tests when changing lifecycle, RAG, SQL safety, permissions, or public API behavior.
- Preserve user changes in the working tree; do not reset or revert unrelated files.
- Important module work should update `AGENTS.md`, `CLAUDE.md`, and relevant `README`/docs when project facts change.
- For frontend/backend integration, keep screenshots for every verified user-visible feature under `output/playwright/` with descriptive names.

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the relevant module plan
under specs/<module>/plan.md.
<!-- SPECKIT END -->
