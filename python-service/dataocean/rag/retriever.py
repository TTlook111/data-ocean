"""Schema RAG retriever backed by LangChain Milvus VectorStore.

支持上下文扩展：命中一个 chunk 时自动带出相邻 chunk，解决跨节语义断裂问题。
"""

from __future__ import annotations

import json
import logging
from typing import Any

from .schema import ColumnInfo, RetrievedSchema, RetrieveRequest
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

    # 上下文扩展：使用文档版本、语义分组和 chunk 顺序查询真正相邻的切片。
    expanded_hits = list(hits)
    chunk_contexts: list[dict[str, Any]] = []
    for hit in hits:
        metadata = hit.document.metadata
        if (
            metadata.get("doc_id") is not None
            and metadata.get("chunk_group_id")
            and metadata.get("chunk_index") is not None
        ):
            chunk_contexts.append(
                {
                    "doc_id": metadata["doc_id"],
                    "version_no": metadata.get("source_version", 0),
                    "chunk_group_id": metadata["chunk_group_id"],
                    "chunk_index": metadata["chunk_index"],
                }
            )

    if chunk_contexts:
        adjacent_hits = await _fetch_adjacent_chunks(
            chunk_contexts=chunk_contexts,
            datasource_id=request.datasource_id,
            snapshot_id=request.active_snapshot_id,
            limit=max(1, len(chunk_contexts) * 3),
        )
        # 优先按 source_id 去重；兼容旧索引时再按文本去重。
        existing_ids = {
            hit.document.metadata.get("source_id")
            for hit in expanded_hits
            if hit.document.metadata.get("source_id") is not None
        }
        existing_texts = {hit.document.page_content for hit in expanded_hits}
        for adj_hit in adjacent_hits:
            source_id = adj_hit.document.metadata.get("source_id")
            if (
                (source_id is not None and source_id not in existing_ids)
                or (source_id is None and adj_hit.document.page_content not in existing_texts)
            ):
                expanded_hits.append(adj_hit)
                existing_texts.add(adj_hit.document.page_content)
                if source_id is not None:
                    existing_ids.add(source_id)

    retrieved: list[RetrievedSchema] = []
    for hit in expanded_hits:
        metadata = hit.document.metadata
        related_columns = _as_string_list(metadata.get("related_columns"))
        if not related_columns:
            related_columns = _as_string_list(metadata.get("related_column"))
        # 兼容两种 key：table_name 和 related_table
        table_name = metadata.get("table_name") or metadata.get("related_table", "")
        related_tables = _as_string_list(metadata.get("related_tables"))
        if not related_tables and table_name:
            related_tables = [table_name]
        entity_ids = _as_int_list(metadata.get("entity_ids"))
        retrieved.append(
            RetrievedSchema(
                table_name=table_name,
                columns=[
                    ColumnInfo(name=column, trust_score=metadata.get("trust_score"))
                    for column in related_columns
                ],
                score=hit.score,
                relevance_score=hit.score,
                chunk_type=metadata.get("chunk_type", ""),
                source_version=metadata.get("source_version") or metadata.get("knowledge_version_no", 0),
                snapshot_id=metadata.get("snapshot_id"),
                doc_id=metadata.get("doc_id"),
                source_id=metadata.get("source_id"),
                chunk_index=metadata.get("chunk_index"),
                chunk_group_id=metadata.get("chunk_group_id", ""),
                related_tables=related_tables,
                related_columns=related_columns,
                entity_ids=entity_ids,
                trust_score=metadata.get("trust_score"),
                context_expansion=bool(metadata.get("context_expansion", False)),
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
    chunk_contexts: list[dict[str, Any]],
    datasource_id: int,
    snapshot_id: int,
    limit: int = 20,
) -> list:
    """查询与命中 chunk 同文档的相邻 chunk（上下文扩展）

    通过 doc_id + knowledge_version_no + chunk_group_id + chunk_index
    查找同一语义小节的前后 chunk，为跨段查询提供完整上下文。

    Args:
        chunk_contexts: 命中 chunk 的文档/版本/分组/顺序信息
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
        groups: list[str] = []
        seen = set()
        for context in chunk_contexts:
            key = (
                context.get("doc_id"),
                context.get("version_no"),
                context.get("chunk_group_id"),
                context.get("chunk_index"),
            )
            if key in seen:
                continue
            seen.add(key)
            group_id = str(context["chunk_group_id"]).replace('"', '\\"')
            index = int(context["chunk_index"])
            groups.append(
                "("
                f"doc_id == {int(context['doc_id'])} "
                f"and knowledge_version_no == {int(context.get('version_no') or 0)} "
                f'and chunk_group_id == "{group_id}" '
                f"and chunk_index >= {max(0, index - 1)} "
                f"and chunk_index <= {index + 1}"
                ")"
            )
        if not groups:
            return []
        expr = (
            f"datasource_id == {datasource_id} "
            f"and snapshot_id == {snapshot_id} "
            f'and review_status == "APPROVED" '
            f"and governance_status in [{eligible_statuses}] "
            f"and ({' or '.join(groups)})"
        )

        try:
            results = client.query(
                collection_name=name,
                filter=expr,
                output_fields=[
                    "datasource_id", "snapshot_id", "knowledge_version_no",
                    "doc_id", "source_id", "chunk_index", "chunk_group_id", "chunk_type",
                    "governance_status", "review_status", "chunk_text", "related_table",
                    "related_column", "related_tables", "related_columns", "entity_ids",
                    "trust_score", "content_hash",
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
                        "doc_id": entity.get("doc_id"),
                        "source_id": entity.get("source_id"),
                        "chunk_index": entity.get("chunk_index"),
                        "chunk_group_id": entity.get("chunk_group_id", ""),
                        "related_column": entity.get("related_column", ""),
                        "related_tables": entity.get("related_tables", ""),
                        "related_columns": entity.get("related_columns", ""),
                        "entity_ids": entity.get("entity_ids", ""),
                        "trust_score": entity.get("trust_score"),
                        "content_hash": entity.get("content_hash", ""),
                        "context_expansion": True,
                    },
                )
                hits.append(SearchHit(document=document, score=0.3))
            return hits
        except Exception as e:
            logger.warning("上下文扩展查询失败: %s", e, exc_info=True)
            return []

    import asyncio
    return await asyncio.to_thread(_query)


def _as_string_list(value: Any) -> list[str]:
    """兼容 Milvus 动态字段中的 JSON 字符串、逗号字符串和列表。"""
    if value is None:
        return []
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return []
        try:
            parsed = json.loads(text)
            if isinstance(parsed, list):
                return [str(item).strip() for item in parsed if str(item).strip()]
        except (TypeError, ValueError, json.JSONDecodeError):
            pass
        return [item.strip() for item in text.split(",") if item.strip()]
    return [str(value).strip()] if str(value).strip() else []


def _as_int_list(value: Any) -> list[int]:
    result: list[int] = []
    for item in _as_string_list(value):
        try:
            result.append(int(item))
        except (TypeError, ValueError):
            continue
    return result
