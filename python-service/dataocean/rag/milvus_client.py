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
        utility.list_collections()
        return True
    except Exception:
        return False
