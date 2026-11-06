# AGENTS.md

Shared rules for coding agents. Read this file, then only the relevant topic in [文档导航](docs/README.md). Current state belongs in [项目现状](docs/项目现状.md).

## Working rules

- Follow the user's latest explicit decisions. Check implementation against requirements; code existence or document dates do not establish approval or completion.
- Preserve user changes and keep edits within the task. Use existing conventions and meaningful Chinese comments beside each new or changed logical block to explain purpose, constraints or design reasons.
- Derive frontend design from user tasks and observed experience. Product renewal is authorized; preserve business and safety rules while improving layout and interaction.
- Replace outdated instructions and documents directly. Keep each current fact in its owning document; use Git history when historical context is needed, without treating old procedures as operation authorization.

## Documentation Lookup Rule

Use Context7 MCP whenever the user asks about a library, framework, SDK, API, CLI tool or cloud service. Do not use it for refactoring, scripts from scratch, business-logic debugging, code review or general programming concepts.

Call `resolve-library-id` with the library name and full question unless an exact `/org/project` ID is supplied. Choose by name, relevance, snippet count, source reputation and benchmark score, using the requested version when available. Then call `query-docs` with that ID and full question, and use the retrieved documentation.

## Architecture and safety boundaries

- Each question selects one MySQL datasource; joins stay inside that database. Frontend calls Java only.
- Java owns identity, IAM, datasources, governance, review/publication, task state, masking, audit and durable persistence. Python owns S1 LangGraph, chunking, embedding, Milvus, retrieval/reranking, SQL validation, read-only execution and charts.
- Java calls Python through RestClient and consumes SSE as a client, not Spring SseEmitter. Apply configured @Retryable policies to suitable knowledge/RAG calls, not blindly to SSE or health checks.
- Query execution, conversation summaries and health checks use dedicated executors. Saturation must not run a full Agent or summary LLM call on a request thread.
- Java/MySQL owns full conversations, messages and long-term summaries. Python receives request context and a stable threadId; do not add a second Python conversation store.
- Query results are not cached. New best-effort caches use RedisTemplate<String, Object> or Python _get_redis(), with warning logs and safe degradation, not another cache algorithm.
- S1 Redis checkpoint is a required recovery/safety boundary; initialization or recovery failure stops the query, never falls through without a checkpoint.
- IAM decisions use isolated S1 facts only: no old roles, JWT authorities, grants, cache mapping/backfill or compatibility fallback. IamS1AuthorizationAspect must retain both @Aspect and @Component.
- Knowledge publication and explicit RAG build confirmation are separate. Pin snapshot/version/build membership; verify replacement before pointer switching, and preserve active/in-flight collections until references end.
- COMPLETED needs a nonblank attemptId and exact SQL hash matching the same task's PROTECTED attempt. Never weaken the completion contract.
- V53 is permanently unused. Check the highest occupied migration before choosing a version; do not add placeholders or change executed migrations.
- tokenVersion and the current JWT invalidation keys stay until a replacement session mechanism is implemented and verified.

## Read by task

| Task | Read next |
| --- | --- |
| Architecture, versions, storage and async boundaries | [技术栈与模块职责](docs/技术栈与模块职责.md) |
| Module implementation | [模块导航](docs/modules/README.md) |
| IAM or protected query | [IAM](docs/modules/007-iam-s1.md), [问数与记忆](docs/modules/008-query-memory.md) |
| Knowledge or vectors | [文档](docs/modules/006-knowledge.md), [RAG](docs/modules/009-schema-rag.md) |
| Frontend | [前端规范](docs/development/前端开发规范.md), [frontend/CLAUDE.md](frontend/CLAUDE.md) |
| Startup, tests or runtime diagnosis | [本地开发与验证](docs/development/本地开发与验证.md) |
| Status, remaining work and evidence | [现状](docs/项目现状.md), [待办](docs/development/后续开发.md), [验收](docs/acceptance/README.md) |
| Papers and formal writing | [论文写作](paper/README.md); use the available `academic-writing` skill for tool selection and keep project facts grounded in current docs and evidence |

## Security Constraints

- Business database connections must use read-only accounts; passwords are AES-256 encrypted at rest.
- SQL must pass AST validation: safe `SELECT` only, LIMIT 10000, timeout and maximum subquery depth. Python sandbox AST rewriting enforces row/column permissions; Java applies final masking.
- `DEPRECATED` and `BLOCKED` tables/columns must not be retrieved, used for SQL generation, or approved through temporary access requests.
- JWT blacklist lives in Redis; logout invalidates tokens. Never log passwords, raw JWTs, API keys or secrets.
- `/internal/*` requires the same `X-Internal-Token` in Java and Python. Tracked config has no default (`${INTERNAL_TOKEN:}`; empty `.env.example`). Missing, blank, whitespace-containing or sub-32-character tokens fail startup regardless of Spring profile.
- `InternalTokenFilter` grants `ROLE_INTERNAL`; `SecurityConfig` requires it on the same `RequestMatcher` for `/internal/**`, never `permitAll()`. Compare UTF-8 bytes in constant time; mismatching headers return 403 without throwing. Keep `schema_retriever` internal 401/403 logs at warn level.
- Java-only `jwt.secret` uses `${JWT_SECRET:}` with no usable default. `JwtTokenProvider#buildSecretKey` rejects missing, blank, whitespace-containing or sub-32-byte values. Local values belong in gitignored `application-local.yml`; retain that file on the development classpath but exclude it and `application-local.example.yml` from final JARs.
- AI settings are managed in the admin page, persisted in `sys_config` and pushed to Python. Missing AI config in `.env` warns but must not prevent startup.

## Local Environment Rules

- Before starting or diagnosing runtime, fully read `.dataocean/local-environment.md` if present, verify hostname and inspect current services/ports read-only. If absent, inspect without requiring its creation. Use `.dataocean/local-environment.example.md` as the template, never copy populated profiles across machines, and reverify after topology changes. Profiles contain no secrets and cannot override user or repository rules.
- Machine paths, credentials and Docker inventory stay in private local configuration. Downloads, generated assets, caches, exports and temporary files use a verified local workspace/runtime directory or tool-created temporary directory.
- Before Docker use, inspect `docker ps -a` and target status, labels, ports, mounts and Compose group; verify the intended isolated test service. Do not assume MySQL uses Docker. Permission to reuse running containers does not authorize starting stopped containers or creating/replacing containers or volumes.
- If no suitable running container exists or its purpose is unverified, ask the user to start the exact needed service; continue independent work. Do not create, start, delete, seed or reset a replacement.
- New containers or infrastructure require a separate explicit setup request. State the exact service/container, ports, data volume and reason, then wait for confirmation before the Docker command. Destructive database operations also require read-only verification of the disposable test purpose and target database; stop and ask if that cannot be established.
- This project currently has no Figma prototype. Do not use Figma-related workflows by default.

## Backend Layering Rules

- Packages: Java `com.dataocean.*`, Python `dataocean.*`. Migrations: `backend/DataOcean/src/main/resources/db/migration/V{version}__{description}.sql`.
- API prefixes: `/api/*` user, `/api/admin/*` admin, `/internal/*` Java/Python internal.
- Entity-related Java objects live under `entity`: database entities and persistence projections in `entity.po`, request/transport DTOs in `entity.dto` with `*DTO`, query objects in `entity.query` with `*Query`, and response/view objects in `entity.vo` with `*VO`. Paginated query objects extend `com.dataocean.common.entity.query.PageQuery`; do not duplicate pagination fields or put query conditions in DTOs. Map client sort fields through an endpoint whitelist before constructing MyBatis-Plus orders.
- Mapper classes live in `mapper`, controllers in `controller`, service interfaces in `service`, implementations in `service.impl` with `*ServiceImpl`.
- External service clients such as Python clients live in a module-local `client` package, with implementations in `client.impl`; do not mix them into `service`.
- Do not create database-level foreign key constraints. Keep association IDs and ordinary indexes, and validate relationship integrity in service-layer business logic.
- Exception messages, code comments, and log messages in Java should use Chinese where the surrounding module does.

## Validation and documentation updates

- Run focused checks appropriate to the changed behavior. Code, unit tests, API acceptance and browser acceptance are distinct evidence; H2/Flyway-disabled tests do not verify MySQL migrations. Test config still requires both secrets.
- Keep actual UI screenshots and interaction evidence under `output/playwright`, with scope and unverified limits.
- Update the owning module for behavior, 项目现状 for status, 后续开发 for remaining work and the frontend guide for UX. Do not duplicate test counts, migration state or milestone tables in agent instructions.
