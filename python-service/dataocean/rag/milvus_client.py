"""Milvus 向量库连接管理"""

import logging

from pymilvus import Collection, CollectionSchema, DataType, FieldSchema, connections, utility

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


def get_collection(collection_name: str | None = None) -> Collection:
    """获取 Schema 知识 Collection"""
    return Collection(collection_name or settings.milvus_collection_name)


def ensure_collection(collection_name: str | None = None, dimension: int | None = None) -> Collection:
    """确保目标 Collection 存在。

    维度变化时 pending 索引会写入新的 collection，因此这里允许调用方指定名称和维度。
    """
    connect_milvus()
    name = collection_name or settings.milvus_collection_name
    dim = dimension or settings.embedding_dimension
    if utility.has_collection(name):
        return Collection(name)

    fields = [
        FieldSchema(name="id", dtype=DataType.INT64, is_primary=True, auto_id=True),
        FieldSchema(name="datasource_id", dtype=DataType.INT64),
        FieldSchema(name="snapshot_id", dtype=DataType.INT64),
        FieldSchema(name="knowledge_version_no", dtype=DataType.INT32),
        FieldSchema(name="doc_id", dtype=DataType.INT64),
        FieldSchema(name="source_id", dtype=DataType.INT64),
        FieldSchema(name="chunk_type", dtype=DataType.VARCHAR, max_length=50),
        FieldSchema(name="governance_status", dtype=DataType.VARCHAR, max_length=30),
        FieldSchema(name="review_status", dtype=DataType.VARCHAR, max_length=30),
        FieldSchema(name="chunk_text", dtype=DataType.VARCHAR, max_length=8192),
        FieldSchema(name="related_table", dtype=DataType.VARCHAR, max_length=200),
        FieldSchema(name="related_column", dtype=DataType.VARCHAR, max_length=200),
        FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=dim),
    ]
    schema = CollectionSchema(fields=fields, description="Schema 知识向量库")
    collection = Collection(name=name, schema=schema)
    collection.create_index(
        field_name="embedding",
        index_params={
            "index_type": "IVF_FLAT",
            "metric_type": "IP",
            "params": {"nlist": 128},
        },
    )
    logger.info("Milvus Collection 创建成功 name=%s dim=%d", name, dim)
    return collection


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
