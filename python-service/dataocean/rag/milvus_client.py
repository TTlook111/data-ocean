"""Milvus 向量库连接管理"""

import logging

from pymilvus import MilvusClient, connections

from dataocean.core.config import settings

logger = logging.getLogger(__name__)

# 全局 MilvusClient 实例
_client: MilvusClient | None = None


def get_client() -> MilvusClient:
    """获取 MilvusClient 实例（单例）"""
    global _client
    if _client is None:
        _client = MilvusClient(host=settings.milvus_host, port=settings.milvus_port)
        logger.info("MilvusClient 连接成功 host=%s port=%d", settings.milvus_host, settings.milvus_port)
    return _client


def connect_milvus() -> None:
    """建立 Milvus 连接（兼容旧代码）

    同时建立 MilvusClient 和旧的 connections 连接，
    因为 LangChain Milvus 0.3.x 仍然使用旧的 Collection API。
    """
    # 建立 MilvusClient 连接
    get_client()
    # 建立旧的 connections 连接（兼容 LangChain Milvus 0.3.x）
    # 强制重新连接，确保连接有效
    connections.connect(
        alias="default",
        host=settings.milvus_host,
        port=settings.milvus_port,
    )
    logger.info("旧 API 连接成功 host=%s port=%d", settings.milvus_host, settings.milvus_port)


def ensure_collection(collection_name: str | None = None, dimension: int | None = None):
    """确保目标 Collection 存在。

    维度变化时 pending 索引会写入新的 collection，因此这里允许调用方指定名称和维度。
    返回 collection 名称（字符串），供 LangChain Milvus 使用。
    """
    client = get_client()
    name = collection_name or settings.milvus_collection_name
    dim = dimension or settings.embedding_dimension

    collections = client.list_collections()
    if name in collections:
        return type('Collection', (), {'name': name})()

    # 创建 collection
    client.create_collection(
        collection_name=name,
        dimension=dim,
        metric_type="IP",
        index_params={
            "index_type": "IVF_FLAT",
            "metric_type": "IP",
            "params": {"nlist": 128},
        },
    )
    logger.info("Milvus Collection 创建成功 name=%s dim=%d", name, dim)
    return type('Collection', (), {'name': name})()


def ping() -> bool:
    """健康检查"""
    try:
        client = get_client()
        client.list_collections()
        return True
    except Exception:
        return False


def health_status() -> dict:
    """返回 Milvus 和 Collection 的详细健康状态。"""
    try:
        client = get_client()
        collections = client.list_collections()
        collection_exists = settings.milvus_collection_name in collections
        total_vectors = 0
        if collection_exists:
            stats = client.get_collection_stats(settings.milvus_collection_name)
            total_vectors = stats.get("row_count", 0)
        return {
            "status": "healthy",
            "milvusConnected": True,
            "collectionExists": collection_exists,
            "totalVectors": total_vectors,
        }
    except Exception as exc:
        logger.warning("Milvus 健康检查失败: %s", exc)
        return {
            "status": "unhealthy",
            "milvusConnected": False,
            "collectionExists": False,
            "totalVectors": 0,
            "error": str(exc),
        }
