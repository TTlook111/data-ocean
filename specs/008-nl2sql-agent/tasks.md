# Tasks: NL2SQL Agent 模块

**Input**: Design documents from `specs/008-nl2sql-agent/`
**Prerequisites**: plan.md, spec.md

## Phase 1: Setup

- [ ] T001 创建 Python 包结构 `ai-service/dataocean/agent/`，包含 `__init__.py`、`router.py`、`graph.py`、`state.py`、`sse.py`、`cancellation.py`、`schema.py`、`config.py` 和 `nodes/`、`prompts/` 子目录
- [ ] T002 实现 Agent 配置 `ai-service/dataocean/agent/config.py`，从环境变量读取：QWEN_MODEL、QWEN_API_KEY、AGENT_TOTAL_TIMEOUT（默认 100s）、AGENT_MAX_RETRIES（默认 3）、AGENT_NODE_TIMEOUT（默认 30s）

## Phase 2: Foundational — LangGraph 图骨架

- [ ] T003 实现 `ai-service/dataocean/agent/state.py`：定义 AgentState TypedDict，包含字段 question、datasource_id、schema_context（召回结果）、generated_sql、validation_result、execution_result、visualization、error_message、retry_count、current_node、start_time、task_id、cancelled、confidence_scores、chat_history
- [ ] T004 实现 `ai-service/dataocean/agent/schema.py`：定义 Pydantic 模型——ExecuteRequest（task_id、datasource_id、question、confidence_scores、permissions、chat_history）、ExecuteResponse（task_id、status、sql、data_rows、columns、chart_config、used_tables、used_fields、explanation）、ProgressEvent（task_id、node、status、message、timestamp）
- [ ] T005 实现 `ai-service/dataocean/agent/graph.py`：使用 LangGraph StateGraph 定义节点（schema_retriever、sql_generator、sql_validator、sql_executor、data_visualizer）和边，条件路由逻辑：validator 失败→直接返回安全告警；executor 失败且 retry_count<3→回到 sql_generator；executor 失败且 retry_count>=3→返回错误；每个节点入口检查 cancelled 标记和总时间预算

## Phase 3: User Story 1 (P1) — 各节点实现

**Goal**: 用户自然语言提问并获得查询结果
**Independent Test**: 输入一个明确的业务问题，系统返回正确的数据表格和图表

- [ ] T006 [US1] 实现 `ai-service/dataocean/agent/nodes/schema_retriever.py`：调用 007 模块 POST /internal/rag/retrieve（传入 datasource_id、question、confidence_scores），将返回的 RetrieveResponse 写入 state.schema_context；若召回为空则设置 error_message="未找到相关数据表"并终止
- [ ] T007 [US1] 创建 SQL 生成 Prompt 模板 `ai-service/dataocean/agent/prompts/sql_generation.j2`：包含角色设定（MySQL SQL 专家）、召回的表结构和字段（含可信度标注）、Join Path、指标口径、约束规则（仅 SELECT、禁止 SELECT *、必须指定具体字段）、用户问题、历史对话上下文；若有重试则包含上次错误信息
- [ ] T008 [US1] 实现 `ai-service/dataocean/agent/nodes/sql_generator.py`：从 state 读取 schema_context + question + chat_history + error_message → 填充 Jinja2 模板 → 调用 Qwen API（dashscope SDK）→ 从 LLM 响应中提取 SQL（正则匹配 ```sql 代码块）→ 写入 state.generated_sql
- [ ] T009 [US1] 实现 `ai-service/dataocean/agent/nodes/sql_validator.py`：调用 009 模块 POST /internal/sandbox/validate（传入 sql、datasource_id、permissions），将校验结果写入 state.validation_result；校验不通过时设置 error_message 为拒绝原因
- [ ] T010 [US1] 实现 `ai-service/dataocean/agent/nodes/sql_executor.py`：调用 009 模块 POST /internal/sandbox/execute（传入 validated_sql、datasource_id），将执行结果（data_rows、columns、execution_time_ms）写入 state.execution_result；执行失败时 retry_count += 1 并设置 error_message
- [ ] T011 [US1] 创建图表生成 Prompt 模板 `ai-service/dataocean/agent/prompts/visualization.j2`：包含数据列信息、前 10 行样本数据、要求输出 ECharts option JSON（包含 title、xAxis、yAxis、series），根据数据特征选择图表类型（折线/柱状/饼图）
- [ ] T012 [US1] 实现 `ai-service/dataocean/agent/nodes/data_visualizer.py`：从 state.execution_result 读取数据 → 填充 visualization.j2 模板 → 调用 Qwen API → 解析返回的 JSON 为 ECharts option → 写入 state.visualization；若数据行数为 0 则跳过图表生成

## Phase 4: User Story 2 (P1) — 可信度驱动

**Goal**: SQL 生成优先使用高可信字段
**Independent Test**: 当存在同义的高可信字段和低可信字段时，生成的 SQL 使用高可信字段

- [ ] T013 [US2] 在 sql_generation.j2 模板中添加可信度标注区域：每个字段后标注可信度分数和级别（高/中/低），废弃字段标注"[DEPRECATED - 禁止使用]"，并在约束规则中强调"优先使用可信度>=60的字段，禁止使用标记为DEPRECATED的字段"
- [ ] T014 [US2] 在 schema_retriever.py 中将 confidence_scores 与召回结果合并：为每个召回字段附加可信度分数，废弃字段（governance_status=DEPRECATED）标记为不可用

## Phase 5: User Story 3 (P2) — SSE 进度推送 + 取消

**Goal**: 查询进度实时展示
**Independent Test**: 用户能看到"正在召回相关表 → 正在生成 SQL → 正在执行 → 正在生成图表"的进度

- [ ] T015 [US3] 实现 `ai-service/dataocean/agent/sse.py`：SSE 事件管理器，维护 task_id → asyncio.Queue 映射，提供 emit_progress(task_id, node, status, message) 方法将 ProgressEvent 放入队列，提供 event_stream(task_id) 异步生成器消费队列
- [ ] T016 [US3] 实现 `ai-service/dataocean/agent/cancellation.py`：CancellationToken 管理，维护 task_id → asyncio.Event 映射，提供 cancel(task_id) 设置事件、is_cancelled(task_id) 检查事件、cleanup(task_id) 清理方法
- [ ] T017 [US3] 在 graph.py 每个节点执行前后调用 sse.emit_progress 推送进度事件（node_start、node_complete、node_error、retry），并检查 cancellation.is_cancelled
- [ ] T018 [US3] 实现路由 `ai-service/dataocean/agent/router.py`：POST /internal/agent/execute（接收 ExecuteRequest，启动异步任务执行 graph，返回 HTTP 202 + task_id）、GET /internal/agent/tasks/{task_id}/events（SSE 端点，返回 event_stream）、POST /internal/agent/tasks/{task_id}/cancel（调用 cancellation.cancel）

## Phase 6: 重试与容错

- [ ] T019 在 graph.py 的条件路由中实现重试逻辑：sql_executor 返回失败时检查 retry_count < AGENT_MAX_RETRIES，满足则路由回 sql_generator（携带 error_message），否则路由到终止节点
- [ ] T020 在 graph.py 中实现总时间预算控制：每个节点入口检查 time.time() - state.start_time > AGENT_TOTAL_TIMEOUT，超时则设置 error_message="查询超时，请简化问题后重试" 并终止
- [ ] T021 在各节点中添加 LLM 调用异常处理：捕获 dashscope API 超时/限流/服务不可用异常，设置 error_message="AI 服务暂时不可用，请稍后重试" 并终止

## Phase 7: Polish & Cross-Cutting

- [ ] T022 在 router.py 中添加请求参数校验：question 非空且长度 <= 500、datasource_id 必填、chat_history 最多 5 轮
- [ ] T023 在 graph.py 执行完成后组装最终 ExecuteResponse：包含 sql、data_rows、columns、chart_config、used_tables（从 schema_context 提取）、used_fields、explanation（从 LLM 响应提取）

## Dependencies

```
T001 → T002~T005
T003 + T004 → T005
T005 → T006~T012 (节点依赖图骨架)
T006~T012 → T013~T014 (可信度增强依赖节点实现)
T005 → T015~T018 (SSE 依赖图骨架)
T005 → T019~T021 (容错依赖图骨架)
T006 依赖 007 模块 /internal/rag/retrieve
T009~T010 依赖 009 模块 /internal/sandbox/validate 和 /execute
```

## Implementation Strategy

MVP-first approach:
1. 先搭建 LangGraph 图骨架和 AgentState（Phase 2），确保流程可跑通
2. 逐个实现节点（Phase 3），先用 mock 数据验证流程，再接入真实模块
3. 可信度注入（Phase 4）与 SQL 生成节点同步开发
4. SSE 和取消（Phase 5）在核心流程跑通后添加
5. 重试容错（Phase 6）最后完善，确保生产健壮性
