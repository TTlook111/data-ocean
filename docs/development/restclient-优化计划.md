# RestClient 优化计划

> 创建日期：2026-06-07
> 完成日期：2026-06-08

## 现状：✅ 全部完成

### 已完成 — 统一 Bean

`common/config/PythonRestClientConfig.java` 提供 3 个 Bean：

| Bean | Qualifier | 连接超时 | 读取超时 | 使用者 |
|---|---|---|---|---|
| `pythonRestClient` | `@Primary` | 5s | 120s | Agent、Knowledge、RAG、Pool、AiConfig(re-vectorize) |
| `pythonShortTimeoutRestClient` | — | 3s | 15s | AiConfig(test-provider、detect-dimension、config/reload) |
| `pythonHealthRestClient` | — | 3s | 3s | PythonHealthChecker |

所有 Client 已通过 `@Qualifier` 注入 Bean，不再各自创建 `RestClient`。

### 已完成 — 重试机制

`@EnableRetry` 已启用，3 个方法已加 `@Retryable`：

| Client | 方法 | 重试条件 | 最大尝试 | 间隔 |
|---|---|---|---|---|
| `PythonKnowledgeClientImpl` | `generateDraft()` | `PythonRetryableException`（5xx、连接超时） | 2 次 | 1s |
| `PythonKnowledgeClientImpl` | `analyzeAndGenerate()` | `PythonRetryableException`（5xx、连接超时） | 2 次 | 1s |
| `PythonRagClientImpl` | `chunkDocument()` | `PythonRetryableException`（5xx、连接超时） | 2 次 | 1s |

不加重试的调用：

| Client | 原因 |
|---|---|
| `PythonAgentClientImpl` | SSE 流式调用，LangGraph 已有重试 |
| `PythonHealthChecker` | 已有 3 次失败计数 + 自动恢复 |
| `PythonPoolClientImpl` | 池管理操作，失败仅记录警告 |
| `AiConfigController` | 配置测试/检测，用户主动触发 |
| `PythonRagClientImpl.vectorize()` | 写 Milvus，非幂等，失败不自动重试 |

## 最终接口策略表

> 本表作为实现验收的唯一对照表。若原则说明、完成清单和代码实现出现冲突，以本表为准。

| Java 调用点 | Python 接口 | RestClient Bean | 自动重试 | 事务边界 | 失败处理 | 用户侧文案 |
|---|---|---|---|---|---|---|
| `PythonAgentClientImpl.executeAsync` | `POST /internal/query/execute` | `pythonRestClient` | 否 | 异步任务内，不包数据库长事务 | 回写任务失败，通知 Python cancel 尽力释放资源 | `Agent 服务调用失败，请稍后重试` |
| `PythonAgentClientImpl.cancelTask` | `POST /internal/query/tasks/{taskId}/cancel` | `pythonRestClient` | 否 | 无事务要求 | 仅记录 warn，不影响 Java 侧取消状态 | 不直接返回给用户 |
| `PythonKnowledgeClientImpl.generateDraft` | `POST /internal/knowledge/generate-draft` | `pythonRestClient` | 是，仅 5xx/连接类故障，最多 1 次重试；读超时不重试 | Python 调用必须在数据库事务外；写版本时再开短事务 | 重试耗尽后转业务异常 | `AI 知识库服务暂时不可用，请稍后重试` 或 `AI 草稿生成超时，请稍后重试` |
| `PythonKnowledgeClientImpl.analyzeAndGenerate` | `POST /internal/knowledge/analyze-and-generate` | `pythonRestClient` | 是，仅 5xx/连接类故障，最多 1 次重试；读超时不重试 | Python 调用必须在数据库事务外；批量写文档时再开短事务 | 重试耗尽后转业务异常 | `AI 知识库服务暂时不可用，请稍后重试` 或 `AI 域分析+批量生成超时，请稍后重试` |
| `PythonRagClientImpl.chunkDocument` | `POST /internal/rag/chunk` | `pythonRestClient` | 是，仅 5xx/连接类故障，最多 1 次重试 | 调度任务/预览调用中，不额外持有数据库长事务 | 重试耗尽后任务失败或预览失败 | `RAG 文档切割失败，请稍后重试` |
| `PythonRagClientImpl.vectorize` | `POST /internal/rag/vectorize` | `pythonRestClient` | **否** | 向量化调度任务中；Java 本地 chunk 持久化和任务状态更新分段处理 | 标记向量化任务失败，等待后续人工/任务机制重新处理 | `RAG 向量化失败，请稍后重试` |
| `PythonRagClientImpl.deleteDocVersionVectors` | `POST /internal/rag/vectors/delete` | `pythonRestClient` | 否 | 向量化成功后清理旧版本，不影响新版本发布成功 | 仅记录 warn，后续可再次清理 | 不直接返回给用户 |
| `AiConfigController.testProvider` | `POST /internal/ai-config/test-provider` | `pythonShortTimeoutRestClient` | 否 | Controller 请求内，不涉及数据库长事务 | 标记供应商测试失败并保存状态 | `供应商连接测试失败，请检查配置后重试` |
| `AiConfigController.detectDimension` | `POST /internal/ai-config/detect-dimension` | `pythonShortTimeoutRestClient` | 否 | Controller 请求内 | 失败交由全局异常处理或业务异常处理 | 统一友好错误，不暴露底层异常 |
| `AiConfigController.notifyPythonReload` | `POST /internal/config/reload` | `pythonShortTimeoutRestClient` | 否 | 配置保存后尽力通知 | 仅记录 warn，不回滚 Java 配置 | 不直接返回给用户 |
| `AiConfigController.reVectorize` | `POST /internal/rag/re-vectorize` | `pythonRestClient` | 否 | 当前为快速占位；未来真实长任务应改异步任务模型 | 返回 Python 接受状态或失败 | 统一友好错误，不暴露底层异常 |
| `PythonHealthChecker.checkHealth` | `GET /health` | `pythonHealthRestClient` | 否 | 定时任务，无业务事务 | 连续失败计数，达到阈值标记不可用并通知 | 健康看板展示服务不可用 |
| `PythonPoolClientImpl.getPoolDashboard` | `GET /internal/sql/pools/dashboard` | `pythonRestClient` | 否 | 运维辅助调用 | 返回空 dashboard，并附错误摘要 | 健康/运维页展示降级数据 |
| `PythonPoolClientImpl.resetPool` | `POST /internal/sql/pools/{datasourceId}/reset` | `pythonRestClient` | 否 | 运维辅助调用 | 仅记录 warn，不影响主业务 | 不直接返回底层异常 |
| `PythonPoolClientImpl.destroyPool` | `DELETE /internal/sql/pools/{datasourceId}` | `pythonRestClient` | 否 | 运维辅助调用 | 仅记录 warn，不影响主业务 | 不直接返回底层异常 |

验收规则：

- 表中 `自动重试=否` 的接口不得出现 `@Retryable` 或包装为 `PythonRetryableException` 的逻辑。
- 表中 `事务边界` 标明“事务外”的接口，不得在 `@Transactional` 方法中等待 Python 返回。
- 所有用户侧文案不得直接拼接 Python/HTTP/Milvus/SQLAlchemy 原始异常。
- 如果新增 Python 接口，必须先补充本表，再实现代码。

### 实现方式

使用 `PythonRetryableException` 作为重试触发异常：
- 5xx 错误 → `toPythonStatusException()` 抛出 `PythonRetryableException` → 触发重试
- 连接超时（`ResourceAccessException`）→ catch 后包装为 `PythonRetryableException` → 触发重试
- 4xx 错误 → `toPythonStatusException()` 抛出 `BusinessException` → 不重试
- 每个重试方法都有 `@Recover` 方法处理重试耗尽后的降级
- `/internal/rag/vectorize` 不属于重试方法，5xx/连接超时均返回失败并交给向量化任务机制处理
