"""会话长期上下文摘要生成。

Java 负责完整消息和摘要的持久化，本模块只在请求中接收消息增量，
调用现有外部 LLM 生成结构化摘要，不保存 conversationId 或会话状态。

B6 批次 3：由 `agent/conversation_summary.py` 迁入。旧 Agent 工作流已整条退役，
但会话摘要仍由 Java 的 IamS1QueryServiceImpl → ConversationContextSummaryService →
ConversationSummaryClient 调用（POST /internal/query/context-summary），因此必须保留。
"""

from __future__ import annotations

from datetime import date
from pathlib import Path

from dataocean.infra.llm import call_llm
from dataocean.infra.parsers import JsonBlockOutputParser
from dataocean.prompt.renderer import render_template_file
from dataocean.prompt.scenes import invoke_scene

from .schema import ConversationSummaryRequest

_PROMPTS_DIR = Path(__file__).parent / "prompts"
_LOCAL_TEMPLATE = _PROMPTS_DIR / "conversation_summary.j2"
_MANAGED_TEMPLATE_CODE = "conversation_summary"
_SYSTEM_PROMPT = "你是一个会话上下文整理助手。请严格按照要求只输出 JSON 对象。"
_JSON_PARSER = JsonBlockOutputParser(allow_null=False)


async def generate_context_summary(request: ConversationSummaryRequest) -> dict:
    """合并已有摘要和新增消息，返回结构化摘要。"""
    previous_summary = request.previous_summary or {}
    if not request.messages:
        return previous_summary

    variables = {
        "previous_summary": previous_summary,
        "messages": request.messages,
        "current_date": request.current_date or date.today().isoformat(),
    }

    response = await invoke_scene(
        _MANAGED_TEMPLATE_CODE, variables, invoke=call_llm,
        fixed_system=_SYSTEM_PROMPT,
        fallback_user=render_template_file(_LOCAL_TEMPLATE, **variables),
        temperature=0.1,
    )
    summary = _JSON_PARSER.parse(response)
    if not isinstance(summary, dict):
        raise ValueError("会话摘要必须是 JSON 对象")
    return summary
