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
  - S1 query orchestration: glossary/RAG context assembly, SQL generation
  - SQL AST validation and sandbox execution
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
- Python owns AI/RAG execution: chunking, embedding, Milvus writes, retrieval, reranking, glossary/RAG context assembly, SQL generation, SQL validation, and sandbox execution.
- Java to Python calls use `RestClient`; knowledge/RAG clients use `@Retryable` where configured. SSE streaming and health checks should not be blindly retried.
- Java asynchronous work uses dedicated executors for query execution, conversation summaries, and datasource health checks; saturation must not make request threads run the full Agent or summary LLM call.
- Query results are not cached because similar questions, relative dates, and permission differences can make reuse unsafe.

## Current Status

Last updated: 2026-09-27.

The main end-to-end chain is implemented and has been run through:

```text
Java S1 query task -> Python iam_s1 LangGraph -> build-bound RAG and schema planning
-> bounded SQL generation/semantic check -> Java per-attempt authorization
-> S1 AST validation/read-only execution -> Java final protection
-> conversation persistence -> frontend table/chart rendering
```

Module status summary:

| Area | Status |
| --- | --- |
| Frontend query app | Natural-language-only entry, clarification, progress, message-ID history paging, and protected table/chart fallback are implemented; E-stage browser interaction and visual acceptance remain open |
| Frontend admin governance app | Core complete; includes catalog search, glossary, permissions, audit, system pages |
| Java user/auth/permission modules | IAM-SIMPLE-1 B0–B4 (including B4-A and batches 1–6) code and automation are in `1f0a5f4`; 23 controllers migrated; code switch commit `1e2f458`; B5 local-development switch and core browser acceptance complete on IT-GO-1225. **B6 executed on `codex/iam-s1-b6-cleanup` (2026-09-25, batches 0–4): the legacy permission system is removed** — the legacy permission controllers, the legacy query chain (`QueryController`, `PythonAgentClientImpl`, `PermissionCalculator`, `DatasourceAccessService`), the frontend orphan pages and `api/admin/permission.ts`, and the Python `agent/` package are deleted; the six legacy tables were dropped by `V58`. `permission_change_log` and `access_approval_request` are kept as read-only history |
| Java datasource/metadata/governance/versioning modules | Complete; metadata entity graph and event recording are implemented |
| Java glossary module | Complete; glossary and term approval flow are implemented |
| Java knowledge/skills.md lifecycle | Complete, with Python-owned chunking integration |
| Java query/audit/field confidence modules | Core complete; conversation persistence and feedback confidence updates are implemented; confidence read-time decay with configurable half-life (30d default) added |
| Java prompt module | Complete, including approval workflow, version history, and rollback |
| Java system/dashboard modules | Complete; AI config management and admin dashboard are implemented |
| Python S1 query path (`iam_s1`) | Bounded LangGraph orchestration in `iam_s1/graph.py` covers retrieval, planning, SQL generation, semantic/result checks, Java per-attempt authorization and final protection, recovery, cancellation, and time/call/cost budgets. The old `agent/` package deleted in B6 remains retired; this is a new S1 graph |
| Python RAG/vectorization | Core complete; token-aware skills.md chunking (target 900/max 1000, overlap 150), chunk metadata propagation, snapshot-safe fallback, adjacent context expansion, verified staging rebuild, and model/config-aware embedding cache are implemented |
| Python SQL sandbox | Core complete; SQL-to-Schema hallucination detection added (zero extra LLM calls) |
| Python chart generation | Complete with fallback behavior |
| Data source readiness | Complete |

Current status, Track A remediation details, and admin navigation rules live in `docs/development/completed/DataOcean后台重构状态与整改计划.md`; treat it as the single source of truth. The ordered next-action queue lives in `docs/development/后续开发.md` and must not duplicate status claims. The seven-stage refactor roadmap is complete; do not treat `docs/development/completed/DataOcean统一执行路线图.md` as an active implementation plan unless the user explicitly asks to revisit it.

LangGraph G0/A/B/C/D code and executable acceptance are recorded in `docs/development/completed/DataOcean-LangGraph问数与会话记忆验收记录.md`; the isolated eight-question API run previously passed 6/6 answerable and 2/2 refusal/clarification cases. The later review fixes and V64 are present in the current working tree, but that real question set has not been rerun against them and V64 has not been applied to the current MySQL instance. Browser E-stage interaction and visual acceptance have **not** passed. The local Redis container was replaced with Redis 8.10.2 on 2026-09-27 and the S1 checkpointer was verified on DB 0; this is machine-local evidence, not an acceptance result for E. The provenance of the existing 241 vectors in the default Milvus `schema_knowledge` collection remains unknown; S1 retrieval uses buildId-specific collections. Keep these limits explicit in status reports.

Track B targets the simple permission design in `docs/development/completed/DataOcean-完整权限体系设计.md` (IAM-SIMPLE-1). B0 through B4, including B4-A and batches 1–6, are merged at `1f0a5f4` on `codex/iam-s1-b5-preparation` (IAM implementation commit `1a6e426`). **23 controllers** are annotation-migrated; batch 6 is **61 handlers**; the B4 automation baseline is Java **557** and frontend Vitest **67**. The current code switch commit is `1e2f458`; it removes the formal legacy organization nav and `/admin/access/organization` route. `RoleController` / `PermissionController` / `DatasourcePermissionController` / `AccessPolicyController` / `AccessApprovalController` and their services/tables were retained before B6 and have since been removed. The 22 `IamS1*` permission-domain endpoints remain on explicit guards. `IamS1AuthorizationAspect` must keep `@Aspect` **and** `@Component`. New authorization decisions must read only isolated IAM-SIMPLE-1 facts; do not map or backfill old roles, grants, JWT authorities, or caches. On IT-GO-1225, the local development database completed V51 → V52 → V54 → V55 → V56 → V57 to V57; bootstrap completed for userId=1; the fixed catalog has 54 codes; `iam_s1_data_grant` remains 0; and core service/browser acceptance completed. This is not production evidence or a claim about other machines. Recovery rehearsal on an independent MySQL instance and the two real-user negative scenarios remain gaps.

B6 executed on `codex/iam-s1-b6-cleanup` (2026-09-25) in batches 0–4, each committed separately with its own full test run. Execution record: `docs/development/completed/轨道B-B6删除清单与执行顺序.md`. Traps worth remembering:

- The legacy admin endpoints were already **unreachable** before deletion — `UserDetailsServiceImpl` grants only `AUTHENTICATED_USER`, so the 17 `hasAnyAuthority('security:manage', '*')` expressions were unsatisfiable and returned 403 to everyone.
- The legacy **query** chain was still live (`QueryController` had no authorization annotation; it depended on `PermissionCalculator` and two legacy tables). Parity was checked first: `IamS1QueryController` is a **superset** of `QueryController`.
- `DataMaskingService` is a **generic capability**, not a permission object — the S1 path uses it. It moved to `common/security`; only the `PermissionContextVO`-typed overload was deleted. Delete the legacy chain *before* moving it, or `common` ends up depending on `module`.
- Python's `POST /internal/query/context-summary` is **still called** by the S1 path; it moved to `dataocean/conversation/` with the route path unchanged.
- MyBatis SQL resolves at **runtime**: two dead `DatasourceMapper` methods still had `JOIN datasource_access` and had to go before the table could be dropped.
- `authProtocolVersion` / `sessionEpoch` / `iam-s1:session:*` still have **zero** references in Java main code, and `jwt:blacklist:{jti}` / `user:token-version:{userId}` remain the only session-invalidation mechanism. Rows 497/498 of the frozen checklist are still half-done and `tokenVersion` is deliberately still live — build the replacement before removing it.

B0 权限冻结清单与 B1～B4 实现已完成；B5 的 IT-GO-1225 验收仅代表当时本机开发环境。B6 批次 0～4 已执行并删除旧权限专用对象；`authProtocolVersion` / `sessionEpoch` 的替代会话机制仍未建立，删除 `tokenVersion` 前须先完成该独立任务。V53 永久不用；新迁移号按当前最高已占用版本重新核对。其他环境的正式切换仍须遵循 B5 手册，账号、部门、数据源、元数据、知识、会话和审计等业务数据不属于旧权限专用清理对象。

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
- **Datasource readiness and admin IA added** (2026-06-24; navigation adjustment pending): datasource readiness aggregates connection, published metadata snapshot, blocking governance issues, published skills.md, and permission state. Query entry blocks non-askable sources with visible reasons. The current code still renders primary navigation in the sidebar and workspace navigation in the content header, but the approved target is to place both levels in the sidebar; see `docs/development/completed/DataOcean后台重构状态与整改计划.md`.
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
- Glossary: approved terms and synonyms are sent from Java to Python and injected as model context during SQL generation.
- Access approval: users can request temporary data access; approval creates auditable temporary allow policies with expiry.
- Conversation persistence: Java owns durable conversation, message, and structured long-term summary storage. For each query Java sends Python the conversationId, a stable user/datasource/conversation threadId, the most recent five completed turns, and a current-permission-checked older summary. Redis stores only safe LangGraph checkpoints; summary refresh is an asynchronous Python LLM call triggered by Java after an assistant message is saved.

## RAG And skills.md Lifecycle

The current RAG implementation uses Python for chunking and vector operations. Knowledge document publication and RAG activation are separate operations.

Responsibilities:

- Java manages `skills.md` draft/review/publication, snapshot-bound fact validation, approved document versions, explicit build confirmation, build state, audit, and MySQL chunk/build membership.
- Publishing a document does **not** automatically index it. An authorized user explicitly confirms a RAG build for a source metadata snapshot.
- Python chunks the frozen approved versions, embeds and writes vectors to a buildId-specific Milvus collection, verifies vector counts, and serves filtered retrieval/reranking.
- Java switches the datasource's active build pointer only after count and manifest verification. Old active collections remain available to in-flight queries; superseded build cleanup is deferred until no query references them.

Document and RAG build flow:

```text
APPROVED document -> Java publishes reviewed version without automatic indexing
  -> authorized user confirms source-snapshot RAG build
  -> Java freezes approved version manifest and buildId
  -> Python chunks, embeds and writes build-specific collection
  -> Python verifies vectors; Java atomically switches active build pointer
  -> old build waits for in-flight queries, then collection cleanup/verification
```

Failure rule:

- Failed or superseded builds do not replace the active pointer; their own collection is cleaned or remains in cleanup-pending state for retry.
- Never delete an active or in-flight build collection before the replacement is verified and activated.
- S1 retrieval requires the task-pinned buildId, matching datasource-specific collection name, approved fact membership, full resource dependencies, and current IAM visibility. The default `schema_knowledge` collection is not an S1 query source; its 241 existing vectors have unverified provenance.

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
- `V58`: removes the six legacy permission-only tables after B6 cleanup.
- `V59`: adds the S1 SQL generation prompt; `V60` adds snapshot facts and buildId-scoped RAG lifecycle.
- `V61`: adds conversation turn/message cursors and memory revision; `V62`: adds S1 SQL attempt and model-call budget evidence; `V63`: extends task status for clarification.
- `V64`: adds the conversation-summary permission scope fingerprint. This file is currently uncommitted and has not been applied to the machine-local MySQL database.
- **Historical IT-GO-1225 B5 snapshot:** the local development database reached V57 during the B5 switch, with 14 `iam_s1_*` tables, a 54-code catalog, and bootstrap for userId=1. This was evidence for that date only; the same machine-local `dataocean` schema was read at V59 on 2026-09-27. Verify every target environment independently before migration.
- The IT-GO-1225 B5 result was a local development switch, not a production release. B6 cleanup deleted the legacy permission-only path and six tables. The current machine-local `dataocean` schema was read at V59 on 2026-09-27; it is not evidence that V60–V64 have been applied there. Other environments require their own verification.
- There is no `V53` migration file, and **V53 is permanently unused**. New migrations must use a version higher than the highest occupied version (currently V64 in this working tree); recheck before implementing P9. `outOfOrder` is not enabled, so do not add V53 later or create an empty placeholder.

## Python Service Notes

Main package:

```text
python-service/dataocean/
```

Important modules:

- `iam_s1/graph.py`: current IAM-SIMPLE-1 LangGraph NL2SQL workflow and Redis checkpoint recovery; the legacy `agent/` package was deleted in B6.
- `conversation`: Java-requested conversation summary generation at the unchanged `/internal/query/context-summary` route.
- `rag`: chunking, embedding, Milvus vectorization, retrieval, reranking.
- `sandbox`: SQL AST validation, permission rewriting, read-only execution.
- `knowledge`: skills.md draft generation.
- `chart`: ECharts option generation.
- `prompt`: prompt fetching and rendering.
- `infra`: LLM, embedding, SSE, cancellation, health, config, timeout budget, parsers, auto tagger, and config reload support.

Python route notes:

- `/internal/query/context-summary`: structured conversation summary generation for Java.
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
- New admin pages must follow the domain/workspace ownership and two-level sidebar rules in `docs/development/completed/DataOcean后台重构状态与整改计划.md` before adding routes or navigation entries.
- `/admin/assets`: metadata catalog search and entity graph entry.
- `/admin/semantics/glossaries`: glossary management.
- `/admin/platform/operation-logs`: operation log management.
- `/admin/platform/ai`: AI provider/model/embedding configuration.

The query page persists server-side conversations and reloads message-ID-paged history through `/api/iam-s1/query/conversations` and `/api/iam-s1/query/conversations/{conversationId}/messages`. It also checks `/api/datasources/{datasourceId}/readiness` so users can only ask against sources whose lifecycle is ready.

Frontend API modules live under `frontend/src/api/`, including notification and admin modules for catalog, glossary, metadata, operation-log, permission, prompt, system, user, versioning, and related domains.

## Key APIs

Internal service APIs:

| Direction | Path | Purpose |
| --- | --- | --- |
| Java -> Python | `POST /internal/iam-s1/query/execute` | Start S1 LangGraph query through SSE |
| Java -> Python | `POST /internal/query/context-summary` | Generate a structured conversation summary from Java-provided message deltas |
| Java -> Python | `POST /internal/iam-s1/query/tasks/{taskId}/cancel` | Cancel S1 query |
| Python -> Java | `POST /internal/iam-s1/query/tasks/{taskId}/attempts/authorize` | Reauthorize one SQL attempt before execution |
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
| `POST` | `/api/iam-s1/query/ask` | Start S1 query |
| `GET` | `/api/iam-s1/query/tasks/{taskId}` | Query task result |
| `POST` | `/api/iam-s1/query/tasks/{taskId}/resume` | Resume a processing task |
| `GET` | `/api/iam-s1/query/conversations` | Conversation list |
| `GET` | `/api/iam-s1/query/conversations/{conversationId}/messages` | Message-ID-paged conversation history |
| `GET` | `/api/datasources/{datasourceId}/readiness` | Current user's datasource askability |
| `GET` | `/api/admin/datasources/{id}/readiness` | Admin datasource lifecycle readiness |
| `GET` | `/api/admin/catalog/search` | Metadata catalog search |
| `GET` | `/api/admin/catalog/entities/{id}` | Entity detail |
| `GET` | `/api/admin/glossary` | Glossary list |
| `POST` | `/api/admin/glossary/{id}/terms` | Create glossary term |
| `POST` | `/api/admin/glossary/terms/{id}/review` | Review glossary term |
| `POST` | `/api/iam-s1/access-requests` | Submit S1 access request |
| `GET` | `/api/iam-s1/access-requests/queue` | Review queue |
| `POST` | `/api/iam-s1/access-requests/{requestId}/review` | Review S1 access request |
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

- Java (2026-09-25, after B6): **585 passed, 0 failures, 0 errors, 0 skipped**. The drop from 603 is exactly the 18 legacy tests deleted with their subjects; no kept test was lost.
- Python (2026-09-25, after B6): **105 passed**. The drop from 228 is exactly the 123 legacy tests removed (120 in 11 deleted files, 2 agent classes from `test_f0_regression.py`, 1 from `test_rag_context_contract.py`), reconciled file by file.
- Frontend (2026-09-25, after B6): Vitest **73 passed** (12 files); `npm run build` exit 0.
- Before B6 the same day: Java **603**, Python **228** (4 skipped, pre-existing count 207), frontend Vitest **70** — the required-secrets hardening baseline (internal token + JWT secret; 22 tests added then across `InternalTokenValidatorTest`, `InternalTokenFilterTest`, `JwtTokenProviderTest`).
- `mvn test` needs no external service; `@SpringBootTest` uses H2 with Flyway disabled. `src/test/resources/application-test.yml` must supply both `internal.token` and `jwt.secret` — neither has a default, so omitting either makes every `@SpringBootTest` fail to start.
- Remaining test gap: the S1 query path end to end — SQL generation/validation/execution, output aliasing, source-trace completeness, RAG degradation, and Java query integration. The legacy Agent workflow it replaced no longer exists.

## Security Constraints

- Business database connections must use read-only accounts; passwords are AES-256 encrypted at rest.
- SQL execution must pass AST validation. Only safe `SELECT` queries are allowed.
- Enforce LIMIT 10000, execution timeout, and maximum subquery depth.
- Row/column permissions are enforced in Python sandbox AST rewriting, not by prompt alone.
- Java performs final masking for sensitive fields.
- `DEPRECATED` and `BLOCKED` tables/columns must not be retrieved, used for SQL generation, or approved through temporary access requests.
- JWT blacklist lives in Redis; logout invalidates tokens.
- Do not log passwords, raw JWTs, API keys, or secrets.
- `/internal/*` on both services is guarded by one shared `X-Internal-Token` value, and that token is the only control on those paths. Rules that must not be relaxed:
  - No default value in tracked config (`application.yml` uses `${INTERNAL_TOKEN:}`, `.env.example` ships it empty).
  - Missing, blank, whitespace-containing, or sub-32-character tokens must make the service refuse to start. The check must stay independent of `spring.profiles.active`.
  - Java grants `ROLE_INTERNAL` in `InternalTokenFilter` and `SecurityConfig` requires that authority for `/internal/**`; the filter and the authorization rule share one `RequestMatcher`. Never restore `permitAll()` for `/internal/**` — that turns "filter skipped" into "request allowed".
  - Compare in constant time, encoding to UTF-8 bytes first (header values arrive latin-1 decoded; a non-ASCII byte would otherwise raise instead of returning 403).
  - Java and Python must be configured with the same value; a mismatch surfaces as 403s, so keep the 401/403 warn-level logging in `schema_retriever` rather than lowering it to debug.
- `jwt.secret` is required with no usable default, and a public value would let anyone forge any user's session. Keep it absent from **both** `application.yml` and `application-dev.yml` (the dev copy is the one that takes effect, since `dev` is the default profile). Validation lives in `JwtTokenProvider#buildSecretKey`; a missing, blank, whitespace-containing, or sub-32-byte secret must fail startup. Local development supplies it from the gitignored `config/application-local.yml`. `openssl rand -base64 32` generates a suitable value.
- Note the asymmetry: the internal token must match across Java and Python; `jwt.secret` is Java-only (Python never verifies JWTs), so it never needs to be shared between services.
- AI model, API key, temperature and similar settings do **not** belong in this category: they are managed through the admin page, stored in `sys_config`, and pushed to Python at runtime. Missing AI config in `.env` only logs a warning and must never block startup. Do not "harden" them into required variables.

## Local Environment Rules

- Do not add project downloads, generated assets, dependency caches, exported files, or temporary project files to the repository or an arbitrary system root. Use a verified machine-local workspace/runtime directory recorded in `.dataocean/local-environment.md`, or a temporary directory created by the relevant tool.
- Docker work starts with a read-only inventory: inspect `docker ps -a` and the relevant container details (status, labels, ports, mounts, and Compose group) to identify containers the user already started and verify their purpose before connecting to or changing data in them.
- An instruction to use already-started containers authorizes reuse of suitable existing containers only. It does not authorize `docker run`, `docker compose up`, creating/replacing containers or volumes, or starting stopped containers.
- Reuse an existing container only after confirming it is the intended isolated test service. Do not create a replacement because a container is stopped, missing, on a different port, or has an incompatible version.
- If no suitable running container exists, or its data/purpose cannot be verified, do not create, recreate, start, delete, seed, or reset it. Tell the user the exact service/container needed and ask them to start it; continue independent work that does not depend on it.
- Creating any new container or infrastructure service requires a separate, explicit user request for that setup. Before running the requested Docker command, state the exact service/container, ports, data volume, and reason, and wait for confirmation. Broad permission to use local test infrastructure is not permission to provision it.
- Never run destructive database operations against an existing container until its disposable test purpose and target database have been verified. If that cannot be established read-only, stop that operation and ask the user.
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
- Java owns durable conversation history and long-term summaries. Python receives Java's conversationId/threadId for safe LangGraph checkpointing plus request-scoped history and summary; do not create an independent Python conversation store or duplicate Java's history authority.
- Java consumes Python SSE as a client. Do not replace this with Spring `SseEmitter`; fix client-side SSE parsing and read timeouts instead.
- **Caching is Redis-only**: New best-effort caches use the existing `RedisTemplate<String, Object>` bean (Java) or `_get_redis()` (Python). Cache failures degrade with warning logs. The S1 LangGraph Redis checkpoint is a separate required safety boundary: its initialization/recovery failure must fail the query explicitly, never fall through to execution without a checkpoint.
- Use focused tests when changing lifecycle, RAG, SQL safety, permissions, or public API behavior.
- Preserve user changes in the working tree; do not reset or revert unrelated files.
- Important module work should update `AGENTS.md`, `CLAUDE.md`, and relevant `README`/docs when project facts change.
- For frontend/backend integration, keep screenshots for every verified user-visible feature under `output/playwright/` with descriptive names.

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the relevant module plan
under specs/<module>/plan.md.
<!-- SPECKIT END -->
