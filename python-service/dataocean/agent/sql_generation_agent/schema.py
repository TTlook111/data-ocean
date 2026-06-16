"""SQL Generator Agent 结构化输出和工具输入 Schema

定义 Agent 的结构化响应模型和各只读工具的输入参数模型。
"""

from __future__ import annotations

from pydantic import BaseModel, Field


# ── Agent 结构化输出 ──────────────────────────────────────────


class SqlGenerationResult(BaseModel):
    """SQL 生成 Agent 的结构化输出"""

    sql: str = Field(description="Only one MySQL SELECT statement.")
    explanation: str = Field(
        description="Brief explanation of selected tables, joins, filters and metrics."
    )
    used_tool_names: list[str] = Field(default_factory=list)
    confidence: float = Field(ge=0, le=1, default=0.7)
    assumptions: list[str] = Field(default_factory=list)


# ── Tool 输入 Schema ──────────────────────────────────────────


class GetSchemaContextInput(BaseModel):
    """get_schema_context 工具输入"""

    table_names: list[str] | None = Field(
        default=None,
        description="按表名过滤，为空则返回全部",
    )
    chunk_types: list[str] | None = Field(
        default=None,
        description="按 chunk 类型过滤，如 CORE_TABLE、FIELD_NOTE",
    )
    limit: int = Field(default=10, description="最大返回条数")


class GetJoinPathsInput(BaseModel):
    """get_join_paths 工具输入"""

    table_names: list[str] | None = Field(
        default=None,
        description="按表名过滤，为空则返回全部 Join Path",
    )


class GetMetricDefinitionsInput(BaseModel):
    """get_metric_definitions 工具输入"""

    metric_keywords: list[str] | None = Field(
        default=None,
        description="按关键词过滤指标，为空则返回全部",
    )


class GetFieldNotesInput(BaseModel):
    """get_field_notes 工具输入"""

    table_names: list[str] | None = Field(
        default=None,
        description="按表名过滤",
    )
    column_names: list[str] | None = Field(
        default=None,
        description="按列名过滤",
    )


class GetGenerationFeedbackInput(BaseModel):
    """get_generation_feedback 工具输入"""

    include_previous_sql: bool = Field(
        default=True,
        description="是否包含上一轮生成的 SQL",
    )


class LintSqlDraftInput(BaseModel):
    """lint_sql_draft 工具输入"""

    sql: str = Field(description="待检查的 SQL 草稿")
