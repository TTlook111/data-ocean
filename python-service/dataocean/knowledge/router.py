"""知识库模块路由

提供 skills.md 草稿生成的 HTTP 接口。
路由前缀由 main.py 统一挂载时指定（/internal/knowledge）。
"""

import logging
import asyncio

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from .schema import (
    GenerateDraftRequest,
    GenerateDraftResponse,
)
from .service import generate_draft

logger = logging.getLogger(__name__)

router = APIRouter()

def _candidate_error(exc:Exception) -> tuple[str,str]:
    if 'arrearage' in str(exc).lower() or 'insufficient_balance' in str(exc).lower():
        return 'AI_BILLING_REQUIRED','AI 供应商账户欠费，候选未生成；可人工补充或处理账户后重试'
    if isinstance(exc,TimeoutError) or 'timeout' in type(exc).__name__.lower():
        return 'AI_GENERATION_TIMEOUT','候选生成超时，原有内容保持不变，可人工补充或重试'
    if isinstance(exc,ValueError):return 'AI_INVALID_OUTPUT','候选内容或来源无效，请核对范围后重试'
    return 'AI_GENERATION_UNAVAILABLE','候选生成服务暂不可用，可人工补充或重试'

@router.post("/business-candidate", response_model=None)
async def business_candidate(payload: dict):
    from .business import generate_candidate
    try:
        return await generate_candidate(payload)
    except Exception as exc:
        logger.warning("业务知识候选未完成 type=%s", type(exc).__name__)
        code,message=_candidate_error(exc)
        return JSONResponse(status_code=502, content={"code":code,"message":message})

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
        code,message=_candidate_error(exc)
        logger.warning("字段语义候选生成失败 code=%s", code)
        return JSONResponse(status_code=502, content={"code": code, "message": message})


@router.post("/generate-draft", response_model=GenerateDraftResponse)
async def generate_draft_endpoint(request: GenerateDraftRequest) -> GenerateDraftResponse:
    """生成 skills.md 草稿接口

    接收固定元数据与审核来源，由程序生成完整结构目录，不调用 LLM。
    """
    logger.info("收到草稿生成请求 snapshot_id=%d", request.snapshot_id)
    return await generate_draft(request)
