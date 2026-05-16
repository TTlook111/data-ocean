# Research: NL2SQL Agent 模块

## Agent 编排框架

**Decision**: LangGraph (langgraph)

**Rationale**: LangGraph 提供有状态图编排，支持条件路由、循环（重试）、中断点。比 LangChain Agent 更可控，适合固定流程 + 条件分支的场景。

**Alternatives considered**:
- LangChain AgentExecutor: 过于自由，难以控制执行路径
- 自定义状态机: 可行但需要自己实现持久化、重试、中断等基础设施
- CrewAI: 多 Agent 协作框架，本场景是单 Agent 多步骤，过重

## LLM 调用方案

**Decision**: dashscope SDK 直接调用 Qwen API，模型通过 QWEN_MODEL 环境变量配置

**Rationale**: dashscope 是阿里云官方 SDK，稳定可靠。环境变量配置支持不同环境使用不同模型。

**模型选择**: 
- SQL 生成: qwen-plus (平衡速度和质量)
- 图表生成: qwen-turbo (速度优先，图表 Prompt 简单)

## SSE 推送方案

**Decision**: sse-starlette 库 + FastAPI StreamingResponse

**Rationale**: sse-starlette 是 FastAPI 生态最成熟的 SSE 库，支持异步生成器。

**事件格式**:
```
event: progress
data: {"taskId":"xxx","node":"SQL_Generator","status":"running","message":"正在生成 SQL","retryCount":0,"elapsed":5200}

event: result
data: {"taskId":"xxx","sql":"SELECT...","data":[...],"chart":{...}}

event: error
data: {"taskId":"xxx","error":"TIMEOUT","message":"查询超时，请简化问题后重试"}
```

**Java 消费**: Java 通过 WebClient 或 OkHttp EventSource 消费 SSE 流，转发给前端。

## 重试策略

**Decision**: SQL 执行失败时回到 SQL_Generator，携带错误信息，最多 3 次

**规则**:
1. SQL_Validator 拒绝 (非 SELECT, 危险函数) → 直接返回安全告警，不重试
2. SQL_Executor 失败 (语法错误, 表不存在, 超时) → 重试
3. 重试时 Prompt 追加: "上次生成的 SQL 执行报错: {error}，请修正"
4. 重试次数 >= 3 → 放弃，返回友好错误

**Rationale**: Validator 拒绝说明 LLM 生成了危险 SQL，重试大概率还是危险的。Executor 失败通常是语法或逻辑错误，LLM 有能力自我修正。

## 超时控制

**Decision**: 总时间预算 100s，使用 asyncio.wait_for 包裹整个图执行

**实现**:
- 图执行开始记录 start_time
- 每个节点开始前检查剩余时间
- 剩余时间 < 10s 时不再发起新的 LLM 调用
- asyncio.wait_for(graph.ainvoke(...), timeout=100) 兜底

## 取消机制

**Decision**: 内存 CancellationToken (dict[taskId, asyncio.Event])

**实现**:
1. 任务开始时创建 Event: `tokens[taskId] = asyncio.Event()`
2. 每个节点开始前检查: `if tokens[taskId].is_set(): raise CancelledException`
3. 取消接口: `tokens[taskId].set()`
4. 任务结束后清理: `del tokens[taskId]`

**Rationale**: 请求级无状态，内存 dict 足够。服务重启时所有进行中任务自然终止，Java 侧超时后标记 FAILED。

## Prompt 工程

**Decision**: Jinja2 模板 + 结构化 Prompt

**SQL 生成 Prompt 结构**:
```
[System] 你是一个 SQL 生成专家，只生成 MySQL SELECT 语句。
[Context] 可用表和字段: {{schema_context}}
[Trust] 字段可信度: {{trust_info}} (优先使用高可信字段，禁止使用废弃字段)
[History] 最近对话: {{history}}
[Error] 上次错误: {{last_error}} (仅重试时)
[User] {{user_question}}
[Output] 只输出 SQL，不要解释。
```

## 状态管理

**Decision**: LangGraph State 仅限单次请求，不跨请求持久化

**AgentState fields**:
- question: str
- datasource_id: int
- user_id: int
- conversation_history: list[dict] (前端传入最近 5 轮)
- schema_context: list[RetrievedContext] (RAG 召回结果)
- generated_sql: str
- validation_result: dict
- execution_result: dict
- chart_config: dict
- current_node: str
- retry_count: int
- errors: list[str]
- start_time: float
- cancelled: bool
