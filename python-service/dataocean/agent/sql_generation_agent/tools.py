"""SQL Generator Agent 只读工具

所有工具通过闭包绑定当前 AgentState，只读取已有信息，
不访问外部服务、不执行 SQL、不修改任何状态。
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any

from langchain_core.tools import tool

from .schema import (
    GetFieldNotesInput,
    GetGenerationFeedbackInput,
    GetJoinPathsInput,
    GetMetricDefinitionsInput,
    GetSchemaContextInput,
    LintSqlDraftInput,
)

logger = logging.getLogger(__name__)


def create_tools(state: dict[str, Any]) -> list:
    """根据当前 AgentState 创建只读工具列表

    每个工具通过闭包捕获 state 中的数据，Agent 调用时无需重新传入 state。

    Args:
        state: 当前 AgentState 字典

    Returns:
        工具列表，可直接传给 create_agent
    """
    schema_context: list[dict] = state.get("schema_context", [])

    # ── get_schema_context ────────────────────────────────────

    @tool(args_schema=GetSchemaContextInput)
    def get_schema_context(
        table_names: list[str] | None = None,
        chunk_types: list[str] | None = None,
        limit: int = 10,
    ) -> str:
        """读取本次 Schema Retriever 已召回的 schema 上下文。

        返回表名、chunk 类型、chunk 文本、关联列、置信度和治理状态。
        不重新访问 Milvus，只读取已召回的数据。
        """
        results = list(schema_context)

        if table_names:
            names_lower = [n.lower() for n in table_names]
            results = [
                item
                for item in results
                if (item.get("table_name") or "").lower() in names_lower
            ]

        if chunk_types:
            types_lower = [t.lower() for t in chunk_types]
            results = [
                item
                for item in results
                if (item.get("chunk_type") or "").lower() in types_lower
            ]

        results = results[:limit]

        logger.debug("get_schema_context: 返回 %d 条记录", len(results))
        return json.dumps(results, ensure_ascii=False)

    # ── get_join_paths ────────────────────────────────────────

    @tool(args_schema=GetJoinPathsInput)
    def get_join_paths(table_names: list[str] | None = None) -> str:
        """读取表之间的 Join Path，帮助生成正确的 ON 条件。

        只返回 chunk_type 为 JOIN_PATH 的记录。
        如果为空，说明当前上下文没有已知关联，请不要编造 ON 条件。
        """
        join_chunks = [
            item for item in schema_context
            if (item.get("chunk_type") or "").upper() == "JOIN_PATH"
        ]

        if table_names:
            names_lower = [n.lower() for n in table_names]
            join_chunks = [
                item for item in join_chunks
                if (item.get("table_name") or "").lower() in names_lower
            ]

        logger.debug("get_join_paths: 返回 %d 条 Join Path", len(join_chunks))
        return json.dumps(join_chunks, ensure_ascii=False)

    # ── get_metric_definitions ────────────────────────────────

    @tool(args_schema=GetMetricDefinitionsInput)
    def get_metric_definitions(
        metric_keywords: list[str] | None = None,
    ) -> str:
        """读取指标口径定义，包含 SQL 表达式、过滤条件和统计口径说明。

        只返回 chunk_type 为 METRIC 的记录。
        优先使用此处返回的指标口径，不要自己猜测聚合表达式。
        """
        metric_chunks = [
            item for item in schema_context
            if (item.get("chunk_type") or "").upper() == "METRIC"
        ]

        if metric_keywords:
            keywords_lower = [k.lower() for k in metric_keywords]
            metric_chunks = [
                item for item in metric_chunks
                if any(
                    kw in (item.get("chunk_text") or "").lower()
                    for kw in keywords_lower
                )
            ]

        logger.debug(
            "get_metric_definitions: 返回 %d 条指标定义", len(metric_chunks)
        )
        return json.dumps(metric_chunks, ensure_ascii=False)

    # ── get_field_notes ───────────────────────────────────────

    @tool(args_schema=GetFieldNotesInput)
    def get_field_notes(
        table_names: list[str] | None = None,
        column_names: list[str] | None = None,
    ) -> str:
        """读取字段防坑指南，包含状态枚举、金额单位、时间字段选择、废弃字段说明。

        返回 chunk_type 为 FIELD_NOTE 或 CORE_TABLE 的记录。
        """
        note_chunks = [
            item for item in schema_context
            if (item.get("chunk_type") or "").upper() in ("FIELD_NOTE", "CORE_TABLE")
        ]

        if table_names:
            names_lower = [n.lower() for n in table_names]
            note_chunks = [
                item for item in note_chunks
                if (item.get("table_name") or "").lower() in names_lower
            ]

        if column_names:
            cols_lower = [c.lower() for c in column_names]
            note_chunks = [
                item for item in note_chunks
                if (item.get("related_column") or "").lower() in cols_lower
            ]

        logger.debug("get_field_notes: 返回 %d 条字段说明", len(note_chunks))
        return json.dumps(note_chunks, ensure_ascii=False)

    # ── get_generation_feedback ───────────────────────────────

    @tool(args_schema=GetGenerationFeedbackInput)
    def get_generation_feedback(
        include_previous_sql: bool = True,
    ) -> str:
        """读取上一轮 SQL 生成的结果和错误信息，用于重试修正。

        返回 retry_count、previous_sql 和 error_message。
        """
        feedback: dict[str, Any] = {
            "retry_count": state.get("retry_count", 0),
            "previous_sql": state.get("generated_sql", "") if include_previous_sql else "",
            "error_message": state.get("error_message", ""),
        }

        logger.debug("get_generation_feedback: retry=%d", feedback["retry_count"])
        return json.dumps(feedback, ensure_ascii=False)

    # ── lint_sql_draft ────────────────────────────────────────

    @tool(args_schema=LintSqlDraftInput)
    def lint_sql_draft(sql: str) -> str:
        """对 SQL 草稿做轻量级检查，识别明显问题。

        检查项：是否 SELECT、是否含危险操作、是否缺 FROM、是否多语句。
        注意：此工具只能返回提示，不替代正式 SQL_Validator 安全校验。
        """
        issues: list[str] = []
        sql_stripped = sql.strip()
        sql_upper = sql_stripped.upper()

        if not sql_upper.startswith("SELECT"):
            issues.append("SQL 不是 SELECT 语句")

        # 使用词边界匹配，避免 UPDATE_TIME 等列名误报
        dangerous_pattern = re.compile(
            r"\b(?:DELETE|UPDATE|INSERT|DROP|ALTER|TRUNCATE|CREATE|REPLACE|GRANT|REVOKE)\b"
        )
        matches = dangerous_pattern.findall(sql_upper)
        for kw in dict.fromkeys(matches):  # 去重并保持顺序
            issues.append(f"SQL 包含危险关键词: {kw}")

        if "FROM" not in sql_upper and "SELECT" in sql_upper:
            # 允许 SELECT 1 等无 FROM 语句
            if "DUAL" not in sql_upper:
                issues.append("SQL 可能缺少 FROM 子句")

        if ";" in sql_stripped.rstrip(";"):
            issues.append("SQL 包含多语句分号")

        result = {"valid": len(issues) == 0, "issues": issues}
        logger.debug("lint_sql_draft: valid=%s issues=%d", result["valid"], len(issues))
        return json.dumps(result, ensure_ascii=False)

    return [
        get_schema_context,
        get_join_paths,
        get_metric_definitions,
        get_field_notes,
        get_generation_feedback,
        lint_sql_draft,
    ]
