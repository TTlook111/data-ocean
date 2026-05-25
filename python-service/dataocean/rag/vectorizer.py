"""向量化写入逻辑

将 chunks 批量 embedding 后写入 Milvus。
"""

import asyncio
import logging
from time import perf_counter

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
    doc_id: int | None = None,
    previous_version_no: int | None = None,
    force: bool = False,
) -> VectorizeResponse:
    """将 chunks 向量化写入 Milvus

    Args:
        datasource_id: 数据源 ID
        snapshot_id: 快照 ID
        version_no: 版本号
        chunks: 切片列表
        doc_id: 知识文档 ID
        previous_version_no: 新版本写入成功后待清理的上一版
        force: 是否强制全量重建
    """
    start = perf_counter()
    if not chunks:
        return VectorizeResponse(duration_ms=_elapsed_ms(start))

    # doc_id 为 None 时写入 Milvus 会导致后续按 doc_id==0 误匹配，强制要求传入
    if doc_id is None:
        logger.warning("vectorize_chunks 未传入 doc_id，将使用 0 作为占位值")

    try:
        def _connect():
            connect_milvus()
            return get_collection()

        collection = await asyncio.to_thread(_connect)
    except Exception as e:
        logger.error("Milvus 连接失败: %s", e)
        return VectorizeResponse(
            status="FAILED",
            failed_count=len(chunks),
            errors=[f"Milvus 连接失败: {e}"],
            duration_ms=_elapsed_ms(start),
        )

    # 强制模式：同一文档重建时先删该文档，缺少 doc_id 时才退化为数据源级重建。
    if force:
        if doc_id is not None:
            await asyncio.to_thread(_delete_by_doc, collection, datasource_id, doc_id)
        else:
            await asyncio.to_thread(_delete_by_datasource, collection, datasource_id)

    # 批量生成 embedding
    texts = [chunk.chunk_text for chunk in chunks]
    try:
        embeddings = await embed_texts(texts)
    except Exception as e:
        logger.error("Embedding 生成失败: %s", e)
        return VectorizeResponse(
            status="FAILED",
            failed_count=len(chunks),
            errors=[f"Embedding 失败: {e}"],
            duration_ms=_elapsed_ms(start),
        )

    if len(embeddings) != len(chunks):
        msg = f"Embedding 数量不匹配: chunks={len(chunks)} embeddings={len(embeddings)}"
        logger.error(msg)
        return VectorizeResponse(
            status="FAILED",
            failed_count=len(chunks),
            errors=[msg],
            duration_ms=_elapsed_ms(start),
        )

    # 组装写入数据
    entities = [
        [datasource_id] * len(chunks),          # datasource_id
        [snapshot_id] * len(chunks),            # snapshot_id
        [version_no] * len(chunks),             # knowledge_version_no
        [doc_id or 0] * len(chunks),            # doc_id
        [c.source_id or 0 for c in chunks],     # source_id
        [c.chunk_type for c in chunks],         # chunk_type
        [c.governance_status for c in chunks],  # governance_status
        [c.review_status for c in chunks],      # review_status
        [c.chunk_text[:8192] for c in chunks],  # chunk_text
        [c.related_table for c in chunks],      # related_table
        [c.related_column for c in chunks],     # related_column
        embeddings,                              # embedding
    ]

    try:
        def _do_insert():
            collection.insert(entities)
            collection.flush()
            try:
                if doc_id is not None and previous_version_no is not None and previous_version_no != version_no:
                    _switch_doc_version(
                        collection,
                        datasource_id,
                        doc_id,
                        version_no,
                        previous_version_no,
                        len(chunks),
                    )
            except Exception:
                _delete_doc_version(collection, datasource_id, doc_id, version_no)
                raise

        await asyncio.to_thread(_do_insert)
        logger.info("向量写入成功 datasource_id=%d count=%d", datasource_id, len(chunks))
        return VectorizeResponse(
            status="COMPLETED",
            success_count=len(chunks),
            duration_ms=_elapsed_ms(start),
        )
    except Exception as e:
        logger.error("Milvus 写入失败: %s", e)
        return VectorizeResponse(
            status="FAILED",
            failed_count=len(chunks),
            errors=[f"写入失败: {e}"],
            duration_ms=_elapsed_ms(start),
        )


def switch_version(
    collection: Collection,
    datasource_id: int,
    new_snapshot_id: int,
    old_snapshot_id: int,
    expected_count: int,
) -> None:
    """版本切换：验证新版本写入完成后删除旧版本向量"""
    actual_count = _count_vectors(
        collection,
        expr=f"datasource_id == {datasource_id} and snapshot_id == {new_snapshot_id}",
    )
    if actual_count != expected_count:
        raise ValueError(
            f"新版本向量数量不一致 expected={expected_count} actual={actual_count}"
        )

    collection.delete(
        expr=f"datasource_id == {datasource_id} and snapshot_id == {old_snapshot_id}"
    )
    collection.flush()
    logger.info(
        "版本切换完成 datasource_id=%d old=%d new=%d count=%d",
        datasource_id,
        old_snapshot_id,
        new_snapshot_id,
        actual_count,
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


def _switch_doc_version(
    collection: Collection,
    datasource_id: int,
    doc_id: int,
    new_version_no: int,
    old_version_no: int,
    expected_count: int,
) -> None:
    """验证文档新版本向量数量后删除旧版本向量。"""
    actual_count = _count_vectors(
        collection,
        expr=(
            f"datasource_id == {datasource_id} "
            f"and doc_id == {doc_id} "
            f"and knowledge_version_no == {new_version_no}"
        ),
    )
    if actual_count != expected_count:
        raise ValueError(
            f"新版本向量数量不一致 expected={expected_count} actual={actual_count}"
        )

    collection.delete(
        expr=(
            f"datasource_id == {datasource_id} "
            f"and doc_id == {doc_id} "
            f"and knowledge_version_no == {old_version_no}"
        )
    )
    collection.flush()
    logger.info(
        "文档版本切换完成 datasource_id=%d doc_id=%d old=%d new=%d count=%d",
        datasource_id,
        doc_id,
        old_version_no,
        new_version_no,
        actual_count,
    )


def _delete_by_doc(collection: Collection, datasource_id: int, doc_id: int) -> None:
    """按数据源和文档 ID 删除所有向量"""
    collection.delete(expr=f"datasource_id == {datasource_id} and doc_id == {doc_id}")
    collection.flush()
    logger.info("已删除文档向量 datasource_id=%d doc_id=%d", datasource_id, doc_id)


def _delete_doc_version(
    collection: Collection, datasource_id: int, doc_id: int | None, version_no: int
) -> None:
    """删除本次写入失败的文档版本向量。"""
    if doc_id is None:
        return
    collection.delete(
        expr=(
            f"datasource_id == {datasource_id} "
            f"and doc_id == {doc_id} "
            f"and knowledge_version_no == {version_no}"
        )
    )
    collection.flush()
    logger.warning(
        "已清理失败的新版本向量 datasource_id=%d doc_id=%d version_no=%d",
        datasource_id,
        doc_id,
        version_no,
    )


def _delete_by_datasource(collection: Collection, datasource_id: int) -> None:
    """按数据源 ID 删除所有向量"""
    collection.delete(expr=f"datasource_id == {datasource_id}")
    collection.flush()
    logger.info("已删除数据源向量 datasource_id=%d", datasource_id)


def _count_vectors(collection: Collection, expr: str) -> int:
    """查询 Milvus 聚合数量，兼容不同 pymilvus 返回键名。"""
    rows = collection.query(expr=expr, output_fields=["count(*)"])
    if not rows:
        return 0
    row = rows[0]
    for key in ("count(*)", "count"):
        if key in row:
            return int(row[key])
    return 0


def _elapsed_ms(start: float) -> int:
    return int((perf_counter() - start) * 1000)
