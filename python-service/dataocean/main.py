"""DataOcean AI 服务入口

统一注册所有模块路由，配置全局异常处理和生命周期事件。
"""

import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from dataocean.core.config import settings
from dataocean.core.exceptions import ServiceException
from dataocean.core.logging import setup_logging
from dataocean.knowledge.router import router as knowledge_router
from dataocean.rag.router import router as rag_router
from dataocean.agent.router import router as agent_router
from dataocean.sandbox.router import router as sandbox_router
from dataocean.chart.router import router as chart_router
from dataocean.prompt.router import router as prompt_router
from dataocean.infra.health import router as health_router

logger = logging.getLogger(__name__)


async def _periodic_pool_cleanup() -> None:
    """后台定时清理空闲连接池（每 5 分钟执行一次）"""
    from dataocean.sandbox.pool_manager import cleanup_idle_pools

    while True:
        await asyncio.sleep(300)
        try:
            cleaned = cleanup_idle_pools()
            if cleaned > 0:
                logger.info("定时清理空闲连接池完成 count=%d", cleaned)
        except Exception as e:
            logger.warning("连接池清理异常: %s", e)


def _destroy_all_pools() -> None:
    """销毁所有活跃连接池（进程关闭时调用）"""
    from dataocean.sandbox.pool_manager import get_pool_status, destroy_pool

    pools = get_pool_status()
    for pool_info in pools:
        try:
            destroy_pool(pool_info["datasourceId"])
        except Exception as e:
            logger.warning("关闭时销毁连接池异常 datasourceId=%s: %s", pool_info["datasourceId"], e)
    if pools:
        logger.info("已销毁全部连接池 count=%d", len(pools))


@asynccontextmanager
async def lifespan(application: FastAPI):
    """应用生命周期管理"""
    setup_logging(settings.log_level)
    _validate_config()
    # 启动连接池定时清理后台任务
    cleanup_task = asyncio.create_task(_periodic_pool_cleanup())
    logger.info("DataOcean AI Service 启动完成")
    yield
    # 关闭定时清理任务
    cleanup_task.cancel()
    try:
        await cleanup_task
    except asyncio.CancelledError:
        pass
    # 销毁所有连接池
    _destroy_all_pools()
    logger.info("DataOcean AI Service 已关闭")


def _validate_config() -> None:
    """启动时校验关键配置"""
    missing = []
    if not settings.dashscope_api_key:
        missing.append("DASHSCOPE_API_KEY")
    if not settings.milvus_host:
        missing.append("MILVUS_HOST")
    if missing:
        logger.warning("以下关键配置缺失，相关功能将不可用: %s", ", ".join(missing))


app = FastAPI(
    title="DataOcean AI Service",
    version="0.1.0",
    description="NL2SQL 智能数据查询平台 — Python AI 服务层",
    lifespan=lifespan,
)


# === 全局异常处理 ===


@app.exception_handler(ServiceException)
async def service_exception_handler(request: Request, exc: ServiceException) -> JSONResponse:
    """统一处理业务异常，返回标准错误格式"""
    logger.warning("业务异常 path=%s message=%s", request.url.path, exc.message)
    return JSONResponse(status_code=exc.code, content={"message": exc.message})


@app.exception_handler(Exception)
async def generic_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    """兜底异常处理，防止堆栈信息泄露"""
    logger.error("未处理异常 path=%s error=%s", request.url.path, exc, exc_info=True)
    return JSONResponse(status_code=500, content={"message": "服务内部错误"})


# === 健康检查 ===

app.include_router(health_router, tags=["health"])


# === 路由注册（统一 prefix 管理） ===

app.include_router(knowledge_router, prefix="/internal/knowledge", tags=["knowledge"])
app.include_router(rag_router, prefix="/internal/rag", tags=["rag"])
app.include_router(agent_router, prefix="/internal/query", tags=["agent"])
app.include_router(sandbox_router, prefix="/internal/sql", tags=["sandbox"])
app.include_router(chart_router, prefix="/internal/chart", tags=["chart"])
app.include_router(prompt_router, prefix="/internal/prompts", tags=["prompt"])
