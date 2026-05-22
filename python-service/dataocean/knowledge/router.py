"""知识库模块路由"""

import logging

from fastapi import APIRouter

from .schema import GenerateDraftRequest, GenerateDraftResponse
from .service import generate_draft

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/internal/knowledge", tags=["knowledge"])


@router.post("/generate-draft", response_model=GenerateDraftResponse)
async def generate_draft_endpoint(request: GenerateDraftRequest) -> GenerateDraftResponse:
    """生成 skills.md 草稿接口

    接收元数据快照信息，调用 LLM 生成结构化的 skills.md 草稿。
    """
    logger.info("收到草稿生成请求 snapshot_id=%d", request.snapshot_id)
    return await generate_draft(request)
