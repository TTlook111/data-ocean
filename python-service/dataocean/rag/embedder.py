"""Embedding 向量生成封装

使用 dashscope text-embedding-v4 API，支持批量生成和重试。
"""

import asyncio
import logging

import httpx

from dataocean.core.config import settings
from dataocean.core.exceptions import LLMException

logger = logging.getLogger(__name__)


async def embed_texts(texts: list[str]) -> list[list[float]]:
    """批量生成文本向量

    按 batch_size 分批调用 API，每批最多重试 3 次。

    Args:
        texts: 待向量化的文本列表

    Returns:
        对应的向量列表（维度由 settings.embedding_dimension 决定）
    """
    all_embeddings = []
    batch_size = settings.embedding_batch_size

    for i in range(0, len(texts), batch_size):
        batch = texts[i : i + batch_size]
        embeddings = await _embed_batch_with_retry(batch)
        all_embeddings.extend(embeddings)

    return all_embeddings


async def embed_single(text: str) -> list[float]:
    """生成单条文本向量"""
    result = await embed_texts([text])
    return result[0]


async def _embed_batch_with_retry(texts: list[str], max_retries: int = 3) -> list[list[float]]:
    """带重试的批量 embedding 调用"""
    for attempt in range(max_retries):
        try:
            async with httpx.AsyncClient(timeout=30.0) as client:
                response = await client.post(
                    f"{settings.dashscope_base_url}/embeddings",
                    headers={"Authorization": f"Bearer {settings.dashscope_api_key}"},
                    json={
                        "model": settings.qwen_embedding_model,
                        "input": texts,
                        "encoding_format": "float",
                    },
                )
                response.raise_for_status()
                data = response.json()
                return [item["embedding"] for item in data["data"]]
        except (httpx.TimeoutException, httpx.ConnectError) as e:
            if attempt < max_retries - 1:
                wait = (attempt + 1) * 2
                logger.warning(
                    "Embedding 调用失败，%ds 后重试 attempt=%d reason=%s",
                    wait,
                    attempt + 1,
                    str(e),
                )
                await asyncio.sleep(wait)
            else:
                raise LLMException(f"Embedding 生成失败：{e}")
    raise LLMException("Embedding 生成失败：重试耗尽")
