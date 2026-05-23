"""图表生成路由

提供基于查询结果生成 ECharts Option 的 HTTP 接口。
- POST /generate — 根据数据和用户意图生成图表配置
"""

from fastapi import APIRouter, HTTPException

router = APIRouter()


@router.post("/generate")
async def generate_chart() -> dict[str, str]:
    """生成 ECharts 图表配置（待实现）"""
    raise HTTPException(status_code=501, detail="图表生成尚未实现")
