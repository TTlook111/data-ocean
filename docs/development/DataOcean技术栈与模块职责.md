# DataOcean 技术栈与模块职责

> 本文档是 DataOcean 当前实现的技术栈、模块职责、数据归属和异步边界的详细说明。
> 更新日期：2026-08-31。版本号以 `frontend/package.json`、`backend/DataOcean/pom.xml` 和 `python-service/pyproject.toml` 为准。

## 1. 文档定位

本文档回答三个问题：

1. 项目使用了哪些技术。
2. 每项技术参与哪些模块，以及它负责什么、不负责什么。
3. 哪些流程应该异步，哪些流程必须等待结果。

`README.md` 只保留快速概览；`AGENTS.md` 和 `CLAUDE.md` 记录协作约束与稳定架构事实；项目完成度和测试数据以 [`项目真实状态看板.md`](./项目真实状态看板.md) 为准；模块级接口和数据模型以对应 `specs/<module>/` 为准。

本项目不引入 Google ADK，也不通过 API Gateway 平台转发模型请求。Java 负责业务网关和持久化，Python 负责 AI/RAG 执行，模型通过外部 OpenAI 兼容 API（当前为 Qwen/通义千问配置）调用。

## 2. 总体架构

```text
Vue 3 前端
  ├─ 查询端：提问、任务状态、SSE 结果、会话恢复
  └─ 管理端：数据源、元数据治理、语义、权限、运营与系统设置
          │ 只调用 Java 公共 API
          ▼
Spring Boot Java 网关
  ├─ 认证、权限、数据源和治理生命周期
  ├─ 查询任务、会话消息、长期摘要和审计持久化
  └─ 通过 RestClient 调用 Python 内部 API
          │ 内部 HTTP / SSE
          ▼
Python FastAPI AI 服务
  ├─ LangGraph Agent 工作流
  ├─ LangChain 模型、Prompt、工具和输出解析
  ├─ Schema RAG、Embedding、Milvus 检索与重排
  └─ sqlglot 校验、权限改写和只读 SQL Sandbox
          │
          ├─ 外部 Qwen/OpenAI 兼容 API：LLM 与 Embedding
          ├─ Milvus：可重建的向量索引
          └─ 外部业务 MySQL：只读查询执行
```

会话数据的边界如下：

```text
Java MySQL：conversation、conversation_message、conversation_context_summary
        │ 每次查询组装
        ▼
Python：conversation_history + conversation_summary + 当前请求
        │ 请求级状态，执行结束即释放
        └─ 不接收 conversationId，不持久化完整会话
```

## 3. 技术栈与职责矩阵

### 3.1 前端

| 技术 | 参与模块 | 主要职责 | 不负责 |
| --- | --- | --- | --- |
| Vue 3 | 查询端、后台管理端 | 页面组件和交互 | 不直接调用 Python，不执行 SQL |
| Vite、TypeScript | 前端工程 | 构建、类型检查和开发服务 | 不承担后端业务规则 |
| Vue Router、Pinia | 前端路由和状态 | 路由、登录状态、页面状态 | 不作为会话历史的持久化源 |
| Axios | `frontend/src/api` | 调用 Java 公共 API、SSE/任务接口适配 | 不保存业务事实 |
| Element Plus | 管理端和查询端 | 表单、表格、弹窗、布局等 UI | 不决定权限和治理规则 |
| ECharts | 查询结果展示 | 渲染 Python 生成的图表配置 | 不生成 SQL |
| D3、dagre | 血缘和实体关系可视化 | DAG/关系图布局和绘制 | 不维护关系数据 |
| DOMPurify、GSAP、Lucide | 安全展示、动效、图标 | 输出清理和视觉交互 | 不参与查询决策 |

### 3.2 Java 网关

| 技术 | 参与模块 | 主要职责 | 不负责 |
| --- | --- | --- | --- |
| Spring Boot 3.3.5、JDK 17 | `backend/DataOcean` | 公共 HTTP API、依赖注入、事务和应用启动 | 不实现 Python Agent 内部推理 |
| Spring Security、JWT | `common/security`、`user` | 登录、鉴权、Token 黑名单和令牌版本 | 不把 JWT 或 API Key 传给模型 |
| MyBatis-Plus 3.5.9 | Java 各业务模块 | MySQL 实体、Mapper、分页和条件查询 | 不替代 Python 的 SQL Sandbox |
| Flyway | `db/migration` | 数据库结构和增量迁移 | 不迁移外部业务数据 |
| Spring Data Redis | `user`、`common`、`query`、`fieldtag` 等 | JWT、验证码、限频、缓存和临时数据 | 不作为完整会话历史主库 |
| Spring Cache、Caffeine | 当前权限计算模块 | 当前进程内权限缓存 | 不是新增缓存的首选；新增缓存遵循项目 Redis 约定 |
| RestClient、Spring Retry | Java/Python client | Java 调用 Python 内部 API，按配置重试知识/RAG调用 | SSE 和健康检查不盲目重试 |
| Spring `@Async`、`@Scheduled`、事件监听 | 查询、同步、审计、通知、维护 | 受控后台任务和定时任务 | 不应把权限、安全校验和查询依赖改成丢失结果的后台任务 |
| AOP、操作日志、审计 | `audit`、`system`、权限和管理端 | 横切日志、审计和运行记录 | 不记录密码、Token、API Key 或密钥 |

### 3.3 Python AI/RAG 服务

| 技术 | 参与模块 | 主要职责 | 不负责 |
| --- | --- | --- | --- |
| Python 3.13、FastAPI、Uvicorn | `python-service/dataocean` | 内部 AI/RAG API、健康检查和 SSE | 不向浏览器提供公共业务 API |
| Pydantic | `agent`、`rag`、`sandbox` 等 schema | 请求、响应和 Agent 状态边界校验 | 不持久化会话 |
| HTTPX | Prompt、配置和部分内部外部 HTTP 调用 | 异步 HTTP 客户端 | 不拥有 Java 业务事务 |
| LangGraph | `agent/graph.py`、Agent workflow | StateGraph、节点、边、条件路由、并行 fan-out、重试和请求级状态 | 不保存长期会话，不替代 MySQL |
| LangChain | `agent`、`infra`、`rag` | 模型抽象、Prompt、工具、输出解析、Agent 组装、Document 等基础组件 | 不决定业务权限，不是工作流持久化层 |
| `langchain-openai` | `infra/llm.py`、`infra/embeddings.py` | 通过 OpenAI 兼容协议调用 Qwen/Embedding 服务 | 不保存模型会话 |
| `langchain-text-splitters` | `rag/chunker.py` | 对 `skills.md` 做文档切分 | 不负责发布状态和任务状态 |
| SQLAlchemy、PyMySQL | `sandbox`、连接池 | 使用只读账号连接外部业务 MySQL 并执行安全 SQL | 不负责 Java 应用库的业务持久化 |
| sqlglot | `sandbox`、Agent SQL 节点 | SQL AST 解析、安全规则、权限改写、LIMIT、深度和危险函数检查 | 不依赖 Prompt 作为唯一安全措施 |
| PyMilvus、Milvus 客户端 | `rag/vector_store.py`、`vectorizer.py` | 向量写入、检索、删除和数量校验 | 不作为业务事实源，索引失败不能覆盖 Java 状态 |
| Python Redis asyncio | `infra/memory.py`、`rag`、Few-shot | Embedding、Schema/Fallback、用户偏好和 Few-shot 等缓存/增强记忆 | 不保存 Java 的完整会话历史 |
| Jinja2、LangChain PromptTemplate | `agent/prompts`、`prompt` | Prompt 模板渲染 | 不执行模型安全校验 |

### 3.4 数据与模型基础设施

| 技术/服务 | 当前定位 | 关键模块或数据 | 权威性 |
| --- | --- | --- | --- |
| Java 应用 MySQL | Java 持久化数据库 | 用户、数据源、元数据、权限、任务、会话、消息、摘要、审计、知识切片 | 业务事实和会话事实的权威源 |
| 外部业务 MySQL | 被查询的数据源 | 业务表和业务数据 | 查询结果的外部数据源；必须只读 |
| Redis | 临时状态和缓存 | JWT、验证码、限频、Embedding、Glossary、Fallback、Few-shot、用户偏好 | 可失效、可降级，不是持久化事实源 |
| Milvus | RAG 向量索引 | `skills.md` 和 Schema 向量 | 可重建的派生索引，不是权威源 |
| Qwen/外部 OpenAI 兼容 API | 模型服务 | 查询改写、SQL 生成、摘要、Embedding、图表等模型调用 | 只提供推理结果，不保存项目业务状态 |

## 4. LangChain 与 LangGraph 的边界

两者不是同一个职责：

1. LangChain 是“组件层”：模型客户端、Prompt、工具、Document、Embedding、输出解析器和 Agent 构建能力都属于这一层。
2. LangGraph 是“流程层”：把改写、元数据预取、RAG、SQL 生成、校验、执行和图表生成组织成有状态的节点图。
3. LangGraph 中的状态是一次查询的请求级状态，不等于用户会话记忆。
4. 长期会话仍由 Java 写入 MySQL；Python 只接受 Java 组装好的历史消息和摘要。

## 5. 异步边界

### 5.1 三种不同含义

| 类型 | 含义 | 当前例子 | 是否需要等待 |
| --- | --- | --- | --- |
| 异步 I/O | 调用期间不阻塞事件循环，但业务仍依赖结果 | `await chat.ainvoke()`、异步 Redis | 需要 |
| 线程卸载 | 把同步阻塞库放到线程执行，保护事件循环 | `asyncio.to_thread()` 执行 SQL、Milvus | 需要 |
| 后台任务 | 调用方先返回，任务稍后执行 | Java `@Async`、Python `create_task` | 不需要，但必须允许失败或重试 |

### 5.2 当前正确的异步位置

| 流程 | 当前实现 | 判断 |
| --- | --- | --- |
| 用户提问后的 Agent 执行 | Java `queryExecutor` + Python `create_task` + LangGraph | 正确，前端通过任务 ID/SSE 获取结果 |
| 会话长期摘要 | 助手消息落库后调用 `conversationSummaryExecutor` | 正确，摘要失败不影响本次查询 |
| 元数据全量同步 | Java 同步任务表 + `@Async` 执行 | 正确，接口只返回任务 ID |
| `skills.md` 向量化 | Java 向量任务调度器执行，Python 向量化接口内部等待写入和校验 | 正确，发布必须等待向量校验完成 |
| SQL、Milvus、健康检查阻塞调用 | Python `asyncio.to_thread()` | 正确，避免阻塞事件循环，但业务仍需等待结果 |
| 审计、操作日志、血缘、通知和快照同步 | Spring 异步事件/服务 | 适合后台执行，但要明确是否允许进程异常时丢失 |
| 清理、健康检查、过期处理 | Spring/Python 定时任务 | 适合后台执行，不属于用户请求链路 |
| 成功查询 Few-shot 保存 | Python 后台 `create_task` | 当前可接受，属于最佳努力增强，不影响主查询 |

### 5.3 必须保持等待的流程

以下内容不能简单改成 fire-and-forget：

1. 权限计算、数据源 readiness 和敏感字段策略。
2. 历史消息、摘要、Glossary、Fallback 和 Schema RAG 加载。
3. 查询改写、SQL 生成、AST 校验、权限改写和只读执行。
4. 查询结果、任务状态、用户消息和助手消息的必要持久化。
5. 新向量写入和数量校验。只有确认成功后，Java 才能发布新版本并清理旧向量。

### 5.4 当前异步待优化点

1. `DatasourceHealthCheckScheduler` 使用 `CompletableFuture.runAsync()` 时没有显式指定项目线程池，当前会使用公共 ForkJoinPool。后续应改为受控的健康检查线程池。
2. `AsyncConfig` 的 `conversationSummaryExecutor` 当前使用 `CallerRunsPolicy`。线程池满时，摘要 LLM 调用可能回退到查询线程执行，削弱摘要异步隔离。后续可改成拒绝并记录、定时重试或任务表重试。
3. Python 的 Few-shot `create_task` 是进程内最佳努力任务，服务重启可能丢失。当前不值得为此引入 API Gateway 或消息平台；如果未来要求可靠投递，再增加持久化任务或 outbox。
4. 向量任务当前按待处理任务顺序执行。只有在增加任务抢占/幂等/并发上限后，才适合并行处理，不能只给调度器简单加线程。

## 6. 数据一致性和失败原则

1. Java MySQL 是会话、任务、治理和发布状态的权威来源。
2. Redis 故障应降级为无缓存或较慢路径，不能阻断核心查询。
3. Milvus 是派生索引。新版本向量未写入并校验成功前，不能删除旧版本向量。
4. 外部模型 API 失败时，查询任务必须进入明确的失败或降级状态，不能把异常吞掉后伪装成成功。
5. 后台副作用如果将来具备审计或合规上的“不能丢失”要求，应采用 MySQL outbox/任务表保证投递；当前项目没有为此额外引入消息平台。

## 7. 文档维护规则

| 发生变化 | 应更新的文档 |
| --- | --- |
| 技术版本、模块归属、存储或异步边界变化 | 本文档，并同步 `AGENTS.md`、`CLAUDE.md` 的摘要入口 |
| 当前完成度、测试结果、风险变化 | `docs/development/项目真实状态看板.md` |
| 未完成任务和优先级变化 | `docs/development/后续开发.md` |
| 某个模块的接口、数据模型或实现计划变化 | 对应 `specs/<module>/` |
| README 快速开始或项目入口变化 | `README.md` |

