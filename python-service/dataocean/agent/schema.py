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


class ConversationTurn(BaseModel):
    """对话轮次"""

    role: Literal["user", "assistant"]
    content: str


class ExecuteRequest(BaseModel):
    """查询执行请求（Java → Python）"""

    task_id: str = Field(validation_alias=AliasChoices("task_id", "taskId"))
    datasource_id: int = Field(validation_alias=AliasChoices("datasource_id", "datasourceId"))
    user_id: int = Field(validation_alias=AliasChoices("user_id", "userId"))
    question: str = Field(min_length=1, max_length=500)
    conversation_history: list[ConversationTurn] = Field(
        default_factory=list,
        max_length=5,
        validation_alias=AliasChoices("conversation_history", "conversationHistory"),
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


class ColumnMeta(BaseModel):
    """结果列元信息"""

    name: str
    type: str
    comment: str | None = None


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
    masked_fields: list[str] = Field(default_factory=list, serialization_alias="maskedFields")


class ProgressEvent(BaseModel):
    """SSE 进度事件"""

    task_id: str = Field(serialization_alias="taskId")
    node: str
    status: Literal["started", "completed", "failed", "retrying"]
    message: str
    retry_count: int = Field(default=0, serialization_alias="retryCount")
    elapsed_ms: int = Field(default=0, serialization_alias="elapsedMs")
