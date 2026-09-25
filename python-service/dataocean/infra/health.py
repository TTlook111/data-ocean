"""健康检查端点

只提供公共健康检查接口。

内部健康详情端点 `/internal/health` 已移除（2026-09-25）：
- 它没有任何调用方（Java 网关的 PythonHealthChecker 调用的是公开的 `/health`）；
- 它在 `main.py` 注册时未挂 `Depends(verify_internal_token)`，是唯一未受保护的
  `/internal/*` 路径，与公开端点共用同一 router，无法单独挂依赖；
- 它把底层异常原文通过 `{"error": str(e)}` 返回。
无调用方的未认证接口属于纯攻击面，故直接删除而非加固。若将来需要内部健康详情，
请新建独立 router 并挂上 `verify_internal_token`。
"""

from fastapi import APIRouter

router = APIRouter()


@router.get("/health")
async def public_health() -> dict:
    """公共健康检查端点（快速响应）"""
    return {"status": "ok"}
