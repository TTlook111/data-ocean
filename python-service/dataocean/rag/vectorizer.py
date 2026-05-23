"""向量化写入逻辑

将 chunks 批量 embedding 后写入 Milvus。
"""

import logging

from pymilvus import Collection

from dataocean.core.config import settings
from .embedder import embed_texts
from .milvus_client import connect_milvus, get_collection
from .schema import ChunkItem, VectorizeResponse

logger = logging.getLogger(__name__)


async def vectorize_chunks(
    datasource_id: int,
    snapshot_id: int,
    version_no: int,
    chunks: list[ChunkItem],
    force: bool = False,
) -> VectorizeResponse:
    """将 chunks 向量化写入 Milvus

    Args:
        datasource_id: 数据源 ID
        snapshot_id: 快照 ID
        version_no: 版本号
        chunks: 切片列表
        force: 是否强制全量重建
    """
    if not chunks:
        return VectorizeResponse()

    try:
        connect_milvus()
        collection = get_collection()
    except Exception as e:
        logger.error("Milvus 连接失败: %s", e)
        return VectorizeResponse(failed_count=len(chunks), errors=[f"Milvus 连接失败: {e}"])

    # 强制模式：先删除该数据源的旧向量
    if force:
        _delete_by_datasource(collection, datasource_id)

    # 批量生成 embedding
    texts = [chunk.chunk_text for chunk in chunks]
    try:
        embeddings = await embed_texts(texts)
    except Exception as e:
        logger.error("Embedding 生成失败: %s", e)
        return VectorizeResponse(failed_count=len(chunks), errors=[f"Embedding 失败: {e}"])

    # 组装写入数据
    entities = [
        [datasource_id] * len(chunks),          # datasource_id
        [snapshot_id] * len(chunks),            # snapshot_id
        [version_no] * len(chunks),             # knowledge_version_no
        [c.chunk_type for c in chunks],         # chunk_type
        [c.governance_status for c in chunks],  # governance_status
        [c.review_status for c in chunks],      # review_status
        [c.chunk_text[:8192] for c in chunks],  # chunk_text
        [c.related_table for c in chunks],      # related_table
        [c.related_column for c in chunks],     # related_column
        embeddings,                              # embedding
    ]

    try:
        collection.insert(entities)
        collection.flush()
        logger.info("向量写入成功 datasource_id=%d count=%d", datasource_id, len(chunks))
        return VectorizeResponse(success_count=len(chunks))
    except Exception as e:
        logger.error("Milvus 写入失败: %s", e)
        return VectorizeResponse(failed_count=len(chunks), errors=[f"写入失败: {e}"])


def switch_version(
    collection: Collection,
    datasource_id: int,
    new_snapshot_id: int,
    old_snapshot_id: int,
) -> None:
    """版本切换：验证新版本写入完成后删除旧版本向量"""
    new_count = collection.query(
        expr=f"datasource_id == {datasource_id} and snapshot_id == {new_snapshot_id}",
        output_fields=["count(*)"],
    )
    if new_count:
        collection.delete(
            expr=f"datasource_id == {datasource_id} and snapshot_id == {old_snapshot_id}"
        )
        collection.flush()
        logger.info(
            "版本切换完成 datasource_id=%d old=%d new=%d",
            datasource_id,
            old_snapshot_id,
            new_snapshot_id,
        )


def cleanup_old_versions(
    collection: Collection, datasource_id: int, current_snapshot_id: int
) -> None:
    """清理旧版本向量（保留当前生效版本）"""
    collection.delete(
        expr=f"datasource_id == {datasource_id} and snapshot_id != {current_snapshot_id}"
    )
    collection.flush()
    logger.info(
        "旧版本清理完成 datasource_id=%d keep_snapshot=%d",
        datasource_id,
        current_snapshot_id,
    )


def _delete_by_datasource(collection: Collection, datasource_id: int) -> None:
    """按数据源 ID 删除所有向量"""
    collection.delete(expr=f"datasource_id == {datasource_id}")
    collection.flush()
    logger.info("已删除数据源向量 datasource_id=%d", datasource_id)
