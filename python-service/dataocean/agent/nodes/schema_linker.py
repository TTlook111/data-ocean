"""Schema Linking 节点

参考 MAC-SQL 和 CHESS 的 Selector Agent 设计：
在 RAG 检索后、SQL 生成前，通过 LLM 过滤无关表/列，减少 context 噪声。

这可以显著提升 SQL 生成准确率，尤其在 schema 较大时效果明显。
"""

from __future__ import annotations

import json
import logging

from dataocean.infra.llm import call_llm
from dataocean.infra.parsers import JsonBlockOutputParser

from ..state import AgentState

logger = logging.getLogger(__name__)

_json_parser = JsonBlockOutputParser(allow_null=False)


async def run_schema_linker(state: AgentState) -> AgentState:
    """Schema Linking：过滤无关表/列，精简 SQL 生成的输入 context

    流程：
    1. 获取 RAG 召回的 schema context
    2. 调用 LLM 分析问题与 schema 的关联
    3. 过滤掉无关的表和列
    4. 返回精简后的 schema context
    """
    task_id = state.get("task_id", "")
    question = state.get("rewritten_query", "") or state.get("question", "")
    schema_context = state.get("schema_context", [])

    if not schema_context:
        return {"current_node": "SCHEMA_LINKER"}

    # 只有当 schema 超过 3 个表时才做 linking（小 schema 直接跳过）
    if len(schema_context) <= 3:
        logger.info("Schema Linking 跳过（表数 <= 3）task_id=%s", task_id)
        return {"current_node": "SCHEMA_LINKER"}

    logger.info("Schema Linking 开始 task_id=%s tables=%d", task_id, len(schema_context))

    try:
        pruned = await _prune_schema(question, schema_context)
        if pruned and len(pruned) < len(schema_context):
            logger.info("Schema Linking 完成 task_id=%s %d → %d tables",
                        task_id, len(schema_context), len(pruned))
            return {"schema_context": pruned, "current_node": "SCHEMA_LINKER"}
        else:
            logger.info("Schema Linking 未减少表数，保持原样 task_id=%s", task_id)
            return {"current_node": "SCHEMA_LINKER"}
    except Exception as e:
        logger.warning("Schema Linking 失败，保持原样 task_id=%s error=%s", task_id, e)
        return {"current_node": "SCHEMA_LINKER"}


async def _prune_schema(question: str, schema_context: list[dict]) -> list[dict]:
    """调用 LLM 分析问题与 schema 的关联，返回精简后的 schema

    Args:
        question: 用户问题
        schema_context: RAG 召回的 schema 列表

    Returns:
        精简后的 schema 列表
    """
    # 构建 schema 摘要（只包含表名和关键列）
    schema_summary = _build_schema_summary(schema_context)

    prompt = (
        f"用户问题：[QUERY_START]{question}[QUERY_END]\n\n"
        f"以下是数据库的表结构信息：\n{schema_summary}\n\n"
        f"请分析这个问题需要用到哪些表和字段，哪些表是无关的。\n"
        f"返回 JSON 格式：{{\"relevant_tables\": [\"表名1\", \"表名2\"], \"reason\": \"原因\"}}\n"
        f"只返回 JSON，不要其他内容。"
    )

    try:
        response = await call_llm(
            system_prompt="你是 Schema Linking 专家。分析用户问题需要哪些数据库表，过滤无关表。",
            user_prompt=prompt,
            temperature=0.1,
        )
        result = _json_parser.parse(response)
        relevant_tables = set(t.lower() for t in result.get("relevant_tables", []))

        if not relevant_tables:
            return schema_context

        # 过滤只保留相关表
        pruned = [
            schema for schema in schema_context
            if schema.get("table_name", "").lower() in relevant_tables
        ]

        # 至少保留一个表
        return pruned if pruned else schema_context[:1]
    except Exception as e:
        logger.warning("Schema Linking LLM 调用失败: %s", e)
        return schema_context


def _build_schema_summary(schema_context: list[dict]) -> str:
    """构建 schema 摘要文本，用于 LLM 分析"""
    lines = []
    for schema in schema_context:
        table_name = schema.get("table_name", "")
        chunk_type = schema.get("chunk_type", "")
        chunk_text = schema.get("chunk_text", "")[:200]  # 截断避免过长
        columns = schema.get("columns", [])

        line = f"- 表 {table_name}（类型: {chunk_type}）"
        if columns:
            col_names = [c.get("name", "") if isinstance(c, dict) else str(c) for c in columns[:5]]
            line += f"，字段: {', '.join(col_names)}"
        if chunk_text:
            line += f"\n  摘要: {chunk_text}"
        lines.append(line)

    return "\n".join(lines)
