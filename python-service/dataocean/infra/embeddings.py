"""Embedding 向量生成层（基于 LangChain OpenAIEmbeddings）

统一封装文本向量化，走 DashScope OpenAI 兼容端点，替代手写 httpx + 重试。
显式指定 model=text-embedding-v4，保证输出维度与现有 Milvus collection（1024 维）一致。

属于 infra 中性层，只依赖 core.config。
保持 embed_texts / embed_single 的 async 签名，写入侧与检索侧调用方零改动。
"""

from __future__ import annotations

import logging

from langchain_openai import OpenAIEmbeddings

from dataocean.core.config import settings
from dataocean.core.exceptions import LLMException

logger = logging.getLogger(__name__)

_embeddings: OpenAIEmbeddings | None = None


def _get_embeddings() -> OpenAIEmbeddings:
    """获取（缓存的）OpenAIEmbeddings 实例，指向 DashScope 兼容端点。

    关键配置：
    - model=settings.qwen_embedding_model（text-embedding-v4，默认 1024 维，与现有索引一致）
    - check_embedding_ctx_length=False：禁用 tiktoken 长度切分（其分词器不适配 qwen 模型）
    - chunk_size=settings.embedding_batch_size：保留原有分批大小
    """
    global _embeddings
    if _embeddings is None:
        _embeddings = OpenAIEmbeddings(
            model=settings.qwen_embedding_model,
            api_key=settings.dashscope_api_key or "dummy",
            base_url=settings.dashscope_base_url,
            check_embedding_ctx_length=False,
            chunk_size=settings.embedding_batch_size,
        )
    return _embeddings


async def embed_texts(texts: list[str]) -> list[list[float]]:
    """批量生成文本向量

    Args:
        texts: 待向量化的文本列表

    Returns:
        对应的向量列表（维度由 text-embedding-v4 决定，默认 1024）

    Raises:
        LLMException: Embedding API 调用失败时抛出（保持与原 embedder.py 一致的异常类型）
    """
    if not texts:
        return []
    try:
        return await _get_embeddings().aembed_documents(texts)
    except Exception as e:
        logger.error("Embedding 批量生成失败 count=%d error=%s", len(texts), e)
        raise LLMException(f"Embedding 生成失败：{e}")


async def embed_single(text: str) -> list[float]:
    """生成单条文本向量"""
    try:
        return await _get_embeddings().aembed_query(text)
    except Exception as e:
        logger.error("Embedding 单条生成失败 error=%s", e)
        raise LLMException(f"Embedding 生成失败：{e}")
