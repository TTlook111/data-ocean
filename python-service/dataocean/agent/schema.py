"""NL2SQL Agent 请求/响应模型

定义 Java → Python 的请求模型和 SSE 事件模型。
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field
from pydantic import AliasChoices


class RowFilter(BaseModel):
    """行级权限过滤条件"""

    table_name: str = Field(validation_alias=AliasChoices("table_name", "tableName"))
    condition: str


class MaskColumn(BaseModel):
    """脱敏字段配置"""

    table_name: str = Field(validation_alias=AliasChoices("table_name", "tableName"))
    column_name: str = Field(validation_alias=AliasChoices("column_name", "columnName"))
    mask_type: str = Field(validation_alias=AliasChoices("mask_type", "maskType"))


class UserPermissions(BaseModel):
    """用户权限信息"""

    row_filters: list[RowFilter] = Field(
        default_factory=list,
        validation_alias=AliasChoices("row_filters", "rowFilters"),
    )
    denied_columns: list[str] = Field(
        default_factory=list,
        validation_alias=AliasChoices("denied_columns", "deniedColumns"),
    )
    mask_columns: list[MaskColumn] = Field(
        default_factory=list,
        validation_alias=AliasChoices("mask_columns", "maskColumns"),
    )
    allowed_tables: list[str] = Field(
        default_factory=list,
        validation_alias=AliasChoices("allowed_tables", "allowedTables"),
    )
    table_scope_mode: str = Field(
        default="UNRESTRICTED",
        validation_alias=AliasChoices("table_scope_mode", "tableScopeMode"),
    )


class ConversationTurn(BaseModel):
    """对话轮次"""

    role: Literal["user", "assistant"]
    content: str


class ConversationSummaryRequest(BaseModel):
    """Java 发送给 Python 的摘要增量请求，不包含会话 ID。"""

    messages: list[dict] = Field(default_factory=list)
    previous_summary: dict = Field(
        default_factory=dict,
        validation_alias=AliasChoices("previous_summary", "previousSummary"),
    )
    current_date: str | None = Field(
        default=None,
        validation_alias=AliasChoices("current_date", "currentDate"),
    )


class ConversationSummaryResponse(BaseModel):
    """结构化会话摘要响应。"""

    summary: dict


class ExecuteRequest(BaseModel):
    """查询执行请求（Java → Python）"""

    task_id: str = Field(validation_alias=AliasChoices("task_id", "taskId"))
    datasource_id: int = Field(validation_alias=AliasChoices("datasource_id", "datasourceId"))
    user_id: int = Field(validation_alias=AliasChoices("user_id", "userId"))
    question: str = Field(min_length=1, max_length=500)
    conversation_history: list[ConversationTurn] = Field(
        default_factory=list,
        validation_alias=AliasChoices("conversation_history", "conversationHistory"),
    )
    conversation_summary: dict | None = Field(
        default=None,
        validation_alias=AliasChoices("conversation_summary", "conversationSummary"),
    )
    user_permissions: UserPermissions = Field(
        validation_alias=AliasChoices("user_permissions", "userPermissions"),
    )
    active_snapshot_id: int = Field(
        validation_alias=AliasChoices("active_snapshot_id", "activeSnapshotId"),
    )
    confidence_scores: dict[str, int] = Field(
        default_factory=dict,
        validation_alias=AliasChoices("confidence_scores", "confidenceScores"),
    )
    connection_config: dict | None = Field(
        default=None,
        validation_alias=AliasChoices("connection_config", "connectionConfig"),
    )
    fallback_chunks: list[dict] | None = Field(
        default=None,
        validation_alias=AliasChoices("fallback_chunks", "fallbackChunks"),
    )
    glossary_terms: list[dict] | None = Field(
        default=None,
        validation_alias=AliasChoices("glossary_terms", "glossaryTerms"),
    )


class ColumnMeta(BaseModel):
    """结果列元信息"""

    name: str
    type: str
    comment: str | None = None


class ColumnDerivation(BaseModel):
    """列级派生关系（SQL AST 提取的列→列血缘）

    对应 Phase 1 DERIVED_FROM 关系的原始数据，
    由 sqlglot AST 解析产生，经 SSE 传递给 Java 侧消费。
    """

    target_table: str = Field(serialization_alias="targetTable")
    target_column: str = Field(serialization_alias="targetColumn")
    target_alias: str | None = Field(default=None, serialization_alias="targetAlias")
    source_table: str = Field(serialization_alias="sourceTable")
    source_column: str = Field(serialization_alias="sourceColumn")
    expression: str | None = None
    expression_type: str = Field(serialization_alias="expressionType")


class QueryResult(BaseModel):
    """查询最终结果（SSE result 事件）"""

    task_id: str = Field(serialization_alias="taskId")
    status: Literal["COMPLETED", "FAILED", "CANCELLED"]
    sql: str | None = None
    sql_explanation: str | None = Field(default=None, serialization_alias="sqlExplanation")
    data: list[dict] | None = None
    columns: list[ColumnMeta] | None = None
    row_count: int = Field(default=0, serialization_alias="rowCount")
    chart_config: dict | None = Field(default=None, serialization_alias="chartConfig")
    used_tables: list[str] = Field(default_factory=list, serialization_alias="usedTables")
    used_columns: list[str] = Field(default_factory=list, serialization_alias="usedColumns")
    rewritten_query: str | None = Field(default=None, serialization_alias="rewrittenQuery")
    retry_count: int = Field(default=0, serialization_alias="retryCount")
    total_time_ms: int = Field(default=0, serialization_alias="totalTimeMs")
    error: str | None = None
    suggested_questions: list[str] = Field(default_factory=list, serialization_alias="suggestedQuestions")
    masked_fields: dict[str, str] = Field(default_factory=dict, serialization_alias="maskedFields")
    prompt_versions: list[dict] = Field(default_factory=list, serialization_alias="promptVersions")
    column_derivations: list[ColumnDerivation] = Field(
        default_factory=list,
        serialization_alias="columnDerivations",
    )
    degraded: bool = Field(default=False, serialization_alias="degraded")
    degrade_notice: str | None = Field(default=None, serialization_alias="degradeNotice")


class ProgressEvent(BaseModel):
    """SSE 进度事件"""

    task_id: str = Field(serialization_alias="taskId")
    node: str
    status: Literal["started", "completed", "failed", "retrying"]
    message: str
    retry_count: int = Field(default=0, serialization_alias="retryCount")
    elapsed_ms: int = Field(default=0, serialization_alias="elapsedMs")
