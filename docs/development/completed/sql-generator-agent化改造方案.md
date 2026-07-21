# SQL_Generator Agent 化改造方案

> 目标：保留现有 LangGraph 主流程，只将 `SQL_Generator` 节点内部从一次性 LLM 调用升级为受控的 LangChain `create_agent` 工具调用子循环。

## 1. 当前实现

当前 NL2SQL 主链路位于：

```text
python-service/dataocean/agent/graph.py
```

主流程是固定 LangGraph DAG：

```text
Query_Rewriter
  -> Schema_Retriever
  -> SQL_Generator
  -> SQL_Validator
  -> SQL_Executor
  -> Data_Visualizer
```

当前 `SQL_Generator` 的实现位于：

```text
python-service/dataocean/agent/nodes/sql_generator.py
```

执行方式是：

```text
读取 AgentState
  -> 渲染 sql_generation Prompt
  -> call_llm(...)
  -> SqlOutputParser 提取 SQL 和 explanation
  -> 写回 generated_sql / sql_explanation
```

当前重试不是完整链路重跑，而是后半段修正循环：

```text
SQL_Generator
  -> SQL_Validator
      普通失败且 retry_count < max_retries -> SQL_Generator
      DANGEROUS / REJECT -> END
  -> SQL_Executor
      执行失败且 retry_count < max_retries -> SQL_Generator
      执行成功 -> Data_Visualizer
```

重试时 `SQL_Generator` 会收到：

```text
error_message
previous_sql
retry_count
schema_context
extracted_intent
confidence_scores
conversation_history
```

也就是说，当前已经具备 SQL 自修复的外层循环，但 `SQL_Generator` 内部仍是单次 LLM 生成。

## 2. 改造原则

不改外层 LangGraph 主流程。

不让 Agent 直接执行 SQL。

不让 Agent 绕过 `SQL_Validator`。

不让 Agent 自由调用外部网络、数据库写操作或业务库执行工具。

`SQL_Generator` Agent 只允许调用只读工具，目标是生成更准确的 SQL 草稿。

最终安全边界仍然是：

```text
SQL_Validator: AST 安全校验、权限改写、脱敏标记
SQL_Executor: 只读沙箱执行、超时、结果返回
```

## 3. 改造后结构

外层流程保持不变：

```text
Query_Rewriter
  -> Schema_Retriever
  -> SQL_Generator_Agent
  -> SQL_Validator
  -> SQL_Executor
  -> Data_Visualizer
```

`SQL_Generator_Agent` 内部变为：

```text
构造消息
  -> create_agent(model, tools, response_format)
  -> Agent 按需调用只读工具
  -> 返回结构化 SQL 结果
  -> 写回 AgentState
```

内部允许的轻量循环：

```text
Reason: 判断当前问题需要哪些表、字段、Join Path、指标口径
Act: 调用只读工具补充上下文
Observe: 获得工具返回
Reason: 生成最终 SQL
Output: structured_response
```

## 4. 需要的 Tool

### 4.1 `get_schema_context`

用途：读取本次 `Schema_Retriever` 已召回的 schema chunk。

禁止重新访问 Milvus，避免每次 SQL 生成时重复召回。

输入：

```python
class GetSchemaContextInput(BaseModel):
    table_names: list[str] | None = None
    chunk_types: list[str] | None = None
    limit: int = 10
```

输出：

```python
list[dict]
```

返回字段：

```text
table_name
chunk_type
chunk_text
related_column
confidence_score
governance_status
score
```

实现策略：

从 `state["schema_context"]` 过滤，不调用外部服务。

### 4.2 `get_join_paths`

用途：专门读取 Join Path chunk，帮助多表查询生成正确 `ON` 条件。

输入：

```python
class GetJoinPathsInput(BaseModel):
    table_names: list[str] | None = None
```

输出：

```python
list[dict]
```

筛选规则：

```text
chunk_type == "JOIN_PATH"
```

如果当前召回上下文没有 Join Path，返回空数组，并提示 Agent 不要编造关联条件。

### 4.3 `get_metric_definitions`

用途：读取指标口径，避免 LLM 自己猜聚合表达式。

输入：

```python
class GetMetricDefinitionsInput(BaseModel):
    metric_keywords: list[str] | None = None
```

筛选规则：

```text
chunk_type == "METRIC"
```

返回内容应包含 SQL 表达式、过滤条件、统计口径说明。

### 4.4 `get_field_notes`

用途：读取字段防坑指南，例如状态枚举、金额单位、时间字段选择、废弃字段说明。

输入：

```python
class GetFieldNotesInput(BaseModel):
    table_names: list[str] | None = None
    column_names: list[str] | None = None
```

筛选规则：

```text
chunk_type in ("FIELD_NOTE", "CORE_TABLE")
```

### 4.5 `get_generation_feedback`

用途：给 Agent 显式读取上一轮 SQL 和错误信息，用于重试修正。

输入：

```python
class GetGenerationFeedbackInput(BaseModel):
    include_previous_sql: bool = True
```

输出：

```python
{
    "retry_count": int,
    "previous_sql": str,
    "error_message": str
}
```

实现策略：

直接读取 `state["retry_count"]`、`state["generated_sql"]`、`state["error_message"]`。

### 4.6 可选：`lint_sql_draft`

用途：在 Agent 内部做轻量 SQL 草稿检查，不替代正式 `SQL_Validator`。

输入：

```python
class LintSqlDraftInput(BaseModel):
    sql: str
```

检查项：

```text
是否 SELECT
是否包含 DELETE / UPDATE / INSERT / DROP / ALTER / TRUNCATE
是否明显缺少 FROM
是否包含多语句分号
```

注意：

这个工具只能返回提示，不能作为安全放行依据。

正式放行仍以 `SQL_Validator` 为准。

## 5. 不应该提供的 Tool

以下工具不要放进 `SQL_Generator_Agent`：

```text
execute_sql
validate_and_rewrite_sql
connect_database
fetch_real_rows
update_metadata
write_prompt
call_external_http
```

原因：

SQL 生成节点只负责生成候选 SQL。执行、权限、安全、脱敏必须由后续确定性节点控制。

## 6. 结构化输出

建议使用 Pydantic 模型作为 `create_agent(..., response_format=...)`。

```python
from pydantic import BaseModel, Field


class SqlGenerationResult(BaseModel):
    sql: str = Field(description="Only one MySQL SELECT statement.")
    explanation: str = Field(description="Brief explanation of selected tables, joins, filters and metrics.")
    used_tool_names: list[str] = Field(default_factory=list)
    confidence: float = Field(ge=0, le=1, default=0.7)
    assumptions: list[str] = Field(default_factory=list)
```

写回 `AgentState`：

```python
return {
    "generated_sql": result.sql,
    "sql_explanation": result.explanation,
    "error_message": "",
    "retry_count": retry_count,
    "current_node": "SQL_GENERATOR",
}
```

如果 Agent 没有返回有效 SQL：

```python
return {
    "generated_sql": "",
    "sql_explanation": "",
    "error_message": "SQL 生成失败：Agent 未返回有效 SELECT 语句",
    "retry_count": retry_count,
    "current_node": "SQL_GENERATOR",
}
```

## 7. 建议文件结构

新增：

```text
python-service/dataocean/agent/sql_generation_agent/
  __init__.py
  agent.py
  schema.py
  tools.py
  prompts.py
```

职责：

```text
agent.py   创建并调用 create_agent
schema.py  定义 SqlGenerationResult 和 Tool input schema
tools.py   定义只读工具，工具闭包绑定当前 AgentState
prompts.py 构造 system prompt 和 user message
```

保留并改造：

```text
python-service/dataocean/agent/nodes/sql_generator.py
```

让它只负责：

```text
读取 state
  -> 调用 sql_generation_agent.generate_sql_with_agent(state)
  -> 兼容 fallback
  -> 返回 AgentState patch
```

## 8. 依赖调整

当前 `python-service/pyproject.toml` 没有显式声明 `langchain` 主包。

需要新增或确认：

```toml
"langchain>=1.0.0"
```

当前已有：

```toml
"langgraph>=0.2.60"
"langchain-openai>=1.2.2"
"langchain-milvus>=0.3.0"
"langchain-text-splitters>=1.1.0"
```

新增依赖后执行：

```bash
cd python-service
uv sync
```

最低版本要求：`langchain>=1.0.0`（`create_agent` 和 `ToolStrategy` 从该版本起可用）。
`ToolStrategy` 位于 `langchain.agents.structured_output`，在代码中通过函数内 import 引入，
避免版本不匹配时阻塞整个模块加载。

然后验证：

```bash
uv run python -c "from langchain.agents import create_agent; print(create_agent)"
```

## 9. 模型接入建议

项目当前 LLM 通过：

```text
dataocean/infra/llm.py
```

封装 DashScope OpenAI 兼容接口。

Agent 化时建议优先复用同一套配置：

```text
settings.qwen_model
settings.dashscope_api_key
settings.dashscope_base_url
settings.llm_temperature
```

如果 `create_agent` 不能直接复用现有 `call_llm`，建议新增一个 `get_chat_model()`，返回 LangChain chat model 实例。

示例形态：

```python
from langchain_openai import ChatOpenAI
from dataocean.core.config import settings


def get_chat_model(temperature: float | None = None) -> ChatOpenAI:
    return ChatOpenAI(
        model=settings.qwen_model,
        api_key=settings.dashscope_api_key,
        base_url=settings.dashscope_base_url,
        temperature=settings.llm_temperature if temperature is None else temperature,
    )
```

注意：

最终代码要以当前 `infra/llm.py` 的缓存策略为准，避免每次请求重复创建 model。

## 10. Prompt 策略

`SQL_Generator_Agent` 的 system prompt 要更像规则边界，而不是长篇 schema 上下文。

建议内容：

```text
你是 DataOcean 的 MySQL SELECT SQL 生成 Agent。
你只能生成单条 SELECT 查询。
你不能生成 INSERT、UPDATE、DELETE、DROP、ALTER、TRUNCATE、CREATE。
你不能执行 SQL。
你必须优先使用工具读取已召回 schema、Join Path、指标口径和字段防坑说明。
如果缺少 Join Path，不要编造 ON 条件。
如果用户问题无法基于 schema_context 回答，返回空 SQL 并说明原因。
正式安全校验由后续 SQL_Validator 负责，但你应尽量生成可通过校验的 SQL。
```

user message 放入：

```text
question
rewritten_query
extracted_intent
conversation_history
confidence_scores 摘要
retry_count
previous_sql
error_message
```

不要把全部 schema_context 无脑塞进 prompt。

Agent 应通过工具按需读取 schema_context，减少上下文膨胀。

## 11. 兼容 fallback

第一版不要一次性删除当前 `call_llm` 方案。

建议保留：

```python
async def run_sql_generator(state: AgentState) -> AgentState:
    if settings.sql_generator_agent_enabled:
        try:
            return await run_sql_generator_agent(state)
        except Exception:
            logger.warning("SQL Generator Agent failed, fallback to legacy LLM path", exc_info=True)
    return await run_sql_generator_legacy(state)
```

新增配置：

```text
SQL_GENERATOR_AGENT_ENABLED=false
SQL_GENERATOR_AGENT_MAX_TOOL_CALLS=5
```

灰度策略：

```text
默认关闭
本地打开验证
联调环境打开
稳定后默认打开
保留 legacy fallback 至少一个迭代周期
```

## 12. 超时和工具调用限制

外层 `_node_wrapper` 已经给节点分配 `_node_timeout`。

Agent 内部必须继续遵守：

```text
node_timeout
max_tool_calls
max_iterations
```

建议：

```text
SQL_GENERATOR_AGENT_MAX_TOOL_CALLS=5
SQL_GENERATOR_AGENT_MAX_ITERATIONS=6
```

超过限制则返回失败，让外层重试机制处理。

## 13. SSE 进度

外层已有：

```text
SQL_GENERATOR started / completed / failed
```

Agent 内部可选新增更细粒度日志，不建议第一版直接暴露给前端。

第一版只记录服务端日志：

```text
tool_call: get_join_paths
tool_call: get_metric_definitions
structured_response received
```

如果后续要展示，可新增 SSE message：

```text
正在分析表关联
正在读取指标口径
正在修正上一轮 SQL
```

但事件 node 仍保持 `SQL_GENERATOR`，避免前端改动过大。

## 14. 测试计划

### 14.1 单元测试

新增：

```text
python-service/tests/agent/test_sql_generation_agent_tools.py
python-service/tests/agent/test_sql_generator_agent.py
```

覆盖：

```text
get_schema_context 可以按 table_names 过滤
get_join_paths 只返回 JOIN_PATH chunk
get_metric_definitions 只返回 METRIC chunk
get_field_notes 返回 FIELD_NOTE / CORE_TABLE chunk
get_generation_feedback 能读取 previous_sql 和 error_message
lint_sql_draft 能识别非 SELECT 和多语句
Agent 返回结构化结果后能写回 generated_sql
Agent 异常时能 fallback legacy
```

### 14.2 集成测试

选择 5 类问题：

```text
单表明细查询
单表聚合查询
多表 Join 查询
指标口径查询
上一轮执行失败后的 SQL 修正
```

验收：

```text
SQL_Validator 通过率不低于 legacy
SQL_Executor 成功率不低于 legacy
平均耗时可接受
不会跳过 SQL_Validator
不会调用执行类工具
```

### 14.3 回归测试

执行：

```bash
cd python-service
uv run pytest
```

如果只跑 Agent 相关测试：

```bash
cd python-service
uv run pytest tests/agent
```

## 15. 迁移步骤

1. 新增 `langchain` 依赖并验证 `create_agent` 可导入。
2. 新增 `sql_generation_agent/schema.py`，定义结构化输出和工具输入模型。
3. 新增 `sql_generation_agent/tools.py`，实现只读工具。
4. 新增 `sql_generation_agent/prompts.py`，定义 Agent system prompt 和 user message 构造。
5. 新增 `sql_generation_agent/agent.py`，封装 `create_agent` 调用。
6. 将现有 `sql_generator.py` 的实现拆为 `run_sql_generator_legacy`。
7. 在 `run_sql_generator` 中加入 feature flag 和 fallback。
8. 增加单元测试。
9. 本地用真实或 mock schema_context 跑 5 类集成样例。
10. 联调成功后再打开默认开关。

## 16. 推荐第一版验收标准

功能验收：

```text
原有 NL2SQL 主链路不变
Agent 开关关闭时行为与当前一致
Agent 开关打开时 SQL_Generator 能生成结构化 SQL
失败时自动 fallback 到 legacy LLM 生成
SQL_Validator / SQL_Executor 仍然强制执行
```

安全验收：

```text
Agent 无 SQL 执行工具
Agent 无数据库连接工具
Agent 无外部 HTTP 工具
Agent 不能修改 metadata / prompt / knowledge
非 SELECT SQL 仍被 SQL_Validator 拦截
```

质量验收：

```text
复杂 Join 查询的 ON 条件更稳定
指标类查询更倾向使用 skills.md 中的指标口径
重试修正时能明确利用 previous_sql 和 error_message
生成 SQL 的解释能说明表、字段、Join 和过滤依据
```

## 17. 风险和控制

| 风险 | 控制方式 |
|---|---|
| Agent 工具调用导致耗时增加 | 设置 max_tool_calls、max_iterations、node_timeout |
| Agent 过度依赖工具，输出变慢 | 工具只读且返回短文本，默认 limit |
| Agent 编造 Join 条件 | `get_join_paths` 为空时 prompt 明确禁止编造 |
| 结构化输出失败 | fallback 到 legacy `call_llm` 路径 |
| LangChain 版本兼容问题 | 先独立验证 `create_agent` import 和最小 demo |
| 安全边界被弱化 | 不提供执行工具，保留 SQL_Validator / SQL_Executor |

## 18. 参考

- LangChain Python Agents: `create_agent`
- LangChain Structured Output: `response_format` 和 `structured_response`
- LangGraph Custom Workflow: 可将 LangChain agent 嵌入 LangGraph node

当前结论：

```text
DataOcean 不应改成自由 ReAct 主 Agent。
推荐只将 SQL_Generator 节点内部改成受控 create_agent 子循环。
```
