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

Last updated: 2026-09-18.

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
| Java user/auth/permission modules | Legacy implementation remains for pre-switch operation; IAM-SIMPLE-1 B1 is committed/pushed, B2 is uncommitted, and B3 is committed with the B3.1–B3.6 fixes applied but not switched |
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

Track B targets the simple permission design in `docs/development/guides/DataOcean-完整权限体系设计.md` (IAM-SIMPLE-1). B0 through B4 are committed and pushed on branch `codex/iam-simple-1-implementation` (B1 `e373c8152b095048ab8a3d7ea7b571e188f29b9a`, B2 `6eb5427`, B3 `d9a0c3b`, B3.1–B3.6 `d08d37f`/`70d4999`, B4 `8a9c5a1`, B4 review fixes `228a306`). **B4-A (unified S1 authorization framework) is committed as `a745ea1`/`38ae102`; batch 5 stays uncommitted in the working tree**: three method-level annotations (`@IamS1Global`, `@IamS1Resource`, `@IamS1ScopedList`), `IamS1AuthorizationAspect` (delegates to `IamS1AdminGuard`, never reimplements the algorithm), a fixed `IamS1ResourceResolverRegistry` with DATASOURCE/SNAPSHOT/METADATA_ENTITY/METADATA_COLUMN resolvers, and `IamS1FunctionCatalog.FunctionScope` so the aspect rejects a source-scoped function wrongly declared global. Batches 1–5 (**95 endpoints**, batches 1–4 committed as `38ae102`, batch 5 in the working tree) are migrated to the annotations: Dashboard 1, DatasourceAdmin 11, MetadataCatalog 12, MetadataCollection 8, SnapshotVersion 9, MetadataGovernance 12, Glossary 14, KnowledgeDoc 18, Prompt 10. List scoping, batch validation, dual-snapshot checks, lineage clipping, row locks and business rules all stay in the Service. `IamS1EndpointCoverageTest` enumerates real `HandlerMethod`s via `RequestMappingHandlerMapping` with a per-endpoint exemption baseline (120 entries, keyed by HTTP method + full path); `MetadataMaskCandidateConfirmTransactionTest` covers the full concurrent `confirm()` transaction. Still open: the 22 `IamS1*` permission-domain endpoints still on explicit guards, and batch 6 (operations/platform, 32 endpoints). Batch 5 (semantic centre) added resource type `KNOWLEDGE_DOCUMENT` (document → datasource, plus a check that the current version and its source snapshot agree; 404 missing / 409 broken ownership) and `KnowledgeDocCrudService.listDocsInDatasources` pushing the responsible scope into SQL (an explicitly requested out-of-scope `datasourceId` is 403, not an empty page). `generate-from-snapshot` re-checks that the requested `datasourceId` equals the snapshot’s real owner before any metadata read or Python call. `glossary:view/manage/approve` stay `FunctionScope.MIXED` and are now frozen: an unbound term checks the function only, while a bound one is checked per linked source (view returns only fields inside the responsible sources and hides terms with no visible source; writes reject the whole operation if any linked source is not managed; link/unlink also resolve the target entity’s real datasource). `@IamS1ScopedList` now accepts RESOURCE **or** MIXED while still rejecting GLOBAL; `@IamS1Global`/`@IamS1Resource` still reject MIXED. The dynamic scope lives in `GlossaryScopeService`, which reuses the Guard and CapabilityService rather than copying authorization SQL. Prompt’s three codes are independent and `PATCH enabled` was tightened from “manage or approve” to `prompt:manage` only. The read-only `POST /knowledge-docs/{id}/preview-chunks` uses `knowledge:view` and is registered as an exact endpoint exception in the coverage scan — never a blanket “any POST + view”. A review of batch 5 then found two P1s and one P2, all fixed: term scope is a **three-state** `UNBOUND`/`BOUND`/`BROKEN` (a relation whose target entity is gone or has no `datasource_id` previously produced an empty set, which the rule read as “legitimately unbound → allow globally”; `BROKEN` now hides the term from the list, returns 409 for write/review/delete/link, and makes the containing glossary unwritable); a new `KnowledgeOwnershipValidator` guards the **historical version chain** (every version’s `datasource_id`, its source snapshot, and every vector task’s `datasource_id` must match the document, else 409 — shared by `listVersions`, `getVersion`, `createVersion`, `listVectorTasksOfDocument` and the document resolver, so rollback writes nothing and creates no vector task when ownership is invalid); and `link-column` only accepts `TYPE_COLUMN` entities. `IamS1AuthorizationAspect` must keep `@Aspect` **and** `@Component`, guarded by the proxy assertions described in `docs/development/guides/DataOcean-IAM-SIMPLE-1鉴权接入设计.md` §11.6.1/§11.7.5. B4 remains a "permissions-and-organization domain plus standalone secure query entry" first cut — the operations/platform domain still runs the pre-switch path. B2 adds isolated data grants, explicit columns, structured row conditions, field protection, department inheritance, one Resolver/preview path and Redis-only revision snapshots. B3 adds an independent Java/Python S1 query path, strict contract, Context Firewall, permission-aware knowledge filtering, full SQL AST checks, parameterized row conditions, source trace, Java final protection and current-permission rechecks for task/history/SQL/export/feedback/SSE. B3.1 fixes three review-found P1 defects: the outer row cap is no longer defeated by a LIMIT inside a subquery/derived table/UNION branch (`limit_rule` inspects every LIMIT node), field `usage` (PROJECTION/FILTER/JOIN) is enforced in the AST with star expansion limited to projectable fields (server-injected row-condition predicates are exempt), and SSE result push reuses the `get` read path so the viewSql gate and current-permission re-masking always apply. B3.2 fixes four blocking defects: table aliases, CTE names and derived-table aliases resolve inside their own scope (an inner subquery may no longer shadow an outer alias and redirect the reference to another table), cross-database qualified table names such as `other_database.orders` are rejected, row conditions are injected only into the scope that actually reads the table (comma/cross joins without their own `ON` fall back to WHERE instead of fabricating one), and `query:use` is rechecked on read so task/history/SQL/CSV/feedback/SSE all refuse once the function is revoked. Derived and CTE `alias.column` references now trace through their projection to physical sources. B3.3 fixes a masking bypass in set operations: `UNION`/`UNION ALL`/`INTERSECT`/`EXCEPT` take their result column names from the first branch but every branch feeds the same positions, so output sources are aggregated positionally across all branches (including top-level set operations, CTEs, derived tables and nested branches); a first-branch-only or first-match lookup left a later branch's masked column untagged on the final column name, so the result was never masked. Branches with mismatched column counts are rejected (in both directions: a later branch with fewer **or more** columns). B3.4 fixes a protection-consistency defect and a contract gap: when several non-empty mask policies (for example `PHONE` and `EMAIL`) converge on one output column name, final masking is keyed by column name and can only honour one of them, so such queries are rejected regardless of branch order (two same-named output columns carrying different policies are rejected too, identical policies stay allowed); and the set-operation branch column-count check now rejects both directions instead of only the shorter-later-branch case. B3.5 fixes a normalisation gap and a validation-order gap: mask-conflict detection now normalises output column names to lower case exactly like Java's `maskResultByFields` (so `phone AS X` and `email AS x` can no longer bypass the check), Java's eight `toLowerCase()` calls on the masking decision path use `Locale.ROOT`, and a set-operation branch output with no traceable field source (a constant or literal) is rejected during AST validation instead of after the query has already run against the database. B3.6 makes the Java side an independent final boundary for mask conflicts: `deriveOutputMasks` aggregates policies by `outputColumn.toLowerCase(Locale.ROOT)` and fails closed when several non-empty policies target one column — completion refuses to persist (`REJECTED_FINAL_PROTECTION`) and task/history reads refuse to return, so a conflicting `sourceTrace` from a legacy persisted task, an upstream regression or an abnormal source can no longer be silently masked with one arbitrary policy. The real database is still at V50: V51, V52, V54, V55, V56 and V57 have never been executed against real MySQL, no `iam_s1*` table exists there, and runtime/browser acceptance, bootstrap and B5 remain incomplete. A 2026-09-20 review of the B4 code found six P1 defects in the frontend and four P2 defects in the backend; all six frontend P1 and backend P2 items B1 (audit text) and B2 (queue binding scope) are fixed in the working tree but **not committed**, and no browser acceptance has been run against them. No P0 privilege-escalation path was found. Roles own functions; department/user/role data grants own queryable data; each user-role binding owns its admin datasource scope. Build and verify the new system, switch and verify it in real operation, then remove old permissions. Do not map/backfill old grants, mix permission algorithms, or delete old permissions during initial setup. New authorization decisions must read only isolated/versioned IAM-SIMPLE-1 facts; old role-permission relations, same-named legacy codes, old JWT authorities, and old permission caches cannot seed or grant new permissions. B0 must commit the complete consumption and B6 deletion/preservation inventory before B1; B5 must explicitly bootstrap and verify at least one new protected system administrator without inferring it from legacy roles. Preserve accounts, real organizations, business assets, conversations, audit history, and Flyway history.

B0 文档已评审通过；`docs/development/轨道B-B0权限清单与决策冻结.md` 是权限消费清单、IAM-SIMPLE-1 独立新表方案、新契约、启动式首个管理员 bootstrap 和 B6 删除/保留基线。B1～B4 均已提交并推送（分支 `codex/iam-simple-1-implementation`，HEAD `228a306`）；B4-A 已实现但未提交。2026-09-20 复跑基线：完整 Java `405/405`、Python `204 passed, 4 skipped, 1 warning`、前端 `vue-tsc -b && vite build` 通过 + Vitest 6 文件 20 个通过。真实数据库停留在 V50，V51/V52/V54/V55/V56/V57 六个迁移均未执行，`iam_s1*` 表在真实库中不存在；未执行服务/浏览器验收、真实 bootstrap 或 B5。2026-09-20 复审另在 B4 代码中发现前端 6 个 P1 与后端 4 个 P2（B4 已完成部分从未被真人操作过）；前端 6 个 P1 与后端 B1（审计文本）、B2（队列同绑定）已修复，修复尚未提交、未做浏览器验收，后端 B3/B4 与 P3 三项仍未处理。

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
- **None of V51, V52, V54, V55, V56 or V57 has ever been executed against real MySQL.** The live database is still at V50, so no `iam_s1*` table exists and `query_task.suggested_questions` (V52) is missing from the live schema even though the `QueryTask` entity maps it. Production runs `flyway.enabled=true`, so the first real startup applies all six unverified migrations at once.
- There is no `V53` migration file (the number is reserved by the P9 alert-history plan). Because `outOfOrder` is not enabled, adding V53 *after* V54–V57 have been applied would fail validation and break startup.

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
- Verify the profile's hostname and any drift-prone runtime state with read-only checks before relying on it. The profile is a machine-local hint, not proof that a process, port, database, or container is currently available.
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

On this Windows machine, prefer the pinned Maven path when needed:

```powershell
D:\tool\apache-maven-3.9.16\bin\mvn.cmd spring-boot:run
```

Python service:

```bash
cd python-service
uv run uvicorn dataocean.main:app --reload --port 8000
```

Infrastructure:

```bash
docker start mysql redis etcd minio milvus
```

Infrastructure is managed as persistent host-level containers: `mysql` (one container), `redis` (one container), and the Milvus Standalone group (`milvus`, `etcd`, and `minio`), with persistent `dataocean-shared-*` volumes. The repository does not retain infrastructure Compose files; other local projects may reuse these services through `localhost`. Stopping DataOcean application processes must not remove these shared containers or volumes.

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

- Do not add project downloads, generated assets, dependency caches, exported files, or temporary project files to `C:\`.
- Keep project-related downloaded/generated files under `D:\Java_study\GraduationProject` unless required by developer tooling.
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
