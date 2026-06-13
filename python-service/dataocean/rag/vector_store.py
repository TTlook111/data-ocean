"""LangChain Milvus VectorStore adapter for DataOcean RAG."""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from typing import Any

from langchain_core.embeddings import Embeddings
from langchain_core.documents import Document
from langchain_milvus import Milvus

from dataocean.core.config import settings

from .milvus_client import ensure_collection

logger = logging.getLogger(__name__)

RAG_ELIGIBLE_STATUSES = ("NORMAL", "RECOMMENDED")

# 缓存 VectorStore 实例，避免每次操作都重建
# key: (collection_name, dimension)
_vector_store_cache: dict[tuple[str | None, int | None], Milvus] = {}


def clear_vector_store_cache() -> None:
    """清除缓存的 VectorStore 实例（配置热重载时调用）"""
    _vector_store_cache.clear()
    logger.info("VectorStore 缓存已清除")


class PrecomputedEmbeddings(Embeddings):
    """Embedding placeholder for vector-by-vector operations.

    DataOcean generates embeddings in infra.embeddings so indexing can use
    explicit per-request AI configuration. LangChain's Milvus store still
    requires an Embeddings object, even when add_embeddings/search_by_vector are
    used with precomputed vectors.
    """

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        raise NotImplementedError("DataOcean passes precomputed document embeddings")

    def embed_query(self, text: str) -> list[float]:
        raise NotImplementedError("DataOcean passes precomputed query embeddings")


@dataclass(frozen=True)
class SearchHit:
    document: Document
    score: float


def build_filter_expr(datasource_id: int, snapshot_id: int) -> str:
    eligible_statuses = ", ".join(f'"{status}"' for status in RAG_ELIGIBLE_STATUSES)
    return (
        f"datasource_id == {datasource_id} "
        f"and snapshot_id == {snapshot_id} "
        f'and review_status == "APPROVED" '
        f"and governance_status in [{eligible_statuses}]"
    )


def get_vector_store(
    collection_name: str | None = None,
    dimension: int | None = None,
) -> Milvus:
    """Return a LangChain Milvus store bound to DataOcean's fixed schema.

    使用缓存避免每次操作都重建实例。
    """
    cache_key = (collection_name, dimension)
    cached = _vector_store_cache.get(cache_key)
    if cached is not None:
        return cached

    collection = ensure_collection(collection_name, dimension)
    name = collection.name
    store = Milvus(
        embedding_function=PrecomputedEmbeddings(),
        collection_name=name,
        connection_args={"host": settings.milvus_host, "port": settings.milvus_port},
        auto_id=True,
        primary_field="id",
        text_field="chunk_text",
        vector_field="embedding",
        index_params={
            "index_type": "IVF_FLAT",
            "metric_type": "IP",
            "params": {"nlist": 128},
        },
        search_params={"metric_type": "IP", "params": {"nprobe": 16}},
    )
    _vector_store_cache[cache_key] = store
    return store


async def add_chunk_embeddings(
    *,
    texts: list[str],
    embeddings: list[list[float]],
    metadatas: list[dict[str, Any]],
    collection_name: str | None = None,
    dimension: int | None = None,
) -> list[str]:
    def _add() -> list[str]:
        store = get_vector_store(collection_name, dimension)
        ids = store.add_embeddings(texts=texts, embeddings=embeddings, metadatas=metadatas)
        _flush(store)
        return ids

    return await asyncio.to_thread(_add)


async def search_by_vector(
    *,
    embedding: list[float],
    datasource_id: int,
    snapshot_id: int,
    limit: int,
) -> list[SearchHit]:
    expr = build_filter_expr(datasource_id, snapshot_id)

    def _search() -> list[SearchHit]:
        store = get_vector_store()
        pairs = store.similarity_search_with_score_by_vector(
            embedding,
            k=limit,
            expr=expr,
            param={"metric_type": "IP", "params": {"nprobe": 16}},
        )
        return [SearchHit(document=document, score=score) for document, score in pairs]

    return await asyncio.to_thread(_search)


async def delete_by_expr(expr: str, collection_name: str | None = None) -> bool:
    def _delete() -> bool:
        store = get_vector_store(collection_name)
        deleted = bool(store.delete(expr=expr))
        _flush(store)
        return deleted

    return await asyncio.to_thread(_delete)


def _flush(store: Milvus) -> None:
    collection = store.col
    if collection is not None:
        collection.flush()
