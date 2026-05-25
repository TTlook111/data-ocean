"""Agent LLM 调用工具

封装 Qwen API 调用逻辑，供各节点统一使用。
支持 system/user 消息、JSON 模式、重试机制。
"""

from __future__ import annotations

import asyncio
import logging

import httpx

from dataocean.core.config import settings
from dataocean.core.exceptions import LLMException

logger = logging.getLogger(__name__)

_http_client: httpx.AsyncClient | None = None


def _get_client() -> httpx.AsyncClient:
    """获取复用的 httpx AsyncClient"""
    global _http_client
    if _http_client is None or _http_client.is_closed:
        _http_client = httpx.AsyncClient(timeout=float(settings.llm_timeout))
    return _http_client


async def call_llm(
    system_prompt: str,
    user_prompt: str,
    *,
    model: str | None = None,
    temperature: float | None = None,
    max_retries: int = 2,
) -> str:
    """调用 Qwen API 获取文本响应

    Args:
        system_prompt: 系统角色提示
        user_prompt: 用户消息
        model: 模型名称，默认使用 settings.qwen_model
        temperature: 温度参数，默认使用 settings.llm_temperature
        max_retries: 最大重试次数

    Returns:
        LLM 生成的文本内容

    Raises:
        LLMException: 调用失败时抛出
    """
    model = model or settings.qwen_model
    temperature = temperature if temperature is not None else settings.llm_temperature

    for attempt in range(max_retries + 1):
        try:
            client = _get_client()
            response = await client.post(
                f"{settings.dashscope_base_url}/chat/completions",
                headers={"Authorization": f"Bearer {settings.dashscope_api_key}"},
                json={
                    "model": model,
                    "messages": [
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_prompt},
                    ],
                    "temperature": temperature,
                },
            )
            response.raise_for_status()
            data = response.json()
            choices = data.get("choices")
            if not choices or not isinstance(choices, list):
                raise LLMException("LLM 响应格式异常：缺少 choices 字段")
            content = choices[0].get("message", {}).get("content")
            if not content:
                raise LLMException("LLM 响应格式异常：content 为空")
            return content
        except (httpx.TimeoutException, httpx.ConnectError, httpx.ReadError) as e:
            if attempt < max_retries:
                wait_seconds = (attempt + 1) * 3
                logger.warning(
                    "LLM 调用失败，%d 秒后重试 attempt=%d/%d reason=%s",
                    wait_seconds, attempt + 1, max_retries + 1, str(e),
                )
                await asyncio.sleep(wait_seconds)
            else:
                raise LLMException(f"AI 服务暂时不可用：{e}")
        except LLMException:
            raise
        except httpx.HTTPStatusError as e:
            raise LLMException(f"AI 服务返回错误：HTTP {e.response.status_code}")
        except Exception as e:
            raise LLMException(f"AI 服务调用异常：{e}")

    raise LLMException("AI 服务调用失败")
