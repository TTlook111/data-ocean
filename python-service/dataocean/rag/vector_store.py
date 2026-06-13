"""Milvus VectorStore adapter for DataOcean RAG.

使用 MilvusClient 直接操作 Milvus，避免 LangChain Milvus 0.3.x 的连接问题。
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from typing import Any

from langchain_core.documents import Document

from dataocean.core.config import settings

from .milvus_client import get_client, ensure_collection

logger = logging.getLogger(__name__)

RAG_ELIGIBLE_STATUSES = ("NORMAL", "RECOMMENDED")


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


async def add_chunk_embeddings(
    *,
    texts: list[str],
    embeddings: list[list[float]],
    metadatas: list[dict[str, Any]],
    collection_name: str | None = None,
    dimension: int | None = None,
) -> list[str]:
    """添加 chunk embeddings 到 Milvus"""
    def _add() -> list[str]:
        # 确保 collection 存在
        ensure_collection(collection_name, dimension)
        name = collection_name or settings.milvus_collection_name

        # 使用 MilvusClient 直接插入
        client = get_client()

        # 构建插入数据
        data = []
        for i, (text, embedding, metadata) in enumerate(zip(texts, embeddings, metadatas)):
            entity = {
                "chunk_text": text[:8192],
                "embedding": embedding,
                **metadata,
            }
            data.append(entity)

        # 批量插入
        result = client.insert(
            collection_name=name,
            data=data,
        )

        # Flush
        client.flush(name)

        ids = result.get("ids", [])
        logger.info("Milvus 插入成功 collection=%s count=%d", name, len(ids))
        return [str(id) for id in ids]

    return await asyncio.to_thread(_add)


async def search_by_vector(
    *,
    embedding: list[float],
    datasource_id: int,
    snapshot_id: int,
    limit: int,
) -> list[SearchHit]:
    """向量检索"""
    expr = build_filter_expr(datasource_id, snapshot_id)

    def _search() -> list[SearchHit]:
        client = get_client()
        name = settings.milvus_collection_name

        results = client.search(
            collection_name=name,
            data=[embedding],
            limit=limit,
            filter=expr,
            output_fields=[
                "datasource_id", "snapshot_id", "knowledge_version_no",
                "doc_id", "source_id", "chunk_type", "governance_status",
                "review_status", "chunk_text", "related_table", "related_column",
            ],
            search_params={"metric_type": "IP", "params": {"nprobe": 16}},
        )

        search_hits = []
        for hits in results:
            for hit in hits:
                entity = hit.get("entity", {})
                score = hit.get("distance", 0.0)

                # 构建 Document
                document = Document(
                    page_content=entity.get("chunk_text", ""),
                    metadata={
                        "table_name": entity.get("related_table", ""),
                        "chunk_type": entity.get("chunk_type", ""),
                        "governance_status": entity.get("governance_status", ""),
                        "review_status": entity.get("review_status", ""),
                        "score": score,
                        "relevance_score": score,
                        "source_type": "SCHEMA",
                        "source_version": entity.get("knowledge_version_no", 0),
                        "snapshot_id": entity.get("snapshot_id"),
                        "related_column": entity.get("related_column", ""),
                    },
                )
                search_hits.append(SearchHit(document=document, score=score))

        return search_hits

    return await asyncio.to_thread(_search)


async def delete_by_expr(expr: str, collection_name: str | None = None) -> bool:
    """按条件删除向量"""
    def _delete() -> bool:
        client = get_client()
        name = collection_name or settings.milvus_collection_name

        try:
            client.delete(
                collection_name=name,
                filter=expr,
            )
            client.flush(name)
            return True
        except Exception as e:
            logger.warning("Delete failed: %s", e)
            return False

    return await asyncio.to_thread(_delete)
