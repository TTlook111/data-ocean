"""Schema RAG 语义检索器

使用 Milvus 向量相似度搜索，强制注入 datasource_id、快照和准入过滤条件。
"""

import logging

from .milvus_client import connect_milvus, get_collection
from .schema import RetrievedSchema, RetrieveRequest

logger = logging.getLogger(__name__)

RAG_ELIGIBLE_STATUSES = ("NORMAL", "RECOMMENDED")


async def retrieve_from_milvus(
    question_embedding: list[float], request: RetrieveRequest
) -> list[RetrievedSchema]:
    """从 Milvus 检索相关 schema

    强制注入过滤条件：
    - datasource_id == 请求中的数据源 ID（必填，缺失则抛异常）
    - review_status == "APPROVED"
    - governance_status in ["NORMAL", "RECOMMENDED"]

    Args:
        question_embedding: 问题的向量表示
        request: 检索请求参数

    Returns:
        检索结果列表（未重排）
    """
    try:
        connect_milvus()
        collection = get_collection()
        collection.load()
    except Exception as e:
        logger.error("Milvus 连接/加载失败: %s", e)
        raise

    # 构建强制过滤表达式（数据源隔离 + 生效快照 + 准入控制）
    eligible_statuses = ", ".join(f'"{status}"' for status in RAG_ELIGIBLE_STATUSES)
    filter_expr = (
        f'datasource_id == {request.datasource_id} '
        f'and snapshot_id == {request.active_snapshot_id} '
        f'and review_status == "APPROVED" '
        f"and governance_status in [{eligible_statuses}]"
    )

    # 执行向量搜索
    results = collection.search(
        data=[question_embedding],
        anns_field="embedding",
        param={"metric_type": "IP", "params": {"nprobe": 16}},
        limit=request.top_k * 2,  # 多召回一些，后续重排再截断
        expr=filter_expr,
        output_fields=[
            "chunk_type",
            "chunk_text",
            "related_table",
            "related_column",
            "snapshot_id",
            "knowledge_version_no",
            "governance_status",
            "review_status",
        ],
    )

    # 转换为 RetrievedSchema
    retrieved = []
    for hits in results:
        for hit in hits:
            entity = hit.entity
            retrieved.append(
                RetrievedSchema(
                    table_name=entity.get("related_table", ""),
                    columns=entity.get("related_column", "").split(",")
                    if entity.get("related_column")
                    else [],
                    score=hit.score,
                    relevance_score=hit.score,
                    chunk_type=entity.get("chunk_type", ""),
                    source_version=entity.get("knowledge_version_no", 0),
                    snapshot_id=entity.get("snapshot_id"),
                    chunk_text=entity.get("chunk_text", ""),
                    governance_status=entity.get("governance_status", ""),
                    review_status=entity.get("review_status", ""),
                )
            )

    logger.info(
        "Milvus 检索完成 datasource_id=%d results=%d",
        request.datasource_id,
        len(retrieved),
    )
    return retrieved
