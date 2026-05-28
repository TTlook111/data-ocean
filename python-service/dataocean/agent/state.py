"""Agent 状态定义

定义 LangGraph 工作流中流转的 AgentState，
仅存在于单次请求生命周期内，不持久化。
"""

from __future__ import annotations

from typing import Any, TypedDict


class QueryIntent(TypedDict, total=False):
    """从用户问题中提取的结构化查询意图"""

    dimensions: list[str]
    metrics: list[str]
    filters: list[str]
    time_range: str | None
    sort: str | None
    is_ambiguous: bool


class RetrievedSchema(TypedDict, total=False):
    """RAG 召回的单条 schema 上下文"""

    table_name: str
    chunk_type: str
    chunk_text: str
    related_column: str | None
    confidence_score: int
    governance_status: str
    score: float


class ValidationResult(TypedDict, total=False):
    """SQL 安全校验结果"""

    valid: bool
    rewritten_sql: str | None
    violations: list[str]
    level: str


class ExecutionResult(TypedDict, total=False):
    """SQL 执行结果"""

    columns: list[dict]
    data_rows: list[dict]
    row_count: int
    execution_time_ms: int
    error: str | None


class AgentState(TypedDict, total=False):
    """LangGraph Agent 工作流状态

    仅存在于单次请求生命周期内，各节点读写此状态推进流程。
    """

    # 输入（由 Java 传入）
    task_id: str
    question: str
    datasource_id: int
    user_id: int
    conversation_history: list[dict]
    user_permissions: dict
    active_snapshot_id: int
    confidence_scores: dict

    # Query Rewriter 输出
    rewritten_query: str
    extracted_intent: QueryIntent

    # Schema Retriever 输出
    schema_context: list[RetrievedSchema]

    # SQL Generator 输出
    generated_sql: str
    sql_explanation: str

    # SQL Validator 输出
    validation_result: ValidationResult

    # SQL Executor 输出
    execution_result: ExecutionResult
    used_tables: list[str]
    used_columns: list[str]

    # Data Visualizer 输出
    chart_config: dict
    suggested_questions: list[str]

    # 控制字段
    current_node: str
    retry_count: int
    error_message: str
    errors: list[str]
    start_time: float
    cancelled: bool
    timeout_budget: Any
    connection_config: dict
    degraded: bool
    degrade_notice: str
