"""SQL 生成节点

基于召回的 Schema 上下文和结构化查询意图，
调用 LLM 生成 SQL 语句。
"""

from __future__ import annotations

import logging
import re
from pathlib import Path

from jinja2 import Template

from ..llm import call_llm
from ..state import AgentState

logger = logging.getLogger(__name__)

_PROMPTS_DIR = Path(__file__).parent.parent / "prompts"
_template_cache: Template | None = None


def _get_template() -> Template:
    """获取缓存的 SQL 生成 Prompt 模板"""
    global _template_cache
    if _template_cache is None:
        path = _PROMPTS_DIR / "sql_generation.j2"
        _template_cache = Template(path.read_text(encoding="utf-8"))
    return _template_cache


def _extract_sql(text: str) -> str:
    """从 LLM 响应中提取 SQL 语句"""
    match = re.search(r"```sql\s*\n?(.*?)\n?```", text, re.DOTALL | re.IGNORECASE)
    if match:
        return match.group(1).strip()
    # 降级：尝试找到以 SELECT 开头的语句
    match = re.search(r"(SELECT\s.+?)(?:;|\n\n|$)", text, re.DOTALL | re.IGNORECASE)
    if match:
        return match.group(1).strip().rstrip(";")
    return ""


def _extract_explanation(text: str) -> str:
    """从 LLM 响应中提取 SQL 口径说明"""
    # 取 ```sql``` 代码块之后的文本作为说明
    match = re.search(r"```\s*\n(.+)", text, re.DOTALL)
    if match:
        explanation = match.group(1).strip()
        # 取第一行非空文本
        for line in explanation.split("\n"):
            line = line.strip()
            if line and not line.startswith("```"):
                return line
    return ""


async def run_sql_generator(state: AgentState) -> AgentState:
    """执行 SQL 生成：填充模板 → 调用 Qwen → 提取 SQL"""
    task_id = state.get("task_id", "")
    retry_count = state.get("retry_count", 0)
    error_message = state.get("error_message", "")
    previous_sql = state.get("generated_sql", "")

    # 重试时递增计数
    if error_message and previous_sql:
        retry_count += 1

    logger.info("SQL 生成 task_id=%s retry=%d", task_id, retry_count)

    template = _get_template()
    prompt = template.render(
        rewritten_query=state.get("rewritten_query", ""),
        intent=state.get("extracted_intent", {}),
        schema_context=state.get("schema_context", []),
        conversation_history=state.get("conversation_history", []),
        error_message=error_message if retry_count > 0 else "",
        previous_sql=previous_sql if retry_count > 0 else "",
    )

    try:
        response_text = await call_llm(
            system_prompt="你是一个 MySQL SQL 专家。请严格按照约束规则生成安全的 SELECT 语句。",
            user_prompt=prompt,
        )
    except Exception as e:
        logger.error("SQL 生成 LLM 调用失败 task_id=%s error=%s", task_id, e)
        return {
            **state,
            "generated_sql": "",
            "error_message": f"AI 服务暂时不可用：{e}",
            "retry_count": retry_count,
            "current_node": "SQL_GENERATOR",
        }

    sql = _extract_sql(response_text)
    explanation = _extract_explanation(response_text)

    if not sql:
        logger.warning("SQL 生成失败：无法从 LLM 响应中提取 SQL task_id=%s", task_id)
        return {
            **state,
            "generated_sql": "",
            "sql_explanation": "",
            "error_message": "SQL 生成失败：LLM 未返回有效的 SQL 语句",
            "retry_count": retry_count,
            "current_node": "SQL_GENERATOR",
        }

    logger.info("SQL 生成完成 task_id=%s sql=%s", task_id, sql[:80])

    return {
        **state,
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
