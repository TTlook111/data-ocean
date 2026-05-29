"""问题改写节点

解析时间表达式、消解多轮指代、提取查询意图，
将用户原始问题改写为结构化查询。
"""

from __future__ import annotations

import json
import logging
import re
from datetime import date
from pathlib import Path

from jinja2 import Template

from ..llm import call_llm
from ..prompt_tracking import record_prompt_version
from ..state import AgentState

logger = logging.getLogger(__name__)

_PROMPTS_DIR = Path(__file__).parent.parent / "prompts"
_template_cache: Template | None = None


def _get_template() -> Template:
    """获取缓存的问题改写 Prompt 模板"""
    global _template_cache
    if _template_cache is None:
        path = _PROMPTS_DIR / "query_rewrite.j2"
        _template_cache = Template(path.read_text(encoding="utf-8"))
    return _template_cache


def _extract_json(text: str) -> dict:
    """从 LLM 响应中提取 JSON 对象"""
    # 尝试匹配 ```json ... ``` 代码块
    match = re.search(r"```(?:json)?\s*\n?(.*?)\n?```", text, re.DOTALL)
    if match:
        return json.loads(match.group(1).strip())
    # 尝试直接解析整个文本
    text = text.strip()
    if text.startswith("{"):
        return json.loads(text)
    raise ValueError("无法从 LLM 响应中提取 JSON")


async def run_query_rewriter(state: AgentState) -> AgentState:
    """执行问题改写：填充模板 → 调用 Qwen → 解析 JSON → 写入 state"""
    question = state.get("question", "")
    task_id = state.get("task_id", "")
    conversation_history = state.get("conversation_history", [])

    logger.info("问题改写 task_id=%s question=%s", task_id, question[:50])
    # TODO: query_rewrite 尚未接入 Prompt 管理模板，暂记录 version=0 用于审计追踪
    state = record_prompt_version(state, "query_rewrite", 0)

    template = _get_template()
    prompt = template.render(
        current_date=date.today().isoformat(),
        question=question,
        conversation_history=conversation_history,
        user_memory=None,
    )

    try:
        response_text = await call_llm(
            system_prompt="你是一个数据查询意图分析专家。请严格按照要求输出 JSON 格式。",
            user_prompt=prompt,
        )
        result = _extract_json(response_text)
    except Exception as e:
        logger.warning("问题改写 LLM 解析失败 task_id=%s error=%s，使用原始问题", task_id, e)
        # 降级：直接使用原始问题
        return {
            **state,
            "rewritten_query": question,
            "extracted_intent": {
                "dimensions": [],
                "metrics": [],
                "filters": [],
                "time_range": None,
                "sort": None,
                "is_ambiguous": False,
            },
            "current_node": "QUERY_REWRITER",
        }

    rewritten_query = result.get("rewritten_query", question)
    intent = result.get("intent", {})
    is_ambiguous = result.get("is_ambiguous", False)
    clarification_hint = result.get("clarification_hint")

    # 如果问题过于模糊且无法自动消解，终止流程
    if is_ambiguous and clarification_hint:
        return {
            **state,
            "rewritten_query": rewritten_query,
            "extracted_intent": intent,
            "error_message": clarification_hint,
            "current_node": "QUERY_REWRITER",
        }

    logger.info("问题改写完成 task_id=%s rewritten=%s", task_id, rewritten_query[:80])

    return {
        **state,
        "rewritten_query": rewritten_query,
        "extracted_intent": intent,
        "current_node": "QUERY_REWRITER",
    }
