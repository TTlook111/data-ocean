"""Milvus 向量库连接管理"""

import logging
import threading

from pymilvus import MilvusClient, connections

from dataocean.core.config import settings

logger = logging.getLogger(__name__)

# 全局 MilvusClient 实例
_client: MilvusClient | None = None
_client_lock = threading.Lock()


def get_client() -> MilvusClient:
    """获取 MilvusClient 实例（单例，线程安全）"""
    global _client
    if _client is None:
        with _client_lock:
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


def _select_index_params(chunk_count_hint: int | None = None) -> dict:
    """根据预期 chunk 数量动态选择索引参数

    小数据集（chunk < 256）使用 FLAT 暴力搜索，避免 IVF_FLAT 的聚类退化；
    大数据集使用 IVF_FLAT，nlist 根据数据量动态计算。

    Args:
        chunk_count_hint: 预期 chunk 数量提示（可选）
    """
    # 无提示或小数据集：FLAT 暴力搜索，保证召回率
    if chunk_count_hint is None or chunk_count_hint < 256:
        return {
            "index_type": "FLAT",
            "metric_type": "IP",
        }

    # 大数据集：IVF_FLAT，nlist 按数据量动态调整
    # 经验值：nlist = sqrt(chunk_count) 的最近 2 的幂，最少 16
    import math
    nlist = max(16, min(2048, 2 ** int(math.log2(math.sqrt(chunk_count_hint)))))
    return {
        "index_type": "IVF_FLAT",
        "metric_type": "IP",
        "params": {"nlist": nlist},
    }


def ensure_collection(collection_name: str | None = None, dimension: int | None = None, chunk_count_hint: int | None = None):
    """确保目标 Collection 存在。

    维度变化时 pending 索引会写入新的 collection，因此这里允许调用方指定名称和维度。
    返回 collection 名称（字符串），供 LangChain Milvus 使用。

    Args:
        collection_name: collection 名称
        dimension: 向量维度
        chunk_count_hint: 预期 chunk 数量，用于动态选择索引类型
    """
    client = get_client()
    name = collection_name or settings.milvus_collection_name
    dim = dimension or settings.embedding_dimension

    collections = client.list_collections()
    if name in collections:
        return type('Collection', (), {'name': name})()

    # 根据数据规模动态选择索引参数
    index_params = _select_index_params(chunk_count_hint)

    # 创建 collection
    client.create_collection(
        collection_name=name,
        dimension=dim,
        metric_type="IP",
        index_params=index_params,
    )
    logger.info("Milvus Collection 创建成功 name=%s dim=%d index=%s", name, dim, index_params["index_type"])
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
