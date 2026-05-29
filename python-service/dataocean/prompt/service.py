"""Prompt 服务模块

组合 router（获取模板）+ renderer（渲染变量）+ token_budget（裁剪），
对外提供 render_prompt(template_code, variables) 方法。
"""

import json
import logging
import os
from typing import Any

import httpx

from .renderer import render_template
from .token_budget import apply_token_budget

logger = logging.getLogger(__name__)

# Java 网关地址
JAVA_BASE_URL = os.getenv("JAVA_GATEWAY_URL", "http://localhost:8080")
# 内部调用 Token（与 Java 端 dataocean.internal.token 配置一致）
INTERNAL_TOKEN = os.getenv("INTERNAL_TOKEN", "dataocean-internal-default")


async def fetch_template(template_code: str) -> dict[str, Any]:
    """从 Java 内部 API 获取 Prompt 模板活跃版本内容

    Args:
        template_code: 模板编码

    Returns:
        包含 code/content/versionNo 的模板元数据。
    """
    url = f"{JAVA_BASE_URL}/internal/prompts/{template_code}"
    headers = {"X-Internal-Token": INTERNAL_TOKEN}
    async with httpx.AsyncClient(timeout=5.0) as client:
        response = await client.get(url, headers=headers)
        response.raise_for_status()
        data = response.json()
        template = data.get("data", {})
        return {
            "code": template.get("code", template_code),
            "content": template.get("content", ""),
            "versionNo": int(template.get("versionNo") or 0),
        }


async def fetch_template_content(template_code: str) -> str:
    """Fetch only the active template content, preserving the original API."""
    return (await fetch_template(template_code)).get("content", "")


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
    rendered, _ = await render_prompt_with_metadata(template_code, variables)
    return rendered


async def render_prompt_with_metadata(
    template_code: str,
    variables: dict[str, Any],
) -> tuple[str, int]:
    """Render a managed prompt and return the active version number."""
    template = await fetch_template(template_code)
    template_content = template.get("content", "")
    if not template_content:
        logger.error("获取 Prompt 模板失败 code=%s", template_code)
        return "", 0

    # Token 预算裁剪
    budgeted_variables = apply_token_budget(_stringify_variables(variables))

    # 渲染变量
    rendered = render_template(template_content, budgeted_variables)

    logger.debug(
        "Prompt 渲染完成 code=%s 长度=%d",
        template_code, len(rendered),
    )
    return rendered, int(template.get("versionNo") or 0)


def _stringify_variables(variables: dict[str, Any]) -> dict[str, str]:
    """将变量值统一转为字符串，复杂类型使用 JSON 序列化。"""
    result: dict[str, str] = {}
    for key, value in variables.items():
        if value is None:
            result[key] = ""
        elif isinstance(value, str):
            result[key] = value
        elif isinstance(value, (dict, list)):
            result[key] = json.dumps(value, ensure_ascii=False)
        else:
            result[key] = str(value)
    return result
