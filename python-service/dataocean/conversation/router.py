"""会话摘要内部路由。

路径刻意保持为 `/internal/query/context-summary`（与旧 Agent 路由时代一致），
以免为纯搬迁而改动 Java 侧的 ConversationSummaryClientImpl。
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException

from dataocean.core.error_messages import sanitize_error

from .schema import ConversationSummaryRequest, ConversationSummaryResponse
from .summary import generate_context_summary

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post("/context-summary", response_model=ConversationSummaryResponse)
async def context_summary(request: ConversationSummaryRequest) -> ConversationSummaryResponse:
    """根据 Java 提供的消息增量生成长期摘要。"""
    try:
        summary = await generate_context_summary(request)
        return ConversationSummaryResponse(summary=summary)
    except Exception as exc:
        logger.warning("生成会话摘要失败: %s", exc, exc_info=True)
        raise HTTPException(status_code=502, detail=sanitize_error(str(exc))) from exc
