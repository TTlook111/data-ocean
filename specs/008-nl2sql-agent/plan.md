# Implementation Plan: NL2SQL Agent 模块

**Branch**: `001-full-module-specs` | **Date**: 2026-05-16 | **Spec**: [spec.md](spec.md)

## Summary

NL2SQL Agent 是系统核心智能链路，使用 LangGraph 编排多节点工作流（Schema_Retriever → SQL_Generator → SQL_Validator → SQL_Executor → Data_Visualizer），将用户自然语言转为安全可执行的 SQL 并返回数据和图表。支持失败重试、超时控制、任务取消和 SSE 进度推送。

## Technical Context

**Language/Version**: Python 3.13 (FastAPI)

**Primary Dependencies**:
- LangGraph (Agent 工作流编排)
- dashscope (Qwen LLM API)
- FastAPI + SSE (sse-starlette)
- Pydantic v2

**Storage**: 无持久化（请求级无状态），任务状态由 Java 管理

**Testing**: pytest + pytest-asyncio, LLM mock

**Target Platform**: Docker Compose (python-service container)

**Performance Goals**: 端到端 P95 < 30s, 总时间预算 100s

**Constraints**: 请求级无状态, 上下文由 Java 传入, 最多重试 3 次

## Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. 元数据治理优先 | ✅ PASS | SQL 生成基于 RAG 召回的已治理内容 |
| II. SQL 安全与只读执行 | ✅ PASS | SQL_Validator 节点调用 009 模块校验 |
| III. 三层分离架构 | ✅ PASS | Python 内部服务，Java 通过内部 API 调用 |
| IV. RAG 准入控制 | ✅ PASS | Schema_Retriever 调用 007 模块，已有准入过滤 |
| V. 可信度驱动生成 | ✅ PASS | 可信度信息注入 SQL 生成 Prompt |
| VI. 渐进式 MVP | ✅ PASS | Python 无状态，每次请求接收 Java 传入的最近 5 轮 chat_history 作为上下文 |

**Gate Result**: PASS

## Project Structure

```text
python-service/dataocean/agent/
├── __init__.py
├── router.py              # FastAPI 路由 (execute, cancel)
├── graph.py               # LangGraph 图定义
├── state.py               # AgentState 定义
├── nodes/
│   ├── __init__.py
│   ├── query_rewriter.py      # 问题理解与改写
│   ├── schema_retriever.py    # 调用 RAG 模块
│   ├── sql_generator.py       # LLM 生成 SQL
│   ├── sql_validator.py       # 调用安全校验模块
│   ├── sql_executor.py        # 调用沙箱执行
│   └── data_visualizer.py     # 生成 ECharts 配置
├── prompts/
│   ├── query_rewrite.j2       # 问题改写 Prompt 模板
│   ├── sql_generation.j2      # SQL 生成 Prompt 模板
│   └── visualization.j2       # 图表生成 Prompt 模板
├── sse.py                 # SSE 事件推送
├── cancellation.py        # CancellationToken 管理
├── schema.py              # Pydantic 请求/响应模型
└── config.py              # Agent 配置
```

## Implementation Phases

### Phase 1: LangGraph 图骨架
- AgentState 定义 (问题, 改写后问题, 数据源, 召回结果, SQL, 错误, 重试次数, 当前节点)
- LangGraph StateGraph 定义 (节点 + 边 + 条件路由)
- 条件路由: Validator 失败 → 直接拒绝; Executor 失败 → 重试或放弃
- 总时间预算 100s 控制

### Phase 2: 各节点实现
- Query_Rewriter: 解析时间表达式、消解多轮指代、提取意图（维度/指标/筛选/排序）、改写为结构化查询
- Schema_Retriever: 使用改写后的查询调用 007 模块 /internal/rag/retrieve
- SQL_Generator: 构造 Prompt (召回上下文 + 可信度 + 改写意图 + 历史对话) → Qwen API
- SQL_Validator: 调用 009 模块校验接口
- SQL_Executor: 调用 009 模块执行接口
- Data_Visualizer: 基于查询结果调用 LLM 生成 ECharts option

### Phase 3: SSE + 取消
- SSE 事件流: 每个节点开始/结束时推送进度事件
- CancellationToken: 内存 dict (taskId → Event)，节点间检查
- POST /internal/tasks/{taskId}/cancel 设置取消标记

### Phase 4: 重试与容错
- SQL 执行失败 → 携带错误信息回到 SQL_Generator (max 3 次)
- Validator 拒绝 → 直接返回安全告警，不重试
- 超时 → 停止重试，返回超时提示
- LLM 调用失败 → 返回"AI 服务暂时不可用"

## Complexity Tracking

无违规项。
