"""Prompt 管理路由

提供 Prompt 模板获取和渲染的 HTTP 接口。
- GET /{template_code} — 获取指定编码的 Prompt 模板活跃版本内容
- POST /{template_code}/render — 渲染模板（传入变量）
"""

import logging

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from .service import fetch_template_content, render_prompt

logger = logging.getLogger(__name__)

router = APIRouter()


class RenderRequest(BaseModel):
    """模板渲染请求"""
    variables: dict[str, str] = {}


@router.get("/{template_code}")
async def get_prompt_template(template_code: str) -> dict[str, str]:
    """获取 Prompt 模板活跃版本内容"""
    try:
        content = await fetch_template_content(template_code)
        return {"code": template_code, "content": content}
    except Exception as e:
        logger.error("获取 Prompt 模板失败 code=%s error=%s", template_code, str(e))
        raise HTTPException(status_code=502, detail=f"获取模板失败：{str(e)}")


@router.post("/{template_code}/render")
async def render_template_endpoint(template_code: str, request: RenderRequest) -> dict[str, str]:
    """渲染 Prompt 模板（含 Token 预算裁剪）"""
    try:
        rendered = await render_prompt(template_code, request.variables)
        return {"code": template_code, "rendered": rendered}
    except Exception as e:
        logger.error("渲染 Prompt 模板失败 code=%s error=%s", template_code, str(e))
        raise HTTPException(status_code=500, detail=f"渲染失败：{str(e)}")
