"""会话长期上下文摘要生成。

Java 负责完整消息和摘要的持久化，本模块只在请求中接收消息增量，
调用现有外部 LLM 生成结构化摘要，不保存 conversationId 或会话状态。
"""

from __future__ import annotations

import logging
from datetime import date
from pathlib import Path

from dataocean.infra.llm import call_llm
from dataocean.infra.parsers import JsonBlockOutputParser
from dataocean.prompt.renderer import render_template_file
from dataocean.prompt.service import render_prompt_with_metadata

from .schema import ConversationSummaryRequest

logger = logging.getLogger(__name__)

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

    try:
        prompt, _ = await render_prompt_with_metadata(_MANAGED_TEMPLATE_CODE, variables)
        if not prompt:
            raise RuntimeError("managed 摘要模板为空")
    except Exception as exc:
        logger.info("managed 摘要模板不可用，使用本地模板: %s", exc)
        prompt = render_template_file(_LOCAL_TEMPLATE, **variables)

    response = await call_llm(
        system_prompt=_SYSTEM_PROMPT,
        user_prompt=prompt,
        temperature=0.1,
    )
    summary = _JSON_PARSER.parse(response)
    if not isinstance(summary, dict):
        raise ValueError("会话摘要必须是 JSON 对象")
    return summary
