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
            if chunk.get("chunk_type") == "TABLE_DESC":
                results.append(
                    RetrievedSchema(
                        table_name=chunk.get("related_table", ""),
                        columns=[],
                        score=0.5,
                        chunk_type="TABLE_DESC",
                        source_version=0,
                        chunk_text=chunk.get("chunk_text", ""),
                    )
                )

    return RetrieveResponse(
        results=results,
        degraded=True,
        message="Milvus 不可用，使用降级方案返回核心表信息",
    )
