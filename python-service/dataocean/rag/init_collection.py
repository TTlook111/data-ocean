"""Milvus Collection 初始化脚本

创建 schema_knowledge Collection，定义字段和索引。
运行方式：python -m dataocean.rag.init_collection

注意：此脚本使用 milvus_client.py 中的统一接口。
"""

from dataocean.core.config import settings
from dataocean.rag.milvus_client import ensure_collection


def create_collection() -> None:
    """创建 schema_knowledge Collection"""
    collection_name = settings.milvus_collection_name
    print(f"正在创建 Collection '{collection_name}'...")
    ensure_collection(collection_name, settings.embedding_dimension)
    print(f"Collection '{collection_name}' 创建成功")


if __name__ == "__main__":
    create_collection()
