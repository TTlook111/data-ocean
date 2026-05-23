"""Schema RAG 路由

提供向量化写入和语义检索的 HTTP 接口。
- POST /retrieve — 根据用户查询召回相关表和字段
- POST /vectorize — 触发知识文档向量化写入 Milvus
"""

from fastapi import APIRouter

router = APIRouter()


@router.post("/retrieve")
async def retrieve() -> dict[str, str]:
    """RAG 语义检索（待实现）"""
    return {"status": "not_implemented"}


@router.post("/vectorize")
async def vectorize() -> dict[str, str]:
    """触发向量化写入（待实现）"""
    return {"status": "not_implemented"}
