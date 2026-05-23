"""RAG 降级方案

当 Milvus 不可用时，从 Java 传入的 fallback_chunks 中读取兜底数据。
"""

import logging

from .schema import RetrievedSchema, RetrieveResponse

logger = logging.getLogger(__name__)


def fallback_retrieve(
    datasource_id: int, fallback_chunks: list[dict] | None = None
) -> RetrieveResponse:
    """降级检索：从预置数据中返回兜底结果

    Args:
        datasource_id: 数据源 ID
        fallback_chunks: Java 传入的 knowledge_chunk 数据（可选）

    Returns:
        标记为 degraded 的检索响应
    """
    logger.warning("RAG 降级触发 datasource_id=%d", datasource_id)

    results = []
    if fallback_chunks:
        for chunk in fallback_chunks[:5]:  # 最多取 5 条
            chunk_type = chunk.get("chunk_type") or chunk.get("chunkType")
            if chunk_type in {"TABLE_DESC", "CORE_TABLE", "SCHEMA"}:
                table_name = (
                    chunk.get("related_table")
                    or chunk.get("relatedTable")
                    or chunk.get("table_name")
                    or chunk.get("tableName")
                    or ""
                )
                results.append(
                    RetrievedSchema(
                        table_name=table_name,
                        columns=[],
                        score=0.5,
                        relevance_score=0.5,
                        chunk_type=chunk_type,
                        source_version=chunk.get("knowledge_version_no")
                        or chunk.get("knowledgeVersionNo")
                        or 0,
                        snapshot_id=chunk.get("snapshot_id")
                        or chunk.get("snapshotId")
                        or chunk.get("metadata_snapshot_id")
                        or chunk.get("metadataSnapshotId"),
                        chunk_text=chunk.get("chunk_text") or chunk.get("chunkText") or "",
                        governance_status=chunk.get("governance_status")
                        or chunk.get("governanceStatus")
                        or "",
                        review_status=chunk.get("review_status") or chunk.get("reviewStatus") or "",
                    )
                )

    return RetrieveResponse(
        results=results,
        total_found=len(results),
        returned=len(results),
        degraded=True,
        degrade_reason="Milvus 不可用，使用降级方案返回核心表信息",
        message="Milvus 不可用，使用降级方案返回核心表信息",
    )
