"""SQL 生成节点

基于召回的 Schema 上下文和结构化查询意图，
调用 LLM 生成 SQL 语句。
"""

from __future__ import annotations

import asyncio

from dataocean.core.error_messages import sanitize_error
import logging
from pathlib import Path

from dataocean.prompt.service import render_prompt_with_metadata
from dataocean.prompt.renderer import render_template_file
from dataocean.infra.llm import call_llm
from dataocean.infra.parsers import SqlOutputParser

from ..prompt_tracking import record_prompt_version
from ..state import AgentState

logger = logging.getLogger(__name__)

_PROMPTS_DIR = Path(__file__).parent.parent / "prompts"
_SQL_GENERATION_TEMPLATE = _PROMPTS_DIR / "sql_generation.j2"
# 从 LLM 响应中提取 SQL 与口径说明
_sql_parser = SqlOutputParser()


async def run_sql_generator(state: AgentState) -> AgentState:
    """执行 SQL 生成：填充模板 → 调用 Qwen → 提取 SQL

    注意：retry_count 由 graph 的条件边递增，此处不再递增
    """
    task_id = state.get("task_id", "")
    retry_count = state.get("retry_count", 0)
    error_message = state.get("error_message", "")
    previous_sql = state.get("generated_sql", "")
    node_timeout = state.get("_node_timeout", 60)

    logger.info("SQL 生成 task_id=%s retry=%d", task_id, retry_count)

    prompt, prompt_version = await _render_sql_prompt(state, retry_count, error_message, previous_sql)
    state = record_prompt_version(state, "sql_generation", prompt_version)

    try:
        response_text = await asyncio.wait_for(
            call_llm(
                system_prompt="你是一个 MySQL SQL 专家。请严格按照约束规则生成安全的 SELECT 语句。",
                user_prompt=prompt,
            ),
            timeout=node_timeout,
        )
    except asyncio.TimeoutError:
        logger.error("SQL 生成超时 task_id=%s timeout=%s", task_id, node_timeout)
        return {
            "generated_sql": "",
            "error_message": "SQL 生成超时",
            "retry_count": retry_count,
            "current_node": "SQL_GENERATOR",
        }
    except Exception as e:
        logger.error("SQL 生成 LLM 调用失败 task_id=%s error=%s", task_id, e)
        return {
            "generated_sql": "",
            "error_message": sanitize_error(e),
            "retry_count": retry_count,
            "current_node": "SQL_GENERATOR",
        }

    sql = ""
    explanation = ""
    if response_text:
        parsed = _sql_parser.parse(response_text)
        sql = parsed["sql"]
        explanation = parsed["explanation"]
        logger.info("LLM 原始响应 task_id=%s response=%s", task_id, response_text[:200])
        logger.info("解析后 SQL task_id=%s sql=%s", task_id, sql)

    if not sql:
        logger.warning("SQL 生成失败：无法从 LLM 响应中提取 SQL task_id=%s", task_id)
        return {
            "generated_sql": "",
            "sql_explanation": "",
            "error_message": "SQL 生成失败：LLM 未返回有效的 SQL 语句",
            "retry_count": retry_count,
            "current_node": "SQL_GENERATOR",
        }

    logger.info("SQL 生成完成 task_id=%s sql=%s", task_id, sql[:80])

    return {
        "generated_sql": sql,
        "sql_explanation": explanation,
        "error_message": "",
        "retry_count": retry_count,
        "current_node": "SQL_GENERATOR",
    }


def estimate_execution_time(sql: str) -> str:
    """基于 SQL 复杂度估算执行时间范围"""
    sql_upper = sql.upper()
    join_count = sql_upper.count("JOIN")
    subquery_count = sql_upper.count("SELECT") - 1
    has_group_by = "GROUP BY" in sql_upper

    if join_count >= 3 or subquery_count >= 2:
        return "预计执行 5-15 秒"
    if join_count >= 1 or has_group_by:
        return "预计执行 2-5 秒"
    return "预计执行 1-2 秒"


async def _render_sql_prompt(
    state: AgentState,
    retry_count: int,
    error_message: str,
    previous_sql: str,
) -> tuple[str, int]:
    variables = {
        "question": state.get("question", ""),
        "rewritten_query": state.get("rewritten_query", ""),
        "intent": state.get("extracted_intent", {}),
        "schema": _format_schema(state.get("schema_context", [])),
        "schema_context": state.get("schema_context", []),
        "field_confidence": state.get("confidence_scores", {}),
        "conversation_history": state.get("conversation_history", []),
        "error_message": error_message if retry_count > 0 else "",
        "previous_sql": previous_sql if retry_count > 0 else "",
    }
    try:
        rendered, version_no = await render_prompt_with_metadata("sql_generation", variables)
        if rendered:
            return rendered, version_no
    except Exception as e:
        logger.debug("SQL Prompt 管理模板不可用，使用本地模板降级 task_id=%s error=%s", state.get("task_id", ""), e)

    # 安全修复：统一 managed/降级路径的变量集
    # 降级路径使用与 managed 路径相同的变量，确保 prompt 信息不丢失
    return (
        render_template_file(
            _SQL_GENERATION_TEMPLATE,
            **variables,
        ),
        0,
    )


def _format_schema(schema_context: list[dict]) -> str:
    lines: list[str] = []
    for item in schema_context:
        table_name = item.get("table_name") or item.get("tableName") or ""
        related_column = item.get("related_column") or item.get("relatedColumn") or ""
        chunk_text = item.get("chunk_text") or item.get("chunkText") or ""
        confidence = item.get("confidence_score") or item.get("confidenceScore") or item.get("score") or ""
        lines.append(f"- {table_name}.{related_column}: {chunk_text} (confidence={confidence})")
    return "\n".join(lines)
