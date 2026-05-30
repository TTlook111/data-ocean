"""健康检查端点

提供公共和内部健康检查接口，内部接口包含各依赖服务的详细状态。
"""

import logging
import time

from fastapi import APIRouter

from dataocean.core.config import settings

logger = logging.getLogger(__name__)

router = APIRouter()


@router.get("/health")
async def public_health() -> dict:
    """公共健康检查端点（快速响应）"""
    return {"status": "ok"}


@router.get("/internal/health")
async def internal_health() -> dict:
    """内部健康检查端点（Java 网关调用）

    返回详细状态：Milvus 连接、LLM 可达性。
    """
    details = {}
    overall = "ok"

    # 检查 Milvus 连接
    milvus_status = await _check_milvus()
    details["milvus"] = milvus_status
    if milvus_status["status"] != "ok":
        overall = "degraded"

    # 检查 LLM API 可达性
    llm_status = await _check_llm()
    details["llm"] = llm_status
    if llm_status["status"] != "ok":
        overall = "degraded"

    return {"status": overall, "details": details}


async def _check_milvus() -> dict:
    """检查 Milvus 向量库连接"""
    import asyncio
    try:
        from pymilvus import connections
        start = time.monotonic()

        def _connect():
            connections.connect(
                alias="_health_check",
                host=settings.milvus_host,
                port=settings.milvus_port,
                timeout=3,
            )
            connections.disconnect(alias="_health_check")

        await asyncio.to_thread(_connect)
        latency_ms = int((time.monotonic() - start) * 1000)
        return {"status": "ok", "latency_ms": latency_ms}
    except Exception as e:
        logger.debug("Milvus 健康检查失败: %s", e)
        return {"status": "unavailable", "error": str(e)}


async def _check_llm() -> dict:
    """检查 LLM API 可达性（统一走 infra.llm，不再单独维护 httpx）"""
    from dataocean.infra.llm import ping_llm

    start = time.monotonic()
    available = await ping_llm()
    latency_ms = int((time.monotonic() - start) * 1000)
    if available:
        return {"status": "ok", "latency_ms": latency_ms}
    return {"status": "unavailable", "latency_ms": latency_ms}
