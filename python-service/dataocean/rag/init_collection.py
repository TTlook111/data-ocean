"""Milvus Collection 初始化脚本

创建 schema_knowledge Collection，定义字段和索引。
运行方式：python -m dataocean.rag.init_collection
"""

from pymilvus import (
    Collection,
    CollectionSchema,
    DataType,
    FieldSchema,
    connections,
    utility,
)

from dataocean.core.config import settings


def create_collection() -> None:
    """创建 schema_knowledge Collection"""
    connections.connect(host=settings.milvus_host, port=settings.milvus_port)

    collection_name = settings.milvus_collection_name
    if utility.has_collection(collection_name):
        print(f"Collection '{collection_name}' 已存在，跳过创建")
        return

    fields = [
        FieldSchema(name="id", dtype=DataType.INT64, is_primary=True, auto_id=True),
        FieldSchema(name="datasource_id", dtype=DataType.INT64),
        FieldSchema(name="snapshot_id", dtype=DataType.INT64),
        FieldSchema(name="knowledge_version_no", dtype=DataType.INT32),
        FieldSchema(name="chunk_type", dtype=DataType.VARCHAR, max_length=50),
        FieldSchema(name="governance_status", dtype=DataType.VARCHAR, max_length=30),
        FieldSchema(name="review_status", dtype=DataType.VARCHAR, max_length=30),
        FieldSchema(name="chunk_text", dtype=DataType.VARCHAR, max_length=8192),
        FieldSchema(name="related_table", dtype=DataType.VARCHAR, max_length=200),
        FieldSchema(name="related_column", dtype=DataType.VARCHAR, max_length=200),
        FieldSchema(
            name="embedding",
            dtype=DataType.FLOAT_VECTOR,
            dim=settings.embedding_dimension,
        ),
    ]

    schema = CollectionSchema(fields=fields, description="Schema 知识向量库")
    collection = Collection(name=collection_name, schema=schema)

    # 创建 IVF_FLAT 索引
    collection.create_index(
        field_name="embedding",
        index_params={
            "index_type": "IVF_FLAT",
            "metric_type": "IP",
            "params": {"nlist": 128},
        },
    )
    print(f"Collection '{collection_name}' 创建成功，索引已建立")


if __name__ == "__main__":
    create_collection()
