"""Schema RAG 路由

提供向量化写入、语义检索、健康检查和数据清理的 HTTP 接口。
"""

import asyncio
import logging
from time import perf_counter

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field
import httpx

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


class ProviderTestRequest(BaseModel):
    base_url: str = Field(alias="baseUrl")
    api_key: str = Field(default="", alias="apiKey")


class DimensionDetectRequest(BaseModel):
    base_url: str | None = Field(default=None, alias="baseUrl")
    api_key: str | None = Field(default=None, alias="apiKey")
    model: str


class ReVectorizeRequest(BaseModel):
    is_pending: bool = Field(default=False, alias="isPending")
    index_version: str | None = Field(default=None, alias="indexVersion")
    target_collection: str | None = Field(default=None, alias="targetCollection")
    target_dimension: int | None = Field(default=None, alias="targetDimension")


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
        doc_id=request.doc_id,
        previous_version_no=request.previous_version_no,
        force=request.force,
        target_collection=request.target_collection,
        target_dimension=request.target_dimension,
        embedding_config=request.embedding_config,
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
        if request.doc_id is not None:
            expr_parts.append(f"doc_id == {request.doc_id}")
        if request.version_no is not None:
            expr_parts.append(f"knowledge_version_no == {request.version_no}")
        if not expr_parts:
            raise ValueError("至少需要提供 datasource_id、snapshot_id、doc_id 或 knowledge_version_no")
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


@router.post("/re-vectorize")
async def re_vectorize(request: ReVectorizeRequest) -> dict:
    """全量重新向量化入口占位。

    当前按文档先完成 pending 向量化协议；实际全量任务编排由 Java 侧读取 chunks 后
    调用 /vectorize 分批完成。这里提供统一入口，便于前端触发和后续扩展进度管理。
    """
    return {
        "status": "ACCEPTED",
        "isPending": request.is_pending,
        "indexVersion": request.index_version,
        "targetCollection": request.target_collection,
        "targetDimension": request.target_dimension,
    }


async def test_provider(request: ProviderTestRequest) -> dict:
    """测试 OpenAI 兼容供应商并同步模型列表。"""
    base_url = request.base_url.rstrip("/")
    headers = {"Authorization": f"Bearer {request.api_key}"} if request.api_key else {}
    async with httpx.AsyncClient(timeout=10.0) as client:
        response = await client.get(f"{base_url}/models", headers=headers)
        response.raise_for_status()
        payload = response.json()

    chat_models = []
    embedding_models = []
    for item in payload.get("data", []):
        model_id = item.get("id") or item.get("name")
        if not model_id:
            continue
        model = {
            "name": model_id,
            "displayName": model_id,
            "dimension": item.get("dimensions") or item.get("embedding_dimensions"),
            "maxContext": item.get("context_length") or item.get("max_context"),
        }
        if "embedding" in model_id.lower():
            model["type"] = "embedding"
            embedding_models.append(model)
        else:
            model["type"] = "chat"
            chat_models.append(model)
    return {"chatModels": chat_models, "embeddingModels": embedding_models}


async def detect_dimension(request: DimensionDetectRequest) -> dict:
    """通过一次 embedding 调用检测向量维度。"""
    from dataocean.infra.embeddings import embed_texts_with_config
    from .schema import EmbeddingConfig

    config = EmbeddingConfig(
        model=request.model,
        base_url=request.base_url,
        api_key=request.api_key,
    )
    vectors = await embed_texts_with_config(["DataOcean dimension detection test"], config)
    dimension = len(vectors[0]) if vectors else None
    return {"dimension": dimension, "model": request.model}


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
