"""知识库模块路由

提供 skills.md 草稿生成的 HTTP 接口。
路由前缀由 main.py 统一挂载时指定（/internal/knowledge）。
"""

import logging
import asyncio

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from .schema import (
    BatchGenerateResponse,
    GenerateDraftRequest,
    GenerateDraftResponse,
)
from .service import analyze_and_generate, generate_draft

logger = logging.getLogger(__name__)

router = APIRouter()

from .semantic import FieldSemanticRequest, SemanticRequest, generate_field_candidate, supplement


@router.post("/supplement-semantics", response_model=None)
async def supplement_semantics(request: SemanticRequest):
    try:
        return await supplement(request)
    except Exception as exc:
        def leaves(error):
            if isinstance(error, ExceptionGroup):
                return [leaf for child in error.exceptions for leaf in leaves(child)]
            return [error]

        errors = leaves(exc)
        if any("Arrearage" in str(error) for error in errors):
            code, message = "AI_BILLING_REQUIRED", "AI 供应商账户欠费，请处理后重试"
        elif any(isinstance(error, (TimeoutError, asyncio.TimeoutError)) for error in errors):
            code, message = "AI_GENERATION_TIMEOUT", "业务释义生成超时，请稍后重试"
        elif all(isinstance(error, ValueError) for error in errors):
            code, message = "AI_INVALID_OUTPUT", "业务释义内容或字段依据无效，请调整范围或重新生成"
        else:
            code, message = "AI_GENERATION_UNAVAILABLE", "AI 业务释义服务暂不可用，请稍后重试"
        logger.warning("业务释义补写失败 code=%s", code)
        return JSONResponse(status_code=502, content={"code": code, "message": message})


@router.post("/field-semantic-candidate", response_model=None)
async def field_semantic_candidate(request: FieldSemanticRequest):
    try:
        return await generate_field_candidate(request)
    except Exception as exc:
        if isinstance(exc, (TimeoutError, asyncio.TimeoutError)):
            code, message = "AI_GENERATION_TIMEOUT", "字段释义生成超时，请稍后重试"
        elif isinstance(exc, ValueError):
            code, message = "AI_INVALID_OUTPUT", "字段释义候选或输入依据无效"
        else:
            code, message = "AI_GENERATION_UNAVAILABLE", "字段释义生成服务暂不可用，请稍后重试"
        logger.warning("字段语义候选生成失败 code=%s", code)
        return JSONResponse(status_code=502, content={"code": code, "message": message})


@router.post("/generate-draft", response_model=GenerateDraftResponse)
async def generate_draft_endpoint(request: GenerateDraftRequest) -> GenerateDraftResponse:
    """生成 skills.md 草稿接口

    接收固定元数据与审核来源，由程序生成完整结构目录，不调用 LLM。
    """
    logger.info("收到草稿生成请求 snapshot_id=%d", request.snapshot_id)
    return await generate_draft(request)


@router.post("/analyze-and-generate", response_model=BatchGenerateResponse)
async def analyze_and_generate_endpoint(request: GenerateDraftRequest) -> BatchGenerateResponse:
    """按快照结构生成 skills.md 文档集合

    完整覆盖固定快照中的表字段；AI 释义使用独立候选入口。
    返回所有生成的文档列表。
    """
    logger.info("收到域分析+批量生成请求 snapshot_id=%d", request.snapshot_id)
    return await analyze_and_generate(request)
