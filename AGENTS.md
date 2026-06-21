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

Important boundaries:

- Frontend calls Java only. Java calls Python through internal APIs.
- Java owns management lifecycle: users, permissions, data sources, metadata governance, review, versioning, publishing, task state, masking, audit, and durable persistence.
- Python owns AI/RAG execution: query rewrite, glossary hints, chunking, embedding, Milvus writes, retrieval, reranking, SQL generation, SQL validation, and sandbox execution.
- Java to Python calls use `RestClient`; knowledge/RAG clients use `@Retryable` where configured. SSE streaming and health checks should not be blindly retried.
- Query results are not cached because similar questions, relative dates, and permission differences can make reuse unsafe.

## Current Status

Last updated: 2026-06-20.

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
| Java user/auth/permission modules | Complete; permission policy priority/time conditions and access approval are implemented |
| Java datasource/metadata/governance/versioning modules | Complete; metadata entity graph and event recording are implemented |
| Java glossary module | Complete; glossary and term approval flow are implemented |
| Java knowledge/skills.md lifecycle | Complete, with Python-owned chunking integration |
| Java query/audit/field confidence modules | Core complete; conversation persistence and feedback confidence updates are implemented |
| Java prompt module | Complete, including approval workflow, version history, and rollback |
| Java system/dashboard modules | Complete; AI config management and admin dashboard are implemented |
| Python Agent workflow | Core complete, with timeout/cancel handling, glossary hints, and degraded result propagation |
| Python RAG/vectorization | Complete; staging/verified vector rebuild semantics are in place |
| Python SQL sandbox | Core complete |
| Python chart generation | Complete with fallback behavior |

Known follow-up areas live in `docs/development/后续开发.md`. The seven-stage refactor roadmap is complete; do not treat `docs/development/DataOcean统一执行路线图.md` as an active implementation plan unless the user explicitly asks to revisit it.

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

## Core Domain Concepts

- `skills.md`: business semantic knowledge generated from metadata governance results, reviewed by humans, then published into RAG. The expected structure has six sections: document source, core table descriptions, Join Paths with concrete SQL conditions, metrics with SQL expressions, field notes, and common query scenes.
- Field confidence: 0-100 score influencing SQL field selection. Usage, successful execution, and feedback adjust confidence.
- Metadata governance loop: collect -> quality check -> fix -> review/publish snapshot -> generate `skills.md` -> vectorize -> feed query lineage and feedback back into governance.
- RAG admission control: only approved and allowed governance states should enter retrieval. `DEPRECATED` and `BLOCKED` fields must not be retrieved or used.
- Sensitive fields: may enter RAG with mask metadata, but Java gateway performs final masking.
- Entity relationship graph: metadata entities, relationships, glossary terms, tags, lineage, and downstream impact analysis share the `metadata_entity`/`metadata_relationship` model.
- Glossary: approved terms and synonyms are sent from Java to Python and used during query rewrite.
- Access approval: users can request temporary data access; approval creates auditable temporary allow policies with expiry.
- Conversation persistence: Java owns durable conversation and message storage. Python receives request-scoped `conversation_history`.

## RAG And skills.md Lifecycle

The current RAG implementation intentionally uses Python for chunking and vector operations.

Responsibilities:

- Java manages `skills.md` lifecycle: `DRAFT -> PENDING_REVIEW -> APPROVED -> INDEXING -> PUBLISHED`.
- Java manages review, versioning, publish task state, rollback state, audit, and MySQL chunk snapshots.
- Python chunks `skills.md` through `/internal/rag/chunk`, embeds chunks, writes vectors to Milvus, verifies vector counts, and serves retrieval/reranking.
- Java marks the new version active only after Milvus write and verification succeed.
- Old active vectors remain available when new vectorization fails.

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
- Old active vectors are not deleted before the replacement version is successfully verified.
- Same-version rebuilds delete only `doc_id + version_no`, not all vectors for the document.
- Do not implement datasource-wide force vectorization as "delete old vectors, then write new vectors". Use doc/version scoped rebuilds or verified staging writes.

Current RAG details:

- Java-side `KnowledgeChunkSplitter` was removed.
- Python `chunker.py` is the source of truth for chunking.
- Python chunking splits by `##` sections and then by `###` subsections for fine-grained chunks.
- RAG reranking applies chunk-type bonuses for `JOIN_PATH`, `METRIC`, `FIELD_NOTE`, and `QUERY_SCENE`.
- Prompt token budget is 5000 total, with `skills` and `schema` both budgeted at 1500 and treated as highest-priority context.

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
- `governance`: metadata quality checks and governance status.
- `versioning`: metadata snapshot lifecycle and review.
- `knowledge`: skills.md lifecycle, chunk snapshot persistence, vector publish tasks.
- `query`: Java-side NL2SQL task management, conversation persistence, SSE bridge, result persistence, fallback chunk loading, glossary term passing.
- `fieldtag`: field tags, confidence, feedback. The older `PredefinedTag` path is deprecated in favor of classification/tag governance where applicable.
- `glossary`: glossary and glossary term management/review.
- `audit`: query audit, lineage, quotas, alerts.
- `permission`: access policy, data masking, priority/time conditions, access approvals, permission change logs.
- `prompt`: prompt template CRUD, approval workflow, version history, rollback, and internal template API for Python.
- `system`: config, notifications, operation logs, AI config, scheduling.
- `dashboard`: admin homepage statistics aggregation.

Database migrations live in:

```text
backend/DataOcean/src/main/resources/db/migration/
```

Migration notes:

- `V1-V14`: user, datasource, metadata, system config, governance, snapshot audit, knowledge tables.
- `V15`: query task and conversation persistence tables.
- `V16-V22`: field tags, user feedback, audit, quotas, notifications, operation logs.
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
- `/internal/rag`: chunking, vectorization, retrieval, and vector management.
- `/internal/sql`: SQL validation, execution, and connection-pool management.
- `/internal/chart`: ECharts option generation.
- `/internal/knowledge`: skills.md draft generation.
- `/internal/prompts`: prompt template access.
- `/internal/config/reload`: AI config reload callback.

## Frontend Notes

Frontend routes are split between:

- `/query`: user-facing intelligent query flow.
- `/admin/*`: governance, metadata, knowledge, glossary, prompt, audit, permissions, and system management.
- `/admin/metadata/catalog`: metadata catalog search and entity graph entry.
- `/admin/glossary/list`: glossary management.
- `/admin/system/operation-logs`: operation log management.
- `/admin/system/ai-config`: AI provider/model/embedding configuration.

The query page persists server-side conversations and can reload historical messages through `/api/query/conversations` and `/api/query/conversations/{id}/messages`.

Frontend API modules live under `frontend/src/api/`, including notification and admin modules for catalog, glossary, metadata, operation-log, permission, prompt, system, user, versioning, and related domains.

## Key APIs

Internal service APIs:

| Direction | Path | Purpose |
| --- | --- | --- |
| Java -> Python | `POST /internal/query/execute` | Start NL2SQL query through SSE |
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
| `GET` | `/api/admin/catalog/search` | Metadata catalog search |
| `GET` | `/api/admin/catalog/entities/{id}` | Entity detail |
| `GET` | `/api/admin/glossary` | Glossary list |
| `POST` | `/api/admin/glossary/{id}/terms` | Create glossary term |
| `POST` | `/api/admin/glossary/terms/{id}/review` | Review glossary term |
| `GET` | `/api/admin/access-approvals` | Access approval list |
| `POST` | `/api/admin/access-approvals` | Submit access request |
| `POST` | `/api/admin/access-approvals/{id}/review` | Review access request |
| `GET` | `/api/notifications` | Current user notifications |
| `PATCH` | `/api/notifications/{id}/read` | Mark notification read |
| `GET` | `/api/notifications/unread-count` | Current user unread notification count |

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
docker compose up -d
```

Tests:

```bash
cd python-service
uv run pytest

cd backend/DataOcean
mvn test
```

Latest documented verification:

- Python: 102 tests passed, 4 skipped.
- Java: 103 tests passed.
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
- Local Docker currently has MySQL, Redis, Elasticsearch, Kibana, RabbitMQ, Nacos, and Seata containers available. Treat exact local credentials as private local notes, not repository documentation.
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
- Java owns durable conversation history. If Redis memory is introduced later, avoid having Java and Python both write the same conversation-history key.
- Java consumes Python SSE as a client. Do not replace this with Spring `SseEmitter`; fix client-side SSE parsing and read timeouts instead.
- Use focused tests when changing lifecycle, RAG, SQL safety, permissions, or public API behavior.
- Preserve user changes in the working tree; do not reset or revert unrelated files.
- Important module work should update `AGENTS.md`, `CLAUDE.md`, and relevant `README`/docs when project facts change.
- For frontend/backend integration, keep screenshots for every verified user-visible feature under `output/playwright/` with descriptive names.

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the relevant module plan
under specs/<module>/plan.md.
<!-- SPECKIT END -->
