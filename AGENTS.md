# AGENTS.md

This is the shared instruction entry for coding agents in DataOcean. Read the core constraints here first, then load only the topic needed for the task. Current project state is maintained in [项目现状](docs/项目现状.md), not in this file.

## User decisions and progressive reading

- Later explicit user corrections override superseded plans, screenshots and prompts. A newer date alone does not prove user approval.
- Use code to check implementation, not to erase a user requirement. Record confirmed gaps or mark unresolved decisions; do not revive an old plan by inference.
- Historical documents and specs do not authorize their embedded setup, migration, bootstrap or deletion steps. Check current applicability and user authorization.
- Preserve user changes. Do not reset, revert, delete or reformat unrelated files.
- Read [文档导航](docs/README.md) for ownership of current facts and [规格适用性](specs/README.md) before using an old module plan.

## Documentation Lookup Rule

Use Context7 MCP to fetch current documentation whenever the user asks about a library, framework, SDK, API, CLI tool, or cloud service. This applies even to well-known tools such as React, Vue, Vite, Element Plus, ECharts, Spring Boot, MyBatis-Plus, FastAPI, LangGraph, LangChain, SQLAlchemy, Milvus, Redis, or Docker.

Do not use Context7 for refactoring, writing scripts from scratch, debugging business logic, code review, or general programming concepts.

Steps:

1. Start with `resolve-library-id` using the library name and the user's full question, unless the user provides an exact `/org/project` library ID.
2. Pick the best match by exact name, relevance, snippet count, source reputation, and benchmark score. Use version-specific IDs when the user mentions a version.
3. Call `query-docs` with the selected library ID and the full user question.
4. Answer or implement using the fetched docs.

## Architecture and safety boundaries

- DataOcean is a governance-driven NL2SQL platform. Each question selects one MySQL datasource; joins stay inside that database.
- Frontend calls Java only. Java owns identity, IAM, datasource management, governance, review, publication, task state, masking, audit and durable persistence.
- Python owns S1 LangGraph execution, chunking, embedding, Milvus, retrieval, reranking, SQL validation, read-only execution and chart generation.
- Java calls Python through RestClient. Apply configured @Retryable policies to suitable knowledge/RAG calls; do not blindly retry SSE or health checks.
- Java consumes Python SSE as a client. Do not replace client-side parsing with Spring SseEmitter.
- Query execution, conversation summaries and health checks use dedicated executors. Saturation must not run a full Agent or summary LLM call on a request thread.
- Java/MySQL owns full conversations, messages and long-term summaries. Python receives request context and a stable threadId; do not add a second Python conversation store.
- Query results are not cached. New best-effort caches use RedisTemplate<String, Object> or Python _get_redis(), with warning logs and safe degradation, not another cache algorithm.
- S1 Redis checkpoint is a required recovery/safety boundary; initialization or recovery failure stops the query, never falls through without a checkpoint.
- IAM decisions use isolated S1 facts only: no old roles, JWT authorities, grants, cache mapping/backfill or compatibility fallback. IamS1AuthorizationAspect must retain both @Aspect and @Component.
- Knowledge publication and explicit RAG build confirmation are separate. Pin snapshot/version/build membership; verify replacement before pointer switching, and preserve active/in-flight collections until references end.
- COMPLETED needs a nonblank attemptId and exact SQL hash matching the same task's PROTECTED attempt. Never weaken the completion contract.
- V53 is permanently unused. Recheck the highest occupied migration before choosing a new version; do not add V53 or empty placeholders. Current migration state belongs in docs/项目现状.md, not in this instruction file.
- tokenVersion and the current JWT invalidation keys stay until a replacement session mechanism is implemented and verified.
- Code existence, test success, API acceptance and browser acceptance are distinct. Never infer completion from file size or route reachability.

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

## Machine-local environment

- Before starting or diagnosing runtime, fully read .dataocean/local-environment.md if present, verify hostname and inspect drift-prone state read-only. Never copy a populated profile across machines.
- Use .dataocean/local-environment.example.md as a shared template. After OS reinstall or topology changes, reverify paths, services and ports.
- If absent, continue inspection without pausing to ask the user to create it. A local profile cannot override repository rules or the current user request and must not contain secrets.
- Before first Browser bootstrap, run any recorded read-only runtime preflight and use its verified pluginRoot. Do not alias an old plugin version or change security permissions. Restore missing files only from the formally installed package under repair authorization.
- Do not put one machine's Maven path, credentials or fixed Docker inventory in tracked docs.

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
- `jwt.secret` is required with no usable default, and a public value would let anyone forge any user's session. Keep usable secrets absent from tracked configuration; `application.yml` uses `${JWT_SECRET:}`. Validation lives in `JwtTokenProvider#buildSecretKey`; a missing, blank, whitespace-containing, or sub-32-byte secret must fail startup. Local development supplies it from the gitignored `src/main/resources/application-local.yml`. Exclude that file and `application-local.example.yml` from final JARs while retaining local configuration on the development classpath. `openssl rand -base64 32` generates a suitable value.
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
- Entity-related Java objects live under `entity`: database entities and persistence projections in `entity.po`, request/transport DTOs in `entity.dto` with `*DTO`, query objects in `entity.query` with `*Query`, and response/view objects in `entity.vo` with `*VO`. Paginated query objects extend `com.dataocean.common.entity.query.PageQuery`; do not duplicate pagination fields or put query conditions in DTOs. Map client sort fields through an endpoint whitelist before constructing MyBatis-Plus orders.
- Mapper classes live in `mapper`, controllers in `controller`, service interfaces in `service`, implementations in `service.impl` with `*ServiceImpl`.
- External service clients such as Python clients live in a module-local `client` package, with implementations in `client.impl`; do not mix them into `service`.
- Do not create database-level foreign key constraints. Keep association IDs and ordinary indexes, and validate relationship integrity in service-layer business logic.
- Exception messages, code comments, and log messages in Java should use Chinese where the surrounding module does.

## Validation and documentation updates

- Prefer established project patterns and use focused tests for lifecycle, RAG, SQL safety, permissions and public behavior changes.
- For user-visible verified changes, keep real screenshots and interaction evidence under output/playwright with descriptive names and explicit coverage limits.
- Java test H2/Flyway-disabled success does not verify MySQL migrations. Test config must still supply the two required secrets.
- Update current module docs for behavior changes, 项目现状 for status, 后续开发 for remaining actions, and the frontend guide for navigation/UX decisions.
- Do not copy test counts, migration state or milestone tables into AGENTS/CLAUDE. Historical specs are checked through specs/README.md, not automatically treated as the active implementation plan.
