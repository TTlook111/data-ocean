"""Prompt 管理路由

提供 Prompt 模板获取的 HTTP 接口。
- GET /{template_code} — 获取指定编码的 Prompt 模板
"""

from fastapi import APIRouter, HTTPException

router = APIRouter()


@router.get("/{template_code}")
async def get_prompt_template(template_code: str) -> dict[str, str]:
    """获取 Prompt 模板（待实现）"""
    raise HTTPException(status_code=501, detail="Prompt 模板管理尚未实现")
