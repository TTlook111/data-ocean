"""Prompt 服务模块

组合 router（获取模板）+ renderer（渲染变量）+ token_budget（裁剪），
对外提供 render_prompt(template_code, variables) 方法。
"""

import logging
import os

import httpx

from .renderer import render_template
from .token_budget import apply_token_budget

logger = logging.getLogger(__name__)

# Java 网关地址
JAVA_BASE_URL = os.getenv("JAVA_GATEWAY_URL", "http://localhost:8080")
# 内部调用 Token（与 Java 端 dataocean.internal.token 配置一致）
INTERNAL_TOKEN = os.getenv("INTERNAL_TOKEN", "dataocean-internal-default")


async def fetch_template_content(template_code: str) -> str:
    """从 Java 内部 API 获取 Prompt 模板活跃版本内容

    Args:
        template_code: 模板编码

    Returns:
        模板内容文本

    Raises:
        httpx.HTTPStatusError: Java API 返回非 200 状态码
    """
    url = f"{JAVA_BASE_URL}/internal/prompts/{template_code}"
    headers = {"X-Internal-Token": INTERNAL_TOKEN}
    async with httpx.AsyncClient(timeout=5.0) as client:
        response = await client.get(url, headers=headers)
        response.raise_for_status()
        data = response.json()
        return data.get("data", {}).get("content", "")


async def render_prompt(template_code: str, variables: dict[str, str]) -> str:
    """完整的 Prompt 渲染流程

    1. 从 Java API 获取模板内容
    2. 对变量应用 Token 预算控制
    3. 渲染模板变量

    Args:
        template_code: 模板编码（如 sql_generation）
        variables: 变量字典

    Returns:
        渲染并裁剪后的完整 Prompt 文本
    """
    # 获取模板
    template_content = await fetch_template_content(template_code)
    if not template_content:
        logger.error("获取 Prompt 模板失败 code=%s", template_code)
        return ""

    # Token 预算裁剪
    budgeted_variables = apply_token_budget(variables)

    # 渲染变量
    rendered = render_template(template_content, budgeted_variables)

    logger.debug(
        "Prompt 渲染完成 code=%s 长度=%d",
        template_code, len(rendered),
    )
    return rendered
