"""SQL 安全沙箱路由

提供 SQL 安全校验和沙箱执行的 HTTP 接口。
- POST /validate — SQL AST 安全校验（仅允许 SELECT，禁止危险函数）
- POST /execute — SQL 沙箱执行（只读连接，强制 LIMIT，超时 30s）
"""

from fastapi import APIRouter

router = APIRouter()


@router.post("/validate")
async def validate_sql() -> dict[str, str]:
    """SQL 安全校验（待实现）"""
    return {"status": "not_implemented"}


@router.post("/execute")
async def execute_sql() -> dict[str, str]:
    """SQL 沙箱执行（待实现）"""
    return {"status": "not_implemented"}
