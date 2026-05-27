"""图表生成路由

提供基于查询结果生成 ECharts Option 的 HTTP 接口。
- POST /generate — 根据数据和用户意图生成图表配置
"""

import logging

from fastapi import APIRouter
from pydantic import BaseModel, Field

from .service import generate_chart

logger = logging.getLogger(__name__)

router = APIRouter()


class ChartGenerateRequest(BaseModel):
    """图表生成请求"""

    question: str = ""
    data_preview: list[dict] = Field(default_factory=list)
    column_types: dict[str, str] = Field(default_factory=dict)
    total_rows: int = 0


@router.post("/generate")
async def generate_chart_endpoint(request: ChartGenerateRequest) -> dict:
    """生成 ECharts 图表配置"""
    logger.info("图表生成请求 question=%s total_rows=%d", request.question[:50], request.total_rows)

    result = await generate_chart(
        question=request.question,
        data_preview=request.data_preview,
        column_types=request.column_types,
        total_rows=request.total_rows,
    )

    return result.to_dict()

