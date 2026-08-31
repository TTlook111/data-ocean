"""Milvus 向量库连接管理"""

import json
import logging
import threading
from dataclasses import dataclass

from pymilvus import MilvusClient, connections

from dataocean.core.config import settings

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class CollectionInfo:
    """Collection 信息（替代匿名类型）"""
    name: str

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
        _validate_existing_collection(client, name, dim)
        return CollectionInfo(name=name)

    # 根据数据规模动态选择索引参数
    index_params = _select_index_params(chunk_count_hint)

    # 创建 collection
    client.create_collection(
        collection_name=name,
        dimension=dim,
        primary_field_name="id",
        vector_field_name="embedding",
        auto_id=True,
        metric_type="IP",
        index_params=index_params,
    )
    logger.info("Milvus Collection 创建成功 name=%s dim=%d index=%s", name, dim, index_params["index_type"])
    return CollectionInfo(name=name)


def _validate_existing_collection(client: MilvusClient, name: str, expected_dimension: int) -> None:
    """校验已有 collection 的向量字段和维度，禁止静默写入不兼容索引。"""
    try:
        description = client.describe_collection(collection_name=name)
    except Exception as exc:
        raise RuntimeError(f"无法读取 Milvus Collection 结构 name={name}: {exc}") from exc

    fields = description.get("fields", []) if isinstance(description, dict) else []
    vector_fields = [
        field for field in fields
        if isinstance(field, dict)
        and (field.get("name") == "embedding" or "VECTOR" in str(field.get("type", "")).upper())
    ]
    embedding_field = next(
        (field for field in vector_fields if field.get("name") == "embedding"),
        vector_fields[0] if vector_fields else None,
    )
    if embedding_field is None:
        raise RuntimeError(
            f"Milvus Collection {name} 缺少 embedding 向量字段，请重建 Collection 后再执行 RAG 向量化"
        )
    if embedding_field.get("name") != "embedding":
        raise RuntimeError(
            f"Milvus Collection {name} 使用向量字段 {embedding_field.get('name')}，"
            "当前 RAG 协议要求 embedding，请重建 Collection"
        )

    params = _as_mapping(embedding_field.get("params"))
    actual_dimension = params.get("dim") or embedding_field.get("dimension")
    if actual_dimension is not None and int(actual_dimension) != expected_dimension:
        raise RuntimeError(
            f"Milvus Collection {name} 维度不匹配 expected={expected_dimension} actual={actual_dimension}"
        )

    try:
        index_names = client.list_indexes(collection_name=name, field_name="embedding")
    except Exception as exc:
        raise RuntimeError(
            f"无法读取 Milvus Collection {name} 的 embedding 索引，请重建 Collection"
        ) from exc
    if not index_names:
        raise RuntimeError(
            f"Milvus Collection {name} 缺少 embedding 索引，请重建 Collection 后再执行 RAG 向量化"
        )

    metrics: set[str] = set()
    for index_name in index_names:
        try:
            index_info = client.describe_index(
                collection_name=name,
                index_name=index_name,
            )
        except Exception as exc:
            raise RuntimeError(
                f"无法读取 Milvus Collection {name} 的 embedding 索引参数，请重建 Collection"
            ) from exc
        metric_type = _extract_metric_type(index_info)
        if not metric_type:
            raise RuntimeError(
                f"Milvus Collection {name} 的 embedding 索引缺少 metric_type，请重建 Collection"
            )
        metrics.add(metric_type.upper())

    if metrics != {"IP"}:
        raise RuntimeError(
            f"Milvus Collection {name} 的 embedding 索引度量不兼容 expected=IP actual={sorted(metrics)}"
        )


def _as_mapping(value: object) -> dict:
    """兼容 pymilvus 返回的 dict 或 JSON 文本参数。"""
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
            return parsed if isinstance(parsed, dict) else {}
        except json.JSONDecodeError:
            return {}
    return {}


def _extract_metric_type(index_info: object) -> str | None:
    """从不同 pymilvus 版本的索引描述中读取 metric_type。"""
    info = _as_mapping(index_info)
    metric = info.get("metric_type") or info.get("metricType")
    if metric:
        return str(metric)
    params = _as_mapping(info.get("params"))
    metric = params.get("metric_type") or params.get("metricType")
    return str(metric) if metric else None


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
