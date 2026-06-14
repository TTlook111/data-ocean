"""Embedding 向量生成层（基于 LangChain OpenAIEmbeddings）

统一封装文本向量化，走 DashScope OpenAI 兼容端点，替代手写 httpx + 重试。
显式指定 model=text-embedding-v4，保证输出维度与现有 Milvus collection（1024 维）一致。

属于 infra 中性层，只依赖 core.config。
保持 embed_texts / embed_single 的 async 签名，写入侧与检索侧调用方零改动。
"""

from __future__ import annotations

import asyncio
import logging

from langchain_openai import OpenAIEmbeddings

from dataocean.core.config import get_settings
from dataocean.core.exceptions import LLMException
from dataocean.rag.schema import EmbeddingConfig

logger = logging.getLogger(__name__)

_embeddings: OpenAIEmbeddings | None = None
_embeddings_lock = asyncio.Lock()
_embedding_cache: dict[tuple[str, str, str], OpenAIEmbeddings] = {}
_embedding_cache_lock = asyncio.Lock()


def clear_embeddings_cache() -> None:
    """清除缓存的 Embeddings 实例（配置热重载时调用）"""
    global _embeddings
    _embeddings = None
    _embedding_cache.clear()
    logger.info("Embeddings 缓存已清除")


async def _get_embeddings() -> OpenAIEmbeddings:
    """获取（缓存的）OpenAIEmbeddings 实例，指向 DashScope 兼容端点。

    使用 asyncio.Lock + double-check 模式避免并发创建多个实例。

    关键配置：
    - model=settings.qwen_embedding_model（text-embedding-v4，默认 1024 维，与现有索引一致）
    - check_embedding_ctx_length=False：禁用 tiktoken 长度切分（其分词器不适配 qwen 模型）
    - chunk_size=settings.embedding_batch_size：保留原有分批大小
    """
    global _embeddings

    # 第一次检查（无锁，快速路径）
    if _embeddings is not None:
        return _embeddings

    # 获取锁后再次检查（double-check 模式）
    async with _embeddings_lock:
        if _embeddings is not None:
            return _embeddings
        settings = get_settings()
        _embeddings = OpenAIEmbeddings(
            model=settings.qwen_embedding_model,
            api_key=settings.embedding_api_key or settings.dashscope_api_key or "dummy",
            base_url=settings.embedding_base_url or settings.dashscope_base_url,
            check_embedding_ctx_length=False,
            chunk_size=settings.embedding_batch_size,
        )
        logger.info("Embeddings 实例已创建 model=%s", settings.qwen_embedding_model)
    return _embeddings


async def _get_embeddings_for_config(config: EmbeddingConfig) -> OpenAIEmbeddings:
    """按显式配置获取 Embedding 实例，用于 pending 索引构建。

    使用 asyncio.Lock 保护缓存写入，避免并发创建重复实例。
    该函数不修改全局 settings，也不影响查询侧 active Embedding 单例。
    """
    settings = get_settings()
    base_url = config.base_url or settings.dashscope_base_url
    api_key = config.api_key or settings.dashscope_api_key or "dummy"
    provider_id = config.provider_id or base_url
    cache_key = (provider_id, base_url, config.model)

    # 快速路径：缓存命中
    cached = _embedding_cache.get(cache_key)
    if cached is not None:
        return cached

    # 获取锁后再次检查（double-check 模式）
    async with _embedding_cache_lock:
        cached = _embedding_cache.get(cache_key)
        if cached is not None:
            return cached
        embeddings = OpenAIEmbeddings(
            model=config.model,
            api_key=api_key,
            base_url=base_url,
            check_embedding_ctx_length=False,
            chunk_size=settings.embedding_batch_size,
        )
        _embedding_cache[cache_key] = embeddings
        logger.info("Embeddings 实例已创建(model config) model=%s", config.model)
    return embeddings


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
        embeddings = await _get_embeddings()
        return await embeddings.aembed_documents(texts)
    except Exception as e:
        logger.error("Embedding 批量生成失败 count=%d error=%s", len(texts), e)
        raise LLMException(f"Embedding 生成失败：{e}")


async def embed_texts_with_config(
    texts: list[str],
    embedding_config: EmbeddingConfig,
) -> list[list[float]]:
    """使用显式 Embedding 配置批量生成向量，供 pending 索引构建使用。"""
    if not texts:
        return []
    try:
        embeddings = await _get_embeddings_for_config(embedding_config)
        return await embeddings.aembed_documents(texts)
    except Exception as e:
        logger.error(
            "Pending Embedding 批量生成失败 model=%s count=%d error=%s",
            embedding_config.model,
            len(texts),
            e,
        )
        raise LLMException(f"Embedding 生成失败：{e}")


async def embed_single(text: str) -> list[float]:
    """生成单条文本向量"""
    try:
        embeddings = await _get_embeddings()
        return await embeddings.aembed_query(text)
    except Exception as e:
        logger.error("Embedding 单条生成失败 error=%s", e)
        raise LLMException(f"Embedding 生成失败：{e}")
