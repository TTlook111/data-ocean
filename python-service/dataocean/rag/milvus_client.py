"""Milvus 向量库连接管理"""

import logging

from pymilvus import Collection, connections, utility

from dataocean.core.config import settings

logger = logging.getLogger(__name__)


def connect_milvus() -> None:
    """建立 Milvus 连接"""
    connections.connect(
        alias="default",
        host=settings.milvus_host,
        port=settings.milvus_port,
    )
    logger.info("Milvus 连接成功 host=%s port=%d", settings.milvus_host, settings.milvus_port)


def get_collection() -> Collection:
    """获取 schema_knowledge Collection"""
    return Collection(settings.milvus_collection_name)


def ping() -> bool:
    """健康检查"""
    try:
        connect_milvus()
        utility.list_collections()
        return True
    except Exception:
        return False


def health_status() -> dict:
    """返回 Milvus 和 Collection 的详细健康状态。"""
    try:
        connect_milvus()
        collection_exists = utility.has_collection(settings.milvus_collection_name)
        total_vectors = 0
        if collection_exists:
            collection = get_collection()
            total_vectors = collection.num_entities
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
