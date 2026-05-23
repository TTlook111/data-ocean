"""Schema RAG 路由

提供向量化写入、语义检索、健康检查和数据清理的 HTTP 接口。
"""

import logging

from fastapi import APIRouter

from .milvus_client import connect_milvus, get_collection, ping
from .schema import RetrieveRequest, RetrieveResponse, VectorizeRequest, VectorizeResponse
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
    return await vectorize_chunks(
        datasource_id=request.datasource_id,
        snapshot_id=request.snapshot_id,
        version_no=request.version_no,
        chunks=request.chunks,
        force=request.force,
    )


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
async def delete_vectors(datasource_id: int) -> dict[str, str]:
    """按数据源删除所有向量"""
    try:
        connect_milvus()
        collection = get_collection()
        collection.delete(expr=f"datasource_id == {datasource_id}")
        collection.flush()
        logger.info("已删除数据源向量 datasource_id=%d", datasource_id)
        return {"status": "ok"}
    except Exception as e:
        logger.error("删除向量失败: %s", e)
        return {"status": "error", "message": str(e)}


@router.get("/health")
async def health() -> dict:
    """RAG 健康检查（含 Milvus 连接状态）"""
    milvus_ok = ping()
    return {
        "status": "ok" if milvus_ok else "degraded",
        "milvus": "connected" if milvus_ok else "disconnected",
    }
