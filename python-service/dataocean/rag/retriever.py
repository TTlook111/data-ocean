"""Schema RAG retriever backed by LangChain Milvus VectorStore.

支持上下文扩展：命中一个 chunk 时自动带出相邻 chunk，解决跨节语义断裂问题。
"""

from __future__ import annotations

import logging

from .schema import RetrievedSchema, RetrieveRequest
from .vector_store import search_by_vector, get_client

logger = logging.getLogger(__name__)


async def retrieve_from_milvus(
    question_embedding: list[float], request: RetrieveRequest
) -> list[RetrievedSchema]:
    """Retrieve schema chunks with enforced datasource/snapshot/admission filters.

    流程：
    1. 向量检索获取初始结果（top_k * 2）
    2. 上下文扩展：对每个命中 chunk，尝试带出同文档的相邻 chunk
    3. 去重后返回
    """
    hits = await search_by_vector(
        embedding=question_embedding,
        datasource_id=request.datasource_id,
        snapshot_id=request.active_snapshot_id,
        limit=request.top_k * 2,
    )

    if not hits:
        return []

    # 上下文扩展：收集命中 chunk 的 source_id，查询相邻 chunk
    expanded_hits = list(hits)
    source_ids = set()
    for hit in hits:
        metadata = hit.document.metadata
        source_id = metadata.get("source_id")
        if source_id is not None:
            source_ids.add(source_id)

    if source_ids:
        # 动态计算 limit：每个 source_id 最多带出 3 个相邻 chunk
        adjacent_limit = min(len(source_ids) * 3, 20)
        adjacent_hits = await _fetch_adjacent_chunks(
            source_ids=source_ids,
            datasource_id=request.datasource_id,
            snapshot_id=request.active_snapshot_id,
            limit=adjacent_limit,
        )
        # 合并去重（按 chunk_text 去重）
        existing_texts = {hit.document.page_content for hit in expanded_hits}
        for adj_hit in adjacent_hits:
            if adj_hit.document.page_content not in existing_texts:
                expanded_hits.append(adj_hit)
                existing_texts.add(adj_hit.document.page_content)

    retrieved: list[RetrievedSchema] = []
    for hit in expanded_hits:
        metadata = hit.document.metadata
        related_column = metadata.get("related_column", "")
        # 兼容两种 key：table_name 和 related_table
        table_name = metadata.get("table_name") or metadata.get("related_table", "")
        retrieved.append(
            RetrievedSchema(
                table_name=table_name,
                columns=[
                    col.strip()
                    for col in related_column.split(",")
                    if col.strip()
                ]
                if related_column
                else [],
                score=hit.score,
                relevance_score=hit.score,
                chunk_type=metadata.get("chunk_type", ""),
                source_version=metadata.get("source_version") or metadata.get("knowledge_version_no", 0),
                snapshot_id=metadata.get("snapshot_id"),
                chunk_text=hit.document.page_content,
                governance_status=metadata.get("governance_status", ""),
                review_status=metadata.get("review_status", ""),
            )
        )

    logger.info(
        "Milvus retrieval completed datasource_id=%d raw=%d expanded=%d",
        request.datasource_id,
        len(hits),
        len(retrieved),
    )
    return retrieved


async def _fetch_adjacent_chunks(
    source_ids: set,
    datasource_id: int,
    snapshot_id: int,
    limit: int = 20,
) -> list:
    """查询与命中 chunk 同文档的相邻 chunk（上下文扩展）

    通过 source_id 关联查找同一 skills.md 文档中的其他 chunk，
    为跨节查询提供完整上下文。

    Args:
        source_ids: 命中 chunk 的 source_id 集合
        datasource_id: 数据源 ID
        snapshot_id: 快照 ID

    Returns:
        相邻 chunk 的 SearchHit 列表
    """
    from dataocean.core.config import settings
    from .vector_store import SearchHit, RAG_ELIGIBLE_STATUSES
    from langchain_core.documents import Document

    def _query() -> list:
        client = get_client()
        name = settings.milvus_collection_name

        # 构建过滤条件：同数据源 + 同快照 + 同文档 + 准入状态
        eligible_statuses = ", ".join(f'"{s}"' for s in RAG_ELIGIBLE_STATUSES)
        source_id_list = ", ".join(str(sid) for sid in source_ids)
        expr = (
            f"datasource_id == {datasource_id} "
            f"and snapshot_id == {snapshot_id} "
            f"and source_id in [{source_id_list}] "
            f'and review_status == "APPROVED" '
            f"and governance_status in [{eligible_statuses}]"
        )

        try:
            results = client.query(
                collection_name=name,
                filter=expr,
                output_fields=[
                    "datasource_id", "snapshot_id", "knowledge_version_no",
                    "doc_id", "source_id", "chunk_type", "governance_status",
                    "review_status", "chunk_text", "related_table", "related_column",
                ],
                limit=limit,
            )

            hits = []
            for entity in results:
                document = Document(
                    page_content=entity.get("chunk_text", ""),
                    metadata={
                        "table_name": entity.get("related_table", ""),
                        "chunk_type": entity.get("chunk_type", ""),
                        "governance_status": entity.get("governance_status", ""),
                        "review_status": entity.get("review_status", ""),
                        "score": 0.3,  # 相邻 chunk 给较低基础分
                        "relevance_score": 0.3,
                        "source_type": "SCHEMA",
                        "source_version": entity.get("knowledge_version_no", 0),
                        "snapshot_id": entity.get("snapshot_id"),
                        "related_column": entity.get("related_column", ""),
                        "source_id": entity.get("source_id"),
                    },
                )
                hits.append(SearchHit(document=document, score=0.3))
            return hits
        except Exception as e:
            logger.warning("上下文扩展查询失败: %s", e, exc_info=True)
            return []

    import asyncio
    return await asyncio.to_thread(_query)
