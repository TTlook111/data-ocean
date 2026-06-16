"""SQL Generator Agent — 基于 LangChain create_agent 的受控子循环

封装 create_agent 调用，提供 generate_sql_with_agent 入口函数。
Agent 只允许调用只读工具，最终返回结构化 SQL 生成结果。
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from langchain.agents import create_agent

from dataocean.core.config import get_settings
from dataocean.infra.llm import get_chat_model

from .prompts import SYSTEM_PROMPT, build_user_message
from .schema import SqlGenerationResult
from .tools import create_tools

logger = logging.getLogger(__name__)


def _create_sql_agent(state: dict[str, Any]):
    """创建 SQL 生成 Agent 实例

    Args:
        state: 当前 AgentState，用于构造工具闭包

    Returns:
        LangChain agent 实例
    """
    current_settings = get_settings()
    llm = get_chat_model()
    tools = create_tools(state)

    # ToolStrategy 在函数内导入，避免 ImportError 阻塞模块加载
    from langchain.agents.structured_output import ToolStrategy

    # 构建 create_agent 参数，兼容不同版本的 LangChain
    agent_kwargs: dict[str, Any] = {
        "model": llm,
        "tools": tools,
        "response_format": ToolStrategy(SqlGenerationResult),
    }

    # max_tool_calls：LangChain 部分版本支持此参数
    # 如果当前版本不支持，外层 _node_wrapper 的超时控制会兜底
    max_tool_calls = getattr(current_settings, "sql_generator_agent_max_tool_calls", None)
    if max_tool_calls and max_tool_calls > 0:
        agent_kwargs["max_tool_calls"] = max_tool_calls

    agent = create_agent(**agent_kwargs)
    return agent


async def generate_sql_with_agent(state: dict[str, Any]) -> dict[str, Any]:
    """使用 Agent 生成 SQL

    构造消息 → 创建 Agent → Agent 按需调用只读工具 → 返回结构化结果。

    Args:
        state: 当前 AgentState

    Returns:
        AgentState patch，包含 generated_sql、sql_explanation 等字段

    Raises:
        Exception: Agent 执行失败时抛出，由调用方 fallback
    """
    task_id = state.get("task_id", "")
    retry_count = state.get("retry_count", 0)

    logger.info("SQL Generator Agent 开始 task_id=%s retry=%d", task_id, retry_count)

    # 构造消息
    user_message = build_user_message(state)
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": user_message},
    ]

    # 创建 Agent 并执行，带超时保护
    agent = _create_sql_agent(state)
    node_timeout = state.get("_node_timeout", 60)

    try:
        result = await asyncio.wait_for(
            asyncio.to_thread(
                agent.invoke,
                {"messages": messages},
            ),
            timeout=node_timeout,
        )
    except asyncio.TimeoutError:
        logger.error(
            "SQL Generator Agent 超时 task_id=%s timeout=%s", task_id, node_timeout
        )
        raise RuntimeError(f"SQL Generator Agent 超时 ({node_timeout}s)")
    except Exception:
        logger.error(
            "SQL Generator Agent 执行失败 task_id=%s", task_id, exc_info=True
        )
        raise

    # 提取结构化响应（防御性处理：structured_response 可能是 dict）
    structured = result.get("structured_response")
    if structured and isinstance(structured, dict):
        try:
            structured = SqlGenerationResult(**structured)
        except Exception:
            logger.warning("structured_response dict 转换失败 task_id=%s", task_id)
            structured = None
    if not structured or not structured.sql:
        logger.warning(
            "SQL Generator Agent 未返回有效 SQL task_id=%s", task_id
        )
        return {
            "generated_sql": "",
            "sql_explanation": "",
            "error_message": "SQL 生成失败：Agent 未返回有效 SELECT 语句",
            "retry_count": retry_count,
            "current_node": "SQL_GENERATOR",
        }

    logger.info(
        "SQL Generator Agent 完成 task_id=%s sql=%s tools=%s",
        task_id,
        structured.sql[:80],
        structured.used_tool_names,
    )

    return {
        "generated_sql": structured.sql,
        "sql_explanation": structured.explanation,
        "error_message": "",
        "retry_count": retry_count,
        "current_node": "SQL_GENERATOR",
    }
