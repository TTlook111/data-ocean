"""Schema RAG 路由

提供向量化写入、语义检索、健康检查和数据清理的 HTTP 接口。
"""

import asyncio
import logging
from time import perf_counter

from fastapi import APIRouter, HTTPException

from .milvus_client import connect_milvus, get_collection, health_status
from .schema import (
    DeleteVectorsRequest,
    DeleteVectorsResponse,
    RetrieveRequest,
    RetrieveResponse,
    VectorizeRequest,
    VectorizeResponse,
)
from .service import retrieve_schemas
from .vectorizer import vectorize_chunks

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post("/vectorize", response_model=VectorizeResponse)
async def vectorize(request: VectorizeRequest) -> VectorizeResponse:
    """向量化写入接口"""
    logger.info(
        "收到向量化请求 datasource_id=%d chunks=%d force=%s",
        request.datasource_id,
        len(request.chunks),
        request.force,
    )
    response = await vectorize_chunks(
        datasource_id=request.datasource_id,
        snapshot_id=request.snapshot_id,
        version_no=request.version_no,
        chunks=request.chunks,
        force=request.force,
    )
    response.task_id = request.task_id
    return response


@router.post("/retrieve", response_model=RetrieveResponse)
async def retrieve(request: RetrieveRequest) -> RetrieveResponse:
    """语义检索接口"""
    logger.info(
        "收到检索请求 datasource_id=%d question=%s",
        request.datasource_id,
        request.question[:50],
    )
    return await retrieve_schemas(request)


@router.delete("/vectors/{datasource_id}")
async def delete_vectors_by_datasource(datasource_id: int) -> DeleteVectorsResponse:
    """按数据源删除所有向量"""
    return await _delete_vectors(DeleteVectorsRequest(datasource_id=datasource_id))


@router.delete("/vectors", response_model=DeleteVectorsResponse)
async def delete_vectors(request: DeleteVectorsRequest) -> DeleteVectorsResponse:
    """按数据源和/或快照批量删除向量"""
    return await _delete_vectors(request)


async def _delete_vectors(request: DeleteVectorsRequest) -> DeleteVectorsResponse:
    start = perf_counter()
    try:
        expr_parts = []
        if request.datasource_id is not None:
            expr_parts.append(f"datasource_id == {request.datasource_id}")
        if request.snapshot_id is not None:
            expr_parts.append(f"snapshot_id == {request.snapshot_id}")
        if not expr_parts:
            raise ValueError("至少需要提供 datasource_id 或 snapshot_id")
        expr = " and ".join(expr_parts)

        def _do_delete() -> int:
            connect_milvus()
            collection = get_collection()
            before = _count_entities(collection, expr)
            collection.delete(expr=expr)
            collection.flush()
            return before

        before = await asyncio.to_thread(_do_delete)
        logger.info("已删除向量 expr=%s count=%d", expr, before)
        return DeleteVectorsResponse(deleted_count=before, duration_ms=_elapsed_ms(start))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    except Exception as e:
        logger.error("删除向量失败: %s", e)
        raise HTTPException(status_code=500, detail=str(e)) from e


@router.get("/health")
async def health() -> dict:
    """RAG 健康检查（含 Milvus 连接状态）"""
    return await asyncio.to_thread(health_status)


def _count_entities(collection, expr: str) -> int:
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
