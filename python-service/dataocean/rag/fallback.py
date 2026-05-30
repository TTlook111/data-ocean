"""RAG 降级方案

当 Milvus 不可用时，从 Java 传入的 fallback_chunks 中读取兜底数据。
同时提供 Milvus 可用性检查和降级提示信息（原 resilience.milvus_fallback 已并入此处）。
"""

import logging

from .milvus_client import ping as milvus_ping
from .schema import RetrievedSchema, RetrieveResponse

logger = logging.getLogger(__name__)


def is_milvus_available() -> bool:
    """检查 Milvus 是否可用（每次查询时调用，连接成功则自动恢复正常 RAG）"""
    return milvus_ping()


def get_degradation_notice() -> str:
    """获取降级提示信息"""
    return "知识库暂时不可用，已使用降级方案，召回精度可能降低"


def _first_non_none(d: dict, *keys) -> any:
    """从字典中按优先级取第一个非 None 的值（允许 0、空字符串等 falsy 值）。"""
    for key in keys:
        val = d.get(key)
        if val is not None:
            return val
    return None


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
                table_name = _first_non_none(
                    chunk, "related_table", "relatedTable", "table_name", "tableName"
                ) or ""
                results.append(
                    RetrievedSchema(
                        table_name=table_name,
                        columns=[],
                        score=0.5,
                        relevance_score=0.5,
                        chunk_type=chunk_type,
                        source_version=_first_non_none(
                            chunk, "knowledge_version_no", "knowledgeVersionNo"
                        ) or 0,
                        snapshot_id=_first_non_none(
                            chunk, "snapshot_id", "snapshotId", "metadata_snapshot_id", "metadataSnapshotId"
                        ),
                        chunk_text=_first_non_none(
                            chunk, "chunk_text", "chunkText"
                        ) or "",
                        governance_status=_first_non_none(
                            chunk, "governance_status", "governanceStatus"
                        ) or "",
                        review_status=_first_non_none(
                            chunk, "review_status", "reviewStatus"
                        ) or "",
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
